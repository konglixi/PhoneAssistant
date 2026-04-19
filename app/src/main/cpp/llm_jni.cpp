//
// JNI bridge for PhoneAssistant MNN LLM integration
// Adapted from MnnLlmChat's llm_mnn_jni.cpp
// Package: dev.phoneassistant.offline.planner.MnnLlmBridge
//

#include <android/log.h>
#include <jni.h>
#include <string>
#include <vector>
#include <ostream>
#include <sstream>
#include "mls_log.h"
#include "MNN/expr/ExecutorScope.hpp"
#include "nlohmann/json.hpp"
#include "llm_stream_buffer.hpp"
#include "utf8_stream_processor.hpp"
#include "llm_session.h"

using MNN::Transformer::Llm;
using json = nlohmann::json;

namespace {
JavaVM* g_jvm = nullptr;
jobject g_waveformListener = nullptr;
}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    MNN_DEBUG("PhoneAssistant JNI_OnLoad");
    return JNI_VERSION_1_4;
}

JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *reserved) {
    g_jvm = nullptr;
    MNN_DEBUG("PhoneAssistant JNI_OnUnload");
}

// ---------- Lifecycle ----------

JNIEXPORT jlong JNICALL
Java_dev_phoneassistant_offline_planner_MnnLlmBridge_initNative(
        JNIEnv *env, jobject thiz,
        jstring modelDir,
        jobject chat_history,
        jstring mergeConfigStr,
        jstring configJsonStr) {

    const char *model_dir = env->GetStringUTFChars(modelDir, nullptr);
    auto model_dir_str = std::string(model_dir);
    const char *config_json_cstr = env->GetStringUTFChars(configJsonStr, nullptr);
    const char *merged_config_cstr = env->GetStringUTFChars(mergeConfigStr, nullptr);
    json merged_config = json::parse(merged_config_cstr);
    json extra_json_config = json::parse(config_json_cstr);
    env->ReleaseStringUTFChars(modelDir, model_dir);
    env->ReleaseStringUTFChars(configJsonStr, config_json_cstr);
    env->ReleaseStringUTFChars(mergeConfigStr, merged_config_cstr);

    MNN_DEBUG("createLLM BeginLoad %s", model_dir_str.c_str());

    std::vector<std::string> history;
    if (chat_history != nullptr) {
        jclass listClass = env->GetObjectClass(chat_history);
        jmethodID sizeMethod = env->GetMethodID(listClass, "size", "()I");
        jmethodID getMethod = env->GetMethodID(listClass, "get", "(I)Ljava/lang/Object;");
        jint listSize = env->CallIntMethod(chat_history, sizeMethod);
        for (jint i = 0; i < listSize; i++) {
            jobject element = env->CallObjectMethod(chat_history, getMethod, i);
            const char *elementCStr = env->GetStringUTFChars((jstring) element, nullptr);
            history.emplace_back(elementCStr);
            env->ReleaseStringUTFChars((jstring) element, elementCStr);
            env->DeleteLocalRef(element);
        }
    }

    auto llm_session = new mls::LlmSession(model_dir_str, merged_config, extra_json_config, history);
    bool load_success = llm_session->Load();
    MNN_DEBUG("LIFECYCLE: LlmSession CREATED at %p, load_success=%d", llm_session, load_success);

    if (!load_success || !llm_session->isModelReady()) {
        std::string err_msg = llm_session->getLastLoadError();
        if (err_msg.empty()) {
            err_msg = "Model load failed for config: " + model_dir_str;
        }
        MNN_DEBUG("Model load failed, cleaning up LlmSession: %s", err_msg.c_str());
        delete llm_session;
        jclass exClass = env->FindClass("java/lang/IllegalStateException");
        if (exClass) {
            env->ThrowNew(exClass, err_msg.c_str());
        }
        return 0;
    }

    MNN_DEBUG("createLLM EndLoad %ld", reinterpret_cast<jlong>(llm_session));
    return reinterpret_cast<jlong>(llm_session);
}

JNIEXPORT void JNICALL
Java_dev_phoneassistant_offline_planner_MnnLlmBridge_releaseNative(
        JNIEnv *env, jobject thiz, jlong objecPtr) {
    MNN_DEBUG("LIFECYCLE: About to DESTROY LlmSession at %p", reinterpret_cast<void*>(objecPtr));
    auto *llm = reinterpret_cast<mls::LlmSession *>(objecPtr);
    delete llm;
    MNN_DEBUG("LIFECYCLE: LlmSession DESTROYED");
}

// ---------- Inference ----------

