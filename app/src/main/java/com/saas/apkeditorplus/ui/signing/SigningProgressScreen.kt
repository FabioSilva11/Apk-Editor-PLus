package com.saas.apkeditorplus.ui.signing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.saas.apkeditorplus.R

@Composable
fun SigningProgressScreen(
    status: String,
    finished: Boolean,
    failed: Boolean,
    onViewOutput: () -> Unit,
    onFinish: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!finished) CircularProgressIndicator(Modifier.size(64.dp))
        else Icon(
            painterResource(if (failed) R.drawable.ic_close else R.drawable.ic_select),
            null,
            Modifier.size(72.dp),
            tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
        Text(
            when { !finished -> "Assinando APK"; failed -> "Falha na assinatura"; else -> "Assinatura concluída" },
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 24.dp)
        )
        Text(status, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        if (finished) Row(
            Modifier.fillMaxWidth().padding(top = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!failed) OutlinedButton(onClick = onViewOutput, Modifier.weight(1f)) { Text("Ver saída") }
            Button(onClick = onFinish, Modifier.weight(1f)) { Text("Concluir") }
        }
    }
}
