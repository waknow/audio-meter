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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.audiometer.utils.WavInfo
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
                    Text("File Info", style = MaterialTheme.typography.titleMedium)
                    Text("Sample Rate: ${info.sampleRate} Hz")
                    Text("Duration: ${formatTime(info.durationMs)}")
                    Text("Channels: ${info.channels}")
                }
            }
        }

        Text(viewModel.offlineResultMessage, style = MaterialTheme.typography.bodyLarge)

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
                                text = "Similarity: ${String.format(Locale.US, "%.2f", result.similarity)} (Threshold: ${String.format(Locale.US, "%.2f", result.threshold)})",
                                style = MaterialTheme.typography.bodyMedium
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
