package com.aly.trueqibla

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * True Qibla — compass that checks itself.
 *
 *  1. GAME_ROTATION_VECTOR (gyroscope + accelerometer, no magnet) gives smooth, interference-free turning.
 *  2. ROTATION_VECTOR (uses the magnet) gives an absolute heading; Android's built-in World Magnetic Model
 *     (GeomagneticField) converts it to TRUE north.
 *  3. Raw MAGNETIC_FIELD is compared with the field Earth SHOULD have at this exact place
 *     (strength and dip angle, also from GeomagneticField). If they don't match, something nearby
 *     is bending the field: the compass is ignored and the gyroscope holds the last good direction,
 *     and the user is told to move.
 */
class MainActivity : Activity(), SensorEventListener, LocationListener {

    private enum class Q { UNKNOWN, GOOD, FAIR, BAD }

    private lateinit var sm: SensorManager
    private lateinit var lm: LocationManager
    private var gameSensor: Sensor? = null
    private var rotSensor: Sensor? = null
    private var magSensor: Sensor? = null

    // location
    private var lat = Double.NaN
    private var lon = Double.NaN
    private var locAcc = -1f
    private var locLabel = ""
    private var geo: GeomagneticField? = null

    // sensors
    private val rmGame = FloatArray(9)
    private val rmRot = FloatArray(9)
    private val up = FloatArray(3)          // world "up" expressed in phone coordinates
    private var haveUp = false
    private var upright = false
    private var gameHeading: Double? = null
    private var magHeading: Double? = null  // magnetic north based (declination not yet added)
    private var magAt = 0L
    private var magAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    private var bStrength = Double.NaN      // µT, smoothed
    private var bDip = Double.NaN           // degrees (+ = downward), smoothed

    // fusion
    private var offset: Double? = null      // true heading − gyro heading
    private var offsetFromGood = false
    private var goodSince = 0L
    private var fairSince = 0L
    private var lastGoodAt = 0L
    private var lastFuse = 0L
    private var quality = Q.UNKNOWN
    private var aligned = false
    private var smX = Double.NaN
    private var smY = Double.NaN

