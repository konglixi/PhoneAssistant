//
// Adapted from MnnLlmChat for PhoneAssistant
//
#pragma once
#include <functional>
#include <ostream>
#include <sstream>

class LlmStreamBuffer : public std::streambuf {
public:
    using CallBack = std::function<void(const char *str, size_t len)>;
    explicit LlmStreamBuffer(CallBack callback) :
            callback_(std::move(callback)) {}

protected:
    std::streamsize xsputn(const char *s, std::streamsize n) override {
        if (callback_) {
            callback_(s, n);
        }
        return n;
    }

private:
    CallBack callback_ = nullptr;
};
