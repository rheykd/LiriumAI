#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>
#include <cstring>
#include <algorithm>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "chat.h"
#include "common.h"
#include "sampling.h"
#include "ggml-backend.h"

#define LOG_TAG "local_llm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *                model = nullptr;
    llama_context *              ctx   = nullptr;
    mtmd_context *               mctx  = nullptr;
    common_chat_templates_ptr    tmpls;
    std::vector<common_chat_msg> history;
    llama_pos                    n_past  = 0;
    int                          n_ctx   = 4096;
    int                          n_batch = 512;
    bool                         vision  = false;
};

Engine *          g_engine = nullptr;
std::mutex        g_mutex;
std::atomic<bool> g_stop{false};
bool              g_backend_ready = false;

static void log_callback(enum ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
    else if (level == GGML_LOG_LEVEL_WARN) LOGW("%s", text);
}

static void throw_illegal(JNIEnv * env, const std::string & msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) {
        env->ThrowNew(cls, msg.c_str());
        env->DeleteLocalRef(cls);
    }
}

// ---------------- UTF-8 / UTF-16 ----------------

static void append_utf8(std::string & out, uint32_t cp) {
    if (cp <= 0x7F) {
        out.push_back((char) cp);
    } else if (cp <= 0x7FF) {
        out.push_back((char) (0xC0 | (cp >> 6)));
        out.push_back((char) (0x80 | (cp & 0x3F)));
    } else if (cp <= 0xFFFF) {
        out.push_back((char) (0xE0 | (cp >> 12)));
        out.push_back((char) (0x80 | ((cp >> 6) & 0x3F)));
        out.push_back((char) (0x80 | (cp & 0x3F)));
    } else {
        out.push_back((char) (0xF0 | (cp >> 18)));
        out.push_back((char) (0x80 | ((cp >> 12) & 0x3F)));
        out.push_back((char) (0x80 | ((cp >> 6) & 0x3F)));
        out.push_back((char) (0x80 | (cp & 0x3F)));
    }
}

