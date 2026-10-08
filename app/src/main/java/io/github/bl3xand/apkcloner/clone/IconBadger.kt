package io.github.bl3xand.apkcloner.clone

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.value.ValueType
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Gives a clone a launcher icon that can be told apart from the original's: the same picture
 * with a coloured dot in the corner.
 *
 * The icon is added to the APK as a new adaptive icon (a full-bleed bitmap, so the launcher's
 * mask still applies) and the manifest is pointed at it; the app's own resources stay as they are.
 */
class IconBadger(private val context: Context) {

    /** Writes [input] to [output] with the badged icon. [seed] picks the colour of the dot. */
    fun apply(input: File, output: File, app: ApplicationInfo, seed: String) {
        val picture = render(app, seed)
        ApkModule.loadApkFile(input).use { module ->
            val manifest = module.androidManifest
            val packageBlock = module.tableBlock.pickOne() ?: error("No resource table")

            module.add(ByteInputSource(picture, PICTURE_PATH))
            val pictureId = packageBlock.getOrCreate("", "drawable", RESOURCE_NAME + "_picture").run {
                setValueAsString(PICTURE_PATH)
                resourceId
            }

            // <adaptive-icon> with the same opaque bitmap as both layers.
            val document = ResXmlDocument().apply { setPackageBlock(packageBlock) }
            val root = document.newElement("adaptive-icon")
            for (layer in listOf("background", "foreground")) {
                root.newElement(layer).getOrCreateAndroidAttribute("drawable", ATTR_DRAWABLE).apply {
                    setValueType(ValueType.REFERENCE)
                    setData(pictureId)
                }
            }
            document.refreshFull()
            module.add(ByteInputSource(document.bytes, ICON_PATH))
            val iconId = packageBlock.getOrCreate("", "mipmap", RESOURCE_NAME).run {
                setValueAsString(ICON_PATH)
                resourceId
            }

            manifest.setIconResourceId(iconId)
            if (manifest.roundIconResourceId != 0) manifest.setRoundIconResourceId(iconId)
            // Activities may carry an icon of their own, which the launcher prefers.
            for (activity in manifest.getActivities(true)) {
                for (attribute in intArrayOf(AndroidManifest.ID_icon, AndroidManifest.ID_roundIcon)) {
                    activity.searchAttributeByResourceId(attribute)?.setData(iconId)
                }
            }
            manifest.refresh()
            module.tableBlock.refresh()
            module.writeApk(output)
        }
    }

    private fun render(app: ApplicationInfo, seed: String): ByteArray {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val icon = app.loadUnbadgedIcon(context.packageManager)
        if (icon is AdaptiveIconDrawable) {
            for (layer in listOfNotNull(icon.background, icon.foreground)) {
                layer.setBounds(0, 0, SIZE, SIZE)
                layer.draw(canvas)
            }
        } else {
            // A legacy icon has its own shape: sit it inside the area no launcher mask cuts off.
            canvas.drawColor(Color.WHITE)
            val inset = (SIZE * (1 - LEGACY_SCALE) / 2).toInt()
            icon.setBounds(inset, inset, SIZE - inset, SIZE - inset)
            icon.draw(canvas)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val hue = (seed.hashCode() and 0x7FFFFFFF) % 360
        paint.color = Color.WHITE
        canvas.drawCircle(BADGE_CENTER, BADGE_CENTER, BADGE_RADIUS + BADGE_RING, paint)
        paint.color = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.75f, 0.9f))
        canvas.drawCircle(BADGE_CENTER, BADGE_CENTER, BADGE_RADIUS, paint)

        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private companion object {
        const val RESOURCE_NAME = "apktoolbox_clone_icon"
        const val PICTURE_PATH = "res/apktoolbox_clone_icon_picture.png"
        const val ICON_PATH = "res/apktoolbox_clone_icon.xml"
        const val ATTR_DRAWABLE = 0x01010199

        // 108dp at xxxhdpi. The dot sits in the lower right, inside the 66dp safe zone.
        const val SIZE = 432
        const val LEGACY_SCALE = 0.6f
        const val BADGE_CENTER = 288f
        const val BADGE_RADIUS = 34f
        const val BADGE_RING = 7f
    }
}
