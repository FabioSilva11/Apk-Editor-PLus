package com.saas.apkeditorplus

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.PhotoSizeSelectLarge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.saas.apkeditorplus.ui.theme.ApkEditorTheme
import java.io.File

class ImageEditorActivity : BaseActivity() {
    private lateinit var imageFile: File
    private var bitmap by mutableStateOf<Bitmap?>(null)
    private var busy by mutableStateOf(true)
    private var modified by mutableStateOf(false)

    override fun shouldHideActionBar() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        imageFile = File(intent.getStringExtra(EXTRA_FILE_PATH).orEmpty())
        if (!imageFile.isFile) { finish(); return }
        setContent { ApkEditorTheme { EditorScreen() } }
        Thread {
            val loaded = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Imagem inválida" }
                require(bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000L) { "Imagem grande demais para edição segura" }
                BitmapFactory.decodeFile(imageFile.absolutePath, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                }) ?: error("Não foi possível abrir a imagem")
            }
            runOnUiThread {
                busy = false
                loaded.onSuccess { bitmap = it }.onFailure {
                    Toast.makeText(this, it.message, Toast.LENGTH_LONG).show(); finish()
                }
            }
        }.start()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun EditorScreen() {
        var scale by androidx.compose.runtime.remember { mutableFloatStateOf(1f) }
        var offset by androidx.compose.runtime.remember { mutableStateOf(Offset.Zero) }
        val transform = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(0.5f, 8f)
            offset += pan
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(intent.getStringExtra(EXTRA_TITLE) ?: imageFile.name) },
                    navigationIcon = { IconButton(onClick = ::finish) { Icon(painterResource(R.drawable.ic_back), "Voltar") } },
                    actions = { Button(onClick = ::saveImage, enabled = modified && !busy) { Text("Salvar") } }
                )
            },
            bottomBar = {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ToolButton("Redimensionar", Icons.Rounded.PhotoSizeSelectLarge, ::showResizeDialog)
                    ToolButton("Transparência", Icons.Rounded.Opacity, ::showAlphaDialog)
                    ToolButton("Remover fundo", Icons.Rounded.AutoFixHigh, ::showRemoveBackgroundDialog)
                }
            }
        ) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val current = bitmap
                if (busy || current == null) CircularProgressIndicator()
                else {
                    Image(
                        current.asImageBitmap(),
                        null,
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = offset.x; translationY = offset.y
                        }.transformable(transform),
                        contentScale = ContentScale.Fit
                    )
                    Text(
                        "${current.width} × ${current.height} px • ${(scale * 100).toInt()}%",
                        Modifier.align(Alignment.TopCenter).padding(10.dp)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), MaterialTheme.shapes.small)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ToolButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = action, enabled = !busy) { Icon(icon, label) }
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }

    private fun showResizeDialog() {
        val current = bitmap ?: return
        val width = numberInput(current.width)
        val height = numberInput(current.height)
        val preserve = CheckBox(this).apply { text = "Manter proporção"; isChecked = true }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 12, 48, 0)
            addView(width); addView(height); addView(preserve)
        }
        width.hint = "Largura"; height.hint = "Altura"
        width.setOnFocusChangeListener { _, focused ->
            if (!focused && preserve.isChecked) width.text.toString().toIntOrNull()?.let {
                height.setText((it * current.height.toDouble() / current.width).toInt().coerceAtLeast(1).toString())
            }
        }
        AlertDialog.Builder(this).setTitle("Novo tamanho").setView(layout)
            .setPositiveButton("Aplicar") { _, _ ->
                val w = width.text.toString().toIntOrNull() ?: return@setPositiveButton
                val h = height.text.toString().toIntOrNull() ?: return@setPositiveButton
                applyEdit { ImageEditEngine.resize(it, w, h) }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun showAlphaDialog() {
        val input = numberInput(80).apply { hint = "Opacidade de 0 a 100" }
        AlertDialog.Builder(this).setTitle("Transparência geral").setView(input)
            .setPositiveButton("Aplicar") { _, _ ->
                input.text.toString().toIntOrNull()?.let { value -> applyEdit { ImageEditEngine.applyOverallAlpha(it, value) } }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun showRemoveBackgroundDialog() {
        val input = numberInput(24).apply { hint = "Tolerância de 0 a 255" }
        AlertDialog.Builder(this).setTitle("Remover cor do canto superior esquerdo").setView(input)
            .setPositiveButton("Aplicar") { _, _ ->
                input.text.toString().toIntOrNull()?.let { value -> applyEdit { ImageEditEngine.removeBackground(it, value) } }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun numberInput(value: Int) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.toString())
        selectAll()
    }

    private fun applyEdit(operation: (Bitmap) -> Bitmap) {
        val current = bitmap ?: return
        busy = true
        Thread {
            val result = runCatching { operation(current) }
            runOnUiThread {
                busy = false
                result.onSuccess { changed ->
                    if (changed !== current) current.recycle()
                    bitmap = changed; modified = true
                }.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun saveImage() {
        if (ImageReplacementProcessor.isNinePatch(imageFile.name)) {
            Toast.makeText(this, "NinePatch é somente leitura neste editor", Toast.LENGTH_LONG).show(); return
        }
        val current = bitmap ?: return
        busy = true
        Thread {
            val result = runCatching { ImageEditEngine.save(current, imageFile) }
            runOnUiThread {
                busy = false
                result.onSuccess { setResult(RESULT_OK); finish() }
                    .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    override fun onDestroy() {
        bitmap?.recycle(); bitmap = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FILE_PATH = "filePath"
        const val EXTRA_TITLE = "imageTitle"
    }
}
