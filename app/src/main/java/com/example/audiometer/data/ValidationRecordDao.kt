package com.example.audiometer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ValidationRecordDao {
    @Query("SELECT * FROM validation_records ORDER BY timestamp DESC")
    fun getAll(): Flow<List<ValidationRecord>>

    @Insert
    suspend fun insert(record: ValidationRecord)

    @Query("DELETE FROM validation_records")
    suspend fun deleteAll()
}

