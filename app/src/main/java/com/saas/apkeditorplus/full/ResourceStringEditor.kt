package com.saas.apkeditorplus.full

import android.content.Context
import com.saas.apkeditorplus.AppSettings
import android.util.Xml
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.max
import org.xmlpull.v1.XmlPullParser

internal object ResourceStringEditor {
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_TABLE_PACKAGE_TYPE = 0x0200
    private const val RES_TABLE_TYPE_TYPE = 0x0201
    private const val RES_TABLE_TYPE_SPEC_TYPE = 0x0202
    private const val UTF8_FLAG = 1 shl 8
    private val hiddenStringPrefixes = listOf(
        "abc_",
        "androidx_",
        "bottomsheet_",
        "character_counter_",
        "clear_text_end_icon_",
        "common_google_play_services_",
        "error_icon_content_description",
        "exposed_dropdown_menu_",
        "fallback_menu_item_",
        "m3_",
        "material_",
        "material_clock_",
        "material_timepicker_",
        "mtrl_",
        "nav_app_bar_",
        "password_toggle_",
        "path_password_",
        "search_menu_",
        "searchbar_",
        "searchview_",
        "side_sheet_",
        "status_bar_",
        "v7_preference_"
    )
    private val hiddenStringPatterns = listOf(
        Regex("^APKTOOL_.*$"),
        Regex("^default_res_0x[0-9a-fA-F]+$"),
        Regex("^string_[0-9a-fA-F]+$"),
        Regex("^mr_.*$")
    )

    private data class Snapshot(
        val bytes: ByteArray,
        val globalPool: StringPool,
        val items: List<FullEditRepository.StringResourceItem>
    )

    private data class StringPool(
        val start: Int,
        val headerSize: Int,
        val chunkSize: Int,
        val styleCount: Int,
        val flags: Int,
        val styleOffsets: IntArray,
        val strings: List<String>,
        val stylesData: ByteArray,
        val utf8: Boolean
    )

    private data class ChunkHeader(
        val type: Int,
        val headerSize: Int,
        val size: Int,
        val start: Int
    ) {
        companion object {
            fun read(reader: Reader): ChunkHeader {
                val start = reader.pos
                val type = reader.u16()
                val headerSize = reader.u16()
                val size = reader.u32()
                if (size <= 0 || headerSize < 8) {
                    throw IllegalStateException("Bad chunk header")
                }
                reader.requireAbsolute(start, size)
                return ChunkHeader(type, headerSize, size, start)
            }

            fun peek(reader: Reader): ChunkHeader {
                val saved = reader.pos
                val header = read(reader)
                reader.seek(saved)
                return header
            }

            fun skip(reader: Reader) {
                val header = read(reader)
                reader.seek(header.start + header.size)
            }
        }
    }

    private class Reader(val data: ByteArray) {
        var pos: Int = 0

        fun require(count: Int) {
            if (count < 0 || pos + count > data.size) {
                throw IllegalStateException("Require failed at $pos")
            }
        }

        fun requireAbsolute(start: Int, count: Int) {
            if (start < 0 || count < 0 || start + count > data.size) {
                throw IllegalStateException("Require absolute failed at $start")
            }
        }

        fun hasRemaining(count: Int): Boolean {
            return count >= 0 && pos + count <= data.size
        }

        fun seek(newPos: Int) {
            requireAbsolute(newPos, 0)
            pos = newPos
        }

        fun skip(count: Int) {
            require(count)
            pos += count
        }

        fun u8(): Int {
            require(1)
            return data[pos++].toInt() and 0xFF
        }

