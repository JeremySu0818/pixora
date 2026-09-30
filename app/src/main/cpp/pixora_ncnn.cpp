#include <jni.h>
#include <android/bitmap.h>
#include <webp/encode.h>
#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <mutex>
#include <new>
#include <pthread.h>
#include <signal.h>
#include <setjmp.h>
#include <string>
#include <stdio.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <vector>
#include <jpeglib.h>
#include "net.h"
#include "gpu.h"
#include "cpu.h"

namespace {
constexpr int kModelScale = 4;
constexpr int kPadding = 10;

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

struct UpscaleSession {
    ncnn::Net net;
};

struct BitmapPixels {
    JNIEnv* env;
    jobject bitmap;
    void* pixels = nullptr;

    bool lock() {
        return AndroidBitmap_lockPixels(env, bitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS;
    }

    ~BitmapPixels() {
        if (pixels != nullptr) AndroidBitmap_unlockPixels(env, bitmap);
    }
};

jstring upscale_bitmap(
    JNIEnv* env,
    ncnn::Net& net,
    jobject input_bitmap,
    jobject output_bitmap,
    jint target_scale,
    jint requested_tile_size,
    jobject progress_callback
) {
    AndroidBitmapInfo input_info{};
    AndroidBitmapInfo output_info{};
    if (input_bitmap == nullptr || output_bitmap == nullptr) {
        return error_string(env, "Missing tile bitmap");
    }
    if (AndroidBitmap_getInfo(env, input_bitmap, &input_info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, output_bitmap, &output_info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return error_string(env, "Could not inspect tile bitmap");
    }
    if (input_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || output_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return error_string(env, "Pixora requires ARGB_8888 tile bitmaps");
    }
    if (target_scale < 2 || target_scale > kModelScale ||
        output_info.width != input_info.width * static_cast<uint32_t>(target_scale) ||
        output_info.height != input_info.height * static_cast<uint32_t>(target_scale)) {
        return error_string(env, "Invalid tile dimensions");
    }

    BitmapPixels input_lock{env, input_bitmap};
    BitmapPixels output_lock{env, output_bitmap};
    if (!input_lock.lock()) return error_string(env, "Could not read input tile");
    if (!output_lock.lock()) return error_string(env, "Could not write output tile");

    auto* input_pixels = static_cast<unsigned char*>(input_lock.pixels);
    auto* output_pixels = static_cast<unsigned char*>(output_lock.pixels);
    const int width = static_cast<int>(input_info.width);
    const int height = static_cast<int>(input_info.height);
    const int tile_size = requested_tile_size > 0
        ? std::clamp(requested_tile_size, 32, 512)
        : (net.opt.use_vulkan_compute ? 128 : 64);
    const int tiles_x = (width + tile_size - 1) / tile_size;
    const int tiles_y = (height + tile_size - 1) / tile_size;
    const int tile_count = tiles_x * tiles_y;
    jmethodID on_progress = nullptr;
    if (progress_callback != nullptr) {
        const jclass progress_class = env->GetObjectClass(progress_callback);
        if (progress_class == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            return error_string(env, "Could not inspect progress callback");
        }
        on_progress = env->GetMethodID(progress_class, "onProgress", "(F)Z");
        env->DeleteLocalRef(progress_class);
        if (on_progress == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            return error_string(env, "Progress callback is incompatible");
        }
    }

    std::string failure;
    int completed_tiles = 0;
    for (int tile_y = 0; tile_y < height && failure.empty(); tile_y += tile_size) {
        for (int tile_x = 0; tile_x < width && failure.empty(); tile_x += tile_size) {
            const int content_x1 = std::min(tile_x + tile_size, width);
            const int content_y1 = std::min(tile_y + tile_size, height);
            const int source_x0 = std::max(0, tile_x - kPadding);
            const int source_y0 = std::max(0, tile_y - kPadding);
            const int source_x1 = std::min(width, content_x1 + kPadding);
            const int source_y1 = std::min(height, content_y1 + kPadding);
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
            if (input.empty()) {
                failure = "Could not allocate inference tile";
                break;
            }
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
                const std::vector<SamplePoint> x_samples = make_samples(target_w, left_model, target_scale, output.w);
                const std::vector<SamplePoint> y_samples = make_samples(target_h, top_model, target_scale, output.h);
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
    return failure.empty() ? nullptr : error_string(env, failure);
}

bool write_all(int fd, const uint8_t* data, size_t size) {
    size_t written = 0;
    while (written < size) {
        const ssize_t count = write(fd, data + written, size - written);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        written += static_cast<size_t>(count);
    }
    return true;
}

struct JpegErrorState {
    jpeg_error_mgr manager;
    jmp_buf jump;
    char message[JMSG_LENGTH_MAX];
};

struct JpegEncodeState {
    jpeg_compress_struct compressor;
    JpegErrorState error;
    FILE* output = nullptr;
    int raw_fd = -1;
    bool created = false;
    uint8_t* rgba_row = nullptr;
    uint8_t* rgb_row = nullptr;
};

struct EncoderProgressContext {
    int fd;
    JNIEnv* env;
    jobject callback;
    jmethodID method;
    bool cancelled = false;
    bool failed = false;
};

class ScopedSigpipeBlock {
public:
    ScopedSigpipeBlock() {
        sigemptyset(&set_);
        sigaddset(&set_, SIGPIPE);
        if (pthread_sigmask(SIG_BLOCK, &set_, &previous_) != 0) return;
        active_ = true;
        sigpending(&pending_before_);
    }

    ~ScopedSigpipeBlock() {
        if (!active_) return;
        if (sigismember(&previous_, SIGPIPE) == 0) {
            sigset_t pending_now{};
            sigpending(&pending_now);
            if (sigismember(&pending_before_, SIGPIPE) == 0 && sigismember(&pending_now, SIGPIPE) == 1) {
                const timespec timeout{0, 0};
                while (sigtimedwait(&set_, nullptr, &timeout) < 0 && errno == EINTR) {}
            }
        }
        pthread_sigmask(SIG_SETMASK, &previous_, nullptr);
    }

private:
    sigset_t set_{};
    sigset_t previous_{};
    sigset_t pending_before_{};
    bool active_ = false;
};

void jpeg_error_exit(j_common_ptr common) {
    auto* state = reinterpret_cast<JpegErrorState*>(common->err);
    (*common->err->format_message)(common, state->message);
    longjmp(state->jump, 1);
}

int webp_write_callback(const uint8_t* data, size_t size, const WebPPicture* picture) {
    const auto* context = static_cast<const EncoderProgressContext*>(picture->custom_ptr);
    return write_all(context->fd, data, size) ? 1 : 0;
}

int webp_progress_callback(int percent, const WebPPicture* picture) {
    auto* context = static_cast<EncoderProgressContext*>(picture->user_data);
    if (context == nullptr || context->callback == nullptr || context->method == nullptr) return 1;
    const jboolean keep_running = context->env->CallBooleanMethod(
        context->callback,
        context->method,
        static_cast<jfloat>(percent) / 100.f
    );
    if (context->env->ExceptionCheck()) {
        context->env->ExceptionClear();
        context->failed = true;
        return 0;
    }
    if (keep_running != JNI_TRUE) {
        context->cancelled = true;
        return 0;
    }
    return 1;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_pixora_app_inference_NativeUpscaler_hasVulkan(JNIEnv*, jobject) {
    init_gpu();
    return gpu_available ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_pixora_app_inference_NativeUpscaler_createSession(
    JNIEnv* env,
    jobject,
    jstring param_path_value,
    jstring model_path_value,
    jboolean request_vulkan,
    jlongArray session_handle
) {
    if (param_path_value == nullptr || model_path_value == nullptr || session_handle == nullptr ||
        env->GetArrayLength(session_handle) < 1) {
        return error_string(env, "Missing model path or session handle");
    }
    const char* param_path = env->GetStringUTFChars(param_path_value, nullptr);
    if (param_path == nullptr) return error_string(env, "Could not read model parameters path");
    const char* model_path = env->GetStringUTFChars(model_path_value, nullptr);
    if (model_path == nullptr) {
        env->ReleaseStringUTFChars(param_path_value, param_path);
        return error_string(env, "Could not read model weights path");
    }

    UpscaleSession* session = new (std::nothrow) UpscaleSession();
    if (session == nullptr) {
        env->ReleaseStringUTFChars(param_path_value, param_path);
        env->ReleaseStringUTFChars(model_path_value, model_path);
        return error_string(env, "Not enough memory to open the model");
    }
    init_gpu();
    const bool use_vulkan = request_vulkan == JNI_TRUE && gpu_available;
    session->net.opt.use_vulkan_compute = use_vulkan;
    session->net.opt.use_fp16_packed = true;
    session->net.opt.use_fp16_storage = use_vulkan;
    session->net.opt.use_fp16_arithmetic = false;
    session->net.opt.use_int8_storage = true;
    session->net.opt.num_threads = std::max(1, std::min(4, ncnn::get_big_cpu_count()));
    if (use_vulkan) session->net.set_vulkan_device(ncnn::get_default_gpu_index());

    int result = session->net.load_param(param_path);
    if (result == 0) result = session->net.load_model(model_path);
    std::string failure;
    if (result == 0) {
        const jlong handle = static_cast<jlong>(reinterpret_cast<intptr_t>(session));
        env->SetLongArrayRegion(session_handle, 0, 1, &handle);
        if (!env->ExceptionCheck()) {
            env->ReleaseStringUTFChars(param_path_value, param_path);
            env->ReleaseStringUTFChars(model_path_value, model_path);
            return nullptr;
        }
        env->ExceptionClear();
        failure = "Could not store native session";
    } else {
        failure = "Could not load the selected ncnn model";
    }
    delete session;
    env->ReleaseStringUTFChars(param_path_value, param_path);
    env->ReleaseStringUTFChars(model_path_value, model_path);
    return error_string(env, failure);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_pixora_app_inference_NativeUpscaler_upscaleTile(
    JNIEnv* env,
    jobject,
    jlong session_handle,
    jobject input_bitmap,
    jobject output_bitmap,
    jint target_scale,
    jint tile_size,
    jobject progress_callback
) {
    auto* session = reinterpret_cast<UpscaleSession*>(static_cast<intptr_t>(session_handle));
    if (session == nullptr) return error_string(env, "Inference session is not open");
    return upscale_bitmap(env, session->net, input_bitmap, output_bitmap, target_scale, tile_size, progress_callback);
}

extern "C" JNIEXPORT void JNICALL
Java_org_pixora_app_inference_NativeUpscaler_destroySession(JNIEnv*, jobject, jlong session_handle) {
    auto* session = reinterpret_cast<UpscaleSession*>(static_cast<intptr_t>(session_handle));
    delete session;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_pixora_app_inference_NativeUpscaler_encodeJpeg(
    JNIEnv* env,
    jobject,
    jstring raw_path_value,
    jint output_fd,
    jint width,
    jint height,
    jint quality,
    jobject progress_callback
) {
    ScopedSigpipeBlock sigpipe_guard;
    if (raw_path_value == nullptr || output_fd < 0 || width <= 0 || height <= 0) {
        return error_string(env, "Invalid JPEG encoder input");
    }
    if (width > 65535 || height > 65535) {
        return error_string(env, "JPEG dimensions cannot exceed 65,535 pixels");
    }
    const jclass progress_class = env->GetObjectClass(progress_callback);
    if (progress_class == nullptr) return error_string(env, "Could not inspect progress callback");
    const jmethodID on_progress = env->GetMethodID(progress_class, "onProgress", "(F)Z");
    env->DeleteLocalRef(progress_class);
    if (on_progress == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return error_string(env, "Progress callback is incompatible");
    }
    const char* raw_path = env->GetStringUTFChars(raw_path_value, nullptr);
    if (raw_path == nullptr) return error_string(env, "Could not read temporary image path");

    auto* state = static_cast<JpegEncodeState*>(std::calloc(1, sizeof(JpegEncodeState)));
    if (state == nullptr) {
        env->ReleaseStringUTFChars(raw_path_value, raw_path);
        return error_string(env, "Not enough memory to start JPEG encoding");
    }
    state->raw_fd = open(raw_path, O_RDONLY | O_CLOEXEC);
    const int output_copy = dup(output_fd);
    if (output_copy >= 0) state->output = fdopen(output_copy, "wb");
    if (state->raw_fd < 0 || output_copy < 0 || state->output == nullptr) {
        if (state->output != nullptr) std::fclose(state->output);
        else if (output_copy >= 0) close(output_copy);
        if (state->raw_fd >= 0) close(state->raw_fd);
        std::free(state);
        env->ReleaseStringUTFChars(raw_path_value, raw_path);
        return error_string(env, "Could not open JPEG output stream");
    }
    state->compressor.err = jpeg_std_error(&state->error.manager);
    state->error.manager.error_exit = jpeg_error_exit;
    if (setjmp(state->error.jump)) {
        char message[JMSG_LENGTH_MAX];
        std::strncpy(message, state->error.message, sizeof(message));
        message[sizeof(message) - 1] = '\0';
        if (state->created) jpeg_destroy_compress(&state->compressor);
        std::free(state->rgba_row);
        std::free(state->rgb_row);
        std::fclose(state->output);
        close(state->raw_fd);
        std::free(state);
        env->ReleaseStringUTFChars(raw_path_value, raw_path);
        return error_string(env, message[0] == '\0' ? "JPEG encoding failed" : message);
    }

    state->rgba_row = static_cast<uint8_t*>(std::malloc(static_cast<size_t>(width) * 4));
    state->rgb_row = static_cast<uint8_t*>(std::malloc(static_cast<size_t>(width) * 3));
    if (state->rgba_row == nullptr || state->rgb_row == nullptr) {
        std::free(state->rgba_row);
        std::free(state->rgb_row);
        std::fclose(state->output);
        close(state->raw_fd);
        std::free(state);
        env->ReleaseStringUTFChars(raw_path_value, raw_path);
        return error_string(env, "Not enough memory for a JPEG row");
    }

    jpeg_create_compress(&state->compressor);
    state->created = true;
    jpeg_stdio_dest(&state->compressor, state->output);
    state->compressor.image_width = static_cast<JDIMENSION>(width);
    state->compressor.image_height = static_cast<JDIMENSION>(height);
    state->compressor.input_components = 3;
    state->compressor.in_color_space = JCS_RGB;
    jpeg_set_defaults(&state->compressor);
    jpeg_set_quality(&state->compressor, std::clamp(quality, 0, 100), TRUE);
    jpeg_start_compress(&state->compressor, TRUE);

    bool row_failure = false;
    bool cancelled = false;
    bool progress_failed = false;
    const int progress_interval = std::max(1, height / 100);
    while (state->compressor.next_scanline < state->compressor.image_height) {
        const size_t row = state->compressor.next_scanline;
        const size_t rgba_size = static_cast<size_t>(width) * 4;
        size_t read_size = 0;
        while (read_size < rgba_size) {
            const ssize_t count = pread(
                state->raw_fd,
                state->rgba_row + read_size,
                rgba_size - read_size,
                static_cast<off_t>(row * rgba_size + read_size)
            );
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) {
                row_failure = true;
                break;
            }
            read_size += static_cast<size_t>(count);
        }
        if (row_failure) break;
        for (int x = 0; x < width; ++x) {
            state->rgb_row[x * 3] = state->rgba_row[x * 4];
            state->rgb_row[x * 3 + 1] = state->rgba_row[x * 4 + 1];
            state->rgb_row[x * 3 + 2] = state->rgba_row[x * 4 + 2];
        }
        JSAMPROW scanline = state->rgb_row;
        if (jpeg_write_scanlines(&state->compressor, &scanline, 1) != 1) {
            row_failure = true;
            break;
        }
        const JDIMENSION completed = state->compressor.next_scanline;
        if (completed % static_cast<JDIMENSION>(progress_interval) == 0 || completed == state->compressor.image_height) {
            const jboolean keep_running = env->CallBooleanMethod(
                progress_callback,
                on_progress,
                static_cast<jfloat>(completed) / static_cast<jfloat>(height)
            );
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                row_failure = true;
                progress_failed = true;
                break;
            }
            if (keep_running != JNI_TRUE) {
                row_failure = true;
                cancelled = true;
                break;
            }
        }
    }
    if (!row_failure) jpeg_finish_compress(&state->compressor);
    jpeg_destroy_compress(&state->compressor);
    bool output_ok = !row_failure && std::fflush(state->output) == 0;
    std::free(state->rgba_row);
    std::free(state->rgb_row);
    output_ok = std::fclose(state->output) == 0 && output_ok;
    close(state->raw_fd);
    std::free(state);
    env->ReleaseStringUTFChars(raw_path_value, raw_path);
    if (cancelled) return error_string(env, "Cancelled");
    if (progress_failed) return error_string(env, "Could not report encoding progress");
    return output_ok ? nullptr : error_string(env, "Could not finish writing JPEG output");
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_pixora_app_inference_NativeUpscaler_encodeWebp(
    JNIEnv* env,
    jobject,
    jstring raw_path_value,
    jint output_fd,
    jint width,
    jint height,
    jint quality,
    jboolean lossless,
    jobject progress_callback
) {
    ScopedSigpipeBlock sigpipe_guard;
    if (raw_path_value == nullptr || output_fd < 0 || width <= 0 || height <= 0) {
        return error_string(env, "Invalid WebP encoder input");
    }
    if (width > WEBP_MAX_DIMENSION || height > WEBP_MAX_DIMENSION) {
        return error_string(env, "WebP dimensions cannot exceed 16,383 pixels");
    }
    const char* raw_path = env->GetStringUTFChars(raw_path_value, nullptr);
    if (raw_path == nullptr) return error_string(env, "Could not read temporary image path");

    const int raw_fd = open(raw_path, O_RDWR | O_CLOEXEC);
    env->ReleaseStringUTFChars(raw_path_value, raw_path);
    if (raw_fd < 0) return error_string(env, "Could not open temporary image for WebP encoding");
    struct stat raw_info{};
    const uint64_t expected_size = static_cast<uint64_t>(width) * static_cast<uint64_t>(height) * 4u;
    if (fstat(raw_fd, &raw_info) != 0 || raw_info.st_size < 0 || static_cast<uint64_t>(raw_info.st_size) < expected_size) {
        close(raw_fd);
        return error_string(env, "Temporary image data is incomplete");
    }

    void* mapped = mmap(nullptr, static_cast<size_t>(expected_size), PROT_READ | PROT_WRITE, MAP_SHARED, raw_fd, 0);
    if (mapped == MAP_FAILED) {
        close(raw_fd);
        return error_string(env, "Could not map temporary image for WebP encoding");
    }
    auto* rgba = static_cast<uint8_t*>(mapped);
    for (uint64_t i = 0; i < expected_size; i += 4) {
        const uint32_t alpha = rgba[i + 3];
        uint32_t red = rgba[i];
        uint32_t green = rgba[i + 1];
        uint32_t blue = rgba[i + 2];
        if (alpha == 0) {
            red = green = blue = 0;
        } else if (alpha < 255) {
            red = std::min(255u, (red * 255u + alpha / 2u) / alpha);
            green = std::min(255u, (green * 255u + alpha / 2u) / alpha);
            blue = std::min(255u, (blue * 255u + alpha / 2u) / alpha);
        }
        rgba[i] = static_cast<uint8_t>(blue);
        rgba[i + 1] = static_cast<uint8_t>(green);
        rgba[i + 2] = static_cast<uint8_t>(red);
    }

    int fd_copy = dup(output_fd);
    if (fd_copy < 0) {
        munmap(mapped, static_cast<size_t>(expected_size));
        close(raw_fd);
        return error_string(env, "Could not open WebP output stream");
    }

    WebPConfig config{};
    WebPPicture picture{};
    EncoderProgressContext progress_context{fd_copy, env, progress_callback, nullptr};
    bool initialized = false;
    std::string failure;
    const jclass progress_class = env->GetObjectClass(progress_callback);
    if (progress_class == nullptr) {
        failure = "Could not inspect progress callback";
    } else {
        progress_context.method = env->GetMethodID(progress_class, "onProgress", "(F)Z");
        env->DeleteLocalRef(progress_class);
        if (progress_context.method == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            failure = "Progress callback is incompatible";
        }
    }
    if (failure.empty() && !WebPConfigPreset(&config, WEBP_PRESET_DEFAULT, static_cast<float>(std::clamp(quality, 0, 100)))) {
        failure = "Could not initialize WebP encoder settings";
    } else if (failure.empty() && !WebPPictureInit(&picture)) {
        failure = "Could not initialize WebP picture";
    } else if (failure.empty()) {
        initialized = true;
        config.lossless = lossless == JNI_TRUE ? 1 : 0;
        config.low_memory = 1;
        config.thread_level = 0;
        if (config.lossless) config.method = 4;
        picture.use_argb = 1;
        picture.width = width;
        picture.height = height;
        picture.argb = reinterpret_cast<uint32_t*>(mapped);
        picture.argb_stride = width;
        picture.writer = webp_write_callback;
        picture.custom_ptr = &progress_context;
        picture.progress_hook = webp_progress_callback;
        picture.user_data = &progress_context;
        if (!WebPValidateConfig(&config)) {
            failure = "Invalid WebP encoder settings";
        } else if (!WebPEncode(&config, &picture)) {
            if (progress_context.cancelled) failure = "Cancelled";
            else if (progress_context.failed) failure = "Could not report encoding progress";
            else failure = "WebP encoding failed (code " + std::to_string(picture.error_code) + ")";
        }
    }
    if (initialized) WebPPictureFree(&picture);
    const bool close_ok = close(fd_copy) == 0;
    munmap(mapped, static_cast<size_t>(expected_size));
    close(raw_fd);
    if (failure.empty() && !close_ok) failure = "Could not finish writing WebP output";
    return failure.empty() ? nullptr : error_string(env, failure);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    if (gpu_instance_created) ncnn::destroy_gpu_instance();
}
