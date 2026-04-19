package dev.phoneassistant.domain.chat

import android.util.Log

/**
 * Processes LLM generation output streams, supporting two formats:
 * 1. THINK_TAGS: Uses <think> and </think> to delimit thinking sections.
 * 2. GPT_OSS: Uses <|channel|>, <|message|>, <|end|>, and final<|message|> control tags.
 *
 * Ported from MnnLlmChat GenerateResultProcessor.kt.
 */
class GenerateResultProcessor {

    companion object {
        const val TAG: String = "GenerateResultProcessor"

        fun noSlashThink(text: String?): String? {
            if (text?.startsWith("</think>") == true) {
                return text.substring("</think>".length)
            }
            return text
        }
    }

    private enum class StreamFormat {
        UNKNOWN, THINK_TAGS, GPT_OSS
    }

    private var generateBeginTime: Long = 0
    private var currentFormat: StreamFormat = StreamFormat.UNKNOWN

    private val rawStringBuilder = StringBuilder()
    val thinkingStringBuilder = StringBuilder()
    val normalStringBuilder = StringBuilder()
    var thinkTime: Long = -1L
        private set

    // State for THINK_TAGS format
    private var isThinking: Boolean = false
    private var hasThought: Boolean = false
    private var thinkHasContent: Boolean = false
    private var tagBuffer = ""
    private val pendingTextBuffer = StringBuilder()

    fun reset() {
        generateBeginTime = 0
        currentFormat = StreamFormat.UNKNOWN
        rawStringBuilder.clear()
        thinkingStringBuilder.clear()
        normalStringBuilder.clear()
        pendingTextBuffer.clear()
        thinkTime = -1L
        isThinking = false
        hasThought = false
        thinkHasContent = false
        tagBuffer = ""
    }

    fun generateBegin() {
        reset()
        this.generateBeginTime = System.currentTimeMillis()
    }

    fun process(progress: String?) {
        if (progress == null) {
            when (currentFormat) {
                StreamFormat.THINK_TAGS, StreamFormat.UNKNOWN -> processThinkTags(null)
                StreamFormat.GPT_OSS -> processGptOss(null)
            }
            return
        }

        rawStringBuilder.append(progress)

        // Format detection
        if (currentFormat == StreamFormat.UNKNOWN) {
            if (rawStringBuilder.contains("<|message|>") || rawStringBuilder.contains("<|channel|>")) {
                currentFormat = StreamFormat.GPT_OSS
                processGptOss(rawStringBuilder.toString())
                return
            } else if (rawStringBuilder.contains("<think>") || rawStringBuilder.contains("</think>")) {
                currentFormat = StreamFormat.THINK_TAGS
            }
        }

        when (currentFormat) {
            StreamFormat.GPT_OSS -> processGptOss(rawStringBuilder.toString())
            else -> processThinkTags(progress)
        }
    }

    // ── GPT_OSS format ──

    private fun processGptOss(fullInput: String?) {
        if (fullInput == null) {
            if (thinkTime == -1L && rawStringBuilder.isNotEmpty()) {
                thinkTime = System.currentTimeMillis() - generateBeginTime
            }
            return
        }
        val finalMessageDelimiter = "final<|message|>"
        thinkingStringBuilder.clear()
        normalStringBuilder.clear()
        val finalMessageIndex = fullInput.lastIndexOf(finalMessageDelimiter)
        if (finalMessageIndex != -1) {
            val normalContent = fullInput.substring(finalMessageIndex + finalMessageDelimiter.length)
            normalStringBuilder.append(normalContent)
            val rawThinkBlock = fullInput.substring(0, finalMessageIndex)
            parseGptOssThinkingBlock(rawThinkBlock)
            if (thinkTime == -1L) {
                thinkTime = System.currentTimeMillis() - generateBeginTime
            }
        } else {
            parseGptOssThinkingBlock(fullInput)
        }
    }

