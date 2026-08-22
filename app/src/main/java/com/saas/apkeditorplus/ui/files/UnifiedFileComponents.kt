package com.saas.apkeditorplus.ui.files

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class FileVisualKind {
    PARENT, FOLDER, APK, XML, TEXT, IMAGE, AUDIO, DEX, SMALI, ARCHIVE, OTHER
}

fun classifyFile(name: String, directory: Boolean = false, parent: Boolean = false): FileVisualKind {
    if (parent || name == "..") return FileVisualKind.PARENT
    if (directory) return FileVisualKind.FOLDER
    return when (name.substringAfterLast('.', "").lowercase()) {
        "apk" -> FileVisualKind.APK
        "xml", "axml" -> FileVisualKind.XML
        "txt", "json", "json5", "md", "properties", "yml", "yaml", "csv" -> FileVisualKind.TEXT
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "svg" -> FileVisualKind.IMAGE
        "mp3", "wav", "ogg", "m4a", "aac", "flac" -> FileVisualKind.AUDIO
        "dex" -> FileVisualKind.DEX
        "smali" -> FileVisualKind.SMALI
        "zip", "jar", "aar", "7z", "rar", "gz" -> FileVisualKind.ARCHIVE
        else -> FileVisualKind.OTHER
    }
}

fun classifyFile(file: File, parent: Boolean = false): FileVisualKind =
    classifyFile(file.name, file.isDirectory, parent)

@Composable
fun UnifiedFileRow(
    name: String,
    detail: String,
    kind: FileVisualKind,
    thumbnail: ImageBitmap? = null,
    thumbnailKey: String? = null,
    thumbnailLoader: (() -> ImageBitmap?)? = null,
    modified: Boolean = false,
    onReplace: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val visibleThumbnail by produceState(
        initialValue = thumbnail,
        key1 = thumbnail,
        key2 = thumbnailKey
    ) {
        value = thumbnail ?: if (thumbnailKey != null && thumbnailLoader != null) {
            withContext(Dispatchers.IO) { runCatching(thumbnailLoader).getOrNull() }
        } else {
            null
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(12.dp),
            color = iconContainerColor(kind)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (visibleThumbnail != null) {
                    Image(
                        bitmap = visibleThumbnail!!,
                        contentDescription = "Miniatura de $name",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = iconFor(kind),
                        contentDescription = null,
                        tint = iconColor(kind),
                        modifier = Modifier.size(23.dp)
                    )
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (modified) {
            Text(
                "Alterado",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        if (onReplace != null || onExport != null || onDelete != null) {
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.MoreVert, "Ações")
                }
                DropdownMenu(menuExpanded, { menuExpanded = false }) {
                    if (onReplace != null) DropdownMenuItem(
                        text = { Text("Substituir") },
                        onClick = { menuExpanded = false; onReplace() }
                    )
                    if (onExport != null) DropdownMenuItem(
                        text = { Text("Exportar") },
                        onClick = { menuExpanded = false; onExport() }
                    )
                    if (onDelete != null) DropdownMenuItem(
                        text = { Text("Excluir") },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }
    }
}

/** Decodes a small preview without keeping the source image at its original resolution in RAM. */
fun decodeImageThumbnail(
    openStream: () -> InputStream?,
    maxDimensionPx: Int = 192
): ImageBitmap? {
    val encoded = openStream()?.use(::readThumbnailBytes) ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxDimensionPx * 2 ||
        bounds.outHeight / sampleSize > maxDimensionPx * 2
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)?.asImageBitmap()
}

private fun readThumbnailBytes(input: InputStream): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > MAX_THUMBNAIL_SOURCE_BYTES) return null
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private const val MAX_THUMBNAIL_SOURCE_BYTES = 16 * 1024 * 1024

fun decodeImageThumbnail(file: File, maxDimensionPx: Int = 192): ImageBitmap? {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxDimensionPx * 2 ||
        bounds.outHeight / sampleSize > maxDimensionPx * 2
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
}

private fun iconFor(kind: FileVisualKind): ImageVector = when (kind) {
    FileVisualKind.PARENT -> Icons.Rounded.ArrowUpward
    FileVisualKind.FOLDER -> Icons.Rounded.Folder
    FileVisualKind.APK -> Icons.Rounded.Android
    FileVisualKind.XML -> Icons.Rounded.Code
    FileVisualKind.TEXT -> Icons.Rounded.Description
    FileVisualKind.IMAGE -> Icons.Rounded.Image
    FileVisualKind.AUDIO -> Icons.Rounded.AudioFile
    FileVisualKind.DEX -> Icons.Rounded.DataObject
    FileVisualKind.SMALI -> Icons.Rounded.Terminal
    FileVisualKind.ARCHIVE -> Icons.Rounded.Archive
    FileVisualKind.OTHER -> Icons.Rounded.InsertDriveFile
}

@Composable
private fun iconContainerColor(kind: FileVisualKind) = when (kind) {
    FileVisualKind.FOLDER, FileVisualKind.PARENT -> MaterialTheme.colorScheme.primaryContainer
    FileVisualKind.APK -> MaterialTheme.colorScheme.secondaryContainer
    FileVisualKind.XML, FileVisualKind.SMALI, FileVisualKind.DEX -> MaterialTheme.colorScheme.tertiaryContainer
    else -> MaterialTheme.colorScheme.surfaceContainerHighest
}

@Composable
private fun iconColor(kind: FileVisualKind) = when (kind) {
    FileVisualKind.FOLDER, FileVisualKind.PARENT -> MaterialTheme.colorScheme.onPrimaryContainer
    FileVisualKind.APK -> MaterialTheme.colorScheme.onSecondaryContainer
    FileVisualKind.XML, FileVisualKind.SMALI, FileVisualKind.DEX -> MaterialTheme.colorScheme.onTertiaryContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
