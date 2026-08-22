package com.saas.apkeditorplus

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.saas.apkeditorplus.ui.signing.SigningProgressScreen
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import java.io.File

class SigningProgressActivity : BaseActivity() {

    private var inputPath: String? = null
    private var outputPath: String? = null
    private var ksPath: String? = null
    private var ksPass: String? = null
    private var alias: String? = null
    private var keyPass: String? = null
    private var status by mutableStateOf("")
    private var finished by mutableStateOf(false)
    private var failed by mutableStateOf(false)

    override fun shouldHideActionBar(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        // Pega dados da Intent
        inputPath = intent.getStringExtra("inputPath")
        outputPath = intent.getStringExtra("outputPath")
        ksPath = intent.getStringExtra("ksPath")
        ksPass = intent.getStringExtra("ksPass")
        alias = intent.getStringExtra("alias")
        keyPass = intent.getStringExtra("keyPass")

        setContent {
            ApkEditorTheme {
                SigningProgressScreen(
                    status = status,
                    finished = finished,
                    failed = failed,
                    onViewOutput = ::openOutputFolder,
                    onFinish = ::finish
                )
            }
        }

        startSigning()
    }

    private fun startSigning() {
        val inFile = File(inputPath ?: return)
        val outFile = File(outputPath ?: return)
        val ksFile = File(ksPath ?: return)
        
        Thread {
            val signer = ApkSignerManager()
            signer.signApk(
                inFile,
                outFile,
                ksFile,
                ksPass?.toCharArray() ?: charArrayOf(),
                alias ?: "",
                keyPass?.toCharArray() ?: charArrayOf(),
                enableV1 = prefs.getBoolean("sign_v1", true),
                enableV2 = prefs.getBoolean("sign_v2", true),
                enableV3 = prefs.getBoolean("sign_v3", true),
                enableV4 = prefs.getBoolean(AppSettings.SIGN_V4, false),
                listener = object : ApkSignerManager.SignerListener {
                    override fun onStart() {
                        updateUI(getString(R.string.signing_started), false)
                    }

                    override fun onProgress(message: String) {
                        updateUI(message, false)
                    }

                    override fun onSuccess() {
                        updateUI(getString(R.string.success_apk_signed), true)
                    }

                    override fun onError(message: String) {
                        updateUI(getString(R.string.error_label, message), true, isError = true)
                    }
                }
            )
        }.start()
    }

    private fun updateUI(message: String, isFinished: Boolean, isError: Boolean = false) {
        runOnUiThread {
            status = message
            finished = isFinished
            failed = isError
        }
    }

    private fun openOutputFolder() {
        val file = File(outputPath ?: return)
        val resultIntent = Intent()
        resultIntent.putExtra("targetPath", file.parent)
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}
