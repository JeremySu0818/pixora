#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <mutex>
#include <string>
#include "net.h"
#include "gpu.h"
#include "cpu.h"

namespace {
std::once_flag gpu_init_flag;
bool gpu_available = false;

void init_gpu() {
    std::call_once(gpu_init_flag, [] {
        gpu_available = ncnn::create_gpu_instance() == 0 && ncnn::get_gpu_count() > 0;
    });
}

jstring error_string(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

unsigned char to_byte(float value) {
    return static_cast<unsigned char>(std::clamp(std::lround(value * 255.f), 0L, 255L));
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_pixora_app_inference_NativeUpscaler_hasVulkan(JNIEnv*, jobject) {
    init_gpu();
    return gpu_available ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_pixora_app_inference_NativeUpscaler_upscale(
    JNIEnv* env,
    jobject,
    jobject input_bitmap,
    jobject output_bitmap,
    jstring param_path_value,
    jstring model_path_value,
    jint target_scale,
    jint requested_tile_size,
    jboolean request_vulkan
) {
    AndroidBitmapInfo input_info{};
    AndroidBitmapInfo output_info{};
    if (AndroidBitmap_getInfo(env, input_bitmap, &input_info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, output_bitmap, &output_info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return error_string(env, "Could not inspect bitmap");
    }
    if (input_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || output_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return error_string(env, "Pixora requires ARGB_8888 bitmaps");
    }
    if (target_scale < 2 || target_scale > 4 ||
        output_info.width != input_info.width * static_cast<uint32_t>(target_scale) ||
        output_info.height != input_info.height * static_cast<uint32_t>(target_scale)) {
        return error_string(env, "Invalid output dimensions");
    }

    const char* param_path = env->GetStringUTFChars(param_path_value, nullptr);
    const char* model_path = env->GetStringUTFChars(model_path_value, nullptr);
    ncnn::Net net;
    init_gpu();
    const bool use_vulkan = request_vulkan == JNI_TRUE && gpu_available;
    net.opt.use_vulkan_compute = use_vulkan;
    net.opt.use_fp16_packed = true;
    net.opt.use_fp16_storage = use_vulkan;
    net.opt.use_fp16_arithmetic = false;
    net.opt.use_int8_storage = true;
    net.opt.num_threads = std::max(1, std::min(4, ncnn::get_big_cpu_count()));
    if (use_vulkan) net.set_vulkan_device(ncnn::get_default_gpu_index());

    int result = net.load_param(param_path);
    if (result == 0) result = net.load_model(model_path);
    env->ReleaseStringUTFChars(param_path_value, param_path);
    env->ReleaseStringUTFChars(model_path_value, model_path);
    if (result != 0) return error_string(env, "Could not load the selected ncnn model");

    void* input_pixels_raw = nullptr;
    void* output_pixels_raw = nullptr;
    if (AndroidBitmap_lockPixels(env, input_bitmap, &input_pixels_raw) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return error_string(env, "Could not read input pixels");
    }
    if (AndroidBitmap_lockPixels(env, output_bitmap, &output_pixels_raw) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, input_bitmap);
        return error_string(env, "Could not write output pixels");
    }

    auto* input_pixels = static_cast<unsigned char*>(input_pixels_raw);
    auto* output_pixels = static_cast<unsigned char*>(output_pixels_raw);
    const int width = static_cast<int>(input_info.width);
    const int height = static_cast<int>(input_info.height);
    const int tile_size = requested_tile_size > 0 ? requested_tile_size : (use_vulkan ? 128 : 64);
    const int padding = 10;

    std::string failure;
    for (int tile_y = 0; tile_y < height && failure.empty(); tile_y += tile_size) {
        for (int tile_x = 0; tile_x < width && failure.empty(); tile_x += tile_size) {
            const int content_x1 = std::min(tile_x + tile_size, width);
            const int content_y1 = std::min(tile_y + tile_size, height);
            const int source_x0 = std::max(0, tile_x - padding);
            const int source_y0 = std::max(0, tile_y - padding);
            const int source_x1 = std::min(width, content_x1 + padding);
            const int source_y1 = std::min(height, content_y1 + padding);
            const int source_w = source_x1 - source_x0;
            const int source_h = source_y1 - source_y0;

            const unsigned char* tile_start = input_pixels + source_y0 * input_info.stride + source_x0 * 4;
            ncnn::Mat input = ncnn::Mat::from_pixels(
                tile_start,
                ncnn::Mat::PIXEL_RGBA2RGB,
                source_w,
                source_h,
                static_cast<int>(input_info.stride)
            );
            for (int channel = 0; channel < 3; ++channel) {
                float* values = input.channel(channel);
                for (int i = 0; i < source_w * source_h; ++i) values[i] *= 1.f / 255.f;
            }

            ncnn::Extractor extractor = net.create_extractor();
            extractor.set_light_mode(true);
            if (extractor.input("data", input) != 0) {
                failure = "Model input layer is incompatible";
                break;
            }
            ncnn::Mat output;
            if (extractor.extract("output", output) != 0 || output.empty() || output.c < 3) {
                failure = "Model inference failed";
                break;
            }

            const int left_model = (tile_x - source_x0) * 4;
            const int top_model = (tile_y - source_y0) * 4;
            const int target_w = (content_x1 - tile_x) * target_scale;
            const int target_h = (content_y1 - tile_y) * target_scale;
            const float* red = output.channel(0);
            const float* green = output.channel(1);
            const float* blue = output.channel(2);

            for (int oy = 0; oy < target_h; ++oy) {
                const int model_y = std::min(output.h - 1, top_model + oy * 4 / target_scale);
                auto* destination = output_pixels + (tile_y * target_scale + oy) * output_info.stride + tile_x * target_scale * 4;
                for (int ox = 0; ox < target_w; ++ox) {
                    const int model_x = std::min(output.w - 1, left_model + ox * 4 / target_scale);
                    const int model_index = model_y * output.w + model_x;
                    const int source_x = std::min(width - 1, tile_x + ox / target_scale);
                    const int source_y = std::min(height - 1, tile_y + oy / target_scale);
                    const unsigned char alpha = input_pixels[source_y * input_info.stride + source_x * 4 + 3];
                    destination[ox * 4] = to_byte(red[model_index]);
                    destination[ox * 4 + 1] = to_byte(green[model_index]);
                    destination[ox * 4 + 2] = to_byte(blue[model_index]);
                    destination[ox * 4 + 3] = alpha;
                }
            }
        }
    }

    AndroidBitmap_unlockPixels(env, output_bitmap);
    AndroidBitmap_unlockPixels(env, input_bitmap);
    net.clear();
    return failure.empty() ? nullptr : error_string(env, failure);
}

JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}
