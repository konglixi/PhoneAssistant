package dev.phoneassistant.data.model

/**
 * Describes a model's type/modality.
 */
enum class ModelType {
    LLM,       // Text-only language model
    VL,        // Vision-Language (supports images)
    AUDIO,     // Audio input model
    OMNI,      // Omni model (text + vision + audio)
    THINKING   // Reasoning/thinking model (supports thought chain)
}

/**
 * Metadata for a downloadable MNN model.
 *
 * [modelScopeRepo] is the ModelScope repository path, e.g. "MNN/Qwen2.5-0.5B-Instruct-MNN".
 * Files are downloaded individually via the ModelScope resolve API.
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val type: ModelType,
    val sizeDescription: String,
    val modelScopeRepo: String,
    val tags: Set<String>,
    val dirName: String // directory name under filesDir
)

// ── Capability tag constants ──

/** Model can be used as the command planner for device automation. */
const val TAG_PLANNER = "Planner"

/** Model supports vision / image input. */
const val TAG_VISUAL = "Visual"

/** Model supports video input. */
const val TAG_VIDEO = "Video"

/** Model supports audio input. */
const val TAG_AUDIO = "Audio"

/** Model supports audio TTS output. */
const val TAG_AUDIO_OUTPUT = "AudioOutput"

/** Model supports toggling the thinking / chain-of-thought mode at runtime. */
const val TAG_THINKING_SWITCH = "ThinkingSwitch"

/** Model is a thinking / reasoning model (always emits <think> blocks). */
const val TAG_THINKING = "Thinking"

// ── Tag-based capability detection helpers ──

fun ModelInfo.isVisual(): Boolean = TAG_VISUAL in tags || type == ModelType.VL || type == ModelType.OMNI
fun ModelInfo.isVideo(): Boolean = TAG_VIDEO in tags
fun ModelInfo.isAudio(): Boolean = TAG_AUDIO in tags || type == ModelType.AUDIO || type == ModelType.OMNI
fun ModelInfo.isPlanner(): Boolean = TAG_PLANNER in tags
fun ModelInfo.supportsThinkingSwitch(): Boolean = TAG_THINKING_SWITCH in tags
fun ModelInfo.isThinking(): Boolean = TAG_THINKING in tags || TAG_THINKING_SWITCH in tags
fun ModelInfo.supportsAudioOutput(): Boolean = TAG_AUDIO_OUTPUT in tags || type == ModelType.OMNI

// ── Built-in model catalog ──

object ModelCatalog {
    val models: List<ModelInfo> = listOf(
        ModelInfo(
            id = "qwen2.5-0.5b",
            name = "Qwen2.5-0.5B (Planner)",
            type = ModelType.LLM,
            sizeDescription = "~ 530MB",
            modelScopeRepo = "MNN/Qwen2.5-0.5B-Instruct-MNN",
            tags = setOf(TAG_PLANNER),
            dirName = "mnn-qwen-model"
        ),
        ModelInfo(
            id = "qwen2.5-1.5b",
            name = "Qwen2.5-1.5B Chat",
            type = ModelType.LLM,
            sizeDescription = "~ 1.1GB",
            modelScopeRepo = "MNN/Qwen2.5-1.5B-Instruct-MNN",
            tags = emptySet(),
            dirName = "mnn-qwen2.5-1.5b"
        ),
        ModelInfo(
            id = "qwen2.5-vl-3b",
            name = "Qwen2.5-VL-3B",
            type = ModelType.VL,
            sizeDescription = "~ 2.2GB",
            modelScopeRepo = "MNN/Qwen2.5-VL-3B-Instruct-MNN",
            tags = setOf(TAG_VISUAL),
            dirName = "mnn-qwen2.5-vl-3b"
        ),
        ModelInfo(
            id = "deepseek-r1-1.5b",
            name = "DeepSeek-R1-1.5B",
            type = ModelType.THINKING,
            sizeDescription = "~ 1.1GB",
            modelScopeRepo = "MNN/DeepSeek-R1-1.5B-Qwen-MNN",
            tags = setOf(TAG_THINKING, TAG_THINKING_SWITCH),
            dirName = "mnn-deepseek-r1-1.5b"
        ),
        ModelInfo(
            id = "qwen2.5-omni-3b",
            name = "Qwen2.5-Omni-3B",
            type = ModelType.OMNI,
            sizeDescription = "~ 2.5GB",
            modelScopeRepo = "MNN/Qwen2.5-Omni-3B-MNN",
            tags = setOf(TAG_VISUAL, TAG_AUDIO, TAG_AUDIO_OUTPUT),
            dirName = "mnn-qwen2.5-omni-3b"
        )
    )

    fun findById(id: String): ModelInfo? = models.find { it.id == id }
}
