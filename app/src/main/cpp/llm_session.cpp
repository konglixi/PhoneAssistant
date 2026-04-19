//
// Adapted from MnnLlmChat for PhoneAssistant
// Removed: Firebase reporting, video processor, benchmark, audio waveform
//

#include "llm_session.h"
#include <utility>
#include <chrono>
#include <algorithm>
#include "MNN/MNNForwardType.h"
#include "MNN/expr/ExecutorScope.hpp"
#include "mls_log.h"
#include "mls_config.h"
#include "utf8_stream_processor.hpp"
#include "llm_stream_buffer.hpp"
#include "processor.h"

namespace mls {

namespace {

void restoreAndroidSteppingStatusIfNeeded(Llm* llm) {
    if (llm == nullptr) {
        return;
    }
    auto* context = llm->getContext();
    if (context == nullptr) {
        return;
    }
    if (context->status == MNN::Transformer::LlmStatus::MAX_TOKENS_FINISHED ||
        context->status == MNN::Transformer::LlmStatus::NORMAL_FINISHED) {
        auto* mutable_context = const_cast<MNN::Transformer::LlmContext*>(context);
        mutable_context->status = MNN::Transformer::LlmStatus::RUNNING;
    }
}

struct AndroidSteppingStreamState {
    std::stringstream& response_buffer;
    const std::function<bool(const std::string&, bool is_eop)>& on_progress;
    bool& generate_text_end;
    bool& stop_requested;
    std::string& response_string_for_debug;
    std::function<void(const std::string&)> on_response_complete;
    const char* result_log_tag;
    bool pending_eop = false;

    void processChunk(const std::string& utf8Char) {
        const bool is_eop = utf8Char.find("<eop>") != std::string::npos;
        if (!is_eop) {
            response_buffer << utf8Char;
            if (on_progress) {
                stop_requested = stop_requested || on_progress(utf8Char, false);
            }
            return;
        }
        pending_eop = true;
    }