        fun u16(): Int {
            require(2)
            val value = (data[pos].toInt() and 0xFF) or
                ((data[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            return value
        }

        fun s32(): Int = u32()

        fun u32(): Int {
            require(4)
            val value = (data[pos].toInt() and 0xFF) or
                ((data[pos + 1].toInt() and 0xFF) shl 8) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or
                ((data[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return value
        }

        fun bytes(length: Int): ByteArray {
            require(length)
            return data.copyOfRange(pos, pos + length).also {
                pos += length
            }
        }

        fun utf16FixedLengthString(byteLength: Int): String {
            var safeByteLength = byteLength
            if (safeByteLength < 0) safeByteLength = 0
            if (!hasRemaining(safeByteLength)) {
                safeByteLength = max(0, data.size - pos)
            }
            val end = pos + safeByteLength
            val builder = StringBuilder()
            while (pos + 1 < end) {
                val charCode = u16()
                if (charCode == 0) {
                    break
                }
                builder.append(charCode.toChar())
            }
            pos = end
            return builder.toString()
        }
    }

    fun parseFromApk(
        apkPath: String,
        localeQualifier: String = ""
    ): List<FullEditRepository.StringResourceItem> {
        return filterDisplayableItems(
            filterItems(parse(loadArscFromApk(apkPath)).items, localeQualifier)
        )
    }

    fun parseFromArscFile(
        arscFile: File,
        localeQualifier: String = ""
    ): List<FullEditRepository.StringResourceItem> {
        return filterDisplayableItems(
            filterItems(parse(arscFile.readBytes()).items, localeQualifier)
        )
    }

    fun listLocales(
        apkPath: String,
        arscSourceFile: File? = null
    ): List<String> {
        val snapshot = if (arscSourceFile != null && arscSourceFile.exists()) {
            parse(arscSourceFile.readBytes())
        } else {
            parse(loadArscFromApk(apkPath))
        }
        return filterDisplayableItems(snapshot.items)
            .map { it.localeQualifier }
            .distinct()
            .sortedWith(
                compareBy<String>({ if (it.isEmpty()) 0 else 1 }, { it.lowercase(Locale.ROOT) })
            )
    }

    fun exportToXml(
        context: Context,
        apkPath: String,
        arscSourceFile: File? = null,
        localeQualifier: String = ""
    ): File {
        val snapshot = if (arscSourceFile != null && arscSourceFile.exists()) {
            parse(arscSourceFile.readBytes())
        } else {
            parse(loadArscFromApk(apkPath))
        }
        val selectedItems = filterDisplayableItems(
            filterItems(snapshot.items, localeQualifier)
        )
        val targetDir = AppSettings.workspaceRoot(context, "full_edit_strings")
        val outputFile = File(
            targetDir,
            "${apkPath.hashCode().toUInt().toString(16)}_strings.xml"
        )
        outputFile.outputStream().use { outputStream ->
            val serializer = Xml.newSerializer()
            serializer.setOutput(outputStream, Charsets.UTF_8.name())
            serializer.startDocument(Charsets.UTF_8.name(), true)
            serializer.text("\n")
            serializer.startTag(null, "resources")
            selectedItems.forEach { item ->
                serializer.text("\n    ")
                serializer.startTag(null, "string")
                serializer.attribute(null, "name", item.name)
                serializer.text(item.value.orEmpty())
                serializer.endTag(null, "string")
            }
            serializer.text("\n")
            serializer.endTag(null, "resources")
            serializer.text("\n")
            serializer.endDocument()
        }
        return outputFile
    }

    fun buildArscFromEditedXml(
        context: Context,
        apkPath: String,
        editedStringsXml: File,
        arscSourceFile: File? = null,
        localeQualifier: String = ""
    ): File {
        require(editedStringsXml.exists()) { "Edited strings file not found" }
        val editedValues = parseEditedStringsXml(editedStringsXml)
        return buildArscWithOverrides(
            context = context,
            apkPath = apkPath,
            overrides = editedValues,
            arscSourceFile = arscSourceFile,
            localeQualifier = localeQualifier
        )
    }

    fun buildArscWithOverrides(
        context: Context,
        apkPath: String,
        overrides: Map<String, String>,
        arscSourceFile: File? = null,
        localeQualifier: String = ""
    ): File {
        require(overrides.isNotEmpty()) { "No string changes to apply" }
        val snapshot = loadSnapshot(apkPath, arscSourceFile)
        val updatedStrings = snapshot.globalPool.strings.toMutableList()
        snapshot.items
            .asSequence()
            .filter { it.localeQualifier == localeQualifier }
            .forEach { item ->
                val valueIndex = item.valueIndex ?: return@forEach
                val editedValue = overrides[item.name] ?: return@forEach
                if (valueIndex in updatedStrings.indices) {
                    updatedStrings[valueIndex] = editedValue
                }
            }
        return writePatchedResources(context, apkPath, snapshot, updatedStrings)
    }

    private fun loadSnapshot(apkPath: String, arscSourceFile: File?): Snapshot {
        val sourceBytes = if (arscSourceFile != null && arscSourceFile.exists()) {
            arscSourceFile.readBytes()
        } else {
            loadArscFromApk(apkPath)
        }
        return parse(sourceBytes)
    }

    private fun writePatchedResources(
        context: Context,
        apkPath: String,
        snapshot: Snapshot,
        updatedStrings: List<String>
    ): File {
        val newStringPoolChunk = buildStringPoolChunk(snapshot.globalPool, updatedStrings)
        val oldChunkEnd = snapshot.globalPool.start + snapshot.globalPool.chunkSize
        val merged = ByteArrayOutputStream(
            snapshot.bytes.size - snapshot.globalPool.chunkSize + newStringPoolChunk.size
        ).apply {
            write(snapshot.bytes, 0, snapshot.globalPool.start)
            write(newStringPoolChunk)
            write(snapshot.bytes, oldChunkEnd, snapshot.bytes.size - oldChunkEnd)
        }.toByteArray()
        writeLeInt(merged, 4, merged.size)

        val outputDir = AppSettings.workspaceRoot(context, "full_edit_resources")
        val outputFile = File(
            outputDir,
            "${apkPath.hashCode().toUInt().toString(16)}_resources.arsc"
        )
        outputFile.writeBytes(merged)
        return outputFile
    }

    private fun loadArscFromApk(apkPath: String): ByteArray {
        ZipFile(apkPath).use { zipFile ->
            val entry = zipFile.getEntry(FullEditRepository.RESOURCES_ENTRY) ?: return ByteArray(0)
            zipFile.getInputStream(entry).use { inputStream ->
                return inputStream.readBytes()
            }
        }
    }

    private fun parseEditedStringsXml(file: File): Map<String, String> {
        val parser = Xml.newPullParser()
        file.inputStream().use { input ->
            parser.setInput(input, Charsets.UTF_8.name())
            val values = linkedMapOf<String, String>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "string") {
                    val name = parser.getAttributeValue(null, "name")
                    val value = parser.nextText()
                    if (!name.isNullOrBlank()) {
                        values[name] = value
                    }
                }
                event = parser.next()
            }
            return values
        }
    }

    private fun parse(bytes: ByteArray): Snapshot {
        if (bytes.isEmpty()) {
            return Snapshot(
                bytes = bytes,
                globalPool = StringPool(
                    start = 0,
                    headerSize = 28,
                    chunkSize = 0,
                    styleCount = 0,
                    flags = 0,
                    styleOffsets = IntArray(0),
                    strings = emptyList(),
                    stylesData = ByteArray(0),
                    utf8 = false
                ),
                items = emptyList()
            )
        }

        val reader = Reader(bytes)
        val tableHeader = ChunkHeader.read(reader)
        if (tableHeader.type != RES_TABLE_TYPE) {
            error("Not a RES_TABLE file")
        }
        reader.u32()
        val tableEnd = tableHeader.start + tableHeader.size
        var globalPool: StringPool? = null
        val items = mutableListOf<FullEditRepository.StringResourceItem>()

        while (reader.pos < tableEnd) {
            val header = ChunkHeader.peek(reader)
            try {
                when (header.type) {
                    RES_STRING_POOL_TYPE -> globalPool = readStringPool(reader)
                    RES_TABLE_PACKAGE_TYPE -> parsePackage(reader, items, globalPool)
                    else -> ChunkHeader.skip(reader)
                }
            } catch (_: IllegalStateException) {
                val skipTo = header.start + header.size
                if (skipTo > reader.data.size) break
                reader.seek(skipTo)
            }
        }

        return Snapshot(
            bytes = bytes,
            globalPool = globalPool ?: error("Global string pool not found"),
            items = items
                .associateBy { "${it.localeQualifier}\u0000${it.name}" }
                .values
                .sortedWith(
                    compareBy<FullEditRepository.StringResourceItem>(
                        { if (it.localeQualifier.isEmpty()) 0 else 1 },
                        { it.localeQualifier.lowercase(Locale.ROOT) },
                        { it.name.lowercase(Locale.ROOT) }
                    )
                )
        )
    }

    private fun parsePackage(
        reader: Reader,
        items: MutableList<FullEditRepository.StringResourceItem>,
        globalPool: StringPool?
    ) {
        val packageHeader = ChunkHeader.read(reader)
        val packageStart = packageHeader.start
        val packageEnd = packageHeader.start + packageHeader.size

        reader.u32()
        reader.utf16FixedLengthString(256)
        val typeStringsOffset = reader.u32()
        reader.u32()
        val keyStringsOffset = reader.u32()
        reader.u32()
        if (reader.pos + 4 <= packageEnd && reader.hasRemaining(4)) {
            reader.u32()
        }

        val savedPos = reader.pos
        var typePool: StringPool? = null
        var keyPool: StringPool? = null

        if (typeStringsOffset != 0) {
            val position = packageStart + typeStringsOffset
            if (position in packageStart until packageEnd) {
                reader.seek(position)
                typePool = readStringPool(reader)
            }
        }
        if (keyStringsOffset != 0) {
            val position = packageStart + keyStringsOffset
            if (position in packageStart until packageEnd) {
                reader.seek(position)
                keyPool = readStringPool(reader)
            }
        }

        reader.seek(savedPos)
        while (reader.pos < packageEnd) {
            if (reader.pos + 8 > packageEnd) break
            val header = ChunkHeader.peek(reader)
            if (header.start < packageStart || header.start + header.size > packageEnd) break
            when (header.type) {
                RES_TABLE_TYPE_SPEC_TYPE -> ChunkHeader.skip(reader)
                RES_TABLE_TYPE_TYPE -> parseTypeChunk(reader, typePool, keyPool, globalPool, items)
                else -> ChunkHeader.skip(reader)
            }
        }
        reader.seek(packageEnd)
    }

    private fun parseTypeChunk(
        reader: Reader,
        typePool: StringPool?,
        keyPool: StringPool?,
        globalPool: StringPool?,
        items: MutableList<FullEditRepository.StringResourceItem>
    ) {
        val typeHeader = ChunkHeader.read(reader)
        if (typeHeader.type != RES_TABLE_TYPE_TYPE) {
            reader.seek(typeHeader.start + typeHeader.size)
            return
        }

        val typeChunkStart = typeHeader.start
        val typeChunkEnd = typeHeader.start + typeHeader.size

        val typeId = reader.u8()
        reader.skip(3)
        var entryCount = reader.u32()
        val entriesStart = reader.u32()
        if (entriesStart < typeHeader.headerSize || typeChunkStart + entriesStart > typeChunkEnd) {
            reader.seek(typeHeader.start + typeHeader.size)
            return
        }

        val configSize = reader.u32()
        val configBytes = reader.bytes(configSize - 4)
        val localeQualifier = parseLocaleQualifier(configBytes)

        val offsetsStart = reader.pos
        val maxOffsetBytes = typeChunkEnd - offsetsStart
        val requiredOffsetBytes = entryCount * 4L
        if (requiredOffsetBytes > maxOffsetBytes) {
            entryCount = max(0, (maxOffsetBytes / 4L).toInt())
        }

        val entryOffsets = IntArray(entryCount)
        repeat(entryCount) { index ->
            entryOffsets[index] = reader.s32()
        }

        val typeName = typePool?.strings?.getOrNull(typeId - 1)
            ?: "type$typeId"

        entryOffsets.forEachIndexed { entryIndex, relativeOffset ->
            if (relativeOffset == -1) return@forEachIndexed
            val entryPosition = typeChunkStart + entriesStart + relativeOffset
            if (entryPosition < typeChunkStart || entryPosition + 8 > typeChunkEnd) return@forEachIndexed

            val savedPos = reader.pos
            reader.seek(entryPosition)
            if (!reader.hasRemaining(8)) {
                reader.seek(savedPos)
                return@forEachIndexed
            }

            val entrySize = reader.u16()
            val flags = reader.u16()
            val keyIndex = reader.u32()
            if (entrySize < 8 || entryPosition + entrySize > typeChunkEnd) {
                reader.seek(savedPos)
                return@forEachIndexed
            }

            val name = keyPool?.strings?.getOrNull(keyIndex) ?: "entry$entryIndex"
            var value: String? = null
            var valueIndex: Int? = null

            if ((flags and 0x0001) == 0 && reader.hasRemaining(8)) {
                reader.skip(2)
                reader.skip(1)
                val valueType = reader.u8()
                val valueData = reader.u32()
                if (valueType == 3) {
                    valueIndex = valueData
                    value = globalPool?.strings?.getOrNull(valueData)
                }
            }

            if (typeName == "string" && valueIndex != null) {
                items.add(
                    FullEditRepository.StringResourceItem(
                        name = name,
                        value = value,
                        valueIndex = valueIndex,
                        localeQualifier = localeQualifier
                    )
                )
            }
            reader.seek(savedPos)
        }

        reader.seek(typeHeader.start + typeHeader.size)
    }

    private fun readStringPool(reader: Reader): StringPool {
        val header = ChunkHeader.read(reader)
        if (header.type != RES_STRING_POOL_TYPE) {
            throw IllegalStateException("Expected string pool")
        }

        val stringCount = reader.u32()
        val styleCount = reader.u32()
        val flags = reader.u32()
        val stringsStart = reader.u32()
        val stylesStart = reader.u32()
        val utf8 = (flags and UTF8_FLAG) != 0

        val stringOffsets = IntArray(stringCount)
        repeat(stringCount) { index ->
            stringOffsets[index] = reader.u32()
        }
        val styleOffsets = IntArray(styleCount)
        repeat(styleCount) { index ->
            styleOffsets[index] = reader.u32()
        }

        val stringsBase = header.start + stringsStart
        val strings = MutableList(stringCount) { "" }
        repeat(stringCount) { index ->
            val offset = stringsBase + stringOffsets[index]
            if (offset in header.start until (header.start + header.size)) {
                val saved = reader.pos
                reader.seek(offset)
                strings[index] = if (utf8) readUtf8String(reader) else readUtf16String(reader)
                reader.seek(saved)
            }
        }

        val stylesData = if (styleCount > 0 && stylesStart > 0) {
            val stylesAbsoluteStart = header.start + stylesStart
            reader.data.copyOfRange(stylesAbsoluteStart, header.start + header.size)
        } else {
            ByteArray(0)
        }

        reader.seek(header.start + header.size)
        return StringPool(
            start = header.start,
            headerSize = header.headerSize,
            chunkSize = header.size,
            styleCount = styleCount,
            flags = flags,
            styleOffsets = styleOffsets,
            strings = strings,
            stylesData = stylesData,
            utf8 = utf8
        )
    }

    private fun buildStringPoolChunk(originalPool: StringPool, updatedStrings: List<String>): ByteArray {
        val stringData = ByteArrayOutputStream()
        val offsets = IntArray(updatedStrings.size)
        updatedStrings.forEachIndexed { index, value ->
            offsets[index] = stringData.size()
            stringData.write(encodeStringPoolValue(value, originalPool.utf8))
        }

        val headerSize = max(28, originalPool.headerSize)
        val stringsStart = headerSize + (updatedStrings.size * 4) + (originalPool.styleCount * 4)
        val stylesStart = if (originalPool.styleCount > 0) {
            stringsStart + stringData.size()
        } else {
            0
        }
        val chunkSize = stringsStart + stringData.size() + originalPool.stylesData.size

        return ByteArrayOutputStream(chunkSize).apply {
            writeLeShort(RES_STRING_POOL_TYPE)
            writeLeShort(headerSize)
            writeLeInt(chunkSize)
            writeLeInt(updatedStrings.size)
            writeLeInt(originalPool.styleCount)
            writeLeInt(originalPool.flags)
            writeLeInt(stringsStart)
            writeLeInt(stylesStart)
            offsets.forEach { writeLeInt(it) }
            originalPool.styleOffsets.forEach { writeLeInt(it) }
            write(stringData.toByteArray())
            write(originalPool.stylesData)
        }.toByteArray()
    }

    private fun encodeStringPoolValue(value: String, utf8: Boolean): ByteArray {
        return if (utf8) {
            val utf8Bytes = value.toByteArray(Charsets.UTF_8)
            ByteArrayOutputStream(utf8Bytes.size + 8).apply {
                write(encodeLength8(value.length))
                write(encodeLength8(utf8Bytes.size))
                write(utf8Bytes)
                write(0)
            }.toByteArray()
        } else {
            val utf16Bytes = value.toByteArray(Charsets.UTF_16LE)
            ByteArrayOutputStream(utf16Bytes.size + 8).apply {
                write(encodeLength16(value.length))
                write(utf16Bytes)
                write(0)
                write(0)
            }.toByteArray()
        }
    }

    private fun encodeLength8(length: Int): ByteArray {
        return if (length < 0x80) {
            byteArrayOf(length.toByte())
        } else {
            byteArrayOf(
                (((length shr 7) and 0x7F) or 0x80).toByte(),
                (length and 0x7F).toByte()
            )
        }
    }

    private fun encodeLength16(length: Int): ByteArray {
        val output = ByteArrayOutputStream(4)
        if (length < 0x8000) {
            output.writeLeShort(length)
        } else {
            output.writeLeShort(((length shr 16) and 0x7FFF) or 0x8000)
            output.writeLeShort(length and 0xFFFF)
        }
        return output.toByteArray()
    }

    private fun readUtf8String(reader: Reader): String {
        readLength8(reader)
        val utf8Length = readLength8(reader)
        if (!reader.hasRemaining(utf8Length + 1)) {
            return ""
        }
        val bytes = reader.bytes(utf8Length)
        reader.u8()
        return String(bytes, Charsets.UTF_8)
    }

    private fun readUtf16String(reader: Reader): String {
        val utf16Length = readLength16(reader)
        if (!reader.hasRemaining((utf16Length * 2) + 2)) {
            return ""
        }
        val bytes = reader.bytes(utf16Length * 2)
        reader.u16()
        return String(bytes, Charsets.UTF_16LE)
    }

    private fun readLength8(reader: Reader): Int {
        val first = reader.u8()
        return if ((first and 0x80) == 0) {
            first
        } else {
            ((first and 0x7F) shl 7) or (reader.u8() and 0x7F)
        }
    }

    private fun readLength16(reader: Reader): Int {
        val first = reader.u16()
        return if ((first and 0x8000) == 0) {
            first
        } else {
            ((first and 0x7FFF) shl 16) or reader.u16()
        }
    }

    private fun filterItems(
        items: List<FullEditRepository.StringResourceItem>,
        localeQualifier: String
    ): List<FullEditRepository.StringResourceItem> {
        return items.filter { it.localeQualifier == localeQualifier }
    }

    private fun filterDisplayableItems(
        items: List<FullEditRepository.StringResourceItem>
    ): List<FullEditRepository.StringResourceItem> {
        return items.filter { item ->
            val name = item.name
            hiddenStringPrefixes.none { prefix -> name.startsWith(prefix) } &&
                hiddenStringPatterns.none { pattern -> pattern.matches(name) }
        }
    }

    private fun parseLocaleQualifier(configBytes: ByteArray): String {
        if (configBytes.size < 8) {
            return ""
        }
        // ResTable_config stores language/country after mcc and mnc.
        val language = decodeLanguage(configBytes[4], configBytes[5]) ?: return ""
        val region = decodeRegion(configBytes[6], configBytes[7])
        return buildString {
            append('-')
            append(language)
            if (!region.isNullOrBlank()) {
                append("-r")
                append(region.uppercase(Locale.ROOT))
            }
        }
    }

    private fun decodeLanguage(firstByte: Byte, secondByte: Byte): String? {
        val first = firstByte.toInt() and 0xFF
        val second = secondByte.toInt() and 0xFF
        if (first == 0 && second == 0) {
            return null
        }
        return if ((first and 0x80) != 0) {
            buildString {
                append(((second and 0x1F) + 'a'.code).toChar())
                append((((second and 0xE0) shr 5) + ((first and 0x03) shl 3) + 'a'.code).toChar())
                append((((first and 0x7C) shr 2) + 'a'.code).toChar())
            }
        } else {
            buildString {
                append(first.toChar())
                append(second.toChar())
            }
        }
    }

    private fun decodeRegion(firstByte: Byte, secondByte: Byte): String? {
        val first = firstByte.toInt() and 0xFF
        val second = secondByte.toInt() and 0xFF
        if (first == 0 && second == 0) {
            return null
        }
        return if ((first and 0x80) != 0) {
            buildString {
                append(((second and 0x1F) + '0'.code).toChar())
                append((((second and 0xE0) shr 5) + ((first and 0x03) shl 3) + '0'.code).toChar())
                append((((first and 0x7C) shr 2) + '0'.code).toChar())
            }
        } else {
            buildString {
                append(first.toChar())
                append(second.toChar())
            }
        }
    }

    private fun writeLeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun ByteArrayOutputStream.writeLeShort(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLeInt(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }
}
