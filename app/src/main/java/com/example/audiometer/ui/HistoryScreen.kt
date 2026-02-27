package com.example.audiometer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(viewModel: MainViewModel = viewModel()) {
    val history by viewModel.history.collectAsState()
    val playingRecordId = viewModel.playingRecordId
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "告警历史",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            "${history.size} 条记录",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.History, null,
                        Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("暂无告警记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(history, key = { it.id }) { record ->
                    val isPlaying = playingRecordId == record.id
                    val hasAudio = record.audioPath != null &&
                            java.io.File(record.audioPath).exists()
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isPlaying)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.surface
                        ),
                        border = if (isPlaying) CardDefaults.outlinedCardBorder() else null
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = dateFormat.format(Date(record.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(
                                        color = if (isPlaying)
                                            MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            "相似度 ${String.format(Locale.US, "%.1f", record.similarity)}%",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isPlaying)
                                                MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Text(
                                        "阈值 ${String.format(Locale.US, "%.1f", record.threshold)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.align(Alignment.CenterVertically),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (hasAudio) {
                                IconButton(onClick = { viewModel.playRecord(record) }) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Stop
                                                      else Icons.Default.PlayCircle,
                                        contentDescription = if (isPlaying) "停止" else "播放",
                                        tint = if (isPlaying) MaterialTheme.colorScheme.error
                                               else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