static std::string jstring_to_utf8(JNIEnv * env, jstring s) {
    if (!s) return {};
    jsize len = env->GetStringLength(s);
    const jchar * chars = env->GetStringChars(s, nullptr);
    std::string out;
    out.reserve((size_t) len * 2);
    for (jsize i = 0; i < len; ++i) {
        uint32_t c = chars[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len) {
            uint32_t c2 = chars[i + 1];
            if (c2 >= 0xDC00 && c2 <= 0xDFFF) {
                c = 0x10000 + ((c - 0xD800) << 10) + (c2 - 0xDC00);
                ++i;
            }
        }
        append_utf8(out, c);
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

static std::u16string utf8_to_utf16(const std::string & s) {
    std::u16string out;
    out.reserve(s.size());
    size_t i = 0;
    while (i < s.size()) {
        unsigned char c = (unsigned char) s[i];
        uint32_t cp = 0;
        size_t need = 0;
        if (c < 0x80) {
            out.push_back((char16_t) c);
            ++i;
            continue;
        } else if ((c & 0xE0) == 0xC0) {
            cp = c & 0x1F; need = 1;
        } else if ((c & 0xF0) == 0xE0) {
            cp = c & 0x0F; need = 2;
        } else if ((c & 0xF8) == 0xF0) {
            cp = c & 0x07; need = 3;
        } else {
            out.push_back(0xFFFD);
            ++i;
            continue;
        }
        if (i + need >= s.size()) break; // incomplete sequence, wait for next token
        bool ok = true;
        for (size_t k = 1; k <= need; ++k) {
            unsigned char cc = (unsigned char) s[i + k];
            if ((cc & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!ok) {
            out.push_back(0xFFFD);
            ++i;
            continue;
        }
        i += need + 1;
        if (cp > 0xFFFF) {
            cp -= 0x10000;
            out.push_back((char16_t) (0xD800 + (cp >> 10)));
            out.push_back((char16_t) (0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back((char16_t) cp);
        }
    }
    return out;
}

static void emit_utf8(JNIEnv * env, jobject sink, jmethodID mid, std::string & pending, bool flush_all) {
    if (!sink || !mid) return;
    size_t cut = 0;
    size_t i = 0;
    while (i < pending.size()) {
        unsigned char c = (unsigned char) pending[i];
        size_t need;
        if (c < 0x80) need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        else { ++i; cut = i; continue; }

        if (i + need > pending.size()) break;
        bool ok = true;
        for (size_t k = 1; k < need; ++k) {
            if (((unsigned char) pending[i + k] & 0xC0) != 0x80) { ok = false; break; }
        }
        if (!ok) { ++i; cut = i; continue; }
        i += need;
        cut = i;
    }
    if (flush_all) cut = pending.size();
    if (cut == 0) return;

    std::string chunk = pending.substr(0, cut);
    pending.erase(0, cut);
    std::u16string u16 = utf8_to_utf16(chunk);
    jstring js = env->NewString(reinterpret_cast<const jchar *>(u16.data()), (jsize) u16.size());
    if (js) {
        env->CallVoidMethod(sink, mid, js);
        env->DeleteLocalRef(js);
    }
}

// ---------------- engine helpers ----------------

static void clear_kv(Engine & e) {
    if (e.ctx) llama_memory_clear(llama_get_memory(e.ctx), true);
    e.n_past = 0;
}

static std::string format_message(Engine & e, const std::vector<common_chat_msg> & past,
                                  const common_chat_msg & msg, bool add_assistant) {
    if (e.tmpls) {
        try {
            return common_chat_format_single(e.tmpls.get(), past, msg, add_assistant, true);
        } catch (...) {
            LOGW("chat template failed, falling back to raw content");
        }
    }
    return msg.content;
}

static int eval_text(Engine & e, const std::string & text, bool add_special, bool logits_last) {
    std::vector<llama_token> toks = common_tokenize(e.ctx, text, add_special, true);
    common_batch batch(e.ctx);
    for (size_t i = 0; i < toks.size(); i += (size_t) e.n_batch) {
        size_t end = std::min(toks.size(), i + (size_t) e.n_batch);
        batch.clear();
        for (size_t j = i; j < end; ++j) {
            bool want_logits = logits_last && (j + 1 == toks.size());
            batch.add(toks[j], e.n_past++, 0, want_logits);
        }
        if (llama_process(e.ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) return 1;
        if (g_stop.load()) return 2;
    }
    return 0;
}

static void strip_media_markers(std::string & s) {
    const std::string marker = mtmd_default_marker();
    if (marker.empty()) return;
    size_t pos;
    while ((pos = s.find(marker)) != std::string::npos) {
        s.erase(pos, marker.size());
    }
}

static void compact_context(Engine & e) {
    if (e.history.empty()) return;
    clear_kv(e);

    size_t keep = 4;
    size_t start = e.history.size() > keep ? e.history.size() - keep : 0;
    std::vector<common_chat_msg> kept;
    if (start > 0 && e.history.front().role == "system") {
        kept.push_back(e.history.front());
    }
    kept.insert(kept.end(), e.history.begin() + (long) start, e.history.end());
    e.history.clear();

    for (auto & m : kept) {
        strip_media_markers(m.content);
        std::string formatted = format_message(e, e.history, m, m.role == "user");
        if (eval_text(e, formatted, e.history.empty(), false) != 0) {
            clear_kv(e);
            e.history.clear();
            return;
        }
        e.history.push_back(m);
        if (e.n_past > e.n_ctx * 2 / 3) return;
    }
}

static void free_engine(Engine * e) {
    if (!e) return;
    if (e->mctx) mtmd_free(e->mctx);
    e->tmpls.reset();
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_localmodels_app_engine_LlamaBridge_nativeInit(JNIEnv * env, jobject, jstring libDir) {
    if (g_backend_ready) return;
    llama_log_set(log_callback, nullptr);
    if (libDir) {
        std::string dir = jstring_to_utf8(env, libDir);
        ggml_backend_load_all_from_path(dir.c_str());
    }
    llama_backend_init();
    g_backend_ready = true;
    LOGI("backend initialized");
}

JNIEXPORT jint JNICALL
Java_com_localmodels_app_engine_LlamaBridge_load(JNIEnv * env, jobject,
        jstring jmodel, jstring jmmproj, jint nCtx, jint nThreads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_stop.store(false);

    if (g_engine) {
        free_engine(g_engine);
        g_engine = nullptr;
    }

    std::string model_path  = jstring_to_utf8(env, jmodel);
    std::string mmproj_path = jmmproj ? jstring_to_utf8(env, jmmproj) : std::string();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model * model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!model) return 1;

    int trained = llama_model_n_ctx_train(model);
    int ctx_size = nCtx > 0 ? nCtx : 4096;
    if (trained > 0 && ctx_size > trained) ctx_size = trained;
    if (ctx_size < 2048) ctx_size = 2048;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) ctx_size;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;
    cparams.n_seq_max = 1;
    cparams.no_perf = true;
    cparams.abort_callback = [](void *) -> bool { return g_stop.load(); };

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        return 2;
    }

    auto engine = new Engine();
    engine->model = model;
    engine->ctx = ctx;
    engine->n_ctx = ctx_size;

    if (!mmproj_path.empty()) {
        mtmd_context_params mp = mtmd_context_params_default();
        mp.n_threads = nThreads;
        mp.use_gpu = false;
        mp.print_timings = false;
        mp.warmup = false;
        engine->mctx = mtmd_init_from_file(mmproj_path.c_str(), model, mp);
        if (engine->mctx) {
            engine->vision = mtmd_support_vision(engine->mctx);
        } else {
            LOGW("failed to init mmproj: %s", mmproj_path.c_str());
        }
    }

    try {
        engine->tmpls = common_chat_templates_init(model, "");
    } catch (...) {
        LOGW("failed to init chat templates");
        engine->tmpls.reset();
    }
    g_engine = engine;
    LOGI("model loaded, ctx=%d, vision=%d", ctx_size, (int) engine->vision);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_localmodels_app_engine_LlamaBridge_unload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_engine) {
        free_engine(g_engine);
        g_engine = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_localmodels_app_engine_LlamaBridge_reset(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_engine) return;
    g_engine->history.clear();
    clear_kv(*g_engine);
}

JNIEXPORT jboolean JNICALL
Java_com_localmodels_app_engine_LlamaBridge_visionSupported(JNIEnv *, jobject) {
    return (g_engine && g_engine->vision) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_localmodels_app_engine_LlamaBridge_restoreHistory(JNIEnv * env, jobject,
        jobjectArray roles, jobjectArray texts, jstring jsystem) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_engine || !g_engine->ctx) { throw_illegal(env, "model is not loaded"); return; }
    Engine & e = *g_engine;
    g_stop.store(false);
    e.history.clear();
    clear_kv(e);
    try {
        if (!roles || !texts || env->GetArrayLength(roles) != env->GetArrayLength(texts)) {
            throw_illegal(env, "invalid conversation history"); return;
        }
        if (env->GetArrayLength(roles) == 0) return;
        std::vector<common_chat_msg> restored;
        const std::string system = jstring_to_utf8(env, jsystem);
        if (!system.empty()) { common_chat_msg msg; msg.role = "system"; msg.content = system; restored.push_back(msg); }
        const size_t prefix = restored.size();
        for (jsize i = 0; i < env->GetArrayLength(roles); ++i) {
            auto role = (jstring) env->GetObjectArrayElement(roles, i);
            auto text = (jstring) env->GetObjectArrayElement(texts, i);
            common_chat_msg msg;
            msg.role = jstring_to_utf8(env, role);
            msg.content = jstring_to_utf8(env, text);
            env->DeleteLocalRef(role);
            env->DeleteLocalRef(text);
            if (msg.role != "user" && msg.role != "assistant") continue;
            strip_media_markers(msg.content);
            restored.push_back(std::move(msg));
        }
        std::string formatted;
        while (true) {
            if (restored.empty()) return;
            if (e.tmpls) {
                common_chat_templates_inputs inputs;
                inputs.messages = restored;
                inputs.add_generation_prompt = false;
                formatted = common_chat_templates_apply(e.tmpls.get(), inputs).prompt.str();
            } else {
                formatted.clear();
                for (const auto & msg : restored) formatted += msg.role + ": " + msg.content + "\n";
            }
            if (common_tokenize(e.ctx, formatted, true, true).size() <= (size_t) e.n_ctx / 2) break;
            if (restored.size() <= prefix) { throw_illegal(env, "system prompt is too long"); return; }
            const size_t count = std::min((size_t) 2, restored.size() - prefix);
            restored.erase(restored.begin() + prefix, restored.begin() + prefix + count);
        }
        if (eval_text(e, formatted, true, false) != 0) {
            clear_kv(e); throw_illegal(env, g_stop.load() ? "stopped" : "failed to restore conversation"); return;
        }
        e.history = std::move(restored);
        LOGI("restored conversation: %zu messages, %d cached tokens", e.history.size(), (int) e.n_past);
    } catch (const std::exception & error) {
        e.history.clear(); clear_kv(e); throw_illegal(env, error.what());
    }
}

JNIEXPORT void JNICALL
Java_com_localmodels_app_engine_LlamaBridge_stop(JNIEnv *, jobject) {
    g_stop.store(true);
}

JNIEXPORT jstring JNICALL
Java_com_localmodels_app_engine_LlamaBridge_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT void JNICALL
Java_com_localmodels_app_engine_LlamaBridge_chat(JNIEnv * env, jobject,
        jstring jtext, jobjectArray jimages, jintArray jwidths, jintArray jheights,
        jint maxTokens, jfloat temp, jfloat topP, jint topK, jfloat repeatPenalty,
        jstring jsystem, jobject sink) {

    std::lock_guard<std::mutex> lock(g_mutex);
    g_stop.store(false);

    if (!g_engine || !g_engine->ctx) {
        throw_illegal(env, "model is not loaded");
        return;
    }
    Engine & e = *g_engine;

    std::string user_text = jstring_to_utf8(env, jtext);
    int n_images = jimages ? env->GetArrayLength(jimages) : 0;
    if (n_images > 0 && !e.vision) {
        throw_illegal(env, "model does not support images");
        return;
    }

    std::vector<std::vector<unsigned char>> image_bufs;
    std::vector<jint> widths, heights;
    std::vector<mtmd_bitmap *> bitmaps;

    if (n_images > 0) {
        widths.resize((size_t) n_images);
        heights.resize((size_t) n_images);
        env->GetIntArrayRegion(jwidths, 0, n_images, widths.data());
        env->GetIntArrayRegion(jheights, 0, n_images, heights.data());
        image_bufs.resize((size_t) n_images);

        for (int i = 0; i < n_images; ++i) {
            jbyteArray arr = (jbyteArray) env->GetObjectArrayElement(jimages, i);
            if (!arr) continue;
            jsize len = env->GetArrayLength(arr);
            image_bufs[i].resize((size_t) len);
            env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(image_bufs[i].data()));
            env->DeleteLocalRef(arr);

            size_t expected = (size_t) widths[i] * (size_t) heights[i] * 3;
            if (expected != (size_t) len || len == 0) {
                for (auto * b : bitmaps) mtmd_bitmap_free(b);
                throw_illegal(env, "bad image buffer");
                return;
            }
            bitmaps.push_back(mtmd_bitmap_init((uint32_t) widths[i], (uint32_t) heights[i], image_bufs[i].data()));
        }
    }

    std::string content;
    const char * marker = mtmd_default_marker();
    for (size_t i = 0; i < bitmaps.size(); ++i) {
        content += marker;
        content += "\n";
    }
    content += user_text;

    std::string system_text = jsystem ? jstring_to_utf8(env, jsystem) : std::string();
    const std::string previous_system = !e.history.empty() && e.history.front().role == "system"
        ? e.history.front().content : std::string();
    if (previous_system != system_text) {
        e.history.clear();
        clear_kv(e);
    }

    common_chat_msg user_msg;
    user_msg.role = "user";
    user_msg.content = content;

    // The system prompt is injected only at the beginning of the conversation.
    common_chat_msg system_msg;
    bool inject_system = e.history.empty() && !system_text.empty();
    if (inject_system) {
        system_msg.role = "system";
        system_msg.content = system_text;
    }
    std::vector<common_chat_msg> past_for_format;
    if (inject_system) past_for_format.push_back(system_msg);
    past_for_format.insert(past_for_format.end(), e.history.begin(), e.history.end());
    bool add_bos = e.history.empty();

    std::string formatted;
    auto t_start = std::chrono::steady_clock::now();

    auto cleanup_bitmaps = [&]() {
        for (auto * b : bitmaps) mtmd_bitmap_free(b);
        bitmaps.clear();
    };

    bool use_mtmd = (e.mctx != nullptr);
    mtmd_input_chunks * chunks = nullptr;
    std::vector<llama_token> plain_tokens;

    auto prepare = [&]() -> bool {
        past_for_format = e.history;
        if (inject_system) past_for_format.insert(past_for_format.begin(), system_msg);
        add_bos = e.history.empty();
        formatted = format_message(e, past_for_format, user_msg, true);
        if (inject_system) {
            formatted = format_message(e, {}, system_msg, false) + formatted;
        }
        if (use_mtmd) {
            if (chunks) mtmd_input_chunks_free(chunks);
            chunks = mtmd_input_chunks_init();
            mtmd_input_text it{formatted.c_str(), formatted.size(), add_bos, true};
            const mtmd_bitmap * const * bptr = bitmaps.empty() ? nullptr : bitmaps.data();
            int32_t rc = mtmd_tokenize(e.mctx, chunks, &it, bptr, bitmaps.size());
            if (rc != 0) return false;
            return true;
        }
        plain_tokens = common_tokenize(e.ctx, formatted, add_bos, true);
        return true;
    };

    if (!prepare()) {
        if (chunks) mtmd_input_chunks_free(chunks);
        cleanup_bitmaps();
        throw_illegal(env, "failed to tokenize prompt");
        return;
    }

    auto n_prompt_tokens = [&]() -> size_t {
        return use_mtmd ? mtmd_helper_get_n_tokens(chunks) : plain_tokens.size();
    };

    int reserve = std::max(64, (int) maxTokens);
    if (e.n_past + (llama_pos) n_prompt_tokens() + reserve + 16 > e.n_ctx) {
        compact_context(e);
        if (!prepare()) {
            if (chunks) mtmd_input_chunks_free(chunks);
            cleanup_bitmaps();
            throw_illegal(env, "failed to tokenize prompt");
            return;
        }
    }

    int available = e.n_ctx - (int) e.n_past - (int) n_prompt_tokens() - 8;
    if (available < 16) {
        if (chunks) mtmd_input_chunks_free(chunks);
        cleanup_bitmaps();
        throw_illegal(env, "prompt is too long");
        return;
    }
    int n_predict = std::min((int) maxTokens, available);
    if (n_predict < 1) n_predict = 1;

    size_t prompt_tokens = n_prompt_tokens();
    LOGI("prompt: %zu tokens, %d image(s), n_past=%d, n_predict=%d",
         prompt_tokens, n_images, (int) e.n_past, n_predict);

    int eval_rc = 0;
    if (use_mtmd) {
        llama_pos new_past = e.n_past;
        eval_rc = mtmd_helper_eval_chunks(e.mctx, e.ctx, chunks, e.n_past, 0, e.n_batch, true, &new_past);
        if (eval_rc == 0) e.n_past = new_past;
        mtmd_input_chunks_free(chunks);
        chunks = nullptr;
    } else {
        common_batch batch(e.ctx);
        for (size_t i = 0; i < plain_tokens.size(); i += (size_t) e.n_batch) {
            size_t end = std::min(plain_tokens.size(), i + (size_t) e.n_batch);
            batch.clear();
            for (size_t j = i; j < end; ++j) {
                batch.add(plain_tokens[j], e.n_past++, 0, (j + 1 == plain_tokens.size()));
            }
            if (llama_process(e.ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
                eval_rc = 1;
                break;
            }
        }
    }
    cleanup_bitmaps();

    if (eval_rc != 0) {
        e.history.clear();
        clear_kv(e);
        throw_illegal(env, g_stop.load() ? "stopped" : "failed to evaluate prompt");
        return;
    }

    {
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t_start).count();
        double tps = ms > 0 ? (double) prompt_tokens * 1000.0 / (double) ms : 0.0;
        LOGI("prompt eval: %zu tokens in %lld ms (%.1f tok/s)", prompt_tokens, (long long) ms, tps);
    }

    if (inject_system) e.history.push_back(system_msg);
    e.history.push_back(user_msg);

    common_params_sampling sp;
    sp.temp = temp;
    sp.top_p = topP;
    sp.top_k = topK;
    sp.min_p = 0.0f;
    sp.penalty_repeat = repeatPenalty;
    sp.penalty_last_n = 64;
    sp.seed = LLAMA_DEFAULT_SEED;

    common_sampler * smpl = common_sampler_init(e.model, sp);
    if (!smpl) {
        throw_illegal(env, "failed to init sampler");
        return;
    }

    jobject sink_ref = sink ? env->NewGlobalRef(sink) : nullptr;
    jmethodID on_token = nullptr;
    if (sink_ref) {
        jclass cls = env->GetObjectClass(sink_ref);
        on_token = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
    }

    const llama_vocab * vocab = llama_model_get_vocab(e.model);
    std::string response;
    std::string pending_utf8;
    common_batch batch(e.ctx);
    int generated = 0;
    auto t_gen = std::chrono::steady_clock::now();

    for (int i = 0; i < n_predict; ++i) {
        if (g_stop.load()) break;
        llama_token tok = common_sampler_sample(smpl, e.ctx, -1);
        if (tok == LLAMA_TOKEN_NULL) break;
        if (llama_vocab_is_eog(vocab, tok)) break;
        common_sampler_accept(smpl, tok, true);

        std::string piece = common_token_to_piece(e.ctx, tok);
        response += piece;
        pending_utf8 += piece;
        ++generated;
        emit_utf8(env, sink_ref, on_token, pending_utf8, false);

        batch.clear();
        batch.add(tok, e.n_past++, 0, true);
        if (llama_process(e.ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) break;
        if (env->ExceptionCheck()) break;
    }

    emit_utf8(env, sink_ref, on_token, pending_utf8, true);
    common_sampler_free(smpl);
    if (sink_ref) env->DeleteGlobalRef(sink_ref);

    {
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t_gen).count();
        double tps = ms > 0 ? (double) generated * 1000.0 / (double) ms : 0.0;
        LOGI("generation: %d tokens in %lld ms (%.1f tok/s)", generated, (long long) ms, tps);
    }

    if (!response.empty()) {
        common_chat_msg amsg;
        amsg.role = "assistant";
        amsg.content = response;
        e.history.push_back(amsg);
    }
}

} // extern "C"
