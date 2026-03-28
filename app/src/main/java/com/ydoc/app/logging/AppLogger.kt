package com.ydoc.app.logging

import android.util.Log

object AppLogger {
    fun ui(message: String) = Log.d("YDOC_UI", message)
    fun overlay(message: String) = Log.d("YDOC_OVERLAY", message)
    fun audio(message: String) = Log.d("YDOC_AUDIO", message)
    fun relay(message: String) = Log.d("YDOC_RELAY", message)
    fun volc(message: String) = Log.d("YDOC_VOLC", message)
    fun sync(message: String) = Log.d("YDOC_SYNC", message)

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
    }
}
