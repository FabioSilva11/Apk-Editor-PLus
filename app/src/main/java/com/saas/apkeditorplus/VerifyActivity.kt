package com.saas.apkeditorplus

import android.os.Bundle
import android.content.Intent
import com.saas.apkeditorplus.utils.Verify
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import com.saas.apkeditorplus.ui.verify.VerifyScreen

class VerifyActivity : BaseActivity() {
    
    private var resultText by mutableStateOf("")
    private var loading by mutableStateOf(false)

    override fun shouldHideActionBar(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContent {
            ApkEditorTheme {
                VerifyScreen(
                    result = resultText,
                    loading = loading,
                    onBack = ::finish,
                    onSelectAnother = ::openSelector
                )
            }
        }
        
        // Abre o seletor de arquivos ao iniciar se for a primeira vez
        if (savedInstanceState == null) {
            openSelector()
        }
    }

    private fun openSelector() {
        val intent = Intent(this, FileListActivity::class.java)
        intent.putExtra("select_for_verify", true)
        startActivityForResult(intent, 1001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val apkPath = data?.getStringExtra("apkPath")
            if (apkPath != null) {
                verifyApk(apkPath)
            }
        } else if (requestCode == 1001 && resultCode == RESULT_CANCELED) {
            // Se o usuário cancelou a seleção sem escolher nada, fecha a atividade
            if (resultText.isEmpty()) {
                finish()
            }
        }
    }

    private fun verifyApk(path: String) {
        loading = true
        resultText = path
        
        // Executamos em uma thread separada para não travar a UI
        Thread {
            val result = Verify.verify(path)
            runOnUiThread {
                resultText = result
                loading = false
            }
        }.start()
    }
}
