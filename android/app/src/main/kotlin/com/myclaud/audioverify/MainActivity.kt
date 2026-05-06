package com.myclaud.audioverify

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.myclaud.audioverify.core.util.AppLogger
import com.myclaud.audioverify.ui.AudioVerifyApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Capture any uncaught exception from this process so the next
        // composition (or post-restart inspection via logcat) can see what
        // killed the app. We delegate to the system handler afterwards so
        // the OS still terminates the process normally.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                AppLogger.e("CrashHandler", "Uncaught on ${thread.name}", throwable)
            } catch (_: Throwable) {
                // never let logging itself prevent the chained handler from running
            }
            previous?.uncaughtException(thread, throwable)
        }

        AppLogger.i("MainActivity", "onCreate")
        setContent {
            MaterialTheme {
                AudioVerifyApp()
            }
        }
    }
}
