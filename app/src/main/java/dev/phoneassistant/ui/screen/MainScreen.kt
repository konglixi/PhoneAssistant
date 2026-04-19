package dev.phoneassistant.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.phoneassistant.data.model.AssistantMode
import dev.phoneassistant.data.model.ChatMessage
import dev.phoneassistant.data.model.ChatRole
import dev.phoneassistant.data.model.ModelCatalog
import dev.phoneassistant.ui.AssistantUiState
import dev.phoneassistant.ui.VoiceUiState
import dev.phoneassistant.ui.chat.ChatInputBar
import dev.phoneassistant.ui.chat.StreamingMessageBubble
import dev.phoneassistant.ui.voice.WaveformBars
import kotlinx.coroutines.launch

private enum class InputMode { CHAT, VOICE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: AssistantUiState,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onUsePrompt: (String) -> Unit,
    onRefreshAccessibility: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onStartRecording: (autoSend: Boolean) -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    // New Phase 2 callbacks
    onToggleAttachmentMenu: () -> Unit = {},
    onAddImage: (Uri) -> Unit = {},
    onRemoveImage: (Uri) -> Unit = {},
    onRemoveAudio: () -> Unit = {},
    onRemoveVideo: () -> Unit = {},
    onToggleThinking: () -> Unit = {},
    onStopGeneration: () -> Unit = {},
    onSetAudio: (Uri?) -> Unit = {},
    onSetVideo: (Uri?) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var inputMode by remember { mutableStateOf(InputMode.CHAT) }
    var isCancelZone by remember { mutableStateOf(false) }

    val activeModel = uiState.activeModelId?.let { ModelCatalog.findById(it) }

    // Permission launcher for mic
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onStartRecording(true)
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("需要麦克风权限才能使用语音输入")
            }
        }
    }

    // Image picker
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { onAddImage(it) }
    }

    // Audio picker
    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { onSetAudio(it) } }

    // Video picker
    val videoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { onSetVideo(it) } }

    // Camera (take picture)
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraImageUri?.let { onAddImage(it) }
        }
    }

    fun requestRecording() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            onStartRecording(true)
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onRefreshAccessibility()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    LaunchedEffect(uiState.isRecording) {
        if (!uiState.isRecording) isCancelZone = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Phone Assistant", fontWeight = FontWeight.Bold)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (uiState.isAccessibilityEnabled) "无障碍已连接" else "请先开启无障碍服务",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (uiState.isAccessibilityEnabled) Color(0xFF2E7D32)
                                    else MaterialTheme.colorScheme.error
                                )
                                Text(
                                    "·",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    when (uiState.mode) {
                                        AssistantMode.OFFLINE -> "离线"
                                        AssistantMode.ONLINE -> "在线"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                // Show active model name
                                activeModel?.let { model ->
                                    Text(
                                        "·",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        model.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    }
                )
            },
            bottomBar = {
                when (inputMode) {
                    InputMode.VOICE -> {
                        // Voice input mode (hold-to-talk)
                        VoiceCommandBar(
                            uiState = uiState,
                            onSwitchToChat = { inputMode = InputMode.CHAT },
                            onRecordStart = { requestRecording() },
                            onRecordStop = onStopRecording,
                            onRecordCancel = onCancelRecording,
                            onCancelZoneChanged = { isCancelZone = it }
                        )
                    }
                    InputMode.CHAT -> {
                        // Rich chat input bar
                        ChatInputBar(
                            draft = uiState.draft,
                            onDraftChanged = onDraftChanged,
                            onSend = onSend,
                            onStopGeneration = onStopGeneration,
                            attachedImages = uiState.attachedImages,
                            attachedAudio = uiState.attachedAudio,
                            attachedVideo = uiState.attachedVideo,
                            onRemoveImage = onRemoveImage,
                            onRemoveAudio = onRemoveAudio,
                            onRemoveVideo = onRemoveVideo,
                            isAttachmentMenuOpen = uiState.isAttachmentMenuOpen,
                            onToggleAttachmentMenu = onToggleAttachmentMenu,
                            onPickCamera = {
                                // TODO: Create temp file via FileProvider for camera capture
                                // For now, fall through to image picker
                                imagePickerLauncher.launch("image/*")
                            },
                            onPickImage = { imagePickerLauncher.launch("image/*") },
                            onPickAudio = { audioPickerLauncher.launch("audio/*") },
                            onPickVideo = { videoPickerLauncher.launch("video/*") },
                            isThinkingEnabled = uiState.isThinkingEnabled,
                            onToggleThinking = onToggleThinking,
                            onSwitchToVoice = { inputMode = InputMode.VOICE },
                            activeModel = activeModel,
                            isGenerating = uiState.isGenerating,
                            enabled = !uiState.isLoading
                        )
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
            ) {
                PromptRow(onUsePrompt = onUsePrompt)
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.messages, key = { it.id }) { message ->
                        StreamingMessageBubble(
                            message = message,
                            thinkingText = message.thinkingText ?: "",
                            isStreaming = message.isStreaming,
                            perfMetrics = message.perfMetrics
                        )
                    }
                    if (uiState.isLoading) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.width(20.dp), strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    when (uiState.mode) {
                                        AssistantMode.OFFLINE -> "正在本地推理并执行动作…"
                                        AssistantMode.ONLINE -> "正在请求 Qwen 并执行动作…"
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Recording overlay
        AnimatedVisibility(
            visible = uiState.isRecording,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200))
        ) {
            RecordingOverlay(
                amplitude = uiState.voiceAmplitude,
                partialText = uiState.partialTranscription,
                isCancelZone = isCancelZone
            )
        }
    }
}

