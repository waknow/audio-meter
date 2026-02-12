package com.example.audiometer.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@Composable
fun OfflineAnalysisScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isAnalyzing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopPlayback()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("离线分析", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        // 操作按钮
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val offlinePath = viewModel.offlineAudioPath
                    if (offlinePath != null) {
                        val file = File(offlinePath)
                        if (file.exists()) {
                            scope.launch {
                                isAnalyzing = true
                                progress = 0f
                                viewModel.offlineResultMessage = "分析中..."
                                viewModel.offlineResults = emptyList()
                                viewModel.offlineWavInfo = null
                                viewModel.currentAnalyzedFile = file

                                try {
                                    viewModel.analyzeOfflineFile(file) { p ->
                                        progress = p
                                    }
                                } catch (e: Exception) {
                                    viewModel.offlineResultMessage = "错误: ${e.message}"
                                    e.printStackTrace()
                                } finally {
                                    isAnalyzing = false
                                }
                            }
                        } else {
                            Toast.makeText(context, "文件不存在，请重新配置", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "请先在配置页面设置离线文件", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !isAnalyzing && viewModel.offlineAudioPath != null,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(4.dp))
                Text(if (isAnalyzing) "分析中..." else "开始分析")
            }

            Button(
                onClick = { viewModel.clearOfflineResults() },
                enabled = !isAnalyzing && viewModel.offlineResults.isNotEmpty(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, null)
                Spacer(Modifier.width(4.dp))
                Text("清空结果")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 进度显示
        if (isAnalyzing) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("${(progress * 100).toInt()}% 完成", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 文件信息
        viewModel.offlineWavInfo?.let { info ->
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("文件信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("采样率: ${info.sampleRate} Hz", style = MaterialTheme.typography.bodyMedium)
                    Text("时长: ${formatTime(info.durationMs)}", style = MaterialTheme.typography.bodyMedium)
                    Text("声道: ${info.channels}", style = MaterialTheme.typography.bodyMedium)
                    Text("位深: ${info.bitDepth} bits", style = MaterialTheme.typography.bodyMedium)
                    
                    if (info.sampleRate != 16000) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "⚠️ 采样率为 ${info.sampleRate} Hz（非 16kHz），时间戳将基于实际采样率计算",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }

        // 结果显示
        Text(viewModel.offlineResultMessage, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        if (viewModel.offlineResults.isNotEmpty()) {
            LazyColumn {
                items(viewModel.offlineResults) { result ->
                    val timeStr = formatTime(result.timestamp)
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        onClick = {
                            viewModel.currentAnalyzedFile?.let { file ->
                                viewModel.playClip(file, result.timestamp)
                            }
                        }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "匹配位置: $timeStr",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "距离: ${String.format(Locale.US, "%.2f", result.similarity)} (阈值: ${String.format(Locale.US, "%.2f", result.threshold)})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (result.similarity < result.threshold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "数值越小越相似",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val sec = ms / 1000
    val m = sec / 60
    val s = sec % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}
