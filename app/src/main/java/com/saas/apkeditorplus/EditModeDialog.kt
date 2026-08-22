package com.saas.apkeditorplus

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme

class EditModeDialog(
    private val context: Context,
    private val apkPath: String,
    private val onModeSelected: (mode: Int, path: String) -> Unit
) {
    companion object {
        const val FULL_EDIT = 0
        const val SIMPLE_EDIT = 1
        const val COMMON_EDIT = 2
        const val XML_FILE_EDIT = 4
    }

    private var dialog: AlertDialog? = null

    fun show() {
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                ApkEditorTheme {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        ModeButton("Full Edit", R.drawable.ic_edit_1) { select(FULL_EDIT) }
                        ModeButton("Simple Edit", R.drawable.ic_edit_2) { select(SIMPLE_EDIT) }
                        ModeButton("Common Edit", R.drawable.ic_edit_3) { select(COMMON_EDIT) }
                        ModeButton("XML/AXML Edit", R.drawable.ic_edit_4) { select(XML_FILE_EDIT) }
                    }
                }
            }
        }
        
        val builder = AlertDialog.Builder(context)
            .setTitle(R.string.edit_mode)
            .setView(view)
            .setNegativeButton(R.string.colormixer_cancel, null)

        dialog = builder.create()

        dialog?.show()
    }

    private fun select(mode: Int) {
        onModeSelected(mode, apkPath)
        dialog?.dismiss()
    }

    @Composable
    private fun ModeButton(label: String, icon: Int, onClick: () -> Unit) {
        TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(icon), null)
            Text(label, modifier = Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
}
