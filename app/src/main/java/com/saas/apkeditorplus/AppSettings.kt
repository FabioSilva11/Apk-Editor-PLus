package com.saas.apkeditorplus

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import java.io.File

object AppSettings {
    const val PREFS = "settings"
    const val APP_LIST_ORDER = "app_list_order"
    const val DECODE_MODE = "decode_mode"
    const val SMALI_API = "smali_api"
    const val DECODE_DIRECTORY = "decode_directory"
    const val SMALI_EDITING = "smali_editing_enabled"
    const val REBUILD_CONFIRMATION = "rebuild_confirmation"
    const val EXTERNAL_EDITOR = "external_editor"
    const val FILE_RENAME_MODE = "file_rename_mode"
    const val OUTPUT_APK_NAME = "output_apk_name"
    const val SIGN_V1 = "sign_v1"
    const val SIGN_V2 = "sign_v2"
    const val SIGN_V3 = "sign_v3"
    const val SIGN_V4 = "sign_v4"
    const val DEFAULT_SIGNER = "default_signer"
    const val EDITOR_LINE_NUMBERS = "editor_show_line_numbers"
    const val EDITOR_WORD_WRAP = "editor_word_wrap"
    const val EDITOR_FONT_SIZE = "editor_font_size_int"
    const val EDITOR_BIG_FILE_KB = "editor_big_file_kb"
    const val EDITOR_SYMBOL_INPUT = "editor_symbol_input"
    const val EDITOR_THEME = "editor_theme"
    const val EDITOR_CUSTOM_COLORS = "editor_custom_colors"
    const val EDITOR_BACKGROUND = "editor_background"
    const val EDITOR_LINE_COLOR = "editor_line_color"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun workspaceRoot(context: Context, child: String): File {
        val configured = prefs(context).getString(DECODE_DIRECTORY, null)?.trim().orEmpty()
        val root = configured.takeIf { it.isNotBlank() }?.let(::File)
            ?.takeIf { it.isDirectory && it.canWrite() }
            ?: context.cacheDir
        return File(root, child).apply { mkdirs() }
    }

    fun validateDecodeDirectory(path: String): Boolean {
        val directory = File(path)
        if (!directory.isDirectory || !directory.canWrite()) return false
        val probe = File(directory, ".apkeditor_write_test")
        return runCatching { probe.writeText("ok"); probe.delete(); true }.getOrDefault(false)
    }

    fun clearWorkspaces(context: Context) {
        context.cacheDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        val configured = prefs(context).getString(DECODE_DIRECTORY, null)?.trim().orEmpty()
        if (configured.isBlank()) return
        val root = File(configured)
        if (!root.isDirectory) return
        val ownedDirectories = setOf(
            "full_edit", "full_edit_built_dex", "full_edit_smali", "full_edit_workspace",
            "full_edit_workspace_manifest", "full_edit_strings_workspace",
            "full_edit_workspace_resources", "full_edit_strings", "full_edit_resources",
            "full_edit_replace"
        )
        ownedDirectories.forEach { name -> File(root, name).takeIf(File::exists)?.deleteRecursively() }
    }

    fun exportTarget(directory: File, desiredName: String, overwrite: Boolean): File {
        val direct = File(directory, desiredName)
        if (overwrite || !direct.exists()) return direct
        val base = desiredName.substringBeforeLast('.', desiredName)
        val extension = desiredName.substringAfterLast('.', "").takeIf { it != desiredName }.orEmpty()
        var index = 1
        while (true) {
            val name = if (extension.isBlank()) "${base}_$index" else "${base}_$index.$extension"
            val candidate = File(directory, name)
            if (!candidate.exists()) return candidate
            index++
        }
    }

    fun themeModeLabel(mode: Int): String = when (mode) {
        AppCompatDelegate.MODE_NIGHT_NO -> "Claro"
        AppCompatDelegate.MODE_NIGHT_YES -> "Escuro"
        else -> "Seguir o sistema"
    }
}
