package dev.phoneassistant.online.planner

import dev.phoneassistant.data.model.AssistantPlan
import dev.phoneassistant.data.model.AssistantSettings
import dev.phoneassistant.data.model.ChatCompletionMessage
import dev.phoneassistant.data.model.ChatCompletionRequest
import dev.phoneassistant.data.model.ChatCompletionResponse
import dev.phoneassistant.data.model.DEFAULT_QWEN_MODEL
import dev.phoneassistant.data.model.ResponseFormat
import dev.phoneassistant.domain.planner.CommandPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

class QwenCommandPlanner(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()
) : CommandPlanner {

    @Volatile
    private var settings: AssistantSettings = AssistantSettings()

    fun configure(settings: AssistantSettings) {
        this.settings = settings
    }

    override suspend fun plan(command: String): AssistantPlan = withContext(Dispatchers.IO) {
        val currentSettings = settings
        if (currentSettings.apiKey.isBlank()) {
            return@withContext AssistantPlan(reply = "请先在设置页填写并保存 Qwen API Key。")
        }

        val requestPayload = ChatCompletionRequest(
            model = currentSettings.model.ifBlank { DEFAULT_QWEN_MODEL },
            messages = listOf(
                ChatCompletionMessage(role = "system", content = SYSTEM_PROMPT),
                ChatCompletionMessage(role = "user", content = command)
            ),
            responseFormat = ResponseFormat(type = "json_object")
        )

        val request = Request.Builder()
            .url(QWEN_ENDPOINT)
            .addHeader("Authorization", "Bearer ${currentSettings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(
                json.encodeToString(ChatCompletionRequest.serializer(), requestPayload)
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()

        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Qwen 请求失败: ${response.code} ${response.message}\n$bodyString")
            }
            val completion = json.decodeFromString(ChatCompletionResponse.serializer(), bodyString)
            val rawContent = completion.choices.firstOrNull()?.message?.content.orEmpty()
            parsePlan(rawContent)
        }
    }

    private fun parsePlan(rawContent: String): AssistantPlan {
        val sanitized = rawContent.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return runCatching {
            json.decodeFromString(AssistantPlan.serializer(), sanitized)
        }.getOrElse {
            AssistantPlan(reply = sanitized.ifBlank { "我暂时没有得到可执行结果。" })
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val QWEN_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

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