// ─── VoiceCommandBar ─────────────────────────────────────────────

@Composable
private fun VoiceCommandBar(
    uiState: AssistantUiState,
    onSwitchToChat: () -> Unit,
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit,
    onRecordCancel: () -> Unit,
    onCancelZoneChanged: (Boolean) -> Unit
) {
    val isRecording = uiState.isRecording
    val isProcessing = uiState.voiceUiState == VoiceUiState.PROCESSING

    Surface(shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                isProcessing -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在识别...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> {
                    HoldToTalkButton(
                        modifier = Modifier.weight(1f),
                        enabled = !uiState.isLoading,
                        isRecording = isRecording,
                        partialText = uiState.partialTranscription,
                        onRecordStart = onRecordStart,
                        onRecordStop = onRecordStop,
                        onRecordCancel = onRecordCancel,
                        onCancelZoneChanged = onCancelZoneChanged
                    )
                    if (!isRecording) {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = onSwitchToChat) {
                            Icon(
                                androidx.compose.material.icons.Icons.Default.Edit,
                                contentDescription = "切换文字输入",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── HoldToTalkButton ───────────────────────────────────────────

@Composable
private fun HoldToTalkButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    isRecording: Boolean,
    partialText: String,
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit,
    onRecordCancel: () -> Unit,
    onCancelZoneChanged: (Boolean) -> Unit
) {
    val density = LocalDensity.current
    val cancelThresholdPx = with(density) { 100.dp.toPx() }

    Surface(
        modifier = modifier
            .height(48.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val startY = down.position.y
                    onRecordStart()
                    onCancelZoneChanged(false)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()
                        if (change != null) {
                            val dragUp = startY - change.position.y
                            onCancelZoneChanged(dragUp > cancelThresholdPx)
                        }
                        if (event.changes.all { it.changedToUp() }) {
                            event.changes.forEach { it.consume() }
                            val finalChange = event.changes.firstOrNull()
                            val dragUp =
                                if (finalChange != null) startY - finalChange.position.y else 0f
                            if (dragUp > cancelThresholdPx) {
                                onRecordCancel()
                            } else {
                                onRecordStop()
                            }
                            break
                        }
                    }
                }
            },
        shape = MaterialTheme.shapes.large,
        color = if (isRecording) MaterialTheme.colorScheme.primaryContainer
        else if (enabled) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            if (isRecording) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot(color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = partialText.ifEmpty { "正在聆听..." },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Text(
                    "按住说话",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }
        }
    }
}

// ─── Recording Overlay ──────────────────────────────────────────

@Composable
private fun RecordingOverlay(
    amplitude: Float,
    partialText: String,
    isCancelZone: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        0.4f to Color(0x15000000),
                        0.75f to Color(0x801565C0),
                        1.0f to Color(0xCC1565C0)
                    )
                )
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (partialText.isNotEmpty()) {
                Text(
                    text = partialText,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            Text(
                text = if (isCancelZone) "松开取消" else "松手发送，上移取消",
                color = if (isCancelZone) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            WaveformBars(
                amplitude = amplitude,
                isActive = !isCancelZone,
                color = if (isCancelZone) Color.White.copy(alpha = 0.3f) else Color.White,
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(32.dp)
            )
        }
    }
}

// ─── Pulsing recording indicator dot ───────────────────────────

@Composable
private fun PulsingDot(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_dot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(color, CircleShape)
    )
}

// ─── Reusable composables ──────────────────────────────────────

@Composable
private fun PromptRow(onUsePrompt: (String) -> Unit) {
    val prompts = listOf(
        "打开微信", "返回桌面", "下滑通知栏",
        "打开设置", "向下滑动", "点击发送"
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("快捷指令", style = MaterialTheme.typography.titleSmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            prompts.take(3).forEach { prompt ->
                AssistChip(
                    onClick = { onUsePrompt(prompt) },
                    label = { Text(prompt) },
                    colors = AssistChipDefaults.assistChipColors()
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            prompts.drop(3).forEach { prompt ->
                AssistChip(
                    onClick = { onUsePrompt(prompt) },
                    label = { Text(prompt) },
                    colors = AssistChipDefaults.assistChipColors()
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER
    val background = when (message.role) {
        ChatRole.USER -> MaterialTheme.colorScheme.primary
        ChatRole.ASSISTANT -> MaterialTheme.colorScheme.secondaryContainer
        ChatRole.SYSTEM -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSecondaryContainer

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .background(background)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(message.content, color = textColor)
        }
    }
}
