package com.example.audiometer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "validation_records")
data class ValidationRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val similarity: Float,
    val threshold: Float,
    val audioPath: String? = null // Path to recorded audio file if saved
)

