package io.github.bl3xand.apkclonner.clone

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renames the package of a compiled (binary) AndroidManifest.xml.
 *
 * Only the string pool is rebuilt; element chunks are edited in place by repointing attribute
 * values at strings appended to the end of the pool. Existing pool entries are never changed, so
 * the resource-id map (which is indexed by pool position) stays valid.
 */
class ManifestPatcher(private val newPackage: String, private val newLabel: String?) {

    var oldPackage: String = ""
        private set

    private lateinit var buf: ByteBuffer
    private lateinit var strings: MutableList<String>
    private var resIds = IntArray(0)
    private val appended = HashMap<String, Int>()

    fun patch(source: ByteArray): ByteArray {
        buf = ByteBuffer.wrap(source.copyOf()).order(ByteOrder.LITTLE_ENDIAN)
        require(source.size > 16 && ushort(0) == RES_XML) { "Not a binary AndroidManifest.xml" }
        require(ushort(POOL_START) == RES_STRING_POOL) { "Manifest string pool not found" }
        val end = minOf(buf.getInt(4), source.size)
        val poolSize = buf.getInt(POOL_START + 4)
        strings = readPool(POOL_START)

        val elements = ArrayList<Int>()
        var pos = POOL_START + poolSize
        while (pos + 8 <= end) {
            val size = buf.getInt(pos + 4)
            require(size >= 8 && pos + size <= end) { "Corrupt manifest chunk" }
            when (ushort(pos)) {
                RES_XML_RESOURCE_MAP -> {
                    val header = ushort(pos + 2)
                    resIds = IntArray((size - header) / 4) { buf.getInt(pos + header + 4 * it) }
                }
                RES_XML_START_ELEMENT -> elements += pos
            }
            pos += size
        }

        // First pass: the old package name and the permissions this app declares itself. Both
        // must be known before any reference to them can be rewritten.
        val declared = HashSet<String>()
        for (element in elements) {
            val tag = tagOf(element)
            forEachAttr(element) { attr ->
                val name = attrName(attr)
                if (tag == "manifest" && name == "package") oldPackage = stringValue(attr).orEmpty()
                if (tag in PERMISSION_DECLARATIONS && name == "name") stringValue(attr)?.let(declared::add)
            }
        }
        require(oldPackage.isNotEmpty()) { "Manifest has no package attribute" }

        var applicationLabel = -1
        var componentLabel = -1
        val launcherLabels = LinkedHashSet<Int>()

        for (element in elements) {
            val tag = tagOf(element)
            if (tag in COMPONENTS) componentLabel = -1
            forEachAttr(element) { attr ->
                val name = attrName(attr)
                val value = stringValue(attr)
                when {
                    name == "label" && tag == "application" -> applicationLabel = attr
                    name == "label" && (tag == "activity" || tag == "activity-alias") -> componentLabel = attr
                    value == null -> Unit
                    tag == "manifest" && name == "package" -> setString(attr, newPackage)
                    tag == "manifest" && name == "sharedUserId" -> setString(attr, rename(value))
                    tag == "category" && name == "name" && value in LAUNCHER_CATEGORIES ->
                        if (componentLabel >= 0) launcherLabels += componentLabel
                    tag == "provider" && name == "authorities" ->
                        setString(attr, value.split(';').joinToString(";") { rename(it.trim()) })
                    // Relative class names are resolved against the manifest package, so they
                    // have to be pinned to the original one before it changes.
                    (name == "name" && (tag in COMPONENTS || tag == "application")) || name in CLASS_ATTRS ->
                        qualify(value).let { if (it != value) setString(attr, it) }
                    name == "name" && (tag in PERMISSION_DECLARATIONS || tag in PERMISSION_USES) ||
                        name in PERMISSION_ATTRS ->
                        if (value in declared) setString(attr, rename(value))
                }
            }
        }

        if (newLabel != null) {
            if (applicationLabel >= 0) setString(applicationLabel, newLabel)
            // A launcher activity's own label wins over the application label on the home screen.
            launcherLabels.forEach { setString(it, newLabel) }
        }

        val pool = writePool()
        val tailStart = POOL_START + poolSize
        val out = ByteBuffer.allocate(POOL_START + pool.size + end - tailStart).order(ByteOrder.LITTLE_ENDIAN)
        out.put(buf.array(), 0, POOL_START)
        out.put(pool)
        out.put(buf.array(), tailStart, end - tailStart)
        out.putInt(4, out.capacity())
        return out.array()
    }

    private fun rename(value: String): String =
        if (value.contains(oldPackage)) value.replace(oldPackage, newPackage) else "$newPackage.$value"

    private fun qualify(className: String): String = when {
        className.startsWith(".") -> oldPackage + className
        !className.contains('.') -> "$oldPackage.$className"
        else -> className
    }

    private fun ushort(offset: Int) = buf.getShort(offset).toInt() and 0xFFFF