    private fun parseGptOssThinkingBlock(block: String) {
        val thinkMessageStartTag = "<|message|>"
        val thinkMessageEndTag = "<|end|>"
        val firstMessageIndex = block.indexOf(thinkMessageStartTag)
        if (firstMessageIndex != -1) {
            val endTagIndex = block.indexOf(thinkMessageEndTag, startIndex = firstMessageIndex)
            val thinkContent = if (endTagIndex != -1) {
                block.substring(firstMessageIndex + thinkMessageStartTag.length, endTagIndex).trim()
            } else {
                block.substring(firstMessageIndex + thinkMessageStartTag.length).trim()
            }
            if (thinkContent.isNotBlank()) {
                thinkingStringBuilder.append(thinkContent)
            }
        }
    }

    // ── THINK_TAGS format ──

    private fun processThinkTags(progress: String?) {
        if (progress == null) {
            if (tagBuffer.isNotEmpty()) {
                normalStringBuilder.append(tagBuffer)
                tagBuffer = ""
            }
            pendingTextBuffer.clear()
            if (isThinking) {
                handleThinkEnd(force = true)
            }
            return
        }

        var buffer = tagBuffer + progress
        tagBuffer = ""

        while (buffer.isNotEmpty()) {
            val thinkTag = "<think>"
            val endThinkTag = "</think>"
            val thinkIndex = buffer.indexOf(thinkTag)
            val endThinkIndex = buffer.indexOf(endThinkTag)

            if (isThinking) {
                val effectiveEndIndex = if (endThinkIndex != -1) endThinkIndex else buffer.length
                val text = buffer.substring(0, effectiveEndIndex)
                if (text.isNotEmpty()) {
                    thinkingStringBuilder.append(text)
                    thinkHasContent = true
                }
                if (endThinkIndex != -1) {
                    handleThinkEnd()
                    buffer = buffer.substring(endThinkIndex + endThinkTag.length)
                } else {
                    buffer = ""
                }
            } else {
                if (thinkIndex != -1 && (endThinkIndex == -1 || thinkIndex < endThinkIndex)) {
                    val textBefore = buffer.substring(0, thinkIndex)
                    normalStringBuilder.append(textBefore)
                    pendingTextBuffer.clear()
                    handleThinkStart()
                    buffer = buffer.substring(thinkIndex + thinkTag.length)
                } else if (endThinkIndex != -1) {
                    val textBefore = buffer.substring(0, endThinkIndex)
                    if (pendingTextBuffer.isNotEmpty()) {
                        val start = normalStringBuilder.length - pendingTextBuffer.length
                        if (start >= 0) {
                            normalStringBuilder.delete(start, normalStringBuilder.length)
                        }
                    }
                    handleThinkStart()
                    val textToThink = pendingTextBuffer.toString() + textBefore
                    if (textToThink.isNotEmpty()) {
                        thinkingStringBuilder.append(textToThink)
                        thinkHasContent = true
                    }
                    pendingTextBuffer.clear()
                    handleThinkEnd()
                    buffer = buffer.substring(endThinkIndex + endThinkTag.length)
                } else {
                    normalStringBuilder.append(buffer)
                    pendingTextBuffer.append(buffer)
                    buffer = ""
                }
            }
        }
    }

    private fun handleThinkStart() {
        if (!isThinking) {
            isThinking = true
            if (!hasThought) hasThought = true
        }
    }

    private fun handleThinkEnd(force: Boolean = false) {
        if (isThinking || force) {
            isThinking = false
            if (thinkTime == -1L) {
                thinkTime = System.currentTimeMillis() - generateBeginTime
            }
            thinkingStringBuilder.append("\n")
        }
    }

    // ── Public getters ──

    fun getRawResult(): String = rawStringBuilder.toString()

    fun getThinkingContent(): String {
        val thinkingContent = if (currentFormat == StreamFormat.THINK_TAGS) {
            if (thinkHasContent) thinkingStringBuilder.toString() else ""
        } else {
            thinkingStringBuilder.toString()
        }
        return if (thinkingContent.isNotBlank()) thinkingContent else ""
    }

    fun getNormalOutput(): String = normalStringBuilder.toString()

    fun getDisplayResult(): String = getThinkingContent() + getNormalOutput()
}
