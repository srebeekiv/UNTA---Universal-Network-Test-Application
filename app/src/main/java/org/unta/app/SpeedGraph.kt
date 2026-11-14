package org.unta.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** График скорости во времени: синяя линия скачивание, оранжевая отдача. */
class SpeedGraph(ctx: Context) : View(ctx) {
    private val dl = ArrayList<FloatArray>()
    private val ul = ArrayList<FloatArray>()
    private var phaseSecs = 10f
    private var phases = 2
    private val d = resources.displayMetrics.density
    private val themeText: Int = run {
        val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        val c = ta.getColor(0, Color.GRAY); ta.recycle(); c
    }
    private val line = { col: Int -> Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeJoin = Paint.Join.ROUND } }
    private val pDl = line(0xFF1E88E5.toInt())
    private val pUl = line(0xFFFB8C00.toInt())
    private val grid = Paint()
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * d }
    private val bg = Paint()

    init { colors(themeText) }

    private fun colors(tc: Int) {
        grid.color = (tc and 0x00FFFFFF) or 0x44000000; grid.strokeWidth = 1f
        txt.color = tc
        bg.color = (tc and 0x00FFFFFF) or 0x14000000
    }

    /** Светлый вид для картинки «поделиться»: тёмный текст на светлом фоне независимо от темы телефона. */
    fun useExportLook() { colors(0xFF333333.toInt()); bg.color = 0xFFF2F4F8.toInt() }

    fun reset(secs: Int, phaseCount: Int) { dl.clear(); ul.clear(); phaseSecs = secs.toFloat(); phases = phaseCount; invalidate() }

    /** Готовые точки (x в секундах внутри этапа) - для истории и картинки. */
    fun setData(secs: Int, phaseCount: Int, dlPts: List<FloatArray>, ulPts: List<FloatArray>) {
        phaseSecs = secs.toFloat(); phases = phaseCount
        dl.clear(); dl.addAll(dlPts)
        ul.clear(); ul.addAll(ulPts.map { floatArrayOf(it[0] + phaseSecs, it[1]) })
        invalidate()
    }

    fun add(phase: String, mbps: Double, frac: Double) {
        val up = phase == "upload"
        val x = ((if (up) 1 else 0) + frac.toFloat()) * phaseSecs
        (if (up) ul else dl).add(floatArrayOf(x, mbps.toFloat()))
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(resolveSize(0, w), (170 * d).toInt()) }

    private fun niceMax(v: Float): Float {
        var k = 1f
        while (true) { for (m in floatArrayOf(1f, 2f, 5f)) if (m * k >= v) return m * k; k *= 10f }
    }

    override fun onDraw(c: Canvas) {
        val left = 44 * d; val top = 22 * d; val right = width - 8 * d; val bottom = height - 20 * d
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 8 * d, 8 * d, bg)
        val peak = maxOf(10f, (dl + ul).maxOfOrNull { it[1] } ?: 0f)
        val maxY = niceMax(peak * 1.1f)
        val spanX = phaseSecs * phases
        for (i in 0..2) {
            val y = bottom - (bottom - top) * i / 2f
            c.drawLine(left, y, right, y, grid)
            c.drawText("%.0f".format(maxY * i / 2f), 4 * d, y + 4 * d, txt)
        }
        c.drawText("0 с", left, height - 5 * d, txt)
        c.drawText("%.0f с".format(spanX), right - 28 * d, height - 5 * d, txt)
        c.drawText("Мбит/с", 4 * d, top - 8 * d, txt)
        fun draw(pts: List<FloatArray>, p: Paint) {
            if (pts.size < 2) return
            val path = Path()
            pts.forEachIndexed { i, pt ->
                val x = left + (right - left) * pt[0] / spanX
                val y = bottom - (bottom - top) * minOf(pt[1], maxY) / maxY
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            c.drawPath(path, p)
        }
        draw(dl, pDl); draw(ul, pUl)
        pDl.style = Paint.Style.FILL; c.drawCircle(left + 70 * d, top - 11 * d, 4 * d, pDl); pDl.style = Paint.Style.STROKE
        c.drawText("скачивание", left + 78 * d, top - 7 * d, txt)
        if (phases > 1) {
            pUl.style = Paint.Style.FILL; c.drawCircle(left + 170 * d, top - 11 * d, 4 * d, pUl); pUl.style = Paint.Style.STROKE
            c.drawText("отдача", left + 178 * d, top - 7 * d, txt)
        }
    }
}