    private fun tagOf(element: Int) = strings.getOrNull(buf.getInt(element + 20)).orEmpty()

    private inline fun forEachAttr(element: Int, block: (Int) -> Unit) {
        val start = element + 16 + ushort(element + 24)
        val size = ushort(element + 26)
        repeat(ushort(element + 28)) { block(start + it * size) }
    }

    private fun attrName(attr: Int): String {
        val index = buf.getInt(attr + 4)
        // Obfuscators sometimes blank attribute names; the framework only looks at resource ids.
        resIds.getOrNull(index)?.let { KNOWN_ATTRS[it] }?.let { return it }
        return strings.getOrNull(index).orEmpty()
    }

    private fun stringValue(attr: Int): String? =
        if (buf.get(attr + 15).toInt() == TYPE_STRING) strings.getOrNull(buf.getInt(attr + 16)) else null

    private fun setString(attr: Int, value: String) {
        val index = appended.getOrPut(value) { strings.add(value); strings.size - 1 }
        buf.putInt(attr + 8, index)
        buf.putShort(attr + 12, 8)
        buf.put(attr + 14, 0)
        buf.put(attr + 15, TYPE_STRING.toByte())
        buf.putInt(attr + 16, index)
    }

    private fun readPool(pool: Int): MutableList<String> {
        val headerSize = ushort(pool + 2)
        val count = buf.getInt(pool + 8)
        require(buf.getInt(pool + 12) == 0) { "Styled manifest strings are not supported" }
        val utf8 = buf.getInt(pool + 16) and FLAG_UTF8 != 0
        val dataStart = pool + buf.getInt(pool + 20)
        val bytes = buf.array()
        return MutableList(count) { i ->
            var p = dataStart + buf.getInt(pool + headerSize + 4 * i)
            if (utf8) {
                p += if (bytes[p].toInt() and 0x80 != 0) 2 else 1
                var length = bytes[p++].toInt() and 0xFF
                if (length and 0x80 != 0) length = (length and 0x7F shl 8) or (bytes[p++].toInt() and 0xFF)
                String(bytes, p, length, Charsets.UTF_8)
            } else {
                var length = ushort(p)
                p += 2
                if (length and 0x8000 != 0) {
                    length = (length and 0x7FFF shl 16) or ushort(p)
                    p += 2
                }
                String(bytes, p, length * 2, Charsets.UTF_16LE)
            }
        }
    }

    /** Always written as UTF-16, the encoding aapt2 itself uses for manifests. */
    private fun writePool(): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        fun short(value: Int) {
            data.write(value and 0xFF)
            data.write(value shr 8 and 0xFF)
        }
        strings.forEachIndexed { i, s ->
            offsets[i] = data.size()
            if (s.length > 0x7FFF) short(s.length shr 16 or 0x8000)
            short(s.length and 0xFFFF)
            data.write(s.toByteArray(Charsets.UTF_16LE))
            short(0)
        }
        while (data.size() % 4 != 0) data.write(0)

        val stringsStart = POOL_HEADER_SIZE + 4 * strings.size
        val out = ByteBuffer.allocate(stringsStart + data.size()).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(RES_STRING_POOL.toShort()).putShort(POOL_HEADER_SIZE.toShort()).putInt(out.capacity())
        out.putInt(strings.size).putInt(0).putInt(0).putInt(stringsStart).putInt(0)
        offsets.forEach(out::putInt)
        out.put(data.toByteArray())
        return out.array()
    }

    private companion object {
        const val RES_STRING_POOL = 0x0001
        const val RES_XML = 0x0003
        const val RES_XML_START_ELEMENT = 0x0102
        const val RES_XML_RESOURCE_MAP = 0x0180
        const val POOL_START = 8
        const val POOL_HEADER_SIZE = 28
        const val FLAG_UTF8 = 1 shl 8
        const val TYPE_STRING = 0x03

        val KNOWN_ATTRS = mapOf(
            0x01010001 to "label",
            0x01010003 to "name",
            0x01010006 to "permission",
            0x01010007 to "readPermission",
            0x01010008 to "writePermission",
            0x0101000b to "sharedUserId",
            0x01010018 to "authorities",
            0x01010202 to "targetActivity",
        )
        val COMPONENTS = setOf("activity", "activity-alias", "service", "receiver", "provider")
        val CLASS_ATTRS = setOf(
            "targetActivity", "parentActivityName", "backupAgent", "manageSpaceActivity",
            "appComponentFactory", "zygotePreloadName",
        )
        val PERMISSION_DECLARATIONS = setOf("permission", "permission-group", "permission-tree")
        val PERMISSION_USES = setOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")
        val PERMISSION_ATTRS = setOf("permission", "readPermission", "writePermission", "permissionGroup")
        val LAUNCHER_CATEGORIES = setOf(
            "android.intent.category.LAUNCHER",
            "android.intent.category.LEANBACK_LAUNCHER",
        )
    }
}
