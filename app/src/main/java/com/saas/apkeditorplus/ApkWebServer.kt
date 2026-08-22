package com.saas.apkeditorplus

import android.text.TextUtils
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.zip.ZipFile

class ApkWebServer(
    private val sourceApk: File,
    private val replacements: () -> Map<String, File>,
    private val deletions: () -> Set<String>
) : AutoCloseable {
    private var socket: ServerSocket? = null
    private val executor = Executors.newFixedThreadPool(2)

    fun start(): String {
        check(socket == null) { "Servidor já iniciado" }
        val server = ServerSocket(0)
        socket = server
        executor.execute {
            while (!server.isClosed) {
                runCatching { server.accept() }.getOrNull()?.let { client ->
                    executor.execute {
                        client.use { connection ->
                            runCatching {
                                val reader = BufferedReader(InputStreamReader(connection.getInputStream()))
                                val request = reader.readLine().orEmpty().split(' ')
                                val path = request.getOrNull(1).orEmpty().substringBefore('?')
                                serve(path, connection.getOutputStream())
                            }
                        }
                    }
                }
            }
        }
        return "http://${localAddress()}:${server.localPort}/"
    }

    private fun serve(rawPath: String, output: java.io.OutputStream) {
        val requested = URLDecoder.decode(rawPath.removePrefix("/"), "UTF-8")
        if (requested.isBlank()) {
            val entries = linkedSetOf<String>()
            ZipFile(sourceApk).use { zip ->
                zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entries += it.name }
            }
            entries += replacements().keys
            val deleted = deletions()
            val body = buildString {
                append("<!doctype html><meta charset=utf-8><title>APK Editor Plus</title>")
                append("<h1>${escape(sourceApk.name)}</h1><ul>")
                entries.filterNot { entry -> deleted.any { entry == it || it.endsWith('/') && entry.startsWith(it) } }
                    .sorted().forEach { entry ->
                        append("<li><a href=\"")
                        append(entry.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") })
                        append("\">${escape(entry)}</a></li>")
                    }
                append("</ul>")
            }.toByteArray()
            writeResponse(output, "200 OK", "text/html; charset=utf-8", body)
            return
        }
        if (requested.contains("..") || requested.startsWith('/')) {
            writeResponse(output, "400 Bad Request", "text/plain", "Caminho inválido".toByteArray())
            return
        }
        if (deletions().any { requested == it || it.endsWith('/') && requested.startsWith(it) }) {
            writeResponse(output, "404 Not Found", "text/plain", "Excluído".toByteArray())
            return
        }
        val replacement = replacements()[requested]
        val bytes = when {
            replacement?.isFile == true -> replacement.readBytes()
            else -> ZipFile(sourceApk).use { zip ->
                val entry = zip.getEntry(requested) ?: return@use null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        }
        if (bytes == null) {
            writeResponse(output, "404 Not Found", "text/plain", "Não encontrado".toByteArray())
        } else {
            writeResponse(output, "200 OK", contentType(requested), bytes)
        }
    }

    private fun writeResponse(output: java.io.OutputStream, status: String, type: String, body: ByteArray) {
        output.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(body)
        output.flush()
    }

    private fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "xml", "txt", "smali", "json", "js", "css" -> "text/plain; charset=utf-8"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        else -> "application/octet-stream"
    }

    private fun localAddress(): String = NetworkInterface.getNetworkInterfaces().asSequence()
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress }?.hostAddress ?: "127.0.0.1"

    private fun escape(value: String): String = TextUtils.htmlEncode(value)

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        executor.shutdownNow()
    }
}
