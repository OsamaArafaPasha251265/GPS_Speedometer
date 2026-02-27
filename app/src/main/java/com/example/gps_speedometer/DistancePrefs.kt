package com.example.gps_speedometer

import android.content.Context
import androidx.core.content.edit

object DistancePrefs {
    private const val PREFS_NAME = "gps_speedometer"
    private const val KEY_TOTAL_DISTANCE = "total_distance_km"

    fun getTotalDistance(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_TOTAL_DISTANCE, 0f)

    fun saveTotalDistance(context: Context, distance: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putFloat(KEY_TOTAL_DISTANCE, distance)
        }
    }
}