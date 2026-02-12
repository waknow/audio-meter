package com.example.audiometer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun RealTimeScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val isRunning by viewModel.isRunning.collectAsState()
    val similarity by viewModel.currentSimilarity.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val totalChecks by viewModel.totalChecks.collectAsState()
    val threshold = viewModel.threshold

    // Chart state
    var chartData by remember { mutableStateOf(listOf<Float>()) }
    LaunchedEffect(similarity) {
        chartData = (chartData + similarity).takeLast(100) // Keep last 100 points
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (recordAudioGranted) {
            viewModel.toggleService()
        } else {
            Toast.makeText(context, "Microphone permission required", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Audio Analysis", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(32.dp))

        // Visualization Chart
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
             val width = size.width
             val height = size.height
             val stepX = width / 100f

             // Draw Threshold Line
             val threshY = height - (threshold / 100f * height)
             drawLine(
                 color = Color.Red,
                 start = androidx.compose.ui.geometry.Offset(0f, threshY),
                 end = androidx.compose.ui.geometry.Offset(width, threshY),
                 strokeWidth = 2f
             )

             // Draw Data Line
             if (chartData.isNotEmpty()) {
                 val path = androidx.compose.ui.graphics.Path()
                 val startY = height - (chartData.first() / 100f * height)
                 path.moveTo(0f, startY)

                 chartData.forEachIndexed { index, value ->
                     val x = index * stepX
                     val y = height - (value / 100f * height)
                     // Using lineTo for simple chart
                     path.lineTo(x, y)
                 }

                 drawPath(
                     path = path,
                     color = Color.Green,
                     style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                 )
             }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Similarity Text
        Text(
            text = "Current: ${String.format(Locale.US, "%.1f%%", similarity)}",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = if (similarity >= threshold) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("Threshold: ${String.format(Locale.US, "%.1f", threshold)}")
        Text("Total Checks: $totalChecks")

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                if (isRunning) {
                    viewModel.toggleService()
                } else {
                    val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                    permissionsLauncher.launch(perms.toTypedArray())
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (isRunning) "Stop Analysis" else "Start Analysis")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Logs", style = MaterialTheme.typography.titleMedium)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                reverseLayout = true
            ) {
                items(logs.reversed()) { log ->
                    Text(log, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(viewModel: MainViewModel = viewModel()) {
    val history by viewModel.history.collectAsState()
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Alert History", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history) { record ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = dateFormat.format(Date(record.timestamp)),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Similarity: ${String.format(Locale.US, "%.1f", record.similarity)}%")
                            Text("Threshold: ${String.format(Locale.US, "%.1f", record.threshold)}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConfigScreen(viewModel: MainViewModel = viewModel()) {
    var threshold by remember { mutableStateOf(viewModel.threshold) }
    var intervalSec by remember { mutableStateOf(viewModel.interval / 1000f) }
    var haUrlStr by remember { mutableStateOf(viewModel.haUrl) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showRecordDialog by remember { mutableStateOf(false) }
    var samplePath by remember { mutableStateOf(viewModel.sampleAudioPath) }
    val isSamplePlaying = viewModel.isSamplePlaying

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val stream = context.contentResolver.openInputStream(it)
                    val file = File(context.filesDir, "sample_imported_${System.currentTimeMillis()}.wav")
                    copyStreamToFile(stream, file)
                    withContext(Dispatchers.Main) {
                        viewModel.updateSampleAudioPath(file.absolutePath)
                        samplePath = file.absolutePath
                        Toast.makeText(context, "Sample Imported", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Import Failed: ${e.message}", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, "Microphone permission needed", Toast.LENGTH_SHORT).show()
        }
    }

    if (showRecordDialog) {
        RecordSampleDialog(
            onDismiss = { showRecordDialog = false },
            onFinish = { path ->
                viewModel.updateSampleAudioPath(path)
                samplePath = path
                showRecordDialog = false
                Toast.makeText(context, "Sample Recorded", Toast.LENGTH_SHORT).show()
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Configuration", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))

        Text("MFCC Distance Threshold: ${String.format(Locale.US, "%.1f", threshold)}")
        Text(
            text = "Lower = Stricter matching (Euclidean distance, typical: 20-50)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = threshold,
            onValueChange = {
                threshold = it
                viewModel.threshold = it
            },
            valueRange = 10f..100f,  // 合理的欧氏距离范围
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("Alert Interval: ${String.format(Locale.US, "%.1f", intervalSec)}s")
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
            label = { Text("HA Upload URL") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text("Sample Audio", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (samplePath != null) "Current: ...${samplePath?.takeLast(20)}" else "No Sample Set",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Record")
            }
            Button(
                onClick = { filePicker.launch("audio/*") },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text("Pick File")
            }
            Button(
                onClick = { viewModel.playSampleAudio() },
                enabled = samplePath != null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSamplePlaying) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isSamplePlaying) "Stop" else "Play")
            }
        }
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
                    com.example.audiometer.utils.WavUtil.saveWav(outputFile, data.toShortArray())
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
