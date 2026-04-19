package dev.phoneassistant.offline.planner

import android.content.Context
import dev.phoneassistant.data.model.AssistantPlan
import dev.phoneassistant.data.model.ModelCatalog
import dev.phoneassistant.data.model.ModelInfo
import dev.phoneassistant.data.model.ModelRepository
import dev.phoneassistant.domain.planner.CommandPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class MnnCommandPlanner(
    private val appContext: Context,
    private val modelRepository: ModelRepository,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) : CommandPlanner {

    private val bridge = MnnLlmBridge()
    private var lastModel: ModelInfo? = null

    /**
     * Initialize with a specific model. Defaults to the built-in planner model.
     */
    suspend fun initialize(
        context: Context = appContext,
        model: ModelInfo = ModelCatalog.findById("qwen2.5-0.5b") ?: ModelCatalog.models.first()
    ) = withContext(Dispatchers.IO) {
        lastModel = model
        android.util.Log.d(TAG, "initialize() called, bridge.isLoaded=${bridge.isLoaded}, model=${model.id}")
        if (bridge.isLoaded) return@withContext
        val modelDir = modelRepository.ensureModel(context, model)
        android.util.Log.d(TAG, "ensureModel done: ${modelDir.absolutePath}, files=${modelDir.listFiles()?.map { "${it.name}(${it.length()})" }}")
        bridge.load(
            configPath = modelDir.absolutePath,
            mergedConfig = "{}",
            extraConfig = """{"mmap_dir":""}"""
        )
        android.util.Log.d(TAG, "bridge.load() completed, isLoaded=${bridge.isLoaded}")
        bridge.updateSystemPrompt(SYSTEM_PROMPT)
    }

    val isReady: Boolean get() = bridge.isLoaded

    override suspend fun plan(command: String): AssistantPlan = withContext(Dispatchers.IO) {
        android.util.Log.d(TAG, "plan() called, bridge.isLoaded=${bridge.isLoaded}, lastModel=${lastModel?.id}")

        // Auto-load if model is downloaded but bridge not yet loaded
        if (!bridge.isLoaded) {
            val model = lastModel ?: ModelCatalog.findById("qwen2.5-0.5b") ?: ModelCatalog.models.first()
            val isReady = modelRepository.isModelReady(appContext, model)
            android.util.Log.d(TAG, "Auto-load check: model=${model.id}, isModelReady=$isReady")

            if (isReady) {
                try {
                    val modelDir = modelRepository.getModelDir(appContext, model)
                    val files = modelDir.listFiles()
                    android.util.Log.d(TAG, "Model dir: ${modelDir.absolutePath}")
                    files?.forEach { f ->
                        android.util.Log.d(TAG, "  file: ${f.name}, size=${f.length()}, readable=${f.canRead()}")
                    }
                    // Check required files
                    val required = listOf("llm.mnn", "llm.mnn.weight", "tokenizer.txt")
                    for (req in required) {
                        val f = java.io.File(modelDir, req)
                        if (!f.exists()) android.util.Log.d(TAG, "MISSING required file: $req")
                        else if (f.length() == 0L) android.util.Log.d(TAG, "EMPTY required file: $req")
                    }
                    bridge.load(
                        configPath = modelDir.absolutePath,
                        mergedConfig = "{}",
                        extraConfig = """{"mmap_dir":""}"""
                    )
                    android.util.Log.d(TAG, "Auto-load SUCCESS, isLoaded=${bridge.isLoaded}")
                    bridge.updateSystemPrompt(SYSTEM_PROMPT)
                } catch (e: Exception) {
                    android.util.Log.d(TAG, "Auto-load FAILED: ${e.message}", e)
                }
            }
        }

        if (!bridge.isLoaded) {
            android.util.Log.d(TAG, "Bridge still not loaded after auto-load attempt")
            return@withContext AssistantPlan(reply = "离线模型未加载，请在设置中下载模型后重试。")
        }

        bridge.reset()
        bridge.updateSystemPrompt(SYSTEM_PROMPT)

        val prompt = command
        val responseBuilder = StringBuilder()
        bridge.generate(prompt, keepHistory = false) { progress ->
            if (progress != null) {
                responseBuilder.append(progress)
            }
            false
        }

        parsePlan(responseBuilder.toString())
    }

    private fun parsePlan(rawContent: String): AssistantPlan {
        val sanitized = rawContent.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val jsonStr = JSON_BLOCK_REGEX.find(sanitized)?.value ?: sanitized

        return runCatching {
            json.decodeFromString(AssistantPlan.serializer(), jsonStr.trim())
        }.getOrElse {
            AssistantPlan(reply = sanitized.ifBlank { "离线模型暂时没有返回可执行结果。" })
        }
    }

    fun release() {
        bridge.release()
    }

    companion object {
        private const val TAG = "MnnCommandPlanner"
        private val JSON_BLOCK_REGEX = Regex("[{][\\s\\S]*[}]")

        private val SYSTEM_PROMPT = """
你是一个 Android 手机 AI 助手的规划器，需要把用户指令翻译成结构化 JSON。
你只能输出一个 JSON 对象，不要输出 markdown、解释或多余文本。

请严格返回如下结构：
{
  "reply": "给用户看的简短说明",
  "actions": [
    {
      "type": "OPEN_APP | HOME | BACK | RECENTS | NOTIFICATIONS | QUICK_SETTINGS | CLICK_TEXT | INPUT_TEXT | SCROLL_UP | SCROLL_DOWN | WAIT",
      "appName": "可选，打开应用时使用",
      "packageName": "可选，打开应用时使用",
      "text": "可选，点击文本时使用",
      "value": "可选，输入文本时使用",
      "seconds": 1
    }
  ]
}

规则：
1. 如果用户要打开某个 App，优先使用 OPEN_APP，并尽量填写 appName。
2. 如果用户说"返回/回到首页/最近任务/通知栏/快捷设置"，分别使用 BACK/HOME/RECENTS/NOTIFICATIONS/QUICK_SETTINGS。
3. 如果用户说"点击某个按钮/文案"，使用 CLICK_TEXT，text 填目标文案。
4. 如果用户说"输入某段文字"，使用 INPUT_TEXT，value 填输入内容。
5. 如果用户说"向上/向下滑动"，使用 SCROLL_UP/SCROLL_DOWN。
6. 如果需要等待页面稳定，可以插入 WAIT，seconds 建议 1~3。
7. 无法可靠执行时，actions 返回空数组，并在 reply 里说明原因。
8. reply 使用简体中文，简洁自然。
""".trimIndent()
    }
}