    // views
    private lateinit var qView: QiblaView
    private lateinit var turnTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var meter: MeterView
    private lateinit var meterTv: TextView
    private lateinit var infoTv: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 60) }
    }

    private val GREEN = Color.parseColor("#127A52")
    private val GREEN_SOFT = Color.parseColor("#E2F2EA")
    private val ORANGE = Color.parseColor("#B5621A")
    private val ORANGE_SOFT = Color.parseColor("#FBEEDD")
    private val RED = Color.parseColor("#B23A3A")
    private val RED_SOFT = Color.parseColor("#F8E3E3")
    private val INK = Color.parseColor("#1D2421")
    private val MUTED = Color.parseColor("#68716C")

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        gameSensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        magSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(Color.parseColor("#F4F1EA"))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "True Qibla"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(INK)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply { text = "القبلة"; textSize = 24f; setTextColor(GREEN); setTypeface(typeface, Typeface.BOLD) })
        root.addView(header)

        qView = QiblaView(this)
        root.addView(qView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        turnTv = TextView(this).apply {
            textSize = 24f; setTypeface(typeface, Typeface.BOLD); setTextColor(INK); gravity = Gravity.CENTER
        }
        root.addView(turnTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })

        statusTv = TextView(this).apply {
            textSize = 15f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(statusTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })

        meterTv = TextView(this).apply { textSize = 13f; setTextColor(MUTED) }
        root.addView(meterTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        meter = MeterView(this)
        root.addView(meter, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

        infoTv = TextView(this).apply { textSize = 13f; setTextColor(MUTED); setLineSpacing(0f, 1.15f) }
        root.addView(infoTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(Color.parseColor("#F4F1EA")) })

        // show the last saved position immediately (works offline); GPS refines it
        val p = getSharedPreferences("qibla", MODE_PRIVATE)
        if (p.contains("lat")) setLocation(p.getFloat("lat", 0f).toDouble(), p.getFloat("lon", 0f).toDouble(), 0.0, -1f, "last known position")
    }

    override fun onResume() {
        super.onResume()
        gameSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        rotSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        startLocation()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
        try { lm.removeUpdates(this) } catch (_: Exception) {}
        handler.removeCallbacks(ticker)
    }

    // ---------------------------------------------------------------- location

    private fun startLocation() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            return
        }
        try {
            for (prov in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                val last = lm.getLastKnownLocation(prov)
                if (last != null && System.currentTimeMillis() - last.time < 10 * 60_000L) onLocationChanged(last)
            }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, this)
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startLocation()
    }

    override fun onLocationChanged(l: Location) {
        // keep the more accurate fix
        if (!lat.isNaN() && locAcc > 0 && l.accuracy > locAcc * 1.5f && l.accuracy > 30f) return
        val label = (if (l.provider == LocationManager.GPS_PROVIDER) "GPS" else "Network") + " ±${l.accuracy.toInt()} m"
        setLocation(l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else 0.0, l.accuracy, label)
        if (l.provider == LocationManager.GPS_PROVIDER && l.accuracy <= 20f) {
            try { lm.removeUpdates(this) } catch (_: Exception) {}   // good enough: save battery
        }
    }

    // Needed on Android 8–10, where these are not default methods
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun setLocation(la: Double, lo: Double, altM: Double, acc: Float, label: String) {
        lat = la; lon = lo; locAcc = acc; locLabel = label
        geo = GeomagneticField(la.toFloat(), lo.toFloat(), altM.toFloat(), System.currentTimeMillis())
        getSharedPreferences("qibla", MODE_PRIVATE).edit()
            .putFloat("lat", la.toFloat()).putFloat("lon", lo.toFloat()).apply()
    }

    // ---------------------------------------------------------------- sensors

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) magAccuracy = accuracy
    }

    /** Heading (deg, clockwise from the frame's north) of the phone's top edge — or, when the phone is
     *  held upright, of the direction the back camera faces. r maps phone -> world (East, North, Up). */
    private fun headingOf(r: FloatArray, updateUpright: Boolean): Double {
        val zUp = abs(r[8])
        if (updateUpright) upright = if (upright) zUp < 0.57f else zUp < 0.42f
        val e: Double
        val n: Double
        if (upright) { e = -r[2].toDouble(); n = -r[5].toDouble() } else { e = r[1].toDouble(); n = r[4].toDouble() }
        return Astro.norm360(Math.toDegrees(atan2(e, n)))
    }

    override fun onSensorChanged(ev: SensorEvent) {
        val now = System.currentTimeMillis()
        when (ev.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rmGame, ev.values)
                up[0] = rmGame[6]; up[1] = rmGame[7]; up[2] = rmGame[8]; haveUp = true
                gameHeading = headingOf(rmGame, true)
                fuse(now)
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rmRot, ev.values)
                if (gameSensor == null) { up[0] = rmRot[6]; up[1] = rmRot[7]; up[2] = rmRot[8]; haveUp = true }
                magHeading = headingOf(rmRot, gameSensor == null)
                magAt = now
                if (gameSensor == null) fuse(now)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magAccuracy = ev.accuracy
                if (!haveUp) return
                val bx = ev.values[0].toDouble(); val by = ev.values[1].toDouble(); val bz = ev.values[2].toDouble()
                val b = sqrt(bx * bx + by * by + bz * bz)
                if (b < 1.0) return
                val vUp = bx * up[0] + by * up[1] + bz * up[2]
                val dip = Math.toDegrees(asin((-vUp / b).coerceIn(-1.0, 1.0)))
                val k = 0.12
                bStrength = if (bStrength.isNaN()) b else bStrength + k * (b - bStrength)
                bDip = if (bDip.isNaN()) dip else bDip + k * (dip - bDip)
            }
        }
    }

    /** How far the measured field is from Earth's expected field here: 0 = perfect. */
    private fun fieldError(): Pair<Double, Double>? {
        val g = geo ?: return null
        if (bStrength.isNaN() || bDip.isNaN()) return null
        val expected = g.fieldStrength / 1000.0       // nT -> µT
        return Pair(abs(bStrength - expected) / expected, abs(bDip - g.inclination))
    }

    private fun evalQuality(): Q {
        val (se, de) = fieldError() ?: return Q.UNKNOWN
        return when {
            se < 0.07 && de < 4.0 -> Q.GOOD
            se < 0.15 && de < 8.0 -> Q.FAIR
            else -> Q.BAD
        }
    }

    /** Android's own accuracy flag is unreliable on many phones, so a field that matches Earth's
     *  expected strength AND dip also counts as calibrated (a badly calibrated sensor can't match both). */
    private fun calibrated() = magAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_LOW || quality == Q.GOOD

    private fun fuse(now: Long) {
        val g = geo ?: return
        val gh = gameHeading
        val mh = magHeading
        quality = evalQuality()
        if (gh == null || mh == null || now - magAt > 1500) return
        val d = Astro.norm180(mh + g.declination - gh)
        val dt = if (lastFuse == 0L) 0.02 else min(0.5, (now - lastFuse) / 1000.0)
        lastFuse = now
        val follow = 1.0 - exp(-dt / 1.5)                // follow the compass with a ~1.5 s time constant

        if (quality == Q.GOOD && calibrated()) {
            if (goodSince == 0L) goodSince = now
            fairSince = 0L
            val o = offset
            if (o == null || !offsetFromGood) {
                if (now - goodSince > 800) { offset = d; offsetFromGood = true }
            } else {
                offset = Astro.norm180(o + follow * Astro.norm180(d - o))
            }
            lastGoodAt = now
        } else {
            goodSince = 0L
            // No clean reading yet at all: accept a "fair" one after 3 s so the user still gets an arrow,
            // clearly labelled as approximate. A clean reading later replaces it.
            if (quality == Q.FAIR && calibrated() && !offsetFromGood) {
                if (fairSince == 0L) fairSince = now
                val o = offset
                if (o == null) { if (now - fairSince > 3000) offset = d }
                else offset = Astro.norm180(o + follow * Astro.norm180(d - o))
            } else fairSince = 0L
            // BAD: ignore the compass completely; the gyroscope keeps the last good direction.
        }
    }

    private fun currentHeading(): Double? {
        val o = offset
        val gh = gameHeading
        if (gameSensor != null) return if (gh != null && o != null) Astro.norm360(gh + o) else null
        // phone without gyroscope: plain corrected compass, only when the field looks clean
        val mh = magHeading ?: return null
        val g = geo ?: return null
        return if (quality == Q.GOOD || quality == Q.FAIR) Astro.norm360(mh + g.declination) else null
    }

    // ---------------------------------------------------------------- render

    private fun setStatus(text: String, fg: Int, bg: Int) {
        statusTv.text = text
        statusTv.setTextColor(fg)
        statusTv.background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(12).toFloat() }
    }

    private fun render() {
        val now = System.currentTimeMillis()
        if (lat.isNaN()) {
            qView.qiblaRel = null; qView.invalidate()
            turnTv.text = "Finding your location…"
            setStatus("Turn on Location (GPS) and allow it for True Qibla. Being outdoors or near a window helps.", ORANGE, ORANGE_SOFT)
            meterTv.text = ""; infoTv.text = ""
            return
        }
        val q = Astro.qibla(lat, lon)
        val g = geo

        // field meter
        val fe = fieldError()
        if (fe != null) {
            val (se, de) = fe
            val frac = (1.0 - min(1.0, max(se / 0.25, de / 15.0))).toFloat()
            meter.fraction = frac
            meter.barColor = when (quality) { Q.GOOD -> GREEN; Q.FAIR -> ORANGE; else -> RED }
            meterTv.text = "Magnetic field check: ${(frac * 100).toInt()}% match with Earth's field here" +
                if (quality == Q.BAD) " — move slowly and watch this bar" else ""
        } else {
            meter.fraction = 0f; meter.barColor = Color.GRAY
            meterTv.text = if (magSensor == null) "This phone has no magnetometer." else "Magnetic field check: measuring…"
        }
        meter.invalidate()

        val raw = currentHeading()
        if (raw == null) {
            qView.qiblaRel = null; qView.sunRel = null; qView.ringColor = Color.parseColor("#D9D4C8"); qView.invalidate()
            smX = Double.NaN
            when {
                magSensor == null || rotSensor == null -> {
                    turnTv.text = "No compass in this phone"
                    setStatus("Use the web version's sun or wall lock instead.", RED, RED_SOFT)
                }
                !calibrated() -> {
                    turnTv.text = "Calibrate the compass"
                    setStatus("Wave the phone slowly in a figure‑8 a few times.", ORANGE, ORANGE_SOFT)
                }
                quality == Q.BAD -> {
                    turnTv.text = "Interference here"
                    setStatus("⚠ Something nearby is bending the magnetic field. Walk 1–2 m away from metal (cars, desks, steel doors or pillars, speakers, laptops). When the bar turns green, the spot is clean.", RED, RED_SOFT)
                }
                else -> {
                    turnTv.text = "Checking the magnetic field…"
                    setStatus("Hold the phone flat for a moment.", ORANGE, ORANGE_SOFT)
                }
            }
        } else {
            // light smoothing for a calm arrow
            val c = cos(Math.toRadians(raw)); val s = sin(Math.toRadians(raw))
            if (smX.isNaN()) { smX = c; smY = s } else { smX += 0.35 * (c - smX); smY += 0.35 * (s - smY) }
            val h = Astro.norm360(Math.toDegrees(atan2(smY, smX)))
            val diff = Astro.norm180(q - h)
            qView.qiblaRel = diff.toFloat()
            val (sunAz, sunAlt) = Astro.sun(now, lat, lon)
            qView.sunRel = if (sunAlt > 0 && sunAlt < 80) Astro.norm180(sunAz - h).toFloat() else null

            val holding = quality == Q.BAD || !calibrated()
            val ok = abs(diff) <= 2.0
            qView.ringColor = when { holding -> ORANGE; ok -> GREEN; else -> Color.parseColor("#D9D4C8") }
            qView.invalidate()
            turnTv.setTextColor(if (ok) GREEN else INK)
            turnTv.text = if (ok) "✓ You are facing the Qibla"
                else "Turn ${abs(diff).toInt()}° ${if (diff > 0) "right ↻" else "left ↺"}"
            if (ok && !aligned) qView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            aligned = ok

            val approx = !offsetFromGood && gameSensor != null
            when {
                !calibrated() -> setStatus("Holding the last good direction. Wave the phone in a figure‑8 to recalibrate the compass.", ORANGE, ORANGE_SOFT)
                quality == Q.BAD -> {
                    val mins = (now - lastGoodAt) / 60000
                    setStatus("Interference here — the compass is being ignored. The gyroscope is holding the last accurate direction." +
                        if (lastGoodAt == 0L || mins >= 2) " Step to a clean spot (green bar) to refresh." else "", ORANGE, ORANGE_SOFT)
                }
                approx -> setStatus("Approximate (small disturbance). Move a little away from metal until the bar is green for full accuracy.", ORANGE, ORANGE_SOFT)
                quality == Q.FAIR -> setStatus("✓ Accurate — using the last clean reading (small disturbance here now).", GREEN, GREEN_SOFT)
                else -> setStatus("✓ Accurate — magnetic field here is clean", GREEN, GREEN_SOFT)
            }
        }

        val dec = g?.declination ?: 0f
        infoTv.text = "Qibla: ${"%.1f".format(q)}° from true north · ${"%,d".format(Astro.distanceKm(lat, lon).toInt())} km to the Kaaba\n" +
            "Location: $locLabel (${"%.5f".format(lat)}, ${"%.5f".format(lon)})\n" +
            "True-north correction (WMM): ${if (dec >= 0) "+" else ""}${"%.1f".format(dec)}°\n" +
            "Hold the phone flat. The small sun on the ring shows where the real sun is — a quick check."
    }
}
