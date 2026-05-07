package com.myclaud.audioverify.core.report

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Storage {
    fun reportsDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "reports")
        dir.mkdirs()
        return dir
    }

    fun newRunDir(context: Context, timestampMs: Long): File {
        val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        val dir = File(reportsDir(context), fmt.format(Date(timestampMs)))
        dir.mkdirs()
        return dir
    }

    fun listRuns(context: Context): List<File> =
        reportsDir(context).listFiles()?.filter { it.isDirectory }?.sortedDescending() ?: emptyList()
}
