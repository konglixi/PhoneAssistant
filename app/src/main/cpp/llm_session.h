//
// Adapted from MnnLlmChat for PhoneAssistant
// Supports: text, multimodal, audio output
//
#pragma once
#include <vector>
#include <string>
#include <functional>
#include "nlohmann/json.hpp"
#include "llm/llm.hpp"

using nlohmann::json;
using MNN::Transformer::Llm;

namespace mls {
using PromptItem = std::pair<std::string, std::string>;

class LlmSession {
public:
    LlmSession(std::string model_path, json config, json extra_config, std::vector<std::string> string_history);
    ~LlmSession();

    bool Load();
    void Reset();
    bool isModelReady() const { return llm_ != nullptr && model_loaded_; }
    const std::string& getLastLoadError() const { return last_load_error_; }

    const MNN::Transformer::LlmContext *
    Response(const std::string &prompt, const std::function<bool(const std::string &, bool is_eop)> &on_progress);

    const MNN::Transformer::LlmContext *
    ResponseWithHistory(const std::vector<PromptItem>& full_history,
                        const std::function<bool(const std::string &, bool is_eop)> &on_progress);

    void SetMaxNewTokens(int i);
    void setSystemPrompt(std::string system_prompt);
    void SetAssistantPrompt(const std::string& assistant_prompt);
    void updateConfig(const std::string& config_json);
    std::string getSystemPrompt() const;
    void clearHistory(int numToKeep = 1);
    std::string getDebugInfo();
    std::string dumpConfig() const;

    // Audio output support
    void setEnableAudioOutput(bool enable);
    bool isAudioOutputEnabled() const { return audio_output_enabled_; }

    using WaveformCallback = std::function<void(const float* data, int size, int sampleRate)>;
    void setWaveformCallback(WaveformCallback callback);

private:
    std::string response_string_for_debug{};
    std::string model_path_;
    std::vector<PromptItem> history_{};
    json extra_config_{};
    json config_{};
    bool is_r1_{false};
    bool stop_requested_{false};
    bool generate_text_end_{false};
    bool keep_history_{true};
    Llm* llm_{nullptr};
    bool model_loaded_{false};
    std::string prompt_string_for_debug{};
    int max_new_tokens_{2048};
    std::string system_prompt_;
    json current_config_{};
    std::string last_load_error_{};

    // Audio output
    bool audio_output_enabled_{false};
    WaveformCallback waveform_callback_{nullptr};
};
} // namespace mls
