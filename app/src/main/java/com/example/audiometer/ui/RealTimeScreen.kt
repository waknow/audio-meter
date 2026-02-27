package com.example.audiometer.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.util.Locale
import androidx.compose.material3.Text

private const val CHART_UPDATE_INTERVAL_MS = 33L
private const val CHART_EMA_PREVIOUS_WEIGHT = 0.75f
private const val CHART_EMA_CURRENT_WEIGHT = 0.25f
private const val CHART_MAX_POINTS = 90
private const val CHART_MOVING_AVERAGE_WINDOW = 3

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
    val simulationProgress by viewModel.simulationProgress.collectAsState()
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
    var lastChartUpdateMs by remember { mutableLongStateOf(0L) }

    // 启动时清空图表，停止时冻结（保留最后结果）
    LaunchedEffect(isRunning) {
        if (isRunning) {
            chartData = emptyList()
            lastChartUpdateMs = 0L
        }
    }
    // 运行中实时追加数据点
    LaunchedEffect(similarity) {
        if (isRunning) {
            val now = System.currentTimeMillis()
            if (now - lastChartUpdateMs >= CHART_UPDATE_INTERVAL_MS) {
                val previous = chartData.lastOrNull() ?: similarity
                val smoothed = previous * CHART_EMA_PREVIOUS_WEIGHT + similarity * CHART_EMA_CURRENT_WEIGHT
                chartData = (chartData + smoothed).takeLast(CHART_MAX_POINTS)
                lastChartUpdateMs = now
            }
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

        // 操作按钮（模拟模式时底部叠加进度条）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Button(
                onClick = {
                    if (isRunning) {
                        viewModel.toggleService()
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
                modifier = Modifier.fillMaxSize(),
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
            // 模拟进度条：叠加在按钮底部
            if (useSimulation && (isRunning || simulationProgress > 0f)) {
                LinearProgressIndicator(
                    progress = { simulationProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .align(Alignment.BottomCenter)
                        .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)),
                    color = Color.White.copy(alpha = 0.75f),
                    trackColor = Color.Transparent
                )
            }
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
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder(),
            shape = RoundedCornerShape(12.dp)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
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

// ── 私有辅助函数 ──────────────────────────────────────────────────────────────

private fun smoothChartData(data: List<Float>, windowSize: Int): List<Float> {
    if (data.isEmpty() || windowSize <= 1) return data
    return data.indices.map { index ->
        val start = (index - windowSize + 1).coerceAtLeast(0)
        val window = data.subList(start, index + 1)
        window.average().toFloat()
    }
}

// ── 可复用 Composable 组件 ────────────────────────────────────────────────────

@Composable
fun ModernTrendChart(data: List<Float>, threshold: Float, modifier: Modifier = Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val displayData = remember(data) { smoothChartData(data, windowSize = CHART_MOVING_AVERAGE_WINDOW) }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val pointsCount = displayData.size

        // Draw Threshold Dash Line
        val threshY = height - (threshold / 100f * height)
        drawLine(
            color = errorColor.copy(alpha = 0.5f),
            start = androidx.compose.ui.geometry.Offset(0f, threshY),
            end = androidx.compose.ui.geometry.Offset(width, threshY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
        )

        if (pointsCount > 1) {
            val stepX = width / (pointsCount - 1)
            val path = Path()
            val fillPath = Path()

            val points = displayData.mapIndexed { index, value ->
                val x = index * stepX
                val y = height - (value / 100f * height)
                androidx.compose.ui.geometry.Offset(x, y)
            }

            path.moveTo(points.first().x, points.first().y)
            fillPath.moveTo(points.first().x, height)
            fillPath.lineTo(points.first().x, points.first().y)

            for (i in 0 until points.size - 1) {
                val nextPoint = points[i + 1]
                path.lineTo(nextPoint.x, nextPoint.y)
                fillPath.lineTo(nextPoint.x, nextPoint.y)
            }

            val last = points.last()
            path.lineTo(last.x, last.y)
            fillPath.lineTo(last.x, last.y)
            fillPath.lineTo(last.x, height)
            fillPath.close()

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    listOf(primaryColor.copy(alpha = 0.3f), Color.Transparent)
                )
            )
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
fun CompactStatCard(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
) {
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
            Canvas(modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
    val animatable = remember { Animatable(0f) }
    LaunchedEffect(normalizedLevel) {
        animatable.animateTo(normalizedLevel, animationSpec = tween(150))
    }
    val animVal = animatable.value

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val barWidth = size.width * animVal
            drawRect(color = Color.Gray.copy(alpha = 0.2f), size = size)
            val gradient = Brush.horizontalGradient(
                listOf(Color(0xFF4CAF50), Color(0xFFFFC107), Color(0xFFF44336)),
                endX = size.width
            )
            drawRect(
                brush = gradient,
                size = androidx.compose.ui.geometry.Size(barWidth, size.height)
            )
        }
        Text(
            text = String.format(Locale.US, "%.0f", level),
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.labelSmall,
            color = if (animVal > 0.5f) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold
        )
    }
}
