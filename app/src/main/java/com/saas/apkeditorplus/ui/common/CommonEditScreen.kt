package com.saas.apkeditorplus.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.saas.apkeditorplus.CommonEditForm
import com.saas.apkeditorplus.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommonEditScreen(
    title: String,
    form: CommonEditForm,
    icon: ImageBitmap?,
    loading: Boolean,
    saving: Boolean,
    onFormChange: (CommonEditForm) -> Unit,
    onPickIcon: () -> Unit,
    onClose: () -> Unit,
    onSave: () -> Unit
) {
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Column { Text("Edição comum"); Text(title, style = MaterialTheme.typography.bodyMedium) } },
                navigationIcon = { IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_back), "Voltar") } }
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onClose, Modifier.weight(1f)) { Text("Fechar") }
                Button(onClick = onSave, enabled = !loading && !saving, modifier = Modifier.weight(1f)) {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp)) else Text("Gerar APK")
                }
            }
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.align(Alignment.CenterHorizontally).clickable(onClick = onPickIcon),
                    shape = CircleShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    if (icon != null) Image(icon, "Ícone do aplicativo", Modifier.size(96.dp), contentScale = ContentScale.Crop)
                    else Icon(painterResource(R.drawable.apk_icon), null, Modifier.padding(24.dp).size(48.dp))
                }
                Text("Toque no ícone para substituí-lo", Modifier.align(Alignment.CenterHorizontally))
                SettingCard {
                    Field("Nome do aplicativo", form.appName) { onFormChange(form.copy(appName = it)) }
                    Field("Nome do pacote", form.packageName) { onFormChange(form.copy(packageName = it)) }
                    Toggle("Renomear nos recursos", form.renameResources) { onFormChange(form.copy(renameResources = it)) }
                    Toggle("Renomear dentro dos DEX", form.renameDex) { onFormChange(form.copy(renameDex = it)) }
                }
                SettingCard {
                    Field("Version code", form.versionCode, true) { onFormChange(form.copy(versionCode = it)) }
                    Field("Version name", form.versionName) { onFormChange(form.copy(versionName = it)) }
                }
                SettingCard {
                    Field("SDK mínimo", form.minSdk, true) { onFormChange(form.copy(minSdk = it)) }
                    Field("SDK alvo", form.targetSdk, true) { onFormChange(form.copy(targetSdk = it)) }
                    Field("SDK máximo (opcional)", form.maxSdk, true) { onFormChange(form.copy(maxSdk = it)) }
                    Text("Local de instalação", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null to "Padrão", 0 to "Auto", 1 to "Interno", 2 to "Externo").forEach { (value, label) ->
                            FilterChip(
                                selected = form.installLocation == value,
                                onClick = { onFormChange(form.copy(installLocation = value)) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}

@Composable
private fun Field(label: String, value: String, numeric: Boolean = false, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(if (numeric) it.filter(Char::isDigit) else it) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = onChange)
    }
}
