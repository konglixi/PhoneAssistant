package dev.phoneassistant.data.model

enum class AssistantMode {
    OFFLINE,
    ONLINE
}

enum class TaskMode {
    CHAT,       // 纯对话，所有模型可用
    EXECUTION   // 设备执行，仅 online 或 isPlanner() 的离线模型
}
