// =============================================================================
// 微玄 · 本地图像生成引擎 —— JNI 桥
//
// Kotlin 侧：cn.yangrq.weixuan.local.ImageGenerationEngine
// 引擎：stable-diffusion.cpp（MIT，leejet/stable-diffusion.cpp）
// 后端：Vulkan（Adreno 840）
//
// 线程模型：所有 nativeGenerate 调用必须发生在 Kotlin 的单线程调度器上
//           （sd.cpp 上下文非线程安全；进度回调在引擎线程触发，只写原子量）
// =============================================================================
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstdlib>
#include <cstring>

#include "stable-diffusion.h"

#define LOG_TAG "WeiXuanSD"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static sd_ctx_t* g_ctx = nullptr;

// 进度：引擎线程只写原子量，Kotlin 侧轮询（避免在回调里做 JNI 附加等复杂操作）
static std::atomic<int> g_step{0};
static std::atomic<int> g_total{0};
static std::atomic<bool> g_running{false};

static void progress_cb(int step, int steps, float /*time*/, void* /*data*/) {
    g_step.store(step);
    g_total.store(steps);
}

// 保存在 ctx 生命周期内必须有效的字符串（sd_ctx_params_t 只存指针）
static char* g_keep_model = nullptr;
static char* g_keep_vae = nullptr;
static char* g_keep_backend = nullptr;

