package com.example.audiometer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val matchCount by viewModel.matchCount.collectAsState()
    val currentDistance by viewModel.currentDistance.collectAsState()
    val audioLevel by viewModel.audioLevel.collectAsState()
    val lastProcessedTime by viewModel.lastProcessedTime.collectAsState()
    val threshold = viewModel.threshold
    
    var useSimulation by remember { mutableStateOf(false) }

    // 动画平滑处理相似度数值
    val animatedSimilarity by animateFloatAsState(
        targetValue = similarity,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "similarity"
    )

    // 图表数据状态
    var chartData by remember { mutableStateOf(listOf<Float>()) }
    LaunchedEffect(similarity) {
        if (isRunning) {
            chartData = (chartData + similarity).takeLast(60) // 保留最近 60 个点
        } else if (chartData.isNotEmpty()) {
            chartData = emptyList()
        }
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
        // Top Header with Status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "实时音频分析",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold
            )
            
            Surface(
                color = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "alpha"
                    )
                    
                    Icon(
                        Icons.Default.FiberManualRecord,
                        contentDescription = "Status",
                        modifier = Modifier.size(12.dp),
                        tint = if (isRunning) Color.Red.copy(alpha = alpha) else Color.Gray
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (isRunning) "运行中" else "已停止",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Top Stats Row with Icons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            CompactStatCard("匹配", matchCount.toString(), Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary)
            CompactStatCard("阈值", threshold.toInt().toString(), Icons.Default.Tune, MaterialTheme.colorScheme.tertiary)
            CompactStatCard(
                "状态", 
                if (remember(lastProcessedTime, isRunning) { if (!isRunning) false else System.currentTimeMillis() - lastProcessedTime < 1000 }) "运行" else "等待",
                Icons.Default.Sensors,
                if (remember(lastProcessedTime, isRunning) { if (!isRunning) false else System.currentTimeMillis() - lastProcessedTime < 1000 }) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.height(20.dp))

        // Visual Distance Meter
        Text(
            "距离值监控",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        DistanceMeter(
            distance = currentDistance,
            threshold = threshold,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Audio Level Waveform
        Text(
            "音频强度",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        AudioLevelBar(
            level = audioLevel,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Mode Selection
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !useSimulation,
                onClick = { if (isRunning) viewModel.toggleService(); useSimulation = false },
                label = { Text("实际录音") },
                leadingIcon = { Icon(Icons.Default.Mic, null, Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = useSimulation,
                onClick = { if (isRunning) viewModel.toggleService(); useSimulation = true },
                label = { Text("模拟测试") },
                leadingIcon = { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f),
                enabled = viewModel.offlineAudioPath != null
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Smooth Trend Chart
        Text(
            "实时趋势",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        ModernTrendChart(
            data = chartData,
            threshold = threshold.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Actions
        Button(
            onClick = {
                if (isRunning) {
                    if (useSimulation) {
                        viewModel.toggleService()
                    } else {
                        viewModel.toggleService()
                    }
                } else {
                    if (useSimulation) {
                        val offlinePath = viewModel.offlineAudioPath
                        if (offlinePath != null) {
                            val offlineFile = File(offlinePath)
                            if (offlineFile.exists()) {
                                viewModel.startRealTimeSimulation(offlineFile)
                            } else {
                                Toast.makeText(context, "离线文件不存在", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "请先在配置中设置离线文件", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        permissionsLauncher.launch(perms.toTypedArray())
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ),
            enabled = if (useSimulation) viewModel.offlineAudioPath != null else true
        ) {
            Icon(if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(
                if (isRunning) "停止${if (useSimulation) "模拟" else "监测"}" 
                else "开始${if (useSimulation) "模拟" else "实时监测"}",
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Logs
        Text(
            "事件日志",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder(),
            shape = RoundedCornerShape(12.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(logs.reversed()) { log ->
                    LogItem(log)
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
fun SimilarityGauge(value: Float, threshold: Float, modifier: Modifier = Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    
    Canvas(modifier = modifier) {
        val strokeWidth = 15.dp.toPx()
        val diameter = size.minDimension - strokeWidth
        val topLeft = androidx.compose.ui.geometry.Offset(
            (size.width - diameter) / 2,
            (size.height - diameter) / 2
        )
        val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)

        // 背景轨道
        drawArc(
            color = trackColor,
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round
            )
        )

        // 进度
        drawArc(
            color = if (value >= threshold) errorColor else primaryColor,
            startAngle = 135f,
            sweepAngle = (value / 100f) * 270f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round
            )
        )
    }
}

@Composable
fun ModernTrendChart(data: List<Float>, threshold: Float, modifier: Modifier = Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val maxPoints = 60
        val stepX = width / (maxPoints - 1)

        // Draw Threshold Dash Line
        val threshY = height - (threshold / 100f * height)
        drawLine(
            color = errorColor.copy(alpha = 0.5f),
            start = androidx.compose.ui.geometry.Offset(0f, threshY),
            end = androidx.compose.ui.geometry.Offset(width, threshY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
        )

        if (data.size > 1) {
            val path = Path()
            val fillPath = Path()
            
            val points = data.mapIndexed { index, value ->
                val x = index * stepX
                val y = height - (value / 100f * height)
                androidx.compose.ui.geometry.Offset(x, y)
            }

            path.moveTo(points.first().x, points.first().y)
            fillPath.moveTo(points.first().x, height)
            fillPath.lineTo(points.first().x, points.first().y)

            // 使用三次贝塞尔曲线平滑图表
            for (i in 0 until points.size - 1) {
                val p0 = points[i]
                val p1 = points[i + 1]
                val controlX = (p0.x + p1.x) / 2
                path.quadraticTo(p0.x, p0.y, controlX, (p0.y + p1.y) / 2)
                fillPath.quadraticTo(p0.x, p0.y, controlX, (p0.y + p1.y) / 2)
            }
            
            val last = points.last()
            path.lineTo(last.x, last.y)
            fillPath.lineTo(last.x, last.y)
            fillPath.lineTo(last.x, height)
            fillPath.close()

            // 绘制渐变填充
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    listOf(primaryColor.copy(alpha = 0.3f), Color.Transparent)
                )
            )

            // 绘制主线条
            drawPath(
                path = path,
                color = primaryColor,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

@Composable
fun StatCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.width(150.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun LogItem(log: String) {
    val isAlert = log.contains("Alert", ignoreCase = true) || log.contains("告警", ignoreCase = true)
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (isAlert) Color.Red else MaterialTheme.colorScheme.outlineVariant)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = log,
            style = MaterialTheme.typography.bodySmall,
            color = if (isAlert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (isAlert) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun CompactStatCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(100.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = tint
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = tint
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun DistanceMeter(distance: Float, threshold: Float, modifier: Modifier = Modifier) {
    val animatedDistance by animateFloatAsState(
        targetValue = distance,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "distance"
    )
    
    val maxDistance = 100f
    val normalizedDistance = (animatedDistance / maxDistance).coerceIn(0f, 1f)
    val isMatch = animatedDistance < threshold
    
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                val barHeight = size.height
                val barWidth = size.width
                
                // 背景刻度线
                for (i in 0..10) {
                    val x = barWidth * (i / 10f)
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.2f),
                        start = androidx.compose.ui.geometry.Offset(x, 0f),
                        end = androidx.compose.ui.geometry.Offset(x, barHeight),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                
                // 阈值标记线
                val thresholdX = barWidth * (threshold / maxDistance).coerceIn(0f, 1f)
                drawLine(
                    color = Color.Red.copy(alpha = 0.7f),
                    start = androidx.compose.ui.geometry.Offset(thresholdX, 0f),
                    end = androidx.compose.ui.geometry.Offset(thresholdX, barHeight),
                    strokeWidth = 3.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                )
                
                // 当前距离指示器
                val currentX = barWidth * normalizedDistance
                drawCircle(
                    color = if (isMatch) Color(0xFF4CAF50) else Color(0xFFF44336),
                    radius = 8.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(currentX, barHeight / 2)
                )
            }
            
            // 数值显示
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "距离: ${String.format(Locale.US, "%.1f", animatedDistance)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isMatch) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "阈值: ${threshold.toInt()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun AudioLevelBar(level: Float, modifier: Modifier = Modifier) {
    val maxLevel = 50000f
    val normalizedLevel = (level / maxLevel).coerceIn(0f, 1f)
    val animatedLevel by animateFloatAsState(
        targetValue = normalizedLevel,
        animationSpec = tween(100),
        label = "audioLevel"
    )
    
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val barWidth = size.width * animatedLevel
            
            // 背景
            drawRect(
                color = Color.Gray.copy(alpha = 0.2f),
                size = size
            )
            
            // 能量条（渐变色）
            val gradient = Brush.horizontalGradient(
                listOf(
                    Color(0xFF4CAF50),
                    Color(0xFFFFC107),
                    Color(0xFFF44336)
                ),
                endX = size.width
            )
            
            drawRect(
                brush = gradient,
                size = androidx.compose.ui.geometry.Size(barWidth, size.height)
            )
        }
        
        // 显示数值
        Text(
            text = String.format(Locale.US, "%.0f", level),
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.labelSmall,
            color = if (animatedLevel > 0.5f) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold
        )
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