    void finalizePendingEop() {
        if (!pending_eop) {
            return;
        }
        std::string response_result = response_buffer.str();
        MNN_DEBUG("%s %s", result_log_tag, response_result.c_str());
        response_string_for_debug = response_result;
        on_response_complete(response_result);
        if (on_progress) {
            stop_requested = stop_requested || on_progress("<eop>", true);
        }
        generate_text_end = true;
        pending_eop = false;
    }
};

void resolveAndroidSteppingEop(
        Llm* llm,
        AndroidSteppingStreamState& stream_state,
        int current_size,
        int max_new_tokens) {
    auto* context = llm != nullptr ? llm->getContext() : nullptr;
    if (context != nullptr &&
        context->status == MNN::Transformer::LlmStatus::MAX_TOKENS_FINISHED &&
        !stream_state.stop_requested &&
        current_size < max_new_tokens) {
        restoreAndroidSteppingStatusIfNeeded(llm);
        if (stream_state.pending_eop) {
            stream_state.generate_text_end = false;
            stream_state.pending_eop = false;
        }
        return;
    }
    if (context != nullptr &&
        context->status == MNN::Transformer::LlmStatus::NORMAL_FINISHED &&
        !stream_state.pending_eop &&
        !stream_state.stop_requested &&
        current_size < max_new_tokens) {
        restoreAndroidSteppingStatusIfNeeded(llm);
        return;
    }
    if (stream_state.pending_eop) {
        stream_state.finalizePendingEop();
    }
}

} // anonymous namespace

std::string trimLeadingWhitespace(const std::string& str) {
    auto it = std::find_if(str.begin(), str.end(), [](unsigned char ch) {
        return !std::isspace(ch);
    });
    return {it, str.end()};
}

std::string getUserString(const char* user_content, bool for_history, bool is_r1) {
    if (is_r1) {
        return "<|User|>" + std::string(user_content) + "<|Assistant|>" + (for_history ? "" : "<think>\n");
    } else {
        return user_content;
    }
}

std::string GetSystemPromptString(std::string system_prompt, bool is_r1) {
    if (is_r1) {
        return std::string("<|begin_of_sentence|>") + system_prompt;
    } else {
        return system_prompt;
    }
}

std::string deleteThinkPart(std::string assistant_content) {
    std::size_t think_start = assistant_content.find("<think>");
    if (think_start == std::string::npos) {
        return assistant_content;
    }
    std::size_t think_end = assistant_content.find("</think>", think_start);
    if (think_end == std::string::npos) {
        return assistant_content;
    }
    think_end += std::string("</think>").length();
    assistant_content.erase(think_start, think_end - think_start);
    return assistant_content;
}

std::string getR1AssistantString(std::string assistant_content) {
    std::size_t pos = assistant_content.find("</think>");
    if (pos != std::string::npos) {
        assistant_content.erase(0, pos + std::string("</think>").length());
    }
    return trimLeadingWhitespace(assistant_content) + "<|end_of_sentence|>";
}

mls::PromptProcessingResult processMultimodalPrompt(const std::string& prompt_text) {
    mls::PromptProcessor processor;
    return processor.Process(prompt_text);
}

void LlmSession::Reset() {
    history_.resize(1);
    if (llm_) {
        llm_->reset();
    }
}

LlmSession::LlmSession(std::string model_path, json config, json extra_config, std::vector<std::string> history):
        model_path_(std::move(model_path)), config_(std::move(config)), extra_config_(std::move(extra_config)) {
    max_new_tokens_ = config_.contains("max_new_tokens") ? config_["max_new_tokens"].get<int>() : 2048;
    keep_history_ = !extra_config_.contains("keep_history") || extra_config_["keep_history"].get<bool>();
    is_r1_ = extra_config_.contains("is_r1") && extra_config_["is_r1"].get<bool>();
    system_prompt_ = config_.contains("system_prompt") ? config_["system_prompt"].get<std::string>() : "You are a helpful assistant.";
    history_.emplace_back("system", GetSystemPromptString(system_prompt_, is_r1_));
    if (!history.empty()) {
        for (size_t i = 0; i < history.size(); i++) {
            if (is_r1_) {
                if (i % 2 == 0) {
                    history_.emplace_back("user", getUserString(history[i].c_str(), true, is_r1_));
                } else {
                    history_.emplace_back("assistant", getR1AssistantString(history[i]));
                }
            } else {
                history_.emplace_back(i % 2 == 0 ? "user" : "assistant",
                                      i % 2 == 0 ? history[i] :
                                      deleteThinkPart(history[i]));
            }
        }
    }
}

bool LlmSession::Load() {
    last_load_error_.clear();
    bool use_mmap = extra_config_.contains("mmap_dir") &&
                    !extra_config_["mmap_dir"].get<std::string>().empty();
    llm_ = Llm::createLLM(model_path_);
    if (llm_ == nullptr) {
        last_load_error_ = "createLLM failed for config path: " + model_path_ +
            " (config file missing or invalid)";
        MNN_DEBUG("Failed to create LLM instance: %s", last_load_error_.c_str());
        model_loaded_ = false;
        return false;
    }
    json config = config_;
    config["use_mmap"] = use_mmap;
    if (use_mmap) {
        config["tmp_path"] = extra_config_["mmap_dir"].get<std::string>();
    }
    if (is_r1_) {
        config["use_template"] = false;
        config["precision"] = "high";
    }
    current_config_ = config;
    auto config_str = config.dump();
    MNN_DEBUG("config: %s", config_str.c_str());
    llm_->set_config(config_str);
    MNN_DEBUG("dumped config: %s", llm_->dump_config().c_str());
    model_loaded_ = llm_->load();
    if (!model_loaded_) {
        last_load_error_ = "Module load failed for config: " + model_path_ +
            ". Common causes: model file (.mnn) missing or corrupted, wrong backend, insufficient memory.";
        MNN_DEBUG("Model load() returned false: %s", last_load_error_.c_str());
    }
    return model_loaded_;
}

LlmSession::~LlmSession() {
    MNN_DEBUG("LIFECYCLE: LlmSession DESTROYED at %p", this);
    delete llm_;
}

const MNN::Transformer::LlmContext * LlmSession::Response(const std::string &prompt,
                                                          const std::function<bool(const std::string&, bool is_eop)>& on_progress) {
    if (llm_ == nullptr) {
        return nullptr;
    }

    if (!keep_history_) {
        history_.resize(1);
    }
    int current_size = 0;
    stop_requested_ = false;
    generate_text_end_ = false;
    std::stringstream response_buffer;
    AndroidSteppingStreamState stream_state{
            response_buffer,
            on_progress,
            generate_text_end_,
            stop_requested_,
            response_string_for_debug,
            [this](const std::string& raw_response) {
                std::string response_result = raw_response;
                if (is_r1_) {
                    auto& last_message = history_.at(history_.size() - 1);
                    std::size_t user_think_pos = last_message.second.find("<think>\n");
                    if (user_think_pos != std::string::npos) {
                        last_message.second.erase(user_think_pos, std::string("<think>\n").length());
                    }
                    response_result = getR1AssistantString(response_result);
                }
                response_result = trimLeadingWhitespace(deleteThinkPart(response_result));
                history_.emplace_back("assistant", response_result);
            },
            "submitNative Result"
    };
    mls::Utf8StreamProcessor processor([&stream_state](const std::string& utf8Char) {
        stream_state.processChunk(utf8Char);
    });
    LlmStreamBuffer stream_buffer{[&processor](const char* str, size_t len){
        processor.processStream(str, len);
    }};
    std::ostream output_ostream(&stream_buffer);
    history_.emplace_back("user", getUserString(prompt.c_str(), false, is_r1_));
    MNN_DEBUG("submitNative history count %zu", history_.size());

    std::string full_prompt_text;
    for (auto & it : history_) {
        full_prompt_text += it.second;
        prompt_string_for_debug += it.second;
    }

    MNN_DEBUG("submitNative max_new_tokens_:%d", max_new_tokens_);

    auto multimodal_result = processMultimodalPrompt(full_prompt_text);
    restoreAndroidSteppingStatusIfNeeded(llm_);
    if (multimodal_result.has_multimodal) {
        llm_->response(multimodal_result.multimodal_prompt, &output_ostream, "<eop>", 0);
    } else {
        llm_->response(history_, &output_ostream, "<eop>", 0);
    }
    resolveAndroidSteppingEop(llm_, stream_state, current_size, max_new_tokens_);
    while (!stop_requested_ && !generate_text_end_ && current_size < max_new_tokens_) {
        llm_->generate(1);
        current_size++;
        resolveAndroidSteppingEop(llm_, stream_state, current_size, max_new_tokens_);
    }

    size_t history_after = history_.size();
    stream_state.finalizePendingEop();
    if (history_.size() > history_after) {
        llm_->syncPromptCache(history_);
    } else if (current_size > 0) {
        std::string response_result = response_buffer.str();
        if (is_r1_) {
            auto& last_message = history_.at(history_.size() - 1);
            std::size_t user_think_pos = last_message.second.find("<think>\n");
            if (user_think_pos != std::string::npos) {
                last_message.second.erase(user_think_pos, std::string("<think>\n").length());
            }
            response_result = getR1AssistantString(response_result);
        }
        response_result = trimLeadingWhitespace(deleteThinkPart(response_result));
        if (!response_result.empty()) {
            history_.emplace_back("assistant", response_result);
        }
        llm_->syncPromptCache(history_);
    }

    auto context = llm_->getContext();
    float prefill_s = context->prefill_us / 1e6f;
    float decode_s = context->decode_us / 1e6f;
    float prefill_tps = (prefill_s > 0) ? context->prompt_len / prefill_s : 0;
    float decode_tps = (decode_s > 0) ? context->gen_seq_len / decode_s : 0;
    MNN_DEBUG("PERF | prefill: %d tok in %.2fs (%.1f t/s) | decode: %d tok in %.2fs (%.1f t/s) | history: %zu msgs",
              context->prompt_len, prefill_s, prefill_tps,
              context->gen_seq_len, decode_s, decode_tps,
              history_.size());
    return context;
}

const MNN::Transformer::LlmContext * LlmSession::ResponseWithHistory(
        const std::vector<PromptItem>& full_history,
        const std::function<bool(const std::string&, bool is_eop)>& on_progress) {
    if (llm_ == nullptr) {
        return nullptr;
    }

    std::vector<PromptItem> temp_history;
    temp_history.insert(temp_history.end(), full_history.begin(), full_history.end());

    int current_size = 0;
    stop_requested_ = false;
    generate_text_end_ = false;
    std::stringstream response_buffer;

    AndroidSteppingStreamState stream_state{
            response_buffer,
            on_progress,
            generate_text_end_,
            stop_requested_,
            response_string_for_debug,
            [this](const std::string& raw_response) {
                std::string response_result = raw_response;
                if (is_r1_) {
                    response_result = getR1AssistantString(response_result);
                }
                response_result = trimLeadingWhitespace(deleteThinkPart(response_result));
            },
            "ResponseWithHistory Result"
    };
    mls::Utf8StreamProcessor processor([&stream_state](const std::string& utf8Char) {
        stream_state.processChunk(utf8Char);
    });

    LlmStreamBuffer stream_buffer{[&processor](const char* str, size_t len){
        processor.processStream(str, len);
    }};
    std::ostream output_ostream(&stream_buffer);

    MNN_DEBUG("ResponseWithHistory history count %zu", temp_history.size());
    prompt_string_for_debug.clear();

    std::string full_prompt_text;
    for (const auto& it : temp_history) {
        prompt_string_for_debug += "[" + it.first + "]: " + it.second + "\n";
        full_prompt_text += it.second;
    }

    auto multimodal_result = processMultimodalPrompt(full_prompt_text);
    restoreAndroidSteppingStatusIfNeeded(llm_);
    if (multimodal_result.has_multimodal) {
        llm_->response(multimodal_result.multimodal_prompt, &output_ostream, "<eop>", 0);
    } else {
        llm_->response(temp_history, &output_ostream, "<eop>", 0);
    }
    size_t kv_before_decode = llm_->getCurrentHistory();
    resolveAndroidSteppingEop(llm_, stream_state, current_size, max_new_tokens_);

    while (!stop_requested_ && !generate_text_end_ && current_size < max_new_tokens_) {
        llm_->generate(1);
        current_size++;
        resolveAndroidSteppingEop(llm_, stream_state, current_size, max_new_tokens_);
    }

    stream_state.finalizePendingEop();
    if (!stop_requested_) {
        std::string response_result = response_buffer.str();
        if (is_r1_) {
            response_result = getR1AssistantString(response_result);
        }
        response_result = trimLeadingWhitespace(deleteThinkPart(response_result));
        if (!response_result.empty()) {
            temp_history.emplace_back("assistant", response_result);
        }
        llm_->syncPromptCache(temp_history);
    } else if (current_size > 0) {
        llm_->eraseHistory(kv_before_decode, 0);
    }

    return llm_->getContext();
}

std::string LlmSession::getDebugInfo() {
    return ("last_prompt:\n" + prompt_string_for_debug + "\nlast_response:\n" + response_string_for_debug);
}

void LlmSession::SetMaxNewTokens(int i) {
    max_new_tokens_ = i;
}

void LlmSession::setSystemPrompt(std::string system_prompt) {
    system_prompt_ = std::move(system_prompt);
    if (history_.size() > 1) {
        history_.at(0).second = GetSystemPromptString(system_prompt_, is_r1_);
    } else {
        history_.emplace_back("system", GetSystemPromptString(system_prompt_, is_r1_));
    }
}

void LlmSession::SetAssistantPrompt(const std::string& assistant_prompt) {
    current_config_["assistant_prompt_template"] = assistant_prompt;
    if (llm_) {
        auto config_str = current_config_.dump();
        llm_->set_config(config_str);
    }
    MNN_DEBUG("dumped config: %s", llm_->dump_config().c_str());
}

void LlmSession::updateConfig(const std::string& config_json) {
    json new_config = json::parse(config_json, nullptr, false);
    if (new_config.is_null()) {
        MNN_ERROR("Failed to parse config JSON: invalid JSON format");
        return;
    }

    for (auto& [key, value] : new_config.items()) {
        current_config_[key] = value;
    }
    if (llm_) {
        auto config_str = current_config_.dump();
        llm_->set_config(config_str);
        MNN_DEBUG("Updated config applied: %s", current_config_.dump().c_str());
    } else {
        MNN_DEBUG("LLM not initialized yet, config saved for later: %s", current_config_.dump().c_str());
    }
}

std::string LlmSession::getSystemPrompt() const {
    return system_prompt_;
}

void LlmSession::clearHistory(int numToKeep) {
    if (numToKeep < 0) {
        numToKeep = 0;
    }
    if (history_.size() > static_cast<size_t>(numToKeep)) {
        history_.erase(history_.begin() + numToKeep, history_.end());
    }
    prompt_string_for_debug.clear();
    if (llm_) {
        llm_->reset();
    }
}

std::string LlmSession::dumpConfig() const {
    if (llm_ != nullptr) {
        return llm_->dump_config();
    }
    return "{}";
}

void LlmSession::setEnableAudioOutput(bool enable) {
    audio_output_enabled_ = enable;
    if (llm_ != nullptr) {
        json audio_config;
        audio_config["audio_output"] = enable;
        llm_->set_config(audio_config.dump());
    }
}

void LlmSession::setWaveformCallback(WaveformCallback callback) {
    waveform_callback_ = std::move(callback);
}

} // namespace mls
