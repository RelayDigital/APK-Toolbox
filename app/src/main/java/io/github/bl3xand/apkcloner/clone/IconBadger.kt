package io.github.bl3xand.apkcloner.clone

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.DisplayMetrics
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
            // A launcher asks for the icon at a density of its own choosing, usually a higher
            // one than the screen's, and so may be handed a different file than the settings
            // are. Every file the icon can resolve to is replaced.
            for (density in DENSITIES) {
                runCatching {
                    val value = TypedValue()
                    if (density == 0) resources.getValue(icon, value, true) else resources.getValueForDensity(icon, density, value, true)
                    val path = value.string?.toString() ?: return@runCatching
                    if (path in result) return@runCatching
                    val replacement = if (path.endsWith(".xml")) adaptiveIcon(resources, icon, color) else bitmapIcon(resources, icon, density, color)
                    if (replacement != null) result[path] = replacement
                }
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
        // A white disc with a coloured one on top. A stroke would do for the ring, but its width
        // is a fixed length while everything else here is a share of the icon.
        stack.newElement("item").disc(DOT_SHARE + 2 * RING_SHARE, Color.WHITE)
        stack.newElement("item").disc(DOT_SHARE, color)
        // Themed icons are drawn from this layer alone, in one colour: the dot goes here too,
        // or a clone on a themed home screen would look exactly like the original.
        layers["monochrome"]?.takeIf { it != 0 }?.let { monochrome ->
            val themed = root.newElement("monochrome").newElement("layer-list")
            themed.newElement("item").reference(monochrome)
            themed.newElement("item").disc(DOT_SHARE + 2 * RING_SHARE, Color.WHITE)
        }
        document.refreshFull()
        return document.bytes
    }

    /**
     * A filled circle, [share] of the visible icon across, centred where the dot belongs. It is
     * placed with insets, which are fractions of the layer, so it keeps its place and size at
     * any icon size.
     */
    private fun ResXmlElement.disc(share: Float, color: Int) {
        val start = (LAYER_MARGIN + VISIBLE_DP * (DOT_CENTER - share / 2)) / LAYER_DP
        val end = 1f - (LAYER_MARGIN + VISIBLE_DP * (DOT_CENTER + share / 2)) / LAYER_DP
        val shape = newElement("inset").apply {
            fraction("insetLeft", android.R.attr.insetLeft, start)
            fraction("insetTop", android.R.attr.insetTop, start)
            fraction("insetRight", android.R.attr.insetRight, end)
            fraction("insetBottom", android.R.attr.insetBottom, end)
        }.newElement("shape")
        shape.value("shape", android.R.attr.shape, ValueType.DEC, SHAPE_OVAL)
        shape.newElement("solid").value("color", android.R.attr.color, ValueType.COLOR_ARGB8, color)
    }

    /** A plain bitmap icon: the same picture, redrawn with the dot on it. */
    private fun bitmapIcon(resources: Resources, icon: Int, density: Int, color: Int): ByteArray {
        val drawable = if (density == 0) resources.getDrawable(icon, null) else resources.getDrawableForDensity(icon, density, null)!!
        val size = drawable.intrinsicWidth.coerceIn(MIN_BITMAP, MAX_BITMAP)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // A launcher sizes a plain picture by how much of it is filled, so the dot would come
        // out differently on every app. A frame too faint to see makes every picture count as
        // filled edge to edge: all of them are then shrunk alike, and so are their dots.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.argb(FRAME_ALPHA, 255, 255, 255)
        canvas.drawRect(0.5f, 0.5f, size - 0.5f, size - 0.5f, paint)
        paint.style = Paint.Style.FILL

        val ring = size * RING_SHARE / LEGACY_SCALE
        val radius = size * DOT_SHARE / LEGACY_SCALE / 2
        val center = size * (0.5f + (DOT_CENTER - 0.5f) / LEGACY_SCALE)
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, radius + ring, paint)
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

        // An adaptive layer is 108dp, of which the middle 72dp is seen.
        const val LAYER_DP = 108f
        const val VISIBLE_DP = 72f

        // One dot for every kind of icon, given as shares of the part of the icon that is
        // seen: how far across the coloured disc is, how wide the white ring around it, and
        // where the centre is, counted from the top left. The place is as far out as a plain
        // picture still has room for once it is shrunk (see LEGACY_SCALE).
        const val DOT_SHARE = 0.2f
        const val RING_SHARE = 0.02f
        const val DOT_CENTER = 0.675f

        private const val LAYER_MARGIN = (LAYER_DP - VISIBLE_DP) / 2

        // A plain picture is shown smaller than an adaptive icon: a launcher shrinks one that
        // is filled edge to edge to about this share to fit it inside its mask. The dot is
        // drawn larger and further out by as much, so that on screen it lands where the dot
        // of an adaptive icon does, at the same size.
        const val LEGACY_SCALE = 0.62f

        /** Just above what a launcher still counts as a visible pixel. */
        const val FRAME_ALPHA = 48

        const val MIN_BITMAP = 48
        const val MAX_BITMAP = 512

        /** The screen's own density first, then every bucket a launcher may ask for. */
        val DENSITIES = intArrayOf(
            0, DisplayMetrics.DENSITY_LOW, DisplayMetrics.DENSITY_MEDIUM, DisplayMetrics.DENSITY_HIGH,
            DisplayMetrics.DENSITY_XHIGH, DisplayMetrics.DENSITY_XXHIGH, DisplayMetrics.DENSITY_XXXHIGH,
        )
    }
}
