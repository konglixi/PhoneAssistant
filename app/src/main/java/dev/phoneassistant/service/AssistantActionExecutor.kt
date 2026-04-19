package dev.phoneassistant.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import dev.phoneassistant.data.model.DeviceAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AssistantActionExecutor(private val context: Context) {

    suspend fun executeAll(actions: List<DeviceAction>): List<String> {
        val results = mutableListOf<String>()
        for (action in actions) {
            results += execute(action)
        }
        return results
    }

    suspend fun execute(action: DeviceAction): String = withContext(Dispatchers.Main) {
        when (action.type.trim().uppercase()) {
            "HOME" -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME, "已返回桌面")
            "BACK" -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK, "已执行返回")
            "RECENTS" -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS, "已打开最近任务")
            "NOTIFICATIONS" -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "已打开通知栏")
            "QUICK_SETTINGS" -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "已打开快捷设置")
            "OPEN_APP" -> openApp(action)
            "CLICK_TEXT" -> clickText(action.text)
            "INPUT_TEXT" -> inputText(action.value)
            "SCROLL_UP" -> scroll(forward = false)
            "SCROLL_DOWN" -> scroll(forward = true)
            "WAIT" -> waitSeconds(action.seconds ?: 1)
            else -> "暂不支持动作: ${action.type}"
        }
    }

    private fun performGlobalAction(action: Int, successMessage: String): String {
        val service = AssistantAccessibilityService.current() ?: return SERVICE_REQUIRED_MESSAGE
        return if (service.performGlobalAction(action)) successMessage else "系统拒绝了该全局操作"
    }

    private fun openApp(action: DeviceAction): String {
        val packageManager = context.packageManager
        val launcherApps = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0
        )
        val target = launcherApps.firstOrNull { info ->
            val label = info.loadLabel(packageManager).toString()
            label.contains(action.appName.orEmpty(), ignoreCase = true) ||
                info.activityInfo.packageName == action.packageName
        } ?: return "没有找到应用：${action.appName ?: action.packageName ?: "未知应用"}"

        val launchIntent = packageManager.getLaunchIntentForPackage(target.activityInfo.packageName)
            ?: return "应用无法直接启动：${target.activityInfo.packageName}"
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return "已尝试打开 ${target.loadLabel(packageManager)}"
    }

    private fun clickText(text: String?): String {
        if (text.isNullOrBlank()) return "缺少要点击的文本"
        val service = AssistantAccessibilityService.current() ?: return SERVICE_REQUIRED_MESSAGE
        val nodes = service.rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty()
        val target = nodes.firstOrNull { it.isVisibleToUser } ?: return "页面上没有找到“$text”"
        val clickable = target.findClickableAncestor() ?: target
        return if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "已尝试点击“$text”"
        } else {
            "找到“$text”，但无法点击"
        }
    }

    private fun inputText(value: String?): String {
        if (value.isNullOrBlank()) return "缺少要输入的文本"
        val service = AssistantAccessibilityService.current() ?: return SERVICE_REQUIRED_MESSAGE
        val root = service.rootInActiveWindow ?: return "当前没有可操作的前台窗口"
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root.findEditableNode()
            ?: return "没有找到可输入的控件，请先将光标放到输入框"
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            "已尝试输入文本"
        } else {
            "输入失败，请确认当前输入框可编辑"
        }
    }

    private fun scroll(forward: Boolean): String {
        val service = AssistantAccessibilityService.current() ?: return SERVICE_REQUIRED_MESSAGE
        val root = service.rootInActiveWindow ?: return "当前没有可操作的前台窗口"
        val scrollNode = root.findScrollableNode() ?: return "当前页面没有可滚动区域"
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (scrollNode.performAction(action)) {
            if (forward) "已向下滚动" else "已向上滚动"
        } else {
            "滚动失败"
        }
    }

    private suspend fun waitSeconds(seconds: Long): String {
        val safeSeconds = seconds.coerceIn(1, 8)
        delay(safeSeconds * 1000)
        return "已等待 ${safeSeconds}s"
    }

    private fun AccessibilityNodeInfo.findClickableAncestor(): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = this
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun AccessibilityNodeInfo.findScrollableNode(): AccessibilityNodeInfo? {
        if (isScrollable) return this
        for (index in 0 until childCount) {
            val match = getChild(index)?.findScrollableNode()
            if (match != null) return match
        }
        return null
    }

    private fun AccessibilityNodeInfo.findEditableNode(): AccessibilityNodeInfo? {
        if (isEditable) return this
        for (index in 0 until childCount) {
            val match = getChild(index)?.findEditableNode()
            if (match != null) return match
        }
        return null
    }

    companion object {
        const val SERVICE_REQUIRED_MESSAGE = "请先在系统设置里开启 Phone Assistant 的无障碍服务"
    }
}
