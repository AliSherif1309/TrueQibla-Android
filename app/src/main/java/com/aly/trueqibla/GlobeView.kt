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

/**
 * A small globe (orthographic view) centred halfway between the user and Makkah,
 * showing the great-circle path — the shortest route over the Earth, which is what the Qibla follows.
 */
class GlobeView(context: Context) : View(context) {
    var userLat = Double.NaN
    var userLon = Double.NaN
    var label = ""
    var oceanColor: Int = Color.parseColor("#E4EEF0")
    var gridColor: Int = Color.parseColor("#C4D3D6")
    var edgeColor: Int = Color.parseColor("#9FB3B7")
    var pathColor: Int = Color.parseColor("#127A52")
    var textColor: Int = Color.parseColor("#1D2421")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.8).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (userLat.isNaN()) return
        val (lat0, lon0) = Astro.greatCirclePoint(userLat, userLon, Astro.KAABA_LAT, Astro.KAABA_LON, 0.5)
        val p0 = Math.toRadians(lat0); val l0 = Math.toRadians(lon0)
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) * 0.44f
        val dens = resources.displayMetrics.density

        // returns screen point, or null when on the far side of the globe
        fun proj(lat: Double, lon: Double): FloatArray? {
            val p = Math.toRadians(lat); val dl = Math.toRadians(lon) - l0
            val cosc = sin(p0) * sin(p) + cos(p0) * cos(p) * cos(dl)
            if (cosc < 0) return null
            val x = cos(p) * sin(dl)
            val y = cos(p0) * sin(p) - sin(p0) * cos(p) * cos(dl)
            return floatArrayOf(cx + (r * x).toFloat(), cy - (r * y).toFloat())
        }
        fun polyline(pts: List<Pair<Double, Double>>, paint: Paint) {
            path.reset(); var pen = false
            for ((la, lo) in pts) {
                val s = proj(la, lo)
                if (s == null) { pen = false; continue }
                if (pen) path.lineTo(s[0], s[1]) else path.moveTo(s[0], s[1])
                pen = true
            }
            c.drawPath(path, paint)
        }

        // ocean + rim
        fill.color = oceanColor; c.drawCircle(cx, cy, r, fill)
        // graticule every 15°
        line.color = gridColor; line.strokeWidth = 1f * dens
        for (lat in -75..75 step 15) polyline((-180..180 step 3).map { Pair(lat.toDouble(), it.toDouble()) }, line)
        for (lon in -180 until 180 step 15) polyline((-90..90 step 3).map { Pair(it.toDouble(), lon.toDouble()) }, line)
        line.color = edgeColor; line.strokeWidth = 1.5f * dens
        c.drawCircle(cx, cy, r, line)

        // great-circle path
        line.color = pathColor; line.strokeWidth = 3.5f * dens
        polyline((0..80).map { Astro.greatCirclePoint(userLat, userLon, Astro.KAABA_LAT, Astro.KAABA_LON, it / 80.0) }, line)

        // end points
        text.color = textColor; text.textSize = 12f * dens
        proj(userLat, userLon)?.let {
            fill.color = pathColor; c.drawCircle(it[0], it[1], 6f * dens, fill)
            fill.color = Color.WHITE; c.drawCircle(it[0], it[1], 2.5f * dens, fill)
        }
        proj(Astro.KAABA_LAT, Astro.KAABA_LON)?.let {
            fill.color = Color.parseColor("#111111")
            c.drawRect(it[0] - 6f * dens, it[1] - 6f * dens, it[0] + 6f * dens, it[1] + 6f * dens, fill)
            fill.color = Color.parseColor("#D4A63A")
            c.drawRect(it[0] - 6f * dens, it[1] - 3f * dens, it[0] + 6f * dens, it[1] - 1f * dens, fill)
        }
        if (label.isNotEmpty()) {
            text.textAlign = Paint.Align.CENTER
            c.drawText(label, cx, height - 4f * dens, text)
        }
    }
}
