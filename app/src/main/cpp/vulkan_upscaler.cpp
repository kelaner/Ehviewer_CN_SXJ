#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <net.h>
#include <gpu.h>

namespace {
std::mutex gpu_mutex;
int gpu_users = 0;
struct Engine { ncnn::Net net; };
void fail(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message);
}
void release(Engine* engine) {
    delete engine;
    std::lock_guard<std::mutex> guard(gpu_mutex);
    if (--gpu_users == 0) ncnn::destroy_gpu_instance();
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_upscale_VulkanUpscaler_nativeCreate(JNIEnv* env, jclass, jobject assets) {
    {
        std::lock_guard<std::mutex> guard(gpu_mutex);
        if (gpu_users == 0 && ncnn::create_gpu_instance() != 0) {
            fail(env, "Vulkan 初始化失败"); return 0;
        }
        ++gpu_users;
    }
    if (ncnn::get_gpu_count() == 0) {
        release(nullptr); fail(env, "没有可用的 Vulkan GPU"); return 0;
    }
    Engine* engine = new Engine();
    engine->net.opt.use_vulkan_compute = true;
    engine->net.opt.use_fp16_packed = false;
    engine->net.opt.use_fp16_storage = false;
    engine->net.opt.use_fp16_arithmetic = false;
    engine->net.set_vulkan_device(ncnn::get_default_gpu_index());
    AAssetManager* manager = AAssetManager_fromJava(env, assets);
    if (!manager || engine->net.load_param(manager, "realesrgan-v3-anime-x2/x2.param") != 0 ||
        engine->net.load_model(manager, "realesrgan-v3-anime-x2/x2.bin") != 0) {
        release(engine); fail(env, "超分模型加载失败"); return 0;
    }
    ncnn::Mat probe(16, 16, 3);
    if (probe.empty()) {
        release(engine); fail(env, "Vulkan 超分自检内存分配失败"); return 0;
    }
    probe.fill(1.f);
    bool valid;
    {
        ncnn::Extractor check = engine->net.create_extractor();
        ncnn::Mat checked;
        valid = !engine->net.input_indexes().empty() && !engine->net.output_indexes().empty() &&
            check.input(engine->net.input_indexes()[0], probe) == 0 &&
            check.extract(engine->net.output_indexes().back(), checked) == 0 &&
            !checked.empty() && checked.w == 32 && checked.h == 32 && checked.c == 3 &&
            checked.elemsize == sizeof(float) && checked.elempack == 1;
        for (int c = 0; valid && c < 3; ++c) {
            float value = checked.channel(c).row(16)[16];
            valid = std::isfinite(value) && value > 0.5f && value < 1.5f;
        }
    }
    if (!valid) {
        release(engine); fail(env, "Vulkan GPU 超分自检失败，设备不支持可靠推理"); return 0;
    }
    return reinterpret_cast<jlong>(engine);
}
extern "C" JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_upscale_VulkanUpscaler_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    if (handle) release(reinterpret_cast<Engine*>(handle));
}
extern "C" JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_upscale_VulkanUpscaler_nativeUpscale(JNIEnv* env, jclass, jlong handle, jobject input, jobject output) {
    Engine* engine = reinterpret_cast<Engine*>(handle);
    AndroidBitmapInfo inInfo{}, outInfo{};
    if (!engine || AndroidBitmap_getInfo(env, input, &inInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, output, &outInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        inInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || outInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        outInfo.width != inInfo.width * 2 || outInfo.height != inInfo.height * 2) {
        fail(env, "无效的输入或输出位图"); return;
    }
    void *inPixels = nullptr, *outPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, input, &inPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        fail(env, "无法读取输入位图"); return;
    }
    if (AndroidBitmap_lockPixels(env, output, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, input); fail(env, "无法写入输出位图"); return;
    }
    bool ok = true;
    constexpr int tile = 128, pad = 18;
    for (int y = 0; ok && y < static_cast<int>(inInfo.height); y += tile) {
        for (int x = 0; ok && x < static_cast<int>(inInfo.width); x += tile) {
            int w = std::min(tile, static_cast<int>(inInfo.width) - x);
            int h = std::min(tile, static_cast<int>(inInfo.height) - y);
            ncnn::Mat in(w + 2 * pad, h + 2 * pad, 3);
            if (in.empty()) { ok = false; break; }
            for (int yy = 0; yy < in.h; ++yy) {
                int sourceY = std::max(0, std::min(static_cast<int>(inInfo.height) - 1, y + yy - pad));
                const auto* row = static_cast<const uint8_t*>(inPixels) + sourceY * inInfo.stride;
                for (int xx = 0; xx < in.w; ++xx) {
                    int sourceX = std::max(0, std::min(static_cast<int>(inInfo.width) - 1, x + xx - pad));
                    const auto* src = row + sourceX * 4;
                    for (int c = 0; c < 3; ++c) in.channel(c).row(yy)[xx] = src[3] ? std::min(1.f, static_cast<float>(src[2 - c]) / src[3]) : 0.f;
                }
            }
            ncnn::Extractor ex = engine->net.create_extractor();
            ncnn::Mat result;
            const auto& inputs = engine->net.input_indexes();
            const auto& outputs = engine->net.output_indexes();
            if (inputs.empty() || outputs.empty() || ex.input(inputs[0], in) != 0 ||
                ex.extract(outputs.back(), result) != 0 || result.empty() || result.c != 3 ||
                result.w != in.w * 2 || result.h != in.h * 2 ||
                result.elemsize != sizeof(float) || result.elempack != 1) { ok = false; break; }
            for (int yy = 0; yy < h*2; ++yy) {
                auto* dst = static_cast<uint8_t*>(outPixels) + (y*2 + yy) * outInfo.stride + x*8;
                const auto* alphaRow = static_cast<const uint8_t*>(inPixels) + (y + yy/2) * inInfo.stride + x*4;
                for (int xx = 0; xx < w*2; ++xx, dst += 4) {
                    dst[3] = alphaRow[(xx/2)*4 + 3];
                    for (int c = 0; c < 3; ++c) {
                        float value = result.channel(2 - c).row(pad * 2 + yy)[pad * 2 + xx] * dst[3];
                        dst[c] = static_cast<uint8_t>(std::max(0.f, std::min(static_cast<float>(dst[3]), value)) + 0.5f);
                    }
                }
            }
        }
    }
    AndroidBitmap_unlockPixels(env, output);
    AndroidBitmap_unlockPixels(env, input);
    if (!ok) fail(env, "Vulkan 超分推理失败");
}
