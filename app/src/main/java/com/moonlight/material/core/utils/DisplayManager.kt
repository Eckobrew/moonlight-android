package com.moonlight.material.core.utils

import android.content.Context
import android.os.Build
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Queries Android window metrics for native resolution and refresh rate.
 * Matches DisplayManager.swift:5-80
 */
object DisplayManager {

    data class DisplayMode(
        val width: Int,
        val height: Int,
        val refreshRate: Int
    )

    fun getNativeDisplayMode(context: Context): DisplayMode {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return DisplayMode(1920, 1080, 60)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            val display = context.display
            val refreshRate = display?.refreshRate?.roundToInt() ?: 60
            DisplayMode(
                width = bounds.width().coerceAtLeast(bounds.height()),
                height = bounds.height().coerceAtMost(bounds.width()),
                refreshRate = refreshRate
            )
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val displayMetrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(displayMetrics)
            val refreshRate = display.refreshRate.roundToInt()
            DisplayMode(
                width = displayMetrics.widthPixels.coerceAtLeast(displayMetrics.heightPixels),
                height = displayMetrics.heightPixels.coerceAtMost(displayMetrics.widthPixels),
                refreshRate = refreshRate
            )
        }
    }
}
