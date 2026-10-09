package io.github.bl3xand.apkcloner.sources.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import io.github.bl3xand.apkcloner.ui.dp

internal const val PALETTE_HEIGHT = 200

/** A field of colours: hue runs left to right, top is pale and bottom is deep. */
internal class PaletteView(context: Context, private val onPick: (Int) -> Unit) : View(context) {
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(3).toFloat()
        color = Color.WHITE
        setShadowLayer(context.dp(2).toFloat(), 0f, 0f, Color.BLACK)
    }
    private var markX = -1f
    private var markY = -1f

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, context.dp(16).toFloat())
            }
        }
    }

    private fun colorAt(x: Float, y: Float): Int {
        val hue = (x.coerceIn(0f, 1f) * 360f).coerceAtMost(359.9f)
        val depth = y.coerceIn(0f, 1f)
        // Pale at the top, pure in the middle, darker at the bottom.
        val saturation = if (depth < 0.5f) 0.15f + depth * 1.7f else 1f
        val value = if (depth < 0.5f) 1f else 1f - (depth - 0.5f) * 1.2f
        return Color.HSVToColor(floatArrayOf(hue, saturation, value))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val columns = 120
        val rows = 60
        val pixels = IntArray(columns * rows) { colorAt((it % columns) / (columns - 1f), (it / columns) / (rows - 1f)) }
        bitmap = Bitmap.createBitmap(pixels, columns, rows, Bitmap.Config.ARGB_8888)
    }

    override fun onDraw(canvas: Canvas) {
        bitmap?.let { canvas.drawBitmap(it, null, Rect(0, 0, width, height), paint) }
        if (markX >= 0) canvas.drawCircle(markX, markY, context.dp(10).toFloat(), ring)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        markX = event.x.coerceIn(0f, width.toFloat())
        markY = event.y.coerceIn(0f, height.toFloat())
        onPick(colorAt(markX / width, markY / height))
        invalidate()
        return true
    }
}
