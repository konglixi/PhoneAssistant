package dev.phoneassistant.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.phoneassistant.ui.AssistantViewModel
import dev.phoneassistant.ui.screen.MainScreen
import dev.phoneassistant.ui.screen.ModelListScreen
import dev.phoneassistant.ui.screen.SettingsScreen

@Composable
fun PhoneAssistantNavHost(
    navController: NavHostController,
    viewModel: AssistantViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val modelStatusMap by viewModel.modelStatusMap.collectAsStateWithLifecycle()

    NavHost(navController = navController, startDestination = Screen.Main.route) {
        composable(Screen.Main.route) {
            MainScreen(
                uiState = uiState,
                onDraftChanged = viewModel::updateDraft,
                onSend = { viewModel.sendCommand() },
                onUsePrompt = { prompt ->
                    viewModel.updateDraft(prompt)
                    viewModel.sendCommand(prompt)
                },
                onRefreshAccessibility = viewModel::refreshAccessibilityStatus,
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onStartRecording = viewModel::startRecording,
                onStopRecording = viewModel::stopRecording,
                onCancelRecording = viewModel::cancelRecording,
                onToggleAttachmentMenu = viewModel::toggleAttachmentMenu,
                onAddImage = viewModel::addImageAttachment,
                onRemoveImage = viewModel::removeImageAttachment,
                onRemoveAudio = { viewModel.setAudioAttachment(null) },
                onRemoveVideo = { viewModel.setVideoAttachment(null) },
                onToggleThinking = viewModel::toggleThinking,
                onStopGeneration = viewModel::stopGeneration,
                onSetAudio = viewModel::setAudioAttachment,
                onSetVideo = viewModel::setVideoAttachment
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                uiState = uiState,
                onSaveSettings = viewModel::saveSettings,
                onSwitchMode = viewModel::switchMode,
                onRefreshAccessibility = viewModel::refreshAccessibilityStatus,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToModels = {
                    navController.navigate(Screen.Models.route)
                }
            )
        }
        composable(Screen.Models.route) {
            ModelListScreen(
                statusMap = modelStatusMap,
                activeModelId = uiState.activeModelId,
                onDownload = viewModel::downloadModel,
                onDelete = viewModel::deleteModel,
                onActivate = viewModel::activateModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
