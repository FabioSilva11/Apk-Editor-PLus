package com.saas.apkeditorplus.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.saas.apkeditorplus.AppSettings
import com.saas.apkeditorplus.R
import com.saas.apkeditorplus.SettingsValues
import java.util.Locale

private data class TextDialogState(
    val title: String,
    val value: String,
    val supporting: String,
    val numeric: Boolean,
    val onSave: (String) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    values: SettingsValues,
    onBack: () -> Unit,
    onBooleanChange: (String, Boolean) -> Unit,
    onStringChange: (String, String) -> Unit,
    onIntChange: (String, Int) -> Unit,
    onThemeModeChange: (Int) -> Unit,
    onClearTemporaryFiles: () -> Unit
) {
    var dialog by remember { mutableStateOf<TextDialogState?>(null) }
    var clearDialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            MediumTopAppBar(
                title = { Column { Text("Configurações"); Text("Equivalentes ao APK Editor 4.5.2", style = MaterialTheme.typography.labelMedium) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Voltar") } }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionTitle("Aparência") }
            item {
                ChoiceSetting("Tema do aplicativo", AppSettings.themeModeLabel(values.themeMode)) {
                    onThemeModeChange(when (values.themeMode) {
                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_NO
                        AppCompatDelegate.MODE_NIGHT_NO -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    })
                }
            }

            item { SectionTitle("Lista de aplicativos") }
            item { ChoiceSetting("Ordenação", if (values.appListOrder == "date") "Data de atualização" else "Nome") { onStringChange(AppSettings.APP_LIST_ORDER, if (values.appListOrder == "name") "date" else "name") } }

            item { SectionTitle("Decodificação") }
            item {
                val labels = mapOf("all" to "Todos os arquivos", "partial" to "Arquivos essenciais", "ask" to "Perguntar")
                ChoiceSetting("Modo de decodificação", labels[values.decodeMode] ?: "Perguntar") {
                    onStringChange(AppSettings.DECODE_MODE, when (values.decodeMode) { "ask" -> "partial"; "partial" -> "all"; else -> "ask" })
                }
            }
            item { ChoiceSetting("API do Smali", values.smaliApi.toString()) {
                dialog = TextDialogState("API do Smali", values.smaliApi.toString(), "Valor entre 15 e 37", true) { onIntChange(AppSettings.SMALI_API, (it.toIntOrNull() ?: 15).coerceIn(15, 37)) }
            } }
            item { ChoiceSetting("Pasta de decodificação", values.decodeDirectory.ifBlank { "Armazenamento temporário interno" }) {
                dialog = TextDialogState("Pasta de decodificação", values.decodeDirectory, "Deixe vazio para usar a pasta interna do app", false) { onStringChange(AppSettings.DECODE_DIRECTORY, it) }
            } }
            item { ToggleSetting("Edição Smali", "Permitir abrir DEX como Smali", values.smaliEditing) { onBooleanChange(AppSettings.SMALI_EDITING, it) } }

            item { SectionTitle("Editor de texto") }
            item { ToggleSetting("Quebra automática", "Ajustar linhas longas", values.wordWrap) { onBooleanChange(AppSettings.EDITOR_WORD_WRAP, it) } }
            item { ToggleSetting("Números de linha", "Mostrar linhas no editor", values.showLineNumbers) { onBooleanChange(AppSettings.EDITOR_LINE_NUMBERS, it) } }
            item { ChoiceSetting("Tamanho da fonte", "${values.fontSize} sp") {
                dialog = TextDialogState("Tamanho da fonte", values.fontSize.toString(), "Entre 8 e 40 sp", true) { onIntChange(AppSettings.EDITOR_FONT_SIZE, (it.toIntOrNull() ?: 14).coerceIn(8, 40)) }
            } }
            item { ChoiceSetting("Arquivo grande", "${values.bigFileKb} KB") {
                dialog = TextDialogState("Limite de arquivo grande", values.bigFileKb.toString(), "Acima deste tamanho o editor reduz efeitos para poupar memória", true) { onIntChange(AppSettings.EDITOR_BIG_FILE_KB, (it.toIntOrNull() ?: 64).coerceIn(16, 4096)) }
            } }
            item { ToggleSetting("Barra de símbolos", "Mostrar atalhos para caracteres de código", values.symbolInput) { onBooleanChange(AppSettings.EDITOR_SYMBOL_INPUT, it) } }
            item { ToggleSetting("Editor externo", "Abrir arquivos em outro aplicativo compatível", values.externalEditor) { onBooleanChange(AppSettings.EXTERNAL_EDITOR, it) } }
            item {
                val themes = listOf("light", "darcula", "ayu-dark", "quietlight", "solarized_dark")
                ChoiceSetting("Tema do editor", values.editorTheme) {
                    onStringChange(AppSettings.EDITOR_THEME, themes[(themes.indexOf(values.editorTheme).coerceAtLeast(0) + 1) % themes.size])
                }
            }
            item { ToggleSetting("Cores personalizadas", "Substituir cores do tema do editor", values.customColors) { onBooleanChange(AppSettings.EDITOR_CUSTOM_COLORS, it) } }
            if (values.customColors) {
                item { ColorSetting("Fundo do editor", values.editorBackground) { color -> onIntChange(AppSettings.EDITOR_BACKGROUND, color) } }
                item { ColorSetting("Números de linha", values.editorLineColor) { color -> onIntChange(AppSettings.EDITOR_LINE_COLOR, color) } }
                items(values.syntaxColors.size) { index ->
                    ColorSetting("Cor de sintaxe ${index + 1}", values.syntaxColors[index]) { color -> onIntChange("editor_syntax_${index + 1}", color) }
                }
            }

            item { SectionTitle("Reconstrução e assinatura") }
            item { ToggleSetting("Confirmar reconstrução", "Pedir confirmação antes de gerar APK", values.rebuildConfirmation) { onBooleanChange(AppSettings.REBUILD_CONFIRMATION, it) } }
            item { ChoiceSetting("Chave de reconstrução", if (values.defaultSigner == "ask") "Perguntar antes de assinar" else "Chave de teste") { onStringChange(AppSettings.DEFAULT_SIGNER, if (values.defaultSigner == "testkey") "ask" else "testkey") } }
            item { ChoiceSetting("Nome do APK gerado", values.outputPattern) {
                dialog = TextDialogState("Nome do APK gerado", values.outputPattern, "Use {package}; a extensão .apk é adicionada automaticamente", false) { onStringChange(AppSettings.OUTPUT_APK_NAME, it.ifBlank { "{package}_mod.apk" }) }
            } }
            item { ChoiceSetting("Arquivo existente", if (values.fileRenameMode == "overwrite") "Sobrescrever" else "Renomear automaticamente") { onStringChange(AppSettings.FILE_RENAME_MODE, if (values.fileRenameMode == "auto") "overwrite" else "auto") } }
            item { ToggleSetting("Assinatura v1", "Android antigo", values.v1) { onBooleanChange(AppSettings.SIGN_V1, it) } }
            item { ToggleSetting("Assinatura v2", "Android 7+", values.v2) { onBooleanChange(AppSettings.SIGN_V2, it) } }
            item { ToggleSetting("Assinatura v3", "Android 9+ e rotação de chave", values.v3) { onBooleanChange(AppSettings.SIGN_V3, it) } }
            item { ToggleSetting("Assinatura v4", "Android 11+; gera arquivo .idsig", values.v4) { onBooleanChange(AppSettings.SIGN_V4, it) } }

            item { SectionTitle("Armazenamento") }
            item { OutlinedButton(onClick = { clearDialog = true }, Modifier.fillMaxWidth().padding(16.dp)) { Text("Limpar arquivos temporários") } }
        }
    }

    dialog?.let { state ->
        var value by remember(state) { mutableStateOf(state.value) }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(state.title) },
            text = { OutlinedTextField(value, { value = if (state.numeric) it.filter(Char::isDigit) else it }, supportingText = { Text(state.supporting) }, keyboardOptions = KeyboardOptions(keyboardType = if (state.numeric) KeyboardType.Number else KeyboardType.Text)) },
            confirmButton = { TextButton(onClick = { state.onSave(value); dialog = null }) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancelar") } }
        )
    }
    if (clearDialog) AlertDialog(
        onDismissRequest = { clearDialog = false },
        title = { Text("Limpar temporários?") },
        text = { Text("Projetos salvos, chaves e APKs originais serão preservados.") },
        confirmButton = { TextButton(onClick = { onClearTemporaryFiles(); clearDialog = false }) { Text("Limpar") } },
        dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("Cancelar") } }
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ToggleSetting(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked, onChange) },
        modifier = Modifier.clickable { onChange(!checked) }
    )
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ChoiceSetting(title: String, value: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        modifier = Modifier.clickable(onClick = onClick)
    )
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ColorSetting(title: String, color: Int, onChange: (Int) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val value = String.format(Locale.US, "#%08X", color)
    ChoiceSetting(title, value) { showDialog = true }
    if (showDialog) {
        var text by remember(color) { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = { OutlinedTextField(text, { text = it.take(9) }, supportingText = { Text("Formato #AARRGGBB") }) },
            confirmButton = { TextButton(onClick = {
                text.removePrefix("#").toLongOrNull(16)?.let { onChange(it.toInt()) }
                showDialog = false
            }) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancelar") } }
        )
    }
}
