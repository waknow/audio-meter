package com.example.audiometer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@Composable
fun ConfigScreen(viewModel: MainViewModel = viewModel()) {
    var threshold by remember { mutableStateOf(viewModel.threshold) }
    var intervalSec by remember { mutableStateOf(viewModel.interval / 1000f) }
    var haUrlStr by remember { mutableStateOf(viewModel.haUrl) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showRecordDialog by remember { mutableStateOf(false) }
    var samplePath by remember { mutableStateOf(viewModel.sampleAudioPath) }
    var offlinePath by remember { mutableStateOf(viewModel.offlineAudioPath) }
    val isSamplePlaying = viewModel.isSamplePlaying

    val sampleFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val stream = context.contentResolver.openInputStream(it)
                    val file = File(context.filesDir, "sample_imported_${System.currentTimeMillis()}.wav")
                    copyStreamToFile(stream, file)
                    withContext(Dispatchers.Main) {
                        viewModel.updateSampleAudioPath(file.absolutePath)
                        samplePath = file.absolutePath
                        Toast.makeText(context, "样本音频导入成功", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val offlineFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val stream = context.contentResolver.openInputStream(it)
                    val file = File(context.filesDir, "offline_imported_${System.currentTimeMillis()}.wav")
                    copyStreamToFile(stream, file)
                    withContext(Dispatchers.Main) {
                        viewModel.updateOfflineAudioPath(file.absolutePath)
                        offlinePath = file.absolutePath
                        Toast.makeText(context, "离线文件导入成功", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showRecordDialog = true
        } else {
            Toast.makeText(context, "需要麦克风权限", Toast.LENGTH_SHORT).show()
        }
    }

    if (showRecordDialog) {
        RecordSampleDialog(
            onDismiss = { showRecordDialog = false },
            onFinish = { path ->
                viewModel.updateSampleAudioPath(path)
                samplePath = path
                showRecordDialog = false
                Toast.makeText(context, "样本已录制", Toast.LENGTH_SHORT).show()
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("配置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))

        // 样本音频配置
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("样本音频", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Icon(Icons.Default.FiberManualRecord, null, tint = if (samplePath != null) Color.Green else Color.Gray, modifier = Modifier.size(12.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (samplePath != null) "...${samplePath?.takeLast(30)}" else "未设置",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (samplePath != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("录制")
                    }
                    Button(
                        onClick = { sampleFilePicker.launch("audio/*") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                    ) {
                        Text("导入")
                    }
                    Button(
                        onClick = { viewModel.playSampleAudio() },
                        enabled = samplePath != null,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSamplePlaying) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (isSamplePlaying) "停止" else "播放")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 离线文件配置
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("离线分析文件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Icon(Icons.Default.FiberManualRecord, null, tint = if (offlinePath != null) Color.Green else Color.Gray, modifier = Modifier.size(12.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (offlinePath != null) "...${offlinePath?.takeLast(30)}" else "未设置",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (offlinePath != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { offlineFilePicker.launch("audio/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("导入文件")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 参数配置
        Text("匹配参数", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Text("MFCC 距离阈值: ${String.format(Locale.US, "%.1f", threshold)}")
        Text(
            text = "数值越小 = 匹配越严格（欧氏距离，推荐: 20-50）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = threshold,
            onValueChange = {
                threshold = it
                viewModel.threshold = it
            },
            valueRange = 10f..100f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("告警间隔: ${String.format(Locale.US, "%.1f", intervalSec)}秒")
        Slider(
            value = intervalSec,
            onValueChange = {
                intervalSec = it
                viewModel.interval = (it * 1000).toLong()
            },
            valueRange = 0f..10.0f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = haUrlStr,
            onValueChange = {
                haUrlStr = it
                viewModel.haUrl = it
            },
            label = { Text("Home Assistant URL") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
@SuppressLint("MissingPermission")
fun RecordSampleDialog(onDismiss: () -> Unit, onFinish: (String) -> Unit) {
    var isRecording by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<SampleRecorder?>(null) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { if (!isRecording && !isSaving) onDismiss() },
        title = { Text("Record Sample Sound") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (isSaving) "Saving..."
                    else if (isRecording) "Recording..."
                    else "Valid sample should be 2-5 seconds."
                )
                Spacer(modifier = Modifier.height(16.dp))
                if (isRecording || isSaving) {
                    CircularProgressIndicator()
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSaving,
                onClick = {
                    if (isRecording) {
                        // Stop
                        isRecording = false
                        isSaving = true
                        recorder?.stopRecording {
                            isSaving = false
                            val path = recorder?.outputFile?.absolutePath
                            if (path != null) {
                                onFinish(path)
                            } else {
                                onDismiss()
                            }
                        }
                    } else {
                        // Start
                        val file = File(context.filesDir, "sample_recorded_${System.currentTimeMillis()}.wav")
                        recorder = SampleRecorder(file)
                        recorder?.startRecording()
                        isRecording = true
                    }
                }
            ) {
                Text(if (isRecording) "Stop & Save" else "Start Recording")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSaving,
                onClick = {
                    if (isRecording) {
                        isRecording = false
                        recorder?.stopRecording { onDismiss() }
                    } else {
                        onDismiss()
                    }
                }
            ) {
                Text("Cancel")
            }
        }
    )
}

class SampleRecorder(val outputFile: File) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val scope = CoroutineScope(Dispatchers.IO)

    @SuppressLint("MissingPermission")
    fun startRecording() {
        val sampleRate = 44100
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .build()

            audioRecord?.startRecording()
            isRecording = true

            scope.launch {
                val data = mutableListOf<Short>()
                val buffer = ShortArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        for (i in 0 until read) data.add(buffer[i])
                    }
                }
                // Save to wav
                try {
                    com.example.audiometer.util.WavUtil.saveWav(outputFile, data.toShortArray())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isRecording = false
        }
    }

    fun stopRecording(onSaved: () -> Unit) {
        if (!isRecording) {
            onSaved()
            return
        }
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        scope.launch {
            kotlinx.coroutines.delay(500)
            withContext(Dispatchers.Main) {
                onSaved()
            }
        }
    }
}

fun copyStreamToFile(inputStream: java.io.InputStream?, outputFile: File) {
    inputStream?.use { input ->
        FileOutputStream(outputFile).use { output ->
            input.copyTo(output)
        }
    }
}
