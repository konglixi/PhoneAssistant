package dev.phoneassistant.ui.navigation

sealed class Screen(val route: String) {
    object Main : Screen("main")
    object Settings : Screen("settings")
    object Models : Screen("models")
}
