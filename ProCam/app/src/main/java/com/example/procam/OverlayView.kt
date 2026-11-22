package com.example.procam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.ln
import kotlin.math.max

/** Прозрачный слой поверх превью: сетка, зебра, focus peaking, гистограмма. */
class OverlayView(context: Context) : View(context) {

    @Volatile var grid = true
    @Volatile var zebra = false
    @Volatile var peaking = false
    @Volatile var histogram = true

    private var bmp: Bitmap? = null
    private var hist = IntArray(64)
    private val dst = RectF()

    private val gridPaint = Paint().apply {
        color = Color.argb(110, 255, 255, 255)
        strokeWidth = 2f
    }
    private val bgPaint = Paint().apply { color = Color.argb(120, 0, 0, 0) }
    private val barPaint = Paint().apply { color = Color.argb(220, 255, 255, 255) }
    private val clipPaint = Paint().apply { color = Color.argb(235, 255, 60, 60) }
    private val bmpPaint = Paint() // без сглаживания: пиксели зебры/пикинга остаются чёткими

    fun setAnalysis(px: IntArray, w: Int, h: Int, newHist: IntArray) {
        var b = bmp
        if (b == null || b.width != w || b.height != h) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp = b
        }
        val bb = b ?: return
        bb.setPixels(px, 0, w, 0, 0, w, h)
        hist = newHist
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        val b = bmp
        if (b != null && (zebra || peaking)) {
            dst.set(0f, 0f, w, h)
            canvas.drawBitmap(b, null, dst, bmpPaint)
        }

        if (grid) {
            for (i in 1..2) {
                canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, gridPaint)
                canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, gridPaint)
            }
        }

        if (histogram) drawHistogram(canvas)
    }

    private fun drawHistogram(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val boxW = 150f * d
        val boxH = 56f * d
        val left = 8f * d
        val top = 8f * d
        canvas.drawRect(left, top, left + boxW, top + boxH, bgPaint)

        val data = hist
        var mx = 1
        for (v in data) mx = max(mx, v)
        val lnMax = ln(1.0 + mx)
        val barW = boxW / data.size
        for (i in data.indices) {
            val frac = (ln(1.0 + data[i]) / lnMax).toFloat()
            val bh = frac * (boxH - 4f * d)
            val p = if (i == 0 || i == data.size - 1) clipPaint else barPaint
            canvas.drawRect(
                left + i * barW,
                top + boxH - 2f * d - bh,
                left + (i + 1) * barW - 1f,
                top + boxH - 2f * d,
                p
            )
        }
    }
}