JNIEXPORT jobject JNICALL
Java_dev_phoneassistant_offline_planner_MnnLlmBridge_submitNative(
        JNIEnv *env, jobject thiz,
        jlong llmPtr, jstring inputStr,
        jboolean keepHistory,
        jobject progressListener) {

    auto *llm = reinterpret_cast<mls::LlmSession *>(llmPtr);

    // Prepare error HashMap helper
    auto makeErrorMap = [&](const char* msg) -> jobject {
        jclass hashMapClass = env->FindClass("java/util/HashMap");
        jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
        jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                               "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
        env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("error"),
                              env->NewStringUTF(msg));
        return hashMap;
    };

    if (!llm) {
        return makeErrorMap("Failed, Chat is not ready!");
    }

    const char *input_str = env->GetStringUTFChars(inputStr, nullptr);
    jclass progressListenerClass = env->GetObjectClass(progressListener);
    jmethodID onProgressMethod = env->GetMethodID(progressListenerClass, "onProgress",
                                                  "(Ljava/lang/String;)Z");

    auto *context = llm->Response(input_str, [&, progressListener, onProgressMethod](
            const std::string &response, bool is_eop) {
        if (progressListener && onProgressMethod) {
            jstring javaString = is_eop ? nullptr : env->NewStringUTF(response.c_str());
            jboolean user_stop_requested = env->CallBooleanMethod(progressListener,
                                                                  onProgressMethod, javaString);
            if (javaString) {
                env->DeleteLocalRef(javaString);
            }
            return (bool) user_stop_requested;
        } else {
            return true;
        }
    });

    env->ReleaseStringUTFChars(inputStr, input_str);

    int64_t prompt_len = 0;
    int64_t decode_len = 0;
    int64_t prefill_time = 0;
    int64_t decode_time = 0;
    if (context) {
        prompt_len = context->prompt_len;
        decode_len = context->gen_seq_len;
        prefill_time = context->prefill_us;
        decode_time = context->decode_us;
    }

    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                           "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);

    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClass, "<init>", "(J)V");

    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prompt_len"),
                          env->NewObject(longClass, longInit, prompt_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_len"),
                          env->NewObject(longClass, longInit, decode_len));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("prefill_time"),
                          env->NewObject(longClass, longInit, prefill_time));
    env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("decode_time"),
                          env->NewObject(longClass, longInit, decode_time));

    return hashMap;
}

