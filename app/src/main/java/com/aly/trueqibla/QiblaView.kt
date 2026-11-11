package com.aly.trueqibla

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The big dial: ring, "you face" marker, Qibla arrow with Kaaba, and the real sun on the ring. */
class QiblaView(context: Context) : View(context) {
    /** Qibla direction relative to the top of the phone, degrees clockwise. null = unknown. */
    var qiblaRel: Float? = null
    /** Sun direction relative to the top of the phone (shown only when the sun is up). */
    var sunRel: Float? = null
    var ringColor: Int = Color.parseColor("#D9D4C8")
    var arrowColor: Int = Color.parseColor("#127A52")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arrow = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val s = if (h > 0) min(w, h) else w
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val s = width / 300f          // drawing is designed on a 300 x 300 grid
        val cx = width / 2f
        val cy = height / 2f

        // face + ring
        fill.color = Color.parseColor("#FBFAF7")
        c.drawCircle(cx, cy, 136 * s, fill)
        stroke.color = ringColor
        stroke.strokeWidth = 7 * s
        c.drawCircle(cx, cy, 136 * s, stroke)

        // small ticks every 30°
        stroke.color = Color.parseColor("#B8B2A5")
        stroke.strokeWidth = 2 * s
        for (a in 0 until 360 step 30) {
            val r = Math.toRadians(a.toDouble())
            c.drawLine(
                cx + (128 * s * sin(r)).toFloat(), cy - (128 * s * cos(r)).toFloat(),
                cx + (118 * s * sin(r)).toFloat(), cy - (118 * s * cos(r)).toFloat(), stroke
            )
        }

        // "you face this way" marker at the top
        fill.color = Color.parseColor("#1D2421")
        arrow.reset()
        arrow.moveTo(cx, 2 * s); arrow.lineTo(cx - 9 * s, 16 * s); arrow.lineTo(cx + 9 * s, 16 * s); arrow.close()
        c.drawPath(arrow, fill)

        // the real sun, as a sanity check
        sunRel?.let { a ->
            c.save()
            c.rotate(a, cx, cy)
            fill.color = Color.parseColor("#F2B01E")
            c.drawCircle(cx, 30 * s, 10 * s, fill)
            c.restore()
        }

        // Qibla arrow + Kaaba
        qiblaRel?.let { a ->
            c.save()
            c.rotate(a, cx, cy)
            fill.color = arrowColor
            arrow.reset()
            arrow.moveTo(150 * s, 62 * s)
            arrow.lineTo(176 * s, 106 * s); arrow.lineTo(160 * s, 106 * s); arrow.lineTo(160 * s, 200 * s)
            arrow.lineTo(140 * s, 200 * s); arrow.lineTo(140 * s, 106 * s); arrow.lineTo(124 * s, 106 * s)
            arrow.close()
            c.drawPath(arrow, fill)
            fill.color = Color.parseColor("#111111")
            c.drawRoundRect(133 * s, 23 * s, 167 * s, 57 * s, 3 * s, 3 * s, fill)
            fill.color = Color.parseColor("#D4A63A")
            c.drawRect(133 * s, 32 * s, 167 * s, 38 * s, fill)
            c.restore()
        }
    }
}

/** Horizontal "field quality" meter: how closely the measured magnetic field matches Earth's field here. */
class MeterView(context: Context) : View(context) {
    var fraction: Float = 0f
    var barColor: Int = Color.GRAY
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (14 * resources.displayMetrics.density).toInt())
    }

    override fun onDraw(c: Canvas) {
        val r = height / 2f
        p.color = Color.parseColor("#E3DED3")
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, p)
        p.color = barColor
        val w = (width * fraction.coerceIn(0.03f, 1f))
        c.drawRoundRect(0f, 0f, w, height.toFloat(), r, r, p)
    }
}
