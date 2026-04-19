package dev.phoneassistant.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class AssistantAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
    }

    companion object {
        @Volatile
        private var instance: AssistantAccessibilityService? = null

        fun current(): AssistantAccessibilityService? = instance

        fun isConnected(): Boolean = instance != null
    }
}
