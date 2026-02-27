package com.example.gps_speedometer.database
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "radars")
data class RadarLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val speedLimit: Int,        // 60,80,90,120
    val timestamp: Long = System.currentTimeMillis()
)
