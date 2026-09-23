package com.watchpicture.app.util

import android.util.Log
import com.watchpicture.app.BuildConfig

/**
 * Central logger for WatchPicture.
 *
 * In debug builds it logs at the natural level so development output is readable.
 * In release builds it only keeps ERROR (and drops d/i/w) to avoid log spamming the
 * production device and to keep path/pack metadata out of the shipped logs.
 */
object AppLog {
    private const val TAG = "WatchPicture"

    fun d(subtag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "[$subtag] $msg")
        }
    }

    fun i(subtag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "[$subtag] $msg")
        }
    }

    fun w(subtag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            Log.w(TAG, "[$subtag] WARN: $msg")
        }
    }

    fun e(subtag: String, msg: String, tr: Throwable? = null) {
        Log.e(TAG, "[$subtag] ERROR: $msg", tr)
    }
}