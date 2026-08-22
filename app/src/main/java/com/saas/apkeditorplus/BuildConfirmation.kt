package com.saas.apkeditorplus

import androidx.appcompat.app.AlertDialog

fun BaseActivity.confirmRebuild(onConfirmed: () -> Unit) {
    if (!AppSettings.prefs(this).getBoolean(AppSettings.REBUILD_CONFIRMATION, false)) {
        onConfirmed()
        return
    }
    AlertDialog.Builder(this)
        .setTitle(R.string.build)
        .setMessage("Gerar e assinar o APK com as alterações atuais?")
        .setPositiveButton(R.string.build) { _, _ -> onConfirmed() }
        .setNegativeButton(R.string.colormixer_cancel, null)
        .show()
}
