package za.kilowatch.ultimatefilemanager.util

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure Kotlin decoder for Android Binary XML (AXML) format.
 * Translates compiled AndroidManifest.xml byte arrays into standard, formatted XML text
 * without requiring Android framework reflection or external dependencies.
 */
object AxmlDecoder {

    private const val CHUNK_AXML_FILE = 0x00080003
    private const val CHUNK_STRING_POOL = 0x001C0001
    private const val CHUNK_RESOURCE_MAP = 0x00080180
    private const val CHUNK_START_NAMESPACE = 0x00100100
    private const val CHUNK_END_NAMESPACE = 0x00100101
    private const val CHUNK_START_TAG = 0x00100102
    private const val CHUNK_END_TAG = 0x00100103
    private const val CHUNK_TEXT = 0x00100104

    // TypedValue data types
    private const val TYPE_NULL = 0x00
    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_ATTRIBUTE = 0x02
    private const val TYPE_STRING = 0x03
    private const val TYPE_FLOAT = 0x04
    private const val TYPE_DIMENSION = 0x05
    private const val TYPE_FRACTION = 0x06
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12
    private const val TYPE_INT_COLOR_ARGB8 = 0x1c
    private const val TYPE_INT_COLOR_RGB8 = 0x1d
    private const val TYPE_INT_COLOR_ARGB4 = 0x1e
    private const val TYPE_INT_COLOR_RGB4 = 0x1f

    private val DIMENSION_UNITS = arrayOf("px", "dp", "sp", "pt", "in", "mm")
    private val FRACTION_UNITS = arrayOf("%", "%p")

