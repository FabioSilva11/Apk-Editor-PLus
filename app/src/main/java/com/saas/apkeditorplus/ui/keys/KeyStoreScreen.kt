package com.saas.apkeditorplus.ui.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.saas.apkeditorplus.KeyCreationForm
import com.saas.apkeditorplus.KeyStoreManager
import com.saas.apkeditorplus.R
import java.io.File
import java.text.DateFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyStoreScreen(
    files: List<File>,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onCreate: (KeyCreationForm) -> String?,
    onInspect: (File, String) -> Pair<List<KeyStoreManager.KeyAliasInfo>, String?>,
    onDelete: (File) -> Unit
) {
    var createDialog by remember { mutableStateOf(false) }
    var inspectFile by remember { mutableStateOf<File?>(null) }
    var deleteFile by remember { mutableStateOf<File?>(null) }
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Column { Text("Chaves de assinatura"); Text("PKCS12 e JKS", style = MaterialTheme.typography.bodyMedium) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Voltar") } },
                actions = { TextButton(onClick = onImport) { Text("Importar") } }
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { createDialog = true }) { Icon(painterResource(R.drawable.ic_add), "Criar") } }
    ) { padding ->
        if (files.isEmpty()) Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center) {
            Text("Nenhuma chave salva", style = MaterialTheme.typography.headlineSmall)
            Text("Crie uma chave ou importe um arquivo PKCS12/JKS.")
        } else LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(files, key = { it.absolutePath }) { file ->
                Card(
                    onClick = { inspectFile = file },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Row(Modifier.fillMaxWidth().padding(18.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.titleMedium)
                            Text("${file.length()} bytes", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { deleteFile = file }) { Icon(painterResource(R.drawable.ic_close), "Excluir") }
                    }
                }
            }
        }
    }
    if (createDialog) CreateKeyDialog(onDismiss = { createDialog = false }, onCreate = onCreate)
    inspectFile?.let { file -> InspectDialog(file, onDismiss = { inspectFile = null }, onInspect = onInspect) }
    deleteFile?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteFile = null },
            title = { Text("Excluir ${file.name}?") },
            text = { Text("Essa chave não poderá ser recuperada.") },
            confirmButton = { TextButton(onClick = { onDelete(file); deleteFile = null }) { Text("Excluir") } },
            dismissButton = { TextButton(onClick = { deleteFile = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun CreateKeyDialog(onDismiss: () -> Unit, onCreate: (KeyCreationForm) -> String?) {
    var form by remember { mutableStateOf(KeyCreationForm()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Criar chave") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("Arquivo", form.fileName) { form = form.copy(fileName = it) }
                Field("Senha do arquivo", form.storePassword, true) { form = form.copy(storePassword = it) }
                Field("Alias", form.alias) { form = form.copy(alias = it) }
                Field("Senha da chave", form.keyPassword, true) { form = form.copy(keyPassword = it) }
                Field("Nome comum", form.commonName) { form = form.copy(commonName = it) }
                Field("Organização", form.organization) { form = form.copy(organization = it) }
                Field("Unidade", form.organizationUnit) { form = form.copy(organizationUnit = it) }
                Field("Cidade", form.locality) { form = form.copy(locality = it) }
                Field("Estado", form.state) { form = form.copy(state = it) }
                Field("País", form.country) { form = form.copy(country = it.take(2)) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = onCreate(form)
                if (error == null) onDismiss()
            }) { Text("Criar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun InspectDialog(
    file: File,
    onDismiss: () -> Unit,
    onInspect: (File, String) -> Pair<List<KeyStoreManager.KeyAliasInfo>, String?>
) {
    var password by remember { mutableStateOf("") }
    var aliases by remember { mutableStateOf<List<KeyStoreManager.KeyAliasInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (aliases == null) Field("Senha", password, true) { password = it }
                else aliases.orEmpty().forEach { info ->
                    Text(info.alias, style = MaterialTheme.typography.titleMedium)
                    Text(info.subject)
                    Text("Válida até ${info.validUntil?.let(DateFormat.getDateInstance()::format).orEmpty()}")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (aliases == null) TextButton(onClick = {
                val result = onInspect(file, password)
                aliases = result.first.takeIf { result.second == null }
                error = result.second
            }) { Text("Abrir") }
            else TextButton(onClick = onDismiss) { Text("Fechar") }
        },
        dismissButton = { if (aliases == null) TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun Field(label: String, value: String, password: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth()
    )
}
