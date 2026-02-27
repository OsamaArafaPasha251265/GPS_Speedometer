package com.example.gps_speedometer.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

@Database(entities = [RadarLocation::class], version = 1, exportSchema = false)
abstract class RadarDatabase : RoomDatabase() {
    abstract fun radarDao(): RadarDao

    companion object {
        @Volatile
        private var INSTANCE: RadarDatabase? = null

        fun getInstance(context: Context): RadarDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RadarDatabase::class.java,
                    "radar_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}