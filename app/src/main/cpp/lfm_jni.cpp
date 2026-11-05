#include <jni.h>
#include <android/log.h>
#include <llama.h>
#include <ggml-backend.h>
#include <nlohmann/json.hpp>
#include <chrono>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#if defined(__aarch64__)
#include <sys/auxv.h>
#include <asm/hwcap.h>
#endif

using json = nlohmann::ordered_json;
using Clock = std::chrono::steady_clock;

struct Session {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    llama_sampler * sampler = nullptr;
    ggml_backend_dev_t devices[2] = {nullptr, nullptr};
    json info;
    int generated = 0;
    int input_tokens = 0;
    llama_token previous = LLAMA_TOKEN_NULL;
    Clock::time_point deadline;
    ~Session() {
        if (sampler) llama_sampler_free(sampler);
        if (context) llama_free(context);
        if (model) llama_model_free(model);
    }
};

static void log_line(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_DEBUG) return;
    __android_log_write(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "LFM-llama", text);
}
static bool expired(void * data) { return Clock::now() > static_cast<Session *>(data)->deadline; }
static void check_time(Session * s) { if (expired(s)) throw std::runtime_error("Generation exceeded 180 seconds"); }
static std::string from_bytes(JNIEnv * env, jbyteArray bytes) {
    std::string value(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, value.size(), reinterpret_cast<jbyte *>(value.data()));
    return value;
}
static jbyteArray to_bytes(JNIEnv * env, const std::string & value) {
    auto bytes = env->NewByteArray(value.size());
    env->SetByteArrayRegion(bytes, 0, value.size(), reinterpret_cast<const jbyte *>(value.data()));
    return bytes;
}
static void fail(JNIEnv * env, const std::exception & error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_cemig_gpudiagnostic_LlamaNative_open(JNIEnv * env, jobject, jbyteArray path, jboolean gpu) {
    try {
        // 1. Select explicitly: CPU gets no offload devices; GPU requires a Vulkan GPU.
#if defined(__aarch64__)
        const auto capabilities = getauxval(AT_HWCAP);
        if ((capabilities & (HWCAP_ASIMDDP | HWCAP_ASIMDHP)) != (HWCAP_ASIMDDP | HWCAP_ASIMDHP))
            throw std::runtime_error("This diagnostic requires ARM dot-product and FP16 instructions (S25 FE target)");
#endif
        llama_log_set(log_line, nullptr);
        llama_backend_init();
        auto s = std::make_unique<Session>();
        auto params = llama_model_default_params();
        params.devices = s->devices;
        params.n_gpu_layers = gpu ? 999 : 0;
        params.split_mode = LLAMA_SPLIT_MODE_NONE;
        if (gpu) {
            for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
                auto dev = ggml_backend_dev_get(i);
                auto type = ggml_backend_dev_type(dev);
                if ((type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) &&
                    std::strcmp(ggml_backend_reg_name(ggml_backend_dev_backend_reg(dev)), "Vulkan") == 0) {
                    s->devices[0] = dev;
                    break;
                }
            }
            if (!s->devices[0]) throw std::runtime_error("No Vulkan GPU device available; CPU fallback is disabled");
        }
        s->info = {{"runtime", "llama.cpp"}, {"requestedGpuLayers", params.n_gpu_layers},
            {"device", gpu ? ggml_backend_dev_description(s->devices[0]) : "CPU"},
            {"deviceName", gpu ? ggml_backend_dev_name(s->devices[0]) : "CPU"},
            {"contextTokens", 4096}, {"batchTokens", 512}, {"microBatchTokens", 128}, {"threads", 4},
            {"backendEvidence", gpu ? "Vulkan GPU selected, all layers requested for offload. Native logs record tensor placement; unsupported operations may still use CPU." : "No GPU offload devices; zero GPU layers, CPU execution."}};
        s->info["cpuBuildFeatures"] = llama_print_system_info();

        // 2. Load once, preserving the model and context for all questions in this worker.
        s->model = llama_model_load_from_file(from_bytes(env, path).c_str(), params);
        if (!s->model) throw std::runtime_error("llama.cpp could not load the model; inspect native log");
        auto cp = llama_context_default_params();
        cp.n_ctx = 4096; cp.n_batch = 512; cp.n_ubatch = 128;
        cp.n_threads = 4; cp.n_threads_batch = 4;
        cp.no_perf = false;
        cp.offload_kqv = gpu; cp.op_offload = gpu;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        s->context = llama_init_from_model(s->model, cp);
        if (!s->context) throw std::runtime_error("llama.cpp context initialization failed; inspect native log");
        s->sampler = llama_sampler_init_greedy();
        return reinterpret_cast<jlong>(s.release());
    } catch (const std::exception & e) { fail(env, e); return 0; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_cemig_gpudiagnostic_LlamaNative_info(JNIEnv * env, jobject, jlong handle) {
    return to_bytes(env, reinterpret_cast<Session *>(handle)->info.dump());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_cemig_gpudiagnostic_LlamaNative_begin(JNIEnv * env, jobject, jlong handle, jbyteArray prompt) {
    try {
        // 1. Clear both attention memory and recurrent state; no prefix reuse across questions.
        auto s = reinterpret_cast<Session *>(handle);
        s->deadline = Clock::now() + std::chrono::seconds(180);
        llama_set_abort_callback(s->context, expired, s);
        llama_memory_clear(llama_get_memory(s->context), true);
        llama_sampler_reset(s->sampler);
        llama_perf_context_reset(s->context);
        s->generated = 0; s->previous = LLAMA_TOKEN_NULL;
        const auto text = from_bytes(env, prompt);
        const auto vocab = llama_model_get_vocab(s->model);
        // The verified template already includes BOS; parse special tokens without adding another BOS.
        int count = -llama_tokenize(vocab, text.data(), text.size(), nullptr, 0, false, true);
        if (count <= 0 || count + 512 > 4096) throw std::runtime_error("Prompt exceeds context budget; no silent truncation");
        std::vector<llama_token> tokens(count);
        if (llama_tokenize(vocab, text.data(), text.size(), tokens.data(), count, false, true) != count)
            throw std::runtime_error("Tokenization count changed");
        s->input_tokens = count;

        // 2. Prefill the complete frozen context in bounded batches.
        for (int offset = 0; offset < count; offset += 512) {
            check_time(s);
            auto batch = llama_batch_get_one(tokens.data() + offset, std::min(512, count - offset));
            if (llama_decode(s->context, batch) != 0) throw std::runtime_error("Prefill failed; inspect native log");
        }
        llama_synchronize(s->context);
        return count;
    } catch (const std::exception & e) { fail(env, e); return 0; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_cemig_gpudiagnostic_LlamaNative_next(JNIEnv * env, jobject, jlong handle) {
    try {
        auto s = reinterpret_cast<Session *>(handle);
        check_time(s);
        if (s->generated >= 512) return nullptr;
        // Decode the previous output only when another token is requested.
        if (s->previous != LLAMA_TOKEN_NULL) {
            auto batch = llama_batch_get_one(&s->previous, 1);
            if (llama_decode(s->context, batch) != 0) throw std::runtime_error("Token decode failed; inspect native log");
        }
        const auto token = llama_sampler_sample(s->sampler, s->context, -1);
        if (llama_vocab_is_eog(llama_model_get_vocab(s->model), token)) return nullptr;
        llama_sampler_accept(s->sampler, token);
        s->previous = token; ++s->generated;
        std::vector<char> piece(256);
        int size = llama_token_to_piece(llama_model_get_vocab(s->model), token, piece.data(), piece.size(), 0, false);
        if (size < 0) {
            piece.resize(-size);
            size = llama_token_to_piece(llama_model_get_vocab(s->model), token, piece.data(), piece.size(), 0, false);
        }
        if (size < 0) throw std::runtime_error("Token-to-text conversion failed");
        return to_bytes(env, std::string(piece.data(), size));
    } catch (const std::exception & e) { fail(env, e); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_cemig_gpudiagnostic_LlamaNative_metrics(JNIEnv * env, jobject, jlong handle) {
    auto s = reinterpret_cast<Session *>(handle);
    llama_synchronize(s->context);
    auto perf = llama_perf_context(s->context);
    return to_bytes(env, json({{"prefillTokens", s->input_tokens}, {"decodeTokens", s->generated},
        {"prefillMs", perf.t_p_eval_ms}, {"decodeMs", perf.t_eval_ms}, {"measuredDecodeSteps", perf.n_eval},
        {"prefillTokensPerSecond", perf.t_p_eval_ms > 0 ? 1000.0 * perf.n_p_eval / perf.t_p_eval_ms : 0.0},
        {"decodeTokensPerSecond", perf.t_eval_ms > 0 ? 1000.0 * perf.n_eval / perf.t_eval_ms : 0.0}}).dump());
}

extern "C" JNIEXPORT void JNICALL
Java_com_cemig_gpudiagnostic_LlamaNative_close(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Session *>(handle);
}
