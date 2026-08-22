package com.saas.apkeditorplus

import android.content.Context

object BuildJobStore {
    enum class Status { RUNNING, SUCCESS, FAILED, CANCELLED }

    data class State(
        val jobId: String,
        val status: Status,
        val detail: String,
        val outputPath: String = "",
        val packageName: String = ""
    )

    private const val PREFS = "apk_build_jobs"

    fun write(context: Context, state: State) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("${state.jobId}.status", state.status.name)
            .putString("${state.jobId}.detail", state.detail)
            .putString("${state.jobId}.output", state.outputPath)
            .putString("${state.jobId}.package", state.packageName)
            .apply()
    }

    fun read(context: Context, jobId: String): State? {
        if (jobId.isBlank()) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val status = prefs.getString("$jobId.status", null)?.let {
            runCatching { Status.valueOf(it) }.getOrNull()
        } ?: return null
        return State(
            jobId = jobId,
            status = status,
            detail = prefs.getString("$jobId.detail", "").orEmpty(),
            outputPath = prefs.getString("$jobId.output", "").orEmpty(),
            packageName = prefs.getString("$jobId.package", "").orEmpty()
        )
    }
}
