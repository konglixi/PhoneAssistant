package dev.phoneassistant.domain.planner

import dev.phoneassistant.data.model.AssistantPlan

interface CommandPlanner {
    suspend fun plan(command: String): AssistantPlan
}