JNIEXPORT jobject JNICALL
Java_dev_phoneassistant_offline_planner_MnnLlmBridge_submitFullHistoryNative(
        JNIEnv *env, jobject thiz,
        jlong llmPtr, jobject historyList,
        jobject progressListener) {

    auto *llm = reinterpret_cast<mls::LlmSession *>(llmPtr);

    auto makeErrorMap = [&](const char* msg) -> jobject {
        jclass hashMapClass = env->FindClass("java/util/HashMap");
        jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
        jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                               "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
        env->CallObjectMethod(hashMap, putMethod, env->NewStringUTF("error"),
                              env->NewStringUTF(msg));
        return hashMap;
    };

    if (!llm) {
        return makeErrorMap("Failed, Chat is not ready!");
    }

    // Parse Java List<Pair<String, String>> to C++ vector
    std::vector<mls::PromptItem> history;

    jclass listClass = env->GetObjectClass(historyList);
    jmethodID sizeMethod = env->GetMethodID(listClass, "size", "()I");
    jmethodID getMethod = env->GetMethodID(listClass, "get", "(I)Ljava/lang/Object;");
    jint listSize = env->CallIntMethod(historyList, sizeMethod);

    jclass pairClass = env->FindClass("android/util/Pair");
    if (pairClass == nullptr) {
        return makeErrorMap("Failed to find android.util.Pair class");
    }
    jfieldID firstField = env->GetFieldID(pairClass, "first", "Ljava/lang/Object;");
    jfieldID secondField = env->GetFieldID(pairClass, "second", "Ljava/lang/Object;");

    for (jint i = 0; i < listSize; i++) {
        jobject pairObj = env->CallObjectMethod(historyList, getMethod, i);
        if (pairObj == nullptr) continue;

        jobject roleObj = env->GetObjectField(pairObj, firstField);
        jobject contentObj = env->GetObjectField(pairObj, secondField);

        const char *role = nullptr;
        const char *content = nullptr;
        if (roleObj) role = env->GetStringUTFChars((jstring) roleObj, nullptr);
        if (contentObj) content = env->GetStringUTFChars((jstring) contentObj, nullptr);

        if (role && content) {
            history.emplace_back(std::string(role), std::string(content));
        }

        if (role) env->ReleaseStringUTFChars((jstring) roleObj, role);
        if (content) env->ReleaseStringUTFChars((jstring) contentObj, content);
        env->DeleteLocalRef(pairObj);
        if (roleObj) env->DeleteLocalRef(roleObj);
        if (contentObj) env->DeleteLocalRef(contentObj);
    }

    jclass progressListenerClass = env->GetObjectClass(progressListener);
    jmethodID onProgressMethod = env->GetMethodID(progressListenerClass, "onProgress",
                                                  "(Ljava/lang/String;)Z");

    auto *context = llm->ResponseWithHistory(history, [&, progressListener, onProgressMethod](
            const std::string &response, bool is_eop) {
        if (progressListener && onProgressMethod) {
            jstring javaString = is_eop ? nullptr : env->NewStringUTF(response.c_str());
            jboolean user_stop_requested = env->CallBooleanMethod(progressListener,
                                                                  onProgressMethod, javaString);
            if (javaString) env->DeleteLocalRef(javaString);
            return (bool) user_stop_requested;
        } else {
            return true;
        }
    });

    int64_t prompt_len = 0;
    int64_t decode_len = 0;
    int64_t prefill_time = 0;
    int64_t decode_time = 0;
    if (context) {
        prompt_len = context->prompt_len;
        decode_len = context->gen_seq_len;
        prefill_time = context->prefill_us;
        decode_time = context->decode_us;
    }

    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID hashMapPut = env->GetMethodID(hashMapClass, "put",
                                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClass, "<init>", "(J)V");

    env->CallObjectMethod(hashMap, hashMapPut, env->NewStringUTF("prompt_len"),
                          env->NewObject(longClass, longInit, prompt_len));
    env->CallObjectMethod(hashMap, hashMapPut, env->NewStringUTF("decode_len"),
                          env->NewObject(longClass, longInit, decode_len));
    env->CallObjectMethod(hashMap, hashMapPut, env->NewStringUTF("prefill_time"),
                          env->NewObject(longClass, longInit, prefill_time));
    env->CallObjectMethod(hashMap, hashMapPut, env->NewStringUTF("decode_time"),
                          env->NewObject(longClass, longInit, decode_time));

    return hashMap;
}

// ---------- State Management ----------

JNIEXPORT void JNICALL
Java_dev_phoneassistant_offline_planner_MnnLlmBridge_resetNative(
        JNIEnv *env, jobject thiz, jlong object_ptr) {
    auto *llm = reinterpret_cast<mls::LlmSession *>(object_ptr);
    if (llm) {
        MNN_DEBUG("RESET");
        llm->Reset();
    }
}

JNIEXPORT void JNICALL
Java_dev_phoneassistant_offline_planner_MnnLlmBridge_clearHistoryNative(
        JNIEnv *env, jobject thiz, jlong llm_ptr) {
    auto *llm = reinterpret_cast<mls::LlmSession *>(llm_ptr);
    if (llm) {
        llm->clearHistory();
    }
}

// ---------- Configuration ----------

JNIEXPORT void JNICALL
Java_dev_phoneassistant_offline_planner_MnnLlmBridge_updateMaxNewTokensNative(
        JNIEnv *env, jobject thiz, jlong llm_ptr, jint max_new_tokens) {
    auto *llm = reinterpret_cast<mls::LlmSession *>(llm_ptr);
    if (llm) {
        llm->SetMaxNewTokens(max_new_tokens);
    }
}

JNIEXPORT void JNICALL
Java_dev_phoneassistant_offline_planner_MnnLlmBridge_updateSystemPromptNative(
        JNIEnv *env, jobject thiz, jlong llm_ptr, jstring system_prompt_j) {
    auto *llm = reinterpret_cast<mls::LlmSession *>(llm_ptr);
    const char *system_prompt_cstr = env->GetStringUTFChars(system_prompt_j, nullptr);
    if (llm) {
        llm->setSystemPrompt(system_prompt_cstr);
    }
    env->ReleaseStringUTFChars(system_prompt_j, system_prompt_cstr);
}

JNIEXPORT void JNICALL
Java_dev_phoneassistant_offline_planner_MnnLlmBridge_updateAssistantPromptNative(
        JNIEnv *env, jobject thiz, jlong llm_ptr, jstring assistant_prompt_j) {
    auto *llm = reinterpret_cast<mls::LlmSession *>(llm_ptr);
    const char *assistant_prompt_cstr = env->GetStringUTFChars(assistant_prompt_j, nullptr);
    if (llm) {
        llm->SetAssistantPrompt(assistant_prompt_cstr);
    }
    env->ReleaseStringUTFChars(assistant_prompt_j, assistant_prompt_cstr);
}

