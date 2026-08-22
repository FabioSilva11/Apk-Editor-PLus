package com.saas.apkeditorplus.ui.full

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.viewpager2.widget.ViewPager2
import com.saas.apkeditorplus.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullEditScreen(
    title: String,
    packageName: String,
    icon: ImageBitmap?,
    selectedTab: Int,
    hasChanges: Boolean,
    patchBusy: Boolean,
    serverRunning: Boolean,
    onBack: () -> Unit,
    onBuild: () -> Unit,
    onPatch: () -> Unit,
    onWebServer: () -> Unit,
    onTabSelected: (Int) -> Unit,
    onPagerReady: (ViewPager2) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Voltar") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (icon != null) Image(icon, null, Modifier.size(42.dp), contentScale = ContentScale.Fit)
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(packageName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onWebServer) {
                        Icon(
                            painterResource(R.drawable.ic_www),
                            if (serverRunning) "Parar servidor" else "Iniciar servidor",
                            tint = if (serverRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = onPatch, enabled = !patchBusy) {
                        if (patchBusy) CircularProgressIndicator(Modifier.size(22.dp))
                        else Icon(painterResource(R.drawable.dg_patch), "Aplicar patch")
                    }
                    Button(onClick = onBuild, enabled = hasChanges, modifier = Modifier.padding(end = 8.dp)) { Text("Gerar") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple("Strings", R.drawable.ic_edit_1, 0),
                    Triple("Arquivos", R.drawable.ic_folder, 1),
                    Triple("Recursos", R.drawable.ic_edit_2, 2),
                    Triple("Manifest", R.drawable.ic_edit_4, 3)
                ).forEach { (label, iconRes, index) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { onTabSelected(index) },
                        icon = { Icon(painterResource(iconRes), null) },
                        label = { Text(label) }
                    )
                }
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { onTabSelected(4) },
                    icon = { Icon(Icons.Rounded.Difference, null) },
                    label = { Text("Diferenças") }
                )
            }
        }
    ) { padding ->
        AndroidView(
            factory = { context -> ViewPager2(context).also(onPagerReady) },
            update = { pager -> if (pager.currentItem != selectedTab) pager.setCurrentItem(selectedTab, false) },
            modifier = Modifier.fillMaxSize().padding(padding)
        )
    }
}
