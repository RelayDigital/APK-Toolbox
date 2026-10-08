package io.github.bl3xand.apkcloner.clone

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import java.io.ByteArrayOutputStream
import org.xmlpull.v1.XmlPullParser

/**
 * Gives a clone a launcher icon that can be told apart from the original's: the same picture
 * with a coloured dot in the corner.
 *
 * Nothing is added to the app's resources. The file that defines the icon is swapped for an
 * equivalent one that stacks the dot on top of the app's own layers, so the size of the app and
 * of its resource table does not matter.
 */
class IconBadger(private val context: Context) {

    /**
     * Files of the base APK to replace, by path inside it, to mark the icon of [app]. Empty when
     * the icon is built in a way this cannot safely rewrite. [seed] picks the colour of the dot.
     */
    fun replacements(app: ApplicationInfo, seed: String): Map<String, ByteArray> {
        val packageManager = context.packageManager
        val resources = packageManager.getResourcesForApplication(app)
        val color = Color.HSVToColor(floatArrayOf(((seed.hashCode() and 0x7FFFFFFF) % 360).toFloat(), 0.75f, 0.9f))

        // Activities may carry an icon of their own, which the launcher prefers.
        val activityIcons = runCatching {
            packageManager.getPackageArchiveInfo(app.sourceDir, PackageManager.GET_ACTIVITIES)
                ?.activities?.map { it.icon }
        }.getOrNull().orEmpty()

        val result = HashMap<String, ByteArray>()
        for (icon in (listOf(app.icon) + activityIcons).filter { it != 0 }.distinct()) {
            runCatching {
                // Resolved the way the system will resolve it on this device.
                val path = TypedValue().also { resources.getValue(icon, it, true) }.string?.toString() ?: return@runCatching
                if (path in result) return@runCatching
                val replacement = if (path.endsWith(".xml")) adaptiveIcon(resources, icon, color) else bitmapIcon(resources, icon, color)
                if (replacement != null) result[path] = replacement
            }
        }
        return result
    }

    /**
     * The app's `<adaptive-icon>` again, with the same background, and a foreground that is the
     * original one plus the dot. Null if the original is not a plain adaptive icon.
     */
    private fun adaptiveIcon(resources: Resources, icon: Int, color: Int): ByteArray? {
        val layers = HashMap<String, Int>()
        resources.getXml(icon).use { parser ->
            var depth = 0
            while (true) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.START_TAG -> {
                        depth++
                        if (depth == 1 && parser.name != "adaptive-icon") return null
                        if (depth == 2) layers[parser.name] = parser.getAttributeResourceValue(ANDROID_NS, "drawable", 0)
                    }
                }
            }
        }
        val background = layers["background"]?.takeIf { it != 0 } ?: return null
        val foreground = layers["foreground"]?.takeIf { it != 0 } ?: return null

        val document = ResXmlDocument()
        val root = document.newElement("adaptive-icon")
        root.newElement("background").reference(background)
        val stack = root.newElement("foreground").newElement("layer-list")
        stack.newElement("item").reference(foreground)
        // Insets are fractions of the layer, so the dot keeps its place at any icon size.
        val dot = stack.newElement("item").newElement("inset").apply {
            fraction("insetLeft", android.R.attr.insetLeft, DOT_START)
            fraction("insetTop", android.R.attr.insetTop, DOT_START)
            fraction("insetRight", android.R.attr.insetRight, DOT_END)
            fraction("insetBottom", android.R.attr.insetBottom, DOT_END)
        }.newElement("shape")
        dot.value("shape", android.R.attr.shape, ValueType.DEC, SHAPE_OVAL)
        dot.newElement("solid").value("color", android.R.attr.color, ValueType.COLOR_ARGB8, color)
        dot.newElement("stroke").apply {
            value("width", android.R.attr.width, ValueType.DIMENSION, complex(RING_DP, TypedValue.COMPLEX_UNIT_DIP))
            value("color", android.R.attr.color, ValueType.COLOR_ARGB8, Color.WHITE)
        }
        layers["monochrome"]?.takeIf { it != 0 }?.let { root.newElement("monochrome").reference(it) }
        document.refreshFull()
        return document.bytes
    }

    /** A plain bitmap icon: the same picture, redrawn with the dot on it. */
    private fun bitmapIcon(resources: Resources, icon: Int, color: Int): ByteArray {
        val drawable = resources.getDrawable(icon, null)
        val size = drawable.intrinsicWidth.coerceIn(48, 512)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = size * 0.14f
        val center = size - radius * 1.25f
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, radius * 1.2f, paint)
        paint.color = color
        canvas.drawCircle(center, center, radius, paint)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun ResXmlElement.reference(resourceId: Int) =
        value("drawable", android.R.attr.drawable, ValueType.REFERENCE, resourceId)

    private fun ResXmlElement.fraction(name: String, attribute: Int, share: Float) =
        value(name, attribute, ValueType.FRACTION, complex(share, TypedValue.COMPLEX_UNIT_FRACTION))

    /**
     * A dimension or fraction in the fixed-point form compiled resources store them in: a 24-bit
     * mantissa, a radix saying where its binary point is, and the unit.
     */
    private fun complex(value: Float, unit: Int): Int {
        val bits = (value * (1 shl 23) + 0.5f).toLong()
        val (radix, shift) = when {
            bits and 0x7FFFFFL == 0L -> COMPLEX_RADIX_23P0 to 23
            bits and 0x7FFFFFL.inv() == 0L -> COMPLEX_RADIX_0P23 to 0
            bits and 0x7FFFFFFFL.inv() == 0L -> COMPLEX_RADIX_8P15 to 8
            bits and 0x7FFFFFFFFFL.inv() == 0L -> COMPLEX_RADIX_16P7 to 16
            else -> COMPLEX_RADIX_23P0 to 23
        }
        val mantissa = ((bits shr shift) and 0xFFFFFF).toInt()
        return (mantissa shl 8) or (radix shl 4) or unit
    }

    private fun ResXmlElement.value(name: String, attribute: Int, type: ValueType, data: Int) {
        getOrCreateAndroidAttribute(name, attribute).apply {
            setValueType(type)
            setData(data)
        }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val SHAPE_OVAL = 1
        const val COMPLEX_RADIX_23P0 = 0
        const val COMPLEX_RADIX_16P7 = 1
        const val COMPLEX_RADIX_8P15 = 2
        const val COMPLEX_RADIX_0P23 = 3

        // The dot sits in the lower right of the 108dp layer, inside the 66dp area every
        // launcher mask keeps: centred at two thirds, 17dp across.
        const val DOT_START = 0.588f
        const val DOT_END = 0.255f
        const val RING_DP = 1.5f
    }
}
