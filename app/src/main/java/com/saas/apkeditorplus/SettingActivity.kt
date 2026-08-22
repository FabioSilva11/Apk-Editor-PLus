package com.saas.apkeditorplus

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.saas.apkeditorplus.ui.settings.SettingsScreen
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme

class SettingActivity : BaseActivity() {
    private var revision by mutableStateOf(0)

    override fun shouldHideActionBar(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            revision
            ApkEditorTheme {
                SettingsScreen(
                    values = readValues(),
                    onBack = ::finish,
                    onBooleanChange = ::changeBooleanSetting,
                    onStringChange = { key, value ->
                        if (key == AppSettings.DECODE_DIRECTORY && value.isNotBlank() && !AppSettings.validateDecodeDirectory(value)) {
                            Toast.makeText(this, "A pasta não existe ou não permite gravação.", Toast.LENGTH_LONG).show()
                        } else {
                            prefs.edit().putString(key, value.trim()).apply(); revision++
                        }
                    },
                    onIntChange = { key, value -> prefs.edit().putInt(key, value).apply(); revision++ },
                    onThemeModeChange = { mode ->
                        prefs.edit().putInt("theme_mode", mode).apply()
                        AppCompatDelegate.setDefaultNightMode(mode)
                        revision++
                    },
                    onClearTemporaryFiles = {
                        AppSettings.clearWorkspaces(this)
                        Toast.makeText(this, "Arquivos temporários removidos.", Toast.LENGTH_SHORT).show()
                        revision++
                    }
                )
            }
        }
    }

    private fun changeBooleanSetting(key: String, value: Boolean) {
        if (key == AppSettings.SIGN_V4 && value) {
            prefs.edit()
                .putBoolean(AppSettings.SIGN_V2, true)
                .putBoolean(AppSettings.SIGN_V3, true)
                .putBoolean(AppSettings.SIGN_V4, true)
                .apply()
            revision++
            return
        }
        if (key in setOf(AppSettings.SIGN_V1, AppSettings.SIGN_V2, AppSettings.SIGN_V3) && !value) {
            val otherEnabled = listOf(AppSettings.SIGN_V1, AppSettings.SIGN_V2, AppSettings.SIGN_V3)
                .filterNot { it == key }
                .any { prefs.getBoolean(it, true) }
            if (!otherEnabled) {
                Toast.makeText(this, "Mantenha ao menos v1, v2 ou v3 ativa.", Toast.LENGTH_LONG).show()
                return
            }
        }
        prefs.edit().putBoolean(key, value).apply()
        revision++
    }

    private fun readValues() = SettingsValues(
        themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        appListOrder = prefs.getString(AppSettings.APP_LIST_ORDER, "name") ?: "name",
        decodeMode = prefs.getString(AppSettings.DECODE_MODE, "ask") ?: "ask",
        smaliApi = prefs.getInt(AppSettings.SMALI_API, 15),
        decodeDirectory = prefs.getString(AppSettings.DECODE_DIRECTORY, "").orEmpty(),
        smaliEditing = prefs.getBoolean(AppSettings.SMALI_EDITING, true),
        showLineNumbers = prefs.getBoolean(AppSettings.EDITOR_LINE_NUMBERS, true),
        wordWrap = prefs.getBoolean(AppSettings.EDITOR_WORD_WRAP, true),
        fontSize = prefs.getInt(AppSettings.EDITOR_FONT_SIZE, 14),
        bigFileKb = prefs.getInt(AppSettings.EDITOR_BIG_FILE_KB, 64),
        symbolInput = prefs.getBoolean(AppSettings.EDITOR_SYMBOL_INPUT, true),
        editorTheme = prefs.getString(AppSettings.EDITOR_THEME, "light") ?: "light",
        customColors = prefs.getBoolean(AppSettings.EDITOR_CUSTOM_COLORS, false),
        editorBackground = prefs.getInt(AppSettings.EDITOR_BACKGROUND, 0xFF002B36.toInt()),
        editorLineColor = prefs.getInt(AppSettings.EDITOR_LINE_COLOR, 0xFFFDF6E3.toInt()),
        syntaxColors = List(9) { index ->
            prefs.getInt("editor_syntax_${index + 1}", DEFAULT_SYNTAX_COLORS[index])
        },
        rebuildConfirmation = prefs.getBoolean(AppSettings.REBUILD_CONFIRMATION, false),
        externalEditor = prefs.getBoolean(AppSettings.EXTERNAL_EDITOR, false),
        fileRenameMode = prefs.getString(AppSettings.FILE_RENAME_MODE, "auto") ?: "auto",
        v1 = prefs.getBoolean(AppSettings.SIGN_V1, true),
        v2 = prefs.getBoolean(AppSettings.SIGN_V2, true),
        v3 = prefs.getBoolean(AppSettings.SIGN_V3, true),
        v4 = prefs.getBoolean(AppSettings.SIGN_V4, false),
        defaultSigner = prefs.getString(AppSettings.DEFAULT_SIGNER, "testkey") ?: "testkey",
        outputPattern = prefs.getString(AppSettings.OUTPUT_APK_NAME, "{package}_mod.apk") ?: "{package}_mod.apk"
    )

    companion object {
        val DEFAULT_SYNTAX_COLORS = intArrayOf(
            0xFF859900.toInt(), 0xFFB58900.toInt(), 0xFF2AA198.toInt(),
            0xFF93A1A1.toInt(), 0xFFCB4B16.toInt(), 0xFF586E75.toInt(),
            0xFFDC322F.toInt(), 0xFF268BD2.toInt(), 0xFF93A1A1.toInt()
        )
    }
}

data class SettingsValues(
    val themeMode: Int,
    val appListOrder: String,
    val decodeMode: String,
    val smaliApi: Int,
    val decodeDirectory: String,
    val smaliEditing: Boolean,
    val showLineNumbers: Boolean,
    val wordWrap: Boolean,
    val fontSize: Int,
    val bigFileKb: Int,
    val symbolInput: Boolean,
    val editorTheme: String,
    val customColors: Boolean,
    val editorBackground: Int,
    val editorLineColor: Int,
    val syntaxColors: List<Int>,
    val rebuildConfirmation: Boolean,
    val externalEditor: Boolean,
    val fileRenameMode: String,
    val v1: Boolean,
    val v2: Boolean,
    val v3: Boolean,
    val v4: Boolean,
    val defaultSigner: String,
    val outputPattern: String
)
