package com.myclaud.audioverify.core.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide ring buffer of log lines. Mirrors to logcat AND keeps the last
 * [MAX_LINES] entries for in-app display via [lines]. Use this from any
 * thread; updates are atomic and Compose-safe.
 */
object AppLogger {
    private const val MAX_LINES = 400
    private const val DEFAULT_TAG = "AudioVerify"
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun i(tag: String = DEFAULT_TAG, msg: String) = emit("I", tag, msg, null)
    fun w(tag: String = DEFAULT_TAG, msg: String, t: Throwable? = null) = emit("W", tag, msg, t)
    fun e(tag: String = DEFAULT_TAG, msg: String, t: Throwable? = null) = emit("E", tag, msg, t)

    fun clear() {
        _lines.value = emptyList()
    }

    private fun emit(level: String, tag: String, msg: String, t: Throwable?) {
        when (level) {
            "I" -> Log.i(tag, msg)
            "W" -> if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
            "E" -> if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        }
        val timestamp = fmt.format(Date())
        val head = "$timestamp $level/$tag: $msg"
        val full = if (t != null) {
            head + "\n" + t.stackTraceToString().lineSequence().take(8).joinToString("\n")
        } else head
        synchronized(this) {
            val current = _lines.value
            val next = ArrayList<String>(minOf(current.size + 1, MAX_LINES))
            next.add(full)
            for (i in 0 until minOf(current.size, MAX_LINES - 1)) next.add(current[i])
            _lines.value = next
        }
    }
}
