package com.watchpicture.app.util

import android.util.Log

/**
 * High-visibility logger for WatchPicture.
 *
 * Directs logs through Log.e on commercial HyperOS / Android ROMs to guarantee
 * logs are never pruned or silenced by restrictive system logd daemon filters.
 */
object AppLog {
    private const val TAG = "WatchPicture"

    fun d(subtag: String, msg: String) {
        Log.e(TAG, "[$subtag] $msg")
    }

    fun i(subtag: String, msg: String) {
        Log.e(TAG, "[$subtag] $msg")
    }

    fun w(subtag: String, msg: String) {
        Log.e(TAG, "[$subtag] WARN: $msg")
    }

    fun e(subtag: String, msg: String, tr: Throwable? = null) {
        Log.e(TAG, "[$subtag] ERROR: $msg", tr)
    }
}