JNIEXPORT void JNICALL
Java_dev_phoneassistant_offline_planner_MnnLlmBridge_updateConfigNative(
        JNIEnv *env, jobject thiz, jlong llm_ptr, jstring config_json_j) {
    auto *llm = reinterpret_cast<mls::LlmSession *>(llm_ptr);
    const char *config_json_cstr = env->GetStringUTFChars(config_json_j, nullptr);
    if (llm) {
        llm->updateConfig(config_json_cstr);
    }
    env->ReleaseStringUTFChars(config_json_j, config_json_cstr);
}

JNIEXPORT jstring JNICALL
Java_dev_phoneassistant_offline_planner_MnnLlmBridge_getSystemPromptNative(
        JNIEnv *env, jobject thiz, jlong llm_ptr) {
    auto *llm = reinterpret_cast<mls::LlmSession *>(llm_ptr);
    if (llm) {
        std::string system_prompt = llm->getSystemPrompt();
        return env->NewStringUTF(system_prompt.c_str());
    }
    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_dev_phoneassistant_offline_planner_MnnLlmBridge_getDebugInfoNative(
        JNIEnv *env, jobject thiz, jlong objecPtr) {
    auto *llm = reinterpret_cast<mls::LlmSession *>(objecPtr);
    if (llm == nullptr) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(llm->getDebugInfo().c_str());
}

JNIEXPORT jstring JNICALL
Java_dev_phoneassistant_offline_planner_MnnLlmBridge_dumpConfigNative(
        JNIEnv *env, jobject thiz, jlong objecPtr) {
    auto *llm = reinterpret_cast<mls::LlmSession *>(objecPtr);
    if (llm == nullptr) {
        return env->NewStringUTF("{}");
    }
    return env->NewStringUTF(llm->dumpConfig().c_str());
}

JNIEXPORT void JNICALL
Java_dev_phoneassistant_offline_planner_MnnLlmBridge_updateEnableAudioOutputNative(
        JNIEnv *env, jobject thiz, jlong objecPtr, jboolean enable) {
    auto *llm = reinterpret_cast<mls::LlmSession *>(objecPtr);
    if (llm != nullptr) {
        llm->setEnableAudioOutput(enable);
    }
}

JNIEXPORT void JNICALL
Java_dev_phoneassistant_offline_planner_MnnLlmBridge_setWaveformCallbackNative(
        JNIEnv *env, jobject thiz, jlong objecPtr, jobject listener) {
    auto *llm = reinterpret_cast<mls::LlmSession *>(objecPtr);
    if (llm == nullptr) return;

    // Release previous global ref
    if (g_waveformListener != nullptr) {
        env->DeleteGlobalRef(g_waveformListener);
        g_waveformListener = nullptr;
    }

    if (listener != nullptr) {
        g_waveformListener = env->NewGlobalRef(listener);

        llm->setWaveformCallback([](const float* data, int size, int sampleRate) {
            if (g_jvm == nullptr || g_waveformListener == nullptr) return;

            JNIEnv* callbackEnv = nullptr;
            bool didAttach = false;
            int getEnvStat = g_jvm->GetEnv(reinterpret_cast<void**>(&callbackEnv), JNI_VERSION_1_6);
            if (getEnvStat == JNI_EDETACHED) {
                g_jvm->AttachCurrentThread(&callbackEnv, nullptr);
                didAttach = true;
            }

            if (callbackEnv != nullptr) {
                jclass listenerClass = callbackEnv->GetObjectClass(g_waveformListener);
                jmethodID onAudioDataMethod = callbackEnv->GetMethodID(
                    listenerClass, "onAudioData", "([FI)V");
                if (onAudioDataMethod != nullptr) {
                    jfloatArray jData = callbackEnv->NewFloatArray(size);
                    callbackEnv->SetFloatArrayRegion(jData, 0, size, data);
                    callbackEnv->CallVoidMethod(g_waveformListener, onAudioDataMethod, jData, sampleRate);
                    callbackEnv->DeleteLocalRef(jData);
                }
                callbackEnv->DeleteLocalRef(listenerClass);
            }

            if (didAttach) {
                g_jvm->DetachCurrentThread();
            }
        });
    } else {
        llm->setWaveformCallback(nullptr);
    }
}

} // extern "C"
