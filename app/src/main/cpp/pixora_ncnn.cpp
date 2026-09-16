#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <mutex>
#include <string>
#include <vector>
#include "net.h"
#include "gpu.h"
#include "cpu.h"

namespace {
constexpr int kModelScale = 4;

std::once_flag gpu_init_flag;
bool gpu_available = false;
bool gpu_instance_created = false;

void init_gpu() {
    std::call_once(gpu_init_flag, [] {
        gpu_instance_created = ncnn::create_gpu_instance() == 0;
        gpu_available = gpu_instance_created && ncnn::get_gpu_count() > 0;
    });
}

jstring error_string(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

unsigned char to_byte(float value) {
    return static_cast<unsigned char>(std::clamp(std::lround(value * 255.f), 0L, 255L));
}

struct SamplePoint {
    int first;
    int second;
    int source_coordinate;
    float weight;
};

std::vector<SamplePoint> make_samples(int length, int model_offset, int target_scale, int model_length) {
    std::vector<SamplePoint> samples;
    samples.reserve(length);
    for (int output_coordinate = 0; output_coordinate < length; ++output_coordinate) {
        // Map pixel centers instead of upper-left corners.  This is an identity map for
        // 4x output and gives correctly aligned bilinear downsampling for 2x and 3x.
        const float model_coordinate = std::clamp(
            model_offset + ((static_cast<float>(output_coordinate) + .5f) * kModelScale /
                            static_cast<float>(target_scale) - .5f),
            0.f,
            static_cast<float>(model_length - 1)
        );
        const int first = static_cast<int>(std::floor(model_coordinate));
        samples.push_back({
            first,
            std::min(first + 1, model_length - 1),
            output_coordinate / target_scale,
            model_coordinate - static_cast<float>(first),
        });
    }
    return samples;
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
    jboolean request_vulkan,
    jobject progress_callback
) {
    AndroidBitmapInfo input_info{};
    AndroidBitmapInfo output_info{};
    if (input_bitmap == nullptr || output_bitmap == nullptr ||
        param_path_value == nullptr || model_path_value == nullptr) {
        return error_string(env, "Missing bitmap or model path");
    }
    if (AndroidBitmap_getInfo(env, input_bitmap, &input_info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, output_bitmap, &output_info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return error_string(env, "Could not inspect bitmap");
    }
    if (input_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || output_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return error_string(env, "Pixora requires ARGB_8888 bitmaps");
    }
    if (target_scale < 2 || target_scale > kModelScale ||
        output_info.width != input_info.width * static_cast<uint32_t>(target_scale) ||
        output_info.height != input_info.height * static_cast<uint32_t>(target_scale)) {
        return error_string(env, "Invalid output dimensions");
    }

    const char* param_path = env->GetStringUTFChars(param_path_value, nullptr);
    if (param_path == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return error_string(env, "Could not read model parameters path");
    }
    const char* model_path = env->GetStringUTFChars(model_path_value, nullptr);
    if (model_path == nullptr) {
        env->ReleaseStringUTFChars(param_path_value, param_path);
        if (env->ExceptionCheck()) env->ExceptionClear();
        return error_string(env, "Could not read model weights path");
    }
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
    const int tiles_x = (width + tile_size - 1) / tile_size;
    const int tiles_y = (height + tile_size - 1) / tile_size;
    const int tile_count = tiles_x * tiles_y;
    jmethodID on_progress = nullptr;
    if (progress_callback != nullptr) {
        const jclass progress_class = env->GetObjectClass(progress_callback);
        if (progress_class == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            AndroidBitmap_unlockPixels(env, output_bitmap);
            AndroidBitmap_unlockPixels(env, input_bitmap);
            return error_string(env, "Could not inspect progress callback");
        }
        on_progress = env->GetMethodID(progress_class, "onProgress", "(F)Z");
        env->DeleteLocalRef(progress_class);
        if (on_progress == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            AndroidBitmap_unlockPixels(env, output_bitmap);
            AndroidBitmap_unlockPixels(env, input_bitmap);
            return error_string(env, "Progress callback is incompatible");
        }
    }

    std::string failure;
    int completed_tiles = 0;
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

            const int left_model = (tile_x - source_x0) * kModelScale;
            const int top_model = (tile_y - source_y0) * kModelScale;
            const int target_w = (content_x1 - tile_x) * target_scale;
            const int target_h = (content_y1 - tile_y) * target_scale;
            const float* red = output.channel(0);
            const float* green = output.channel(1);
            const float* blue = output.channel(2);
            if (target_scale == kModelScale) {
                // A 4x request has a one-to-one mapping to the model output.  Avoid
                // constructing samples or evaluating the bilinear expression with
                // weights that are always zero.
                for (int oy = 0; oy < target_h; ++oy) {
                    const float* red_row = red + (top_model + oy) * output.w + left_model;
                    const float* green_row = green + (top_model + oy) * output.w + left_model;
                    const float* blue_row = blue + (top_model + oy) * output.w + left_model;
                    const unsigned char* alpha_row = input_pixels +
                        (tile_y + oy / kModelScale) * input_info.stride + tile_x * 4;
                    auto* destination = output_pixels +
                        (tile_y * kModelScale + oy) * output_info.stride + tile_x * kModelScale * 4;
                    for (int ox = 0; ox < target_w; ++ox) {
                        destination[ox * 4] = to_byte(red_row[ox]);
                        destination[ox * 4 + 1] = to_byte(green_row[ox]);
                        destination[ox * 4 + 2] = to_byte(blue_row[ox]);
                        destination[ox * 4 + 3] = alpha_row[(ox / kModelScale) * 4 + 3];
                    }
                }
            } else {
                const std::vector<SamplePoint> x_samples = make_samples(
                    target_w, left_model, target_scale, output.w
                );
                const std::vector<SamplePoint> y_samples = make_samples(
                    target_h, top_model, target_scale, output.h
                );

                for (int oy = 0; oy < target_h; ++oy) {
                    const SamplePoint& y_sample = y_samples[oy];
                    const int first_row = y_sample.first * output.w;
                    const int second_row = y_sample.second * output.w;
                    auto* destination = output_pixels + (tile_y * target_scale + oy) * output_info.stride + tile_x * target_scale * 4;
                    for (int ox = 0; ox < target_w; ++ox) {
                        const SamplePoint& x_sample = x_samples[ox];
                        const int source_x = tile_x + x_sample.source_coordinate;
                        const int source_y = tile_y + y_sample.source_coordinate;
                        const unsigned char alpha = input_pixels[source_y * input_info.stride + source_x * 4 + 3];
                        const int top_left = first_row + x_sample.first;
                        const int top_right = first_row + x_sample.second;
                        const int bottom_left = second_row + x_sample.first;
                        const int bottom_right = second_row + x_sample.second;
                        const float top_red = red[top_left] + (red[top_right] - red[top_left]) * x_sample.weight;
                        const float top_green = green[top_left] + (green[top_right] - green[top_left]) * x_sample.weight;
                        const float top_blue = blue[top_left] + (blue[top_right] - blue[top_left]) * x_sample.weight;
                        const float bottom_red = red[bottom_left] + (red[bottom_right] - red[bottom_left]) * x_sample.weight;
                        const float bottom_green = green[bottom_left] + (green[bottom_right] - green[bottom_left]) * x_sample.weight;
                        const float bottom_blue = blue[bottom_left] + (blue[bottom_right] - blue[bottom_left]) * x_sample.weight;
                        destination[ox * 4] = to_byte(top_red + (bottom_red - top_red) * y_sample.weight);
                        destination[ox * 4 + 1] = to_byte(top_green + (bottom_green - top_green) * y_sample.weight);
                        destination[ox * 4 + 2] = to_byte(top_blue + (bottom_blue - top_blue) * y_sample.weight);
                        destination[ox * 4 + 3] = alpha;
                    }
                }
            }

            ++completed_tiles;
            if (on_progress != nullptr) {
                const jboolean keep_running = env->CallBooleanMethod(
                    progress_callback,
                    on_progress,
                    static_cast<jfloat>(completed_tiles) / static_cast<jfloat>(tile_count)
                );
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    failure = "Could not report upscale progress";
                } else if (keep_running != JNI_TRUE) {
                    failure = "Cancelled";
                }
            }
        }
    }

    AndroidBitmap_unlockPixels(env, output_bitmap);
    AndroidBitmap_unlockPixels(env, input_bitmap);
    net.clear();
    return failure.empty() ? nullptr : error_string(env, failure);
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNI_OnUnload(JavaVM*, void*) {
    if (gpu_instance_created) ncnn::destroy_gpu_instance();
}
