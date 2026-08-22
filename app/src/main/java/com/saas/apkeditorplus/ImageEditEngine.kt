package com.saas.apkeditorplus

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import java.io.File
import kotlin.math.abs

object ImageEditEngine {
    fun resize(source: Bitmap, width: Int, height: Int): Bitmap {
        require(width in 1..8192 && height in 1..8192) { "Use dimensões entre 1 e 8192 pixels" }
        require(width.toLong() * height <= 40_000_000L) { "Imagem grande demais" }
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    fun applyOverallAlpha(source: Bitmap, percent: Int): Bitmap {
        require(percent in 0..100)
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(output.width)
        for (y in 0 until output.height) {
            output.getPixels(pixels, 0, output.width, 0, y, output.width, 1)
            for (x in pixels.indices) {
                val alpha = Color.alpha(pixels[x]) * percent / 100
                pixels[x] = (pixels[x] and 0x00FFFFFF) or (alpha shl 24)
            }
            output.setPixels(pixels, 0, output.width, 0, y, output.width, 1)
        }
        return output
    }

    fun removeBackground(source: Bitmap, tolerance: Int): Bitmap {
        require(tolerance in 0..255)
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val background = output.getPixel(0, 0)
        val br = Color.red(background)
        val bg = Color.green(background)
        val bb = Color.blue(background)
        val pixels = IntArray(output.width)
        for (y in 0 until output.height) {
            output.getPixels(pixels, 0, output.width, 0, y, output.width, 1)
            for (x in pixels.indices) {
                val color = pixels[x]
                val distance = maxOf(
                    abs(Color.red(color) - br),
                    abs(Color.green(color) - bg),
                    abs(Color.blue(color) - bb)
                )
                if (distance <= tolerance) pixels[x] = color and 0x00FFFFFF
            }
            output.setPixels(pixels, 0, output.width, 0, y, output.width, 1)
        }
        return output
    }

    fun save(bitmap: Bitmap, file: File) {
        val extension = file.extension.lowercase()
        val format = when (extension) {
            "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
            "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                legacyWebpFormat()
            }
            else -> Bitmap.CompressFormat.PNG
        }
        file.outputStream().use { output ->
            require(bitmap.compress(format, if (extension in setOf("jpg", "jpeg")) 95 else 100, output)) {
                "Não foi possível salvar a imagem"
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyWebpFormat(): Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP
}
