package com.saas.apkeditorplus

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/** Prepares one replacement per resource-density variant, preserving each original pixel size. */
object ImageReplacementProcessor {
    private const val MAX_SOURCE_PIXELS = 40_000_000L

    data class PreparedVariant(
        val entryName: String,
        val file: File,
        val width: Int,
        val height: Int
    )

    fun prepare(
        sourceApk: File,
        selectedImage: File,
        targetEntries: List<String>,
        outputDirectory: File
    ): List<PreparedVariant> {
        require(sourceApk.isFile) { "APK original não encontrado" }
        require(selectedImage.isFile) { "Imagem selecionada não encontrada" }
        require(targetEntries.isNotEmpty()) { "Nenhuma variante de imagem encontrada" }
        require(targetEntries.none(::isNinePatch)) {
            "Imagens NinePatch (.9.png) não podem ser redimensionadas como PNG comum"
        }

        val sourceBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(selectedImage.absolutePath, sourceBounds)
        require(sourceBounds.outWidth > 0 && sourceBounds.outHeight > 0) { "Imagem selecionada inválida" }
        require(sourceBounds.outWidth.toLong() * sourceBounds.outHeight <= MAX_SOURCE_PIXELS) {
            "Imagem grande demais para edição segura (${sourceBounds.outWidth}×${sourceBounds.outHeight})"
        }
        val sourceBitmap = BitmapFactory.decodeFile(
            selectedImage.absolutePath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        ) ?: error("Não foi possível decodificar a imagem selecionada")

        outputDirectory.mkdirs()
        try {
            return ZipFile(sourceApk).use { zip ->
                targetEntries.distinct().mapIndexed { index, entryName ->
                    val entry = zip.getEntry(entryName) ?: error("Variante ausente no APK: $entryName")
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                        "Não foi possível identificar as dimensões de $entryName"
                    }

                    val extension = entryName.substringAfterLast('.', "png").lowercase(Locale.ROOT)
                    val output = File(
                        outputDirectory,
                        "${index}_${entryName.hashCode().toUInt().toString(16)}.$extension"
                    )
                    val scaled = if (sourceBitmap.width == bounds.outWidth && sourceBitmap.height == bounds.outHeight) {
                        sourceBitmap
                    } else {
                        Bitmap.createScaledBitmap(sourceBitmap, bounds.outWidth, bounds.outHeight, true)
                    }
                    try {
                        output.outputStream().use { stream ->
                            require(scaled.compress(compressFormat(extension), compressionQuality(extension), stream)) {
                                "Falha ao gerar a variante $entryName"
                            }
                        }
                    } finally {
                        if (scaled !== sourceBitmap) scaled.recycle()
                    }
                    PreparedVariant(entryName, output, bounds.outWidth, bounds.outHeight)
                }
            }
        } finally {
            sourceBitmap.recycle()
        }
    }

    fun isNinePatch(entryName: String): Boolean = entryName.lowercase(Locale.ROOT).endsWith(".9.png")

    private fun compressFormat(extension: String): Bitmap.CompressFormat = when (extension) {
        "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
        "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            legacyWebpFormat()
        }
        else -> Bitmap.CompressFormat.PNG
    }

    @Suppress("DEPRECATION")
    private fun legacyWebpFormat(): Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP

    private fun compressionQuality(extension: String): Int = if (extension in setOf("jpg", "jpeg")) 95 else 100
}
