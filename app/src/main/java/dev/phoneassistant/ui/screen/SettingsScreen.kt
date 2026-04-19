package dev.phoneassistant.ui.screen

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.phoneassistant.data.model.AssistantMode
import dev.phoneassistant.ui.AssistantUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: AssistantUiState,
    onSaveSettings: (String, String) -> Unit,
    onSwitchMode: (AssistantMode) -> Unit,
    onRefreshAccessibility: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToModels: () -> Unit = {}
) {
    val context = LocalContext.current
    var apiKeyInput by remember(uiState.apiKey) { mutableStateOf(uiState.apiKey) }
    var modelInput by remember(uiState.model) { mutableStateOf(uiState.model) }
    var showApiKey by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Mode Selection
            SectionHeader("工作模式")
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilterChip(
                            selected = uiState.mode == AssistantMode.OFFLINE,
                            onClick = { onSwitchMode(AssistantMode.OFFLINE) },
                            label = { Text("离线模式") }
                        )
                        FilterChip(
                            selected = uiState.mode == AssistantMode.ONLINE,
                            onClick = { onSwitchMode(AssistantMode.ONLINE) },
                            label = { Text("在线模式") }
                        )
                    }
                    Text(
                        text = when (uiState.mode) {
                            AssistantMode.OFFLINE -> "语音识别和指令规划均在本地运行，无需网络连接"
                            AssistantMode.ONLINE -> "语音识别和指令规划通过云端大模型完成，需要网络和 API Key"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Section 2: Model Configuration (Online)
            SectionHeader("在线配置")
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                        alpha = if (uiState.mode == AssistantMode.ONLINE) 0.45f else 0.2f
                    )
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val isOnline = uiState.mode == AssistantMode.ONLINE
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("DashScope / Qwen API Key") },
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(Icons.Default.Lock, contentDescription = "显示或隐藏 Key")
                            }
                        },
                        visualTransformation = if (showApiKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = isOnline
                    )
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("模型") },
                        supportingText = { Text("默认推荐 qwen-plus，也可改成 qwen-turbo") },
                        singleLine = true,
                        enabled = isOnline
                    )
                    Button(
                        onClick = { onSaveSettings(apiKeyInput, modelInput) },
                        modifier = Modifier.align(Alignment.End),
                        enabled = isOnline
                    ) {
                        Text("保存配置")
                    }
                }
            }

            // Section 3: Offline Models Status
            SectionHeader("离线模型")
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Vosk model status
                    ModelStatusRow(
                        label = "语音模型 (Vosk)",
                        detail = "vosk-model-small-cn · 约 42MB",
                        isReady = uiState.isVoskModelReady,
                        downloadProgress = uiState.voskDownloadProgress
                    )

                    // MNN model status
                    ModelStatusRow(
                        label = "推理模型 (Qwen 0.5B)",
                        detail = "MNN 4bit 量化 · 约 400MB",
                        isReady = uiState.isMnnModelReady,
                        downloadProgress = uiState.mnnDownloadProgress
                    )

                    OutlinedButton(onClick = onNavigateToModels) {
                        Text("管理模型")
                    }
                }
            }

            // Section 4: Accessibility Service
            SectionHeader("无障碍服务")
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (uiState.isAccessibilityEnabled) Color(0xFF2E7D32)
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(10.dp)
                        ) {}
                        Text(
                            if (uiState.isAccessibilityEnabled) "无障碍服务已连接"
                            else "无障碍服务未连接",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) {
                        Text("前往系统无障碍设置")
                    }
                }
            }

            // Section 5: About
            SectionHeader("关于")
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Phone Assistant", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "版本 1.0.0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "支持离线（MNN + Qwen 0.5B + Vosk）和在线（Qwen 云端 API）双模式的手机智能助手",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ModelStatusRow(
    label: String,
    detail: String,
    isReady: Boolean,
    downloadProgress: Float
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                if (isReady) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (isReady) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!isReady && downloadProgress > 0f) {
            Text(
                "下载中：${(downloadProgress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
}
