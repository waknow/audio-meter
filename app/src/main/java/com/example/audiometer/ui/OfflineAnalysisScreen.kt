package com.example.audiometer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun OfflineAnalysisScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isAnalyzing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                isAnalyzing = true
                progress = 0f
                viewModel.offlineResultMessage = "Analyzing..."
                viewModel.offlineResults = emptyList()
                viewModel.offlineWavInfo = null

                // Copy to temp file
                val tempFile = File(context.cacheDir, "temp_offline.wav")
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    viewModel.currentAnalyzedFile = tempFile

                    // Run Analysis
                    viewModel.analyzeOfflineFile(tempFile) { p ->
                        progress = p
                    }

                } catch (e: Exception) {
                    viewModel.offlineResultMessage = "Error: ${e.message}"
                    e.printStackTrace()
                } finally {
                    isAnalyzing = false
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopPlayback()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Offline Analysis", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { filePicker.launch("audio/*") },
                enabled = !isAnalyzing,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isAnalyzing) "Analyzing..." else "Select And Analyze")
            }

            Button(
                onClick = { viewModel.clearOfflineResults() },
                enabled = !isAnalyzing && viewModel.offlineResults.isNotEmpty(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Clear Results")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isAnalyzing) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("${(progress * 100).toInt()}% Analysis Complete", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(16.dp))
        }

        viewModel.offlineWavInfo?.let { info ->
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("File Info", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Sample Rate: ${info.sampleRate} Hz", style = MaterialTheme.typography.bodyMedium)
                    Text("Duration: ${formatTime(info.durationMs)}", style = MaterialTheme.typography.bodyMedium)
                    Text("Channels: ${info.channels}", style = MaterialTheme.typography.bodyMedium)
                    Text("Bit Depth: ${info.bitDepth} bits", style = MaterialTheme.typography.bodyMedium)
                    
                    // 警告：如果采样率不是 16000
                    if (info.sampleRate != 16000) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "⚠️ Sample rate is ${info.sampleRate} Hz (not 16kHz). Timestamps will be calculated based on actual rate.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }

        // 文件路径调试信息
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("File Paths (Debug)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                
                Text("Sample File:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                Text(
                    text = viewModel.sampleAudioPath ?: "Not set",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (viewModel.sampleAudioPath != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                
                // 显示样本文件采样率
                viewModel.sampleWavInfo?.let { sampleInfo ->
                    if (sampleInfo.sampleRate > 0) {
                        Text(
                            text = "  Sample Rate: ${sampleInfo.sampleRate} Hz",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "  Sample Rate: Unknown (using offline file rate)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text("Offline File:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                Text(
                    text = viewModel.currentAnalyzedFile?.absolutePath ?: "Not selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (viewModel.currentAnalyzedFile != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                
                // 显示离线文件采样率
                viewModel.offlineWavInfo?.let { offlineInfo ->
                    Text(
                        text = "  Sample Rate: ${offlineInfo.sampleRate} Hz",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // 采样率不匹配警告
                if (viewModel.sampleWavInfo != null && viewModel.offlineWavInfo != null) {
                    if (viewModel.sampleWavInfo!!.sampleRate != viewModel.offlineWavInfo!!.sampleRate) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "⚠️ Sample rate mismatch! Files have different sample rates, which may affect matching accuracy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 播放按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.playSampleAudio() },
                        enabled = viewModel.sampleAudioPath != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (viewModel.isSamplePlaying) "Stop Sample" else "Play Sample")
                    }
                    
                    Button(
                        onClick = { viewModel.playOfflineFile() },
                        enabled = viewModel.currentAnalyzedFile != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (viewModel.isOfflineFilePlaying) "Stop Offline" else "Play Offline")
                    }
                }
            }
        }

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
                                text = "Match at: $timeStr",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Distance: ${String.format(Locale.US, "%.2f", result.similarity)} (Threshold: ${String.format(Locale.US, "%.2f", result.threshold)})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (result.similarity < result.threshold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "Lower is better",
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
