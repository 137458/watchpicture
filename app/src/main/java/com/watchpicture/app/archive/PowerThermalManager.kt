package com.watchpicture.app.archive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors device thermal status and battery saver mode.
 *
 * Provides dynamic throttling signals to the decompression and prefetching
 * engines, preventing thermal throttling and device overheating.
 */
class PowerThermalManager(
    context: Context? = null
) {
    enum class ThrottleLevel {
        NORMAL,      // Concurrency 3, lookahead enabled (3 pages)
        THROTTLED    // Concurrency 1, lookahead disabled (0 pages), prefetch paused
    }

    private val _throttleLevel = MutableStateFlow(ThrottleLevel.NORMAL)
    val throttleLevel: StateFlow<ThrottleLevel> = _throttleLevel.asStateFlow()

    val isThrottled: Boolean
        get() = _throttleLevel.value == ThrottleLevel.THROTTLED

    init {
        context?.let { ctx ->
            registerListeners(ctx)
        }
    }

    fun setManualThrottleLevel(level: ThrottleLevel) {
        _throttleLevel.value = level
    }

    private fun registerListeners(ctx: Context) {
        val powerManager = try {
            ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        } catch (_: Throwable) {
            null
        } ?: return

        // 1. Initial status check
        updateStatus(powerManager)

        // 2. Battery saver broadcast
        try {
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    updateStatus(powerManager)
                }
            }, filter)
        } catch (_: Throwable) {}

        // 3. Thermal listener (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                powerManager.addThermalStatusListener { status ->
                    val isThermalThrottled = status >= PowerManager.THERMAL_STATUS_MODERATE
                    val isPowerSave = powerManager.isPowerSaveMode
                    _throttleLevel.value = if (isThermalThrottled || isPowerSave) {
                        ThrottleLevel.THROTTLED
                    } else {
                        ThrottleLevel.NORMAL
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun updateStatus(powerManager: PowerManager) {
        val isPowerSave = powerManager.isPowerSaveMode
        val isThermalThrottled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }

        _throttleLevel.value = if (isPowerSave || isThermalThrottled) {
            ThrottleLevel.THROTTLED
        } else {
            ThrottleLevel.NORMAL
        }
    }
}
