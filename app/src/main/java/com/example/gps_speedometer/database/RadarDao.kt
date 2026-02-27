package com.example.gps_speedometer.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RadarDao {
    @Insert
    suspend fun insert(radar: RadarLocation)

    @Query("SELECT * FROM radars ORDER BY timestamp DESC")
    fun getAllRadars(): Flow<List<RadarLocation>>

    @Query("DELETE FROM radars WHERE id = :id")
    suspend fun deleteById(id: Long)
}