static char* dup_or_null(JNIEnv* env, jstring s) {
    if (!s) return nullptr;
    const char* c = env->GetStringUTFChars(s, nullptr);
    if (!c) return nullptr;
    char* out = strdup(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_cn_yangrq_weixuan_local_ImageGenerationEngine_nativeInit(JNIEnv* /*env*/, jclass /*clazz*/) {
    sd_set_progress_callback(progress_cb, nullptr);
    LOGI("stable-diffusion.cpp %s", sd_version());
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_cn_yangrq_weixuan_local_ImageGenerationEngine_nativeVersion(JNIEnv* env, jclass /*clazz*/) {
    return env->NewStringUTF(sd_version());
}

JNIEXPORT jstring JNICALL
Java_cn_yangrq_weixuan_local_ImageGenerationEngine_nativeSystemInfo(JNIEnv* env, jclass /*clazz*/) {
    const char* info = sd_get_system_info();
    return env->NewStringUTF(info ? info : "");
}

JNIEXPORT jstring JNICALL
Java_cn_yangrq_weixuan_local_ImageGenerationEngine_nativeListDevices(JNIEnv* env, jclass /*clazz*/) {
    char buf[8192] = {0};
    sd_list_devices(buf, sizeof(buf));
    return env->NewStringUTF(buf);
}

/**
 * 加载模型。
 * @param modelPath 主模型（.gguf / .safetensors / .ckpt）
 * @param vaePath   独立 VAE（可空；GGUF 内含 VAE 时传 null）
 * @param nThreads  CPU 线程数（Vulkan 设备上主要用于非算子部分）
 * @param backend   ggml 后端名（"vulkan" / "cpu"）
 * @param wtype     权重类型字符串（"F16" / "Q4_0" / "Q8_0" …，见 str_to_sd_type）
 * @param maxVramGiB 显存预算 GiB（<=0 表示不限制，用实时剩余）
 */
JNIEXPORT jboolean JNICALL
Java_cn_yangrq_weixuan_local_ImageGenerationEngine_nativeLoadModel(
        JNIEnv* env, jclass /*clazz*/,
        jstring modelPath, jstring vaePath, jint nThreads,
        jstring backend, jstring wtype, jfloat maxVramGiB) {

    if (g_ctx) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
    }
    free(g_keep_model); free(g_keep_vae); free(g_keep_backend);
    g_keep_model = g_keep_vae = g_keep_backend = nullptr;

    const char* model = env->GetStringUTFChars(modelPath, nullptr);
    if (!model) return JNI_FALSE;

    sd_ctx_params_t params;
    sd_ctx_params_init(&params);

    g_keep_model = strdup(model);
    env->ReleaseStringUTFChars(modelPath, model);
    params.model_path = g_keep_model;

    if (vaePath) {
        g_keep_vae = dup_or_null(env, vaePath);
        params.vae_path = g_keep_vae;
    }

    params.n_threads = nThreads;

    if (backend) {
        g_keep_backend = dup_or_null(env, backend);
        params.backend = g_keep_backend;
    }

    if (wtype) {
        const char* wt = env->GetStringUTFChars(wtype, nullptr);
        if (wt) {
            params.wtype = str_to_sd_type(wt);
            env->ReleaseStringUTFChars(wtype, wt);
        }
    }

    if (maxVramGiB > 0) {
        static char vram_buf[16];
        snprintf(vram_buf, sizeof(vram_buf), "%.0f", maxVramGiB);
        params.max_vram = vram_buf;
    }

    // 内存：Adreno 与 CPU 共享物理内存，给 mmap 留出余量
    params.enable_mmap = true;

    LOGI("加载图像模型: %s (backend=%s)", g_keep_model, g_keep_backend ? g_keep_backend : "default");
    g_ctx = new_sd_ctx(&params);

    g_step.store(0);
    g_total.store(0);

    if (!g_ctx) {
        LOGE("new_sd_ctx 失败");
        return JNI_FALSE;
    }
    LOGI("模型就绪: %s", sd_get_model_version_name(g_ctx));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_cn_yangrq_weixuan_local_ImageGenerationEngine_nativeFreeModel(JNIEnv* /*env*/, jclass /*clazz*/) {
    if (g_ctx) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        LOGI("图像模型已卸载");
    }
}

JNIEXPORT jboolean JNICALL
Java_cn_yangrq_weixuan_local_ImageGenerationEngine_nativeIsLoaded(JNIEnv* /*env*/, jclass /*clazz*/) {
    return g_ctx ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_cn_yangrq_weixuan_local_ImageGenerationEngine_nativeProgressStep(JNIEnv* /*env*/, jclass /*clazz*/) {
    return g_step.load();
}

JNIEXPORT jint JNICALL
Java_cn_yangrq_weixuan_local_ImageGenerationEngine_nativeProgressTotal(JNIEnv* /*env*/, jclass /*clazz*/) {
    return g_total.load();
}

/**
 * 文生图。返回 RGBA 字节数组（width*height*4），失败返回 null。
 * 调用前必须已 nativeLoadModel 成功。
 */
JNIEXPORT jbyteArray JNICALL
Java_cn_yangrq_weixuan_local_ImageGenerationEngine_nativeGenerate(
        JNIEnv* env, jclass /*clazz*/,
        jstring prompt, jstring negativePrompt,
        jint width, jint height, jint steps, jfloat cfgScale,
        jlong seed, jstring sampler, jstring scheduler) {

    if (!g_ctx) {
        LOGE("nativeGenerate: 模型未加载");
        return nullptr;
    }

    const char* p = env->GetStringUTFChars(prompt, nullptr);
    if (!p) return nullptr;
    const char* np = negativePrompt ? env->GetStringUTFChars(negativePrompt, nullptr) : nullptr;

    sd_img_gen_params_t ip;
    sd_img_gen_params_init(&ip);
    sd_sample_params_init(&ip.sample_params);
    ip.prompt = p;
    ip.negative_prompt = np;
    ip.width = width;
    ip.height = height;
    ip.seed = seed;
    ip.batch_count = 1;
    ip.strength = 1.0f;
    ip.clip_skip = 1;

    ip.sample_params.sample_steps = steps;
    ip.sample_params.guidance.txt_cfg = cfgScale;

    if (sampler) {
        const char* sm = env->GetStringUTFChars(sampler, nullptr);
        if (sm) {
            ip.sample_params.sample_method = str_to_sample_method(sm);
            env->ReleaseStringUTFChars(sampler, sm);
        }
    }
    if (scheduler) {
        const char* sc = env->GetStringUTFChars(scheduler, nullptr);
        if (sc) {
            ip.sample_params.scheduler = str_to_scheduler(sc);
            env->ReleaseStringUTFChars(scheduler, sc);
        }
    }

    g_running.store(true);
    g_step.store(0);
    g_total.store(steps);

    sd_image_t* images = nullptr;
    int num_images = 0;
    bool ok = generate_image(g_ctx, &ip, &images, &num_images);

    g_running.store(false);

    if (np) env->ReleaseStringUTFChars(negativePrompt, np);
    env->ReleaseStringUTFChars(prompt, p);

    if (!ok || !images || num_images <= 0) {
        LOGE("generate_image 失败 (ok=%d)", (int) ok);
        if (images) free_sd_images(images, num_images);
        return nullptr;
    }

    sd_image_t& img = images[0];
    const size_t row_bytes = (size_t) img.width * img.channel;
    const size_t total = row_bytes * img.height;

    jbyteArray out = env->NewByteArray((jsize) total);
    if (out) {
        env->SetByteArrayRegion(out, 0, (jsize) total, (const jbyte*) img.data);
    }

    LOGI("出图完成: %ux%u x%u, %zu 字节", img.width, img.height, img.channel, total);

    free_sd_images(images, num_images);
    return out;
}

} // extern "C"