    /**
     * Decodes an Android binary XML byte array into an XML string.
     */
    fun decode(bytes: ByteArray): String {
        return try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val fileType = buffer.int
            if (fileType != CHUNK_AXML_FILE && (fileType and 0xFFFF) != 0x0003) {
                // Not standard AXML, check if it's already plain text XML
                val asString = String(bytes, Charsets.UTF_8).trim()
                if (asString.startsWith("<?xml") || asString.startsWith("<manifest")) {
                    return asString
                }
                return "<!-- Error: Not a valid Android binary XML file -->"
            }

            buffer.position(0)
            parseAxml(buffer)
        } catch (e: Exception) {
            "<!-- Error decoding AndroidManifest.xml: ${e.message} -->"
        }
    }

    fun decode(inputStream: InputStream): String {
        val bytes = inputStream.use { it.readBytes() }
        return decode(bytes)
    }

    private fun parseAxml(buffer: ByteBuffer): String {
        val fileSize = buffer.getInt(4)
        var stringTable: List<String> = emptyList()
        val namespaces = mutableMapOf<String, String>() // uri -> prefix
        val declaredNamespaces = mutableSetOf<String>()
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")

        var indent = 0
        var isFirstElement = true

        buffer.position(8) // Skip file header (type: 4 bytes, size: 4 bytes)

        while (buffer.hasRemaining() && buffer.position() < buffer.capacity() - 4) {
            val chunkPos = buffer.position()
            val chunkType = buffer.int
            val chunkSize = buffer.int

            if (chunkSize <= 0 || chunkPos + chunkSize > buffer.capacity()) {
                break
            }

            when (chunkType) {
                CHUNK_STRING_POOL -> {
                    stringTable = parseStringPool(buffer, chunkPos, chunkSize)
                    buffer.position(chunkPos + chunkSize)
                }

                CHUNK_RESOURCE_MAP -> {
                    // Resource IDs mapping chunk, skip to next chunk
                    buffer.position(chunkPos + chunkSize)
                }

                CHUNK_START_NAMESPACE -> {
                    buffer.getInt() // lineNumber
                    buffer.getInt() // comment
                    val prefixIdx = buffer.getInt()
                    val uriIdx = buffer.getInt()
                    val prefix = stringTable.getOrNull(prefixIdx) ?: ""
                    val uri = stringTable.getOrNull(uriIdx) ?: ""
                    if (uri.isNotEmpty()) {
                        namespaces[uri] = prefix
                    }
                    buffer.position(chunkPos + chunkSize)
                }

                CHUNK_END_NAMESPACE -> {
                    buffer.getInt() // lineNumber
                    buffer.getInt() // comment
                    val prefixIdx = buffer.getInt()
                    val uriIdx = buffer.getInt()
                    val uri = stringTable.getOrNull(uriIdx) ?: ""
                    namespaces.remove(uri)
                    buffer.position(chunkPos + chunkSize)
                }

                CHUNK_START_TAG -> {
                    buffer.getInt() // lineNumber
                    buffer.getInt() // comment
                    val nsIdx = buffer.getInt()
                    val nameIdx = buffer.getInt()
                    buffer.getShort() // attributeStart
                    val attrSize = buffer.getShort().toInt() and 0xFFFF
                    val attrCount = buffer.getShort().toInt() and 0xFFFF
                    buffer.getShort() // idIndex
                    buffer.getShort() // classIndex
                    buffer.getShort() // styleIndex

                    val tagName = stringTable.getOrNull(nameIdx) ?: "unknown"
                    val tagNs = stringTable.getOrNull(nsIdx) ?: ""
                    val prefix = if (tagNs.isNotEmpty()) namespaces[tagNs]?.let { "$it:" } ?: "" else ""

                    appendIndent(sb, indent)
                    sb.append("<").append(prefix).append(tagName)

                    // If this is the root element, output declared namespaces
                    if (isFirstElement) {
                        isFirstElement = false
                        for ((uri, pfx) in namespaces) {
                            if (!declaredNamespaces.contains(uri)) {
                                declaredNamespaces.add(uri)
                                val pfxStr = if (pfx.isNotEmpty()) ":$pfx" else ""
                                sb.append("\n")
                                appendIndent(sb, indent + 1)
                                sb.append("xmlns").append(pfxStr).append("=\"").append(escapeXml(uri)).append("\"")
                            }
                        }
                    }

                    // Parse attributes
                    for (i in 0 until attrCount) {
                        val attrOffset = chunkPos + 36 + (i * attrSize)
                        if (attrOffset + 20 > buffer.capacity()) break
                        buffer.position(attrOffset)

                        val aNsIdx = buffer.getInt()
                        val aNameIdx = buffer.getInt()
                        val aRawValIdx = buffer.getInt()
                        buffer.getShort() // size
                        buffer.get() // res0
                        val dataType = buffer.get().toInt() and 0xFF
                        val data = buffer.getInt()

                        val attrName = stringTable.getOrNull(aNameIdx) ?: "attr$i"
                        val attrNs = stringTable.getOrNull(aNsIdx) ?: ""
                        val attrPrefix = if (attrNs.isNotEmpty()) {
                            namespaces[attrNs]?.let { "$it:" } ?: if (attrNs.contains("android")) "android:" else ""
                        } else ""

                        val attrVal = formatAttributeValue(dataType, data, aRawValIdx, stringTable)

                        sb.append("\n")
                        appendIndent(sb, indent + 1)
                        sb.append(attrPrefix).append(attrName).append("=\"").append(escapeXml(attrVal)).append("\"")
                    }

                    sb.append(">")
                    sb.append("\n")
                    indent++
                    buffer.position(chunkPos + chunkSize)
                }

                CHUNK_END_TAG -> {
                    buffer.getInt() // lineNumber
                    buffer.getInt() // comment
                    val nsIdx = buffer.getInt()
                    val nameIdx = buffer.getInt()

                    val tagName = stringTable.getOrNull(nameIdx) ?: "unknown"
                    val tagNs = stringTable.getOrNull(nsIdx) ?: ""
                    val prefix = if (tagNs.isNotEmpty()) namespaces[tagNs]?.let { "$it:" } ?: "" else ""

                    indent = (indent - 1).coerceAtLeast(0)
                    appendIndent(sb, indent)
                    sb.append("</").append(prefix).append(tagName).append(">\n")
                    buffer.position(chunkPos + chunkSize)
                }

                CHUNK_TEXT -> {
                    buffer.getInt() // lineNumber
                    buffer.getInt() // comment
                    val nameIdx = buffer.getInt()
                    buffer.getInt() // res0
                    buffer.getInt() // res1
                    val text = stringTable.getOrNull(nameIdx) ?: ""
                    if (text.isNotBlank()) {
                        appendIndent(sb, indent)
                        sb.append(escapeXml(text)).append("\n")
                    }
                    buffer.position(chunkPos + chunkSize)
                }

                else -> {
                    // Unknown or skipped chunk
                    buffer.position(chunkPos + chunkSize)
                }
            }
        }

        return sb.toString()
    }

    private fun parseStringPool(buffer: ByteBuffer, chunkPos: Int, chunkSize: Int): List<String> {
        val stringCount = buffer.getInt(chunkPos + 8)
        val flags = buffer.getInt(chunkPos + 16)
        val stringsStart = buffer.getInt(chunkPos + 20)
        val isUtf8 = (flags and (1 shl 8)) != 0

        val offsets = IntArray(stringCount)
        for (i in 0 until stringCount) {
            offsets[i] = buffer.getInt(chunkPos + 28 + (i * 4))
        }

        val baseDataPos = chunkPos + stringsStart
        val strings = ArrayList<String>(stringCount)

        for (i in 0 until stringCount) {
            val strPos = baseDataPos + offsets[i]
            if (strPos >= buffer.capacity()) {
                strings.add("")
                continue
            }

            try {
                if (isUtf8) {
                    // UTF-8: length is encoded as 1 or 2 bytes for char count, then 1 or 2 bytes for byte length
                    var p = strPos
                    var len = buffer.get(p++).toInt() and 0xFF
                    if ((len and 0x80) != 0) {
                        len = ((len and 0x7F) shl 8) or (buffer.get(p++).toInt() and 0xFF)
                    }
                    var byteLen = buffer.get(p++).toInt() and 0xFF
                    if ((byteLen and 0x80) != 0) {
                        byteLen = ((byteLen and 0x7F) shl 8) or (buffer.get(p++).toInt() and 0xFF)
                    }
                    val bytes = ByteArray(byteLen)
                    buffer.position(p)
                    buffer.get(bytes)
                    strings.add(String(bytes, Charsets.UTF_8))
                } else {
                    // UTF-16
                    var p = strPos
                    var charLen = buffer.getShort(p).toInt() and 0xFFFF
                    p += 2
                    if ((charLen and 0x8000) != 0) {
                        charLen = ((charLen and 0x7FFF) shl 16) or (buffer.getShort(p).toInt() and 0xFFFF)
                        p += 2
                    }
                    val chars = CharArray(charLen)
                    for (c in 0 until charLen) {
                        chars[c] = buffer.getChar(p + (c * 2))
                    }
                    strings.add(String(chars))
                }
            } catch (_: Exception) {
                strings.add("")
            }
        }

        return strings
    }

    private fun formatAttributeValue(
        dataType: Int,
        data: Int,
        rawValIdx: Int,
        stringTable: List<String>
    ): String {
        if (rawValIdx >= 0 && rawValIdx < stringTable.size) {
            val raw = stringTable[rawValIdx]
            if (raw.isNotEmpty()) return raw
        }

        return when (dataType) {
            TYPE_STRING -> stringTable.getOrNull(data) ?: ""
            TYPE_INT_BOOLEAN -> if (data != 0) "true" else "false"
            TYPE_INT_DEC -> data.toString()
            TYPE_INT_HEX -> "0x" + Integer.toHexString(data)
            TYPE_REFERENCE -> "@0x" + Integer.toHexString(data)
            TYPE_ATTRIBUTE -> "?0x" + Integer.toHexString(data)
            TYPE_FLOAT -> java.lang.Float.intBitsToFloat(data).toString()
            TYPE_DIMENSION -> formatComplex(data, DIMENSION_UNITS)
            TYPE_FRACTION -> formatComplex(data, FRACTION_UNITS)
            TYPE_INT_COLOR_ARGB8 -> String.format("#%08X", data)
            TYPE_INT_COLOR_RGB8 -> String.format("#%06X", data and 0xFFFFFF)
            TYPE_INT_COLOR_ARGB4 -> String.format("#%04X", data and 0xFFFF)
            TYPE_INT_COLOR_RGB4 -> String.format("#%03X", data and 0xFFF)
            TYPE_NULL -> ""
            else -> data.toString()
        }
    }

    private fun formatComplex(data: Int, units: Array<String>): String {
        val value = (data shr 8).toFloat() / (1 shl 15)
        val unitIdx = data and 0x0F
        val unit = if (unitIdx in units.indices) units[unitIdx] else ""
        return if (value % 1f == 0f) "${value.toInt()}$unit" else "$value$unit"
    }

    private fun appendIndent(sb: StringBuilder, indent: Int) {
        for (i in 0 until indent) {
            sb.append("    ")
        }
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
