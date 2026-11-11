package com.aly.trueqibla

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
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
 *
 * Arabic (with right-to-left layout) is used automatically when the phone language is Arabic,
 * and the dark theme follows the phone's dark mode.
 */
class MainActivity : Activity(), SensorEventListener, LocationListener {

    private enum class Q { UNKNOWN, GOOD, FAIR, BAD }

    private lateinit var sm: SensorManager
    private lateinit var lm: LocationManager
    private var gameSensor: Sensor? = null
    private var rotSensor: Sensor? = null
    private var magSensor: Sensor? = null
    private var linSensor: Sensor? = null     // linear acceleration: used only to notice real steps
    private var vibrator: Vibrator? = null

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
    private enum class Src { NONE, FAIR, AVG, GOOD }   // where the current direction came from
    private var src = Src.NONE
    private val offsetFromGood get() = src == Src.GOOD
    private var noFixSince = 0L

    // indoor averaging: one "bucket" per second of usable readings, each a direction estimate
    private class Bucket(val x: Double, val y: Double, val w: Double, val b: Double, val dip: Double, val good: Boolean, val spot: Int)
    private val buckets = ArrayDeque<Bucket>()
    private var accX = 0.0
    private var accY = 0.0
    private var accW = 0.0
    private var accB = 0.0
    private var accDip = 0.0
    private var accN = 0
    private var accAllGood = true
    private var bucketStart = 0L
    private var avgSpots = 0
    // simple step detector: a new "spot" only counts after the user has really walked
    private var steps = 0
    private var lastStepAt = 0L
    private var accelHigh = false
    // averaging progress, for the "room average" bar
    private var avgCountP = 0.0     // enough readings?
    private var avgSpotP = 0.0      // from enough different spots?
    private var avgErrP = 0.0       // do they agree well enough?
    private var avgStdErr = Double.NaN
    private var goodSince = 0L
    private var fairSince = 0L
    private var lastGoodAt = 0L
    private var lastFuse = 0L
    private var quality = Q.UNKNOWN
    private var aligned = false
    private var smX = Double.NaN
    private var smY = Double.NaN

    // vibration guidance
    private var vibOn = true
    private var lastPulse = 0L

    // views
    private lateinit var qView: QiblaView
    private lateinit var turnTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var meter: MeterView
    private lateinit var meterTv: TextView
    private lateinit var infoTv: TextView
    private lateinit var avgTv: TextView
    private lateinit var avgMeter: MeterView
    private lateinit var mainView: LinearLayout
    private lateinit var settingsView: LinearLayout
    private lateinit var scroll: ScrollView

    // settings
    private val prefs by lazy { getSharedPreferences("qibla", MODE_PRIVATE) }
    private var method = "smart"        // smart | strict | average
    private var detailed = false        // simple | detailed view

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 60) }
    }

    // ---------------------------------------------------------------- language + theme

    /** Language chosen inside the app (first start: follows the phone). */
    private val ar: Boolean by lazy {
        val saved = getSharedPreferences("qibla", MODE_PRIVATE).getString("lang", null)
        if (saved != null) saved == "ar" else resources.configuration.locales[0].language == "ar"
    }
    private fun t(en: String, arabic: String) = if (ar) arabic else en
    private fun f1(x: Double) = String.format(Locale.US, "%.1f", x)
    private fun f5(x: Double) = String.format(Locale.US, "%.5f", x)

    /** Theme chosen in Settings: system (default) / light / dark. */
    private val dark: Boolean by lazy {
        when (prefs.getString("theme", "system")) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }
    private val BG by lazy { Color.parseColor(if (dark) "#0F1513" else "#F4F1EA") }
    private val INK by lazy { Color.parseColor(if (dark) "#E9EEEB" else "#1D2421") }
    private val MUTED by lazy { Color.parseColor(if (dark) "#9AA7A1" else "#68716C") }
    private val GREEN by lazy { Color.parseColor(if (dark) "#3CC88F" else "#127A52") }
    private val GREEN_SOFT by lazy { Color.parseColor(if (dark) "#16342A" else "#E2F2EA") }
    private val ORANGE by lazy { Color.parseColor(if (dark) "#E7A15B" else "#B5621A") }
    private val ORANGE_SOFT by lazy { Color.parseColor(if (dark) "#3A2A18" else "#FBEEDD") }
    private val RED by lazy { Color.parseColor(if (dark) "#EF7B7B" else "#B23A3A") }
    private val RED_SOFT by lazy { Color.parseColor(if (dark) "#3A1D1D" else "#F8E3E3") }
    private val RING by lazy { Color.parseColor(if (dark) "#2A3531" else "#D9D4C8") }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        gameSensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        magSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        linSensor = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        vibrator = if (Build.VERSION.SDK_INT >= 31)
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        else getSystemService(VIBRATOR_SERVICE) as? Vibrator

        vibOn = prefs.getBoolean("vib", true)
        method = prefs.getString("method", "smart") ?: "smart"
        detailed = prefs.getString("view", "simple") == "detailed"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(BG)
            layoutDirection = if (ar) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = t("True Qibla", "القبلة الدقيقة"); textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(INK)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = t("⚙ Settings", "⚙ الإعدادات")
            textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(GREEN)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(1), GREEN); cornerRadius = dp(18).toFloat() }
            isClickable = true
            setOnClickListener { showSettings(true) }
        })
        root.addView(header)

        qView = QiblaView(this).apply {
            faceColor = Color.parseColor(if (dark) "#121A17" else "#FBFAF7")
            tickColor = Color.parseColor(if (dark) "#3A4743" else "#B8B2A5")
            markerColor = INK
            arrowColor = GREEN
            ringColor = RING
        }
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

        avgTv = TextView(this).apply { textSize = 13f; setTextColor(MUTED) }
        root.addView(avgTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        avgMeter = MeterView(this).apply { trackColor = RING }
        root.addView(avgMeter, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

        meterTv = TextView(this).apply { textSize = 13f; setTextColor(MUTED) }
        root.addView(meterTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        meter = MeterView(this).apply { trackColor = RING }
        root.addView(meter, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

        infoTv = TextView(this).apply { textSize = 13f; setTextColor(MUTED); setLineSpacing(0f, 1.15f) }
        root.addView(infoTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })

        mainView = root
        settingsView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(BG)
            layoutDirection = if (ar) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            visibility = View.GONE
        }
        buildSettings()
        val both = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(mainView); addView(settingsView) }
        scroll = ScrollView(this).apply { addView(both); setBackgroundColor(BG) }
        setContentView(scroll)
        if (savedInstanceState?.getBoolean("settingsOpen", false) == true) showSettings(true)

        // show the last saved position immediately (works offline); GPS refines it
        if (prefs.contains("lat")) setLocation(prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lon", 0f).toDouble(), 0.0, -1f,
            t("last known position", "آخر موقع معروف"))
    }

    // ---------------------------------------------------------------- settings screen

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("settingsOpen", settingsView.visibility == View.VISIBLE)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (settingsView.visibility == View.VISIBLE) showSettings(false) else super.onBackPressed()
    }

    private fun showSettings(show: Boolean) {
        settingsView.visibility = if (show) View.VISIBLE else View.GONE
        mainView.visibility = if (show) View.GONE else View.VISIBLE
        scroll.scrollTo(0, 0)
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(MUTED)
        setPadding(dp(4), dp(18), dp(4), dp(6))
    }

    /** A group of choices; the selected one is highlighted. Each option: key, title, optional description. */
    private fun choiceGroup(title: String, options: List<Triple<String, String, String?>>, current: String, onPick: (String) -> Unit) {
        settingsView.addView(sectionTitle(title))
        for ((key, label, desc) in options) {
            val selected = key == current
            val row = TextView(this).apply {
                text = (if (selected) "●  " else "○  ") + label + (if (desc != null) "\n" + desc else "")
                textSize = 15f
                setTextColor(if (selected) GREEN else INK)
                if (selected) setTypeface(typeface, Typeface.BOLD)
                setLineSpacing(0f, 1.1f)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    setColor(if (selected) GREEN_SOFT else Color.TRANSPARENT)
                    setStroke(dp(1), if (selected) GREEN else RING); cornerRadius = dp(12).toFloat()
                }
                isClickable = true
                setOnClickListener { if (!selected) onPick(key) }
            }
            settingsView.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(6) })
        }
    }

    private fun buildSettings() {
        settingsView.removeAllViews()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = t("Settings", "الإعدادات"); textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(INK)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(TextView(this).apply {
            text = t("✓ Done", "✓ تم"); textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(GREEN)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(1), GREEN); cornerRadius = dp(18).toFloat() }
            isClickable = true
            setOnClickListener { showSettings(false) }
        })
        settingsView.addView(top)

        choiceGroup(t("METHOD", "طريقة التحديد"), listOf(
            Triple("smart", t("Smart (recommended)", "ذكي (موصى به)"),
                t("Uses a clean reading when there is one; indoors it averages readings around the room. Shows a quick approximate arrow while it works.",
                  "يستخدم القراءة النظيفة إن وُجدت، وفي الداخل يدمج القراءات من أنحاء الغرفة. يعرض سهمًا تقريبيًا سريعًا أثناء العمل.")),
            Triple("strict", t("Strict", "صارم"),
                t("Shows the arrow only after finding a clean spot. Slower indoors, but never approximate.",
                  "لا يعرض السهم إلا بعد إيجاد مكان نظيف. أبطأ في الداخل، لكنه ليس تقريبيًا أبدًا.")),
            Triple("average", t("Room average", "متوسط الغرفة"),
                t("Always combines several readings, even when one spot looks clean. Most robust inside buildings.",
                  "يدمج دائمًا عدة قراءات حتى لو بدا المكان نظيفًا. الأكثر ثباتًا داخل المباني."))
        ), method) { k ->
            method = k; prefs.edit().putString("method", k).apply(); resetFix(); buildSettings()
        }

        choiceGroup(t("LANGUAGE", "اللغة"), listOf(
            Triple("en", "English", null), Triple("ar", "العربية", null)
        ), if (ar) "ar" else "en") { k -> prefs.edit().putString("lang", k).apply(); recreate() }

        choiceGroup(t("THEME", "المظهر"), listOf(
            Triple("system", t("Same as phone", "مثل الهاتف"), null),
            Triple("light", t("Light", "فاتح"), null),
            Triple("dark", t("Dark", "داكن"), null)
        ), prefs.getString("theme", "system") ?: "system") { k -> prefs.edit().putString("theme", k).apply(); recreate() }

        choiceGroup(t("VIEW", "طريقة العرض"), listOf(
            Triple("simple", t("Simple", "بسيط"), t("Arrow and status only.", "السهم والحالة فقط.")),
            Triple("detailed", t("Detailed", "مفصّل"), t("Also shows the magnetic field check, coordinates and the true-north correction.",
                                                         "يعرض أيضًا فحص المجال المغناطيسي والإحداثيات وتصحيح الشمال الحقيقي."))
        ), if (detailed) "detailed" else "simple") { k ->
            detailed = k == "detailed"; prefs.edit().putString("view", k).apply(); buildSettings()
        }

        choiceGroup(t("VIBRATION GUIDANCE", "التوجيه بالاهتزاز"), listOf(
            Triple("on", t("On", "مُفعّل"), t("Ticks get faster as you turn toward the Qibla, then a double buzz.",
                                              "نبضات تتسارع كلما اقتربت من القبلة، ثم اهتزازة مزدوجة.")),
            Triple("off", t("Off", "متوقف"), null)
        ), if (vibOn) "on" else "off") { k ->
            vibOn = k == "on"; prefs.edit().putBoolean("vib", vibOn).apply(); if (vibOn) pulse(40); buildSettings()
        }

        // About
        settingsView.addView(sectionTitle(t("ABOUT", "حول التطبيق")))
        val ver = try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            "${pi.versionName} (build $code)"
        } catch (_: Exception) { "" }
        settingsView.addView(TextView(this).apply {
            text = t("True Qibla $ver\n\nPoints to the Kaaba using the great-circle direction from your GPS position, corrected to true north. " +
                       "The gyroscope keeps the arrow steady, and the magnetic field is checked against Earth's expected field at your location, " +
                       "so the app knows when nearby metal makes the compass unreliable.\n\nFree and open source (MIT License). No ads, no tracking.",
                     "القبلة الدقيقة $ver\n\nيشير إلى الكعبة باتجاه الدائرة العظمى من موقعك عبر GPS، مع التصحيح إلى الشمال الحقيقي. " +
                       "يحافظ الجيروسكوب على ثبات السهم، ويُقارَن المجال المغناطيسي بمجال الأرض المتوقع في موقعك، " +
                       "فيعرف التطبيق متى تجعل المعادن القريبة البوصلة غير موثوقة.\n\nمجاني ومفتوح المصدر (رخصة MIT). بلا إعلانات ولا تتبّع.")
            textSize = 14f; setTextColor(INK); setLineSpacing(0f, 1.15f); setPadding(dp(4), 0, dp(4), 0)
        })
        settingsView.addView(TextView(this).apply {
            text = t("✉ Contact: alysherif1309@gmail.com", "✉ للتواصل: alysherif1309@gmail.com")
            textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(GREEN); setPadding(dp(4), dp(14), dp(4), dp(4))
            isClickable = true
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:alysherif1309@gmail.com?subject=" + Uri.encode("True Qibla feedback"))))
                } catch (_: Exception) {}
            }
        })
        settingsView.addView(TextView(this).apply {
            text = t("Source code on GitHub ↗", "الشيفرة المصدرية على GitHub ↗")
            textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(GREEN); setPadding(dp(4), dp(14), dp(4), dp(8))
            isClickable = true
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/AliSherif1309/TrueQibla-Android"))) } catch (_: Exception) {}
            }
        })
    }

    /** Forget the current direction (e.g. after changing the method) and find it again. */
    private fun resetFix() {
        offset = null; src = Src.NONE; buckets.clear(); goodSince = 0L; fairSince = 0L; noFixSince = 0L; smX = Double.NaN
        avgCountP = 0.0; avgSpotP = 0.0; avgErrP = 0.0; avgStdErr = Double.NaN; avgSpots = 0
    }

    private fun pulse(ms: Long) {
        try { vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        gameSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        rotSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
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
        val src = if (l.provider == LocationManager.GPS_PROVIDER) "GPS" else t("Network", "الشبكة")
        val label = "$src ±${l.accuracy.toInt()} ${t("m", "م")}"
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
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val ax = ev.values[0]; val ay = ev.values[1]; val az = ev.values[2]
                val m = sqrt((ax * ax + ay * ay + az * az).toDouble())
                if (!accelHigh && m > 1.6 && now - lastStepAt > 350) { steps++; lastStepAt = now; accelHigh = true }
                else if (m < 0.8) accelHigh = false
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

        // "Room average" never trusts a single spot, so a clean reading is treated like any other sample.
        val usableSingle = quality == Q.FAIR || (quality == Q.GOOD && method == "average")
        if (quality == Q.GOOD && calibrated() && method != "average") {
            if (goodSince == 0L) goodSince = now
            fairSince = 0L
            val o = offset
            if (o == null || src != Src.GOOD) {
                if (now - goodSince > 800) { offset = d; src = Src.GOOD }
            } else {
                offset = Astro.norm180(o + follow * Astro.norm180(d - o))
            }
            lastGoodAt = now
        } else {
            goodSince = 0L
            // Quick first arrow: accept a "fair" reading after 2 s, clearly labelled approximate.
            if (method == "strict") {
                fairSince = 0L                                       // strict: wait for a clean spot only
            } else if (usableSingle && calibrated() && src == Src.NONE) {
                if (fairSince == 0L) fairSince = now
                if (now - fairSince > 2000) { offset = d; src = Src.FAIR }
            } else if (usableSingle && calibrated() && src == Src.FAIR) {
                val o = offset ?: d
                offset = Astro.norm180(o + follow * Astro.norm180(d - o))
            } else if (!usableSingle) fairSince = 0L
            // BAD: ignore the compass completely; the gyroscope keeps the last good direction.
        }
        collectForAverage(d, now)
    }

    /**
     * Indoors the field is slightly bent almost everywhere, but differently at each spot.
     * Because the gyroscope ties every reading to the same reference frame, estimates taken at
     * different spots can be averaged: their errors point in different directions and largely cancel.
     * Readings are weighted by how well the field matches Earth's field there.
     */
    private fun collectForAverage(d: Double, now: Long) {
        val fe = fieldError()
        if (fe != null && (quality == Q.GOOD || quality == Q.FAIR) && calibrated()) {
            val (se, de) = fe
            val w = 1.0 / ((se / 0.07) * (se / 0.07) + (de / 4.0) * (de / 4.0) + 0.2)
            accX += w * cos(Math.toRadians(d)); accY += w * sin(Math.toRadians(d)); accW += w
            accB += bStrength; accDip += bDip; accN++
            if (quality != Q.GOOD) accAllGood = false
        }
        if (bucketStart == 0L) bucketStart = now
        if (now - bucketStart < 1000) return
        bucketStart = now
        if (accN >= 5 && accW > 0) {
            val r = sqrt(accX * accX + accY * accY)
            // which spot is this? Every ~2 real steps is a new spot. Without a motion sensor, fall back to
            // distinct field values (less reliable: turning the phone can also change them).
            val bAvg = accB / accN; val dAvg = accDip / accN
            val spot = if (linSensor != null) steps / 2 else (bAvg / 1.5).toInt() * 1000 + (dAvg / 1.0).toInt()
            if (r > 0) buckets.addLast(Bucket(accX / r, accY / r, accW / accN, bAvg, dAvg, accAllGood, spot))
            while (buckets.size > 40) buckets.removeFirst()
        }
        accX = 0.0; accY = 0.0; accW = 0.0; accB = 0.0; accDip = 0.0; accN = 0; accAllGood = true
        if (method == "strict" || src == Src.GOOD || buckets.isEmpty()) return
        // 1) one direction estimate per spot (standing longer at one spot does not count more)
        class SpotEst(val x: Double, val y: Double, val w: Double)
        val perSpot = buckets.groupBy { it.spot }.values.map { g ->
            var gx = 0.0; var gy = 0.0; var gw = 0.0
            for (b in g) { gx += b.w * b.x; gy += b.w * b.y; gw += b.w }
            val r = sqrt(gx * gx + gy * gy).coerceAtLeast(1e-9)
            SpotEst(gx / r, gy / r, gw / g.size)
        }
        // 2) combine the spots; their errors point in different directions and largely cancel
        var sx = 0.0; var sy = 0.0; var sw = 0.0
        for (e in perSpot) { sx += e.w * e.x; sy += e.w * e.y; sw += e.w }
        val n = perSpot.size
        val rbar = (sqrt(sx * sx + sy * sy) / sw).coerceIn(1e-9, 1.0)
        val spread = Math.toDegrees(sqrt(-2.0 * kotlin.math.ln(rbar)))
        val stdErr = if (n >= 2) spread / sqrt(n.toDouble()) else Double.NaN
        avgSpots = n
        avgStdErr = stdErr
        avgCountP = 1.0
        avgSpotP = min(1.0, n / 5.0)
        avgErrP = if (stdErr.isNaN()) 0.0 else if (stdErr <= 2.0) 1.0 else 2.0 / stdErr
        if (n >= 5 && stdErr < 2.0) {
            offset = Astro.norm180(Math.toDegrees(atan2(sy, sx)))
            src = Src.AVG
        }
    }

    /** Number of distinct spots visited (for the progress hints). */
    private fun spotsSeen(): Int = buckets.map { it.spot }.distinct().size

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

    /** Vibration guidance: short ticks that get faster as you turn toward the Qibla, a double buzz on arrival. */
    private fun guide(diff: Double, trustworthy: Boolean, now: Long) {
        if (!vibOn || !trustworthy) return
        val a = abs(diff)
        if (a <= 2.0) {
            if (!aligned) { pulse(70); handler.postDelayed({ pulse(70) }, 160) }
        } else if (a < 40.0) {
            val interval = (110 + (a / 40.0) * 1100).toLong()     // ~1.2 s far away → ~0.15 s close
            if (now - lastPulse > interval) { pulse(15); lastPulse = now }
        }
    }

    /** "Room average" progress bar: how close the averaged direction is to being trusted, and what it still needs. */
    private fun renderAverageBar() {
        val relevant = method != "strict" && magSensor != null && gameSensor != null &&
            (src != Src.GOOD || method == "average")
        avgMeter.visibility = if (relevant) View.VISIBLE else View.GONE
        avgTv.visibility = avgMeter.visibility
        if (!relevant) return
        val n = spotsSeen()
        val p = if (n < 5) min(1.0, n / 5.0) * 0.8 else min(1.0, 0.8 + 0.2 * avgErrP)   // spots fill 80 %, agreement the rest
        val done = src == Src.AVG
        avgMeter.fraction = if (done) 1f else p.toFloat()
        avgMeter.barColor = if (done) GREEN else ORANGE
        avgMeter.invalidate()
        val pct = ((if (done) 1.0 else p) * 100).toInt()
        val acc = if (avgStdErr.isNaN()) "" else String.format(Locale.US, "%.1f", max(1.0, 2.0 * avgStdErr))
        avgTv.setTextColor(if (done) GREEN else MUTED)
        avgTv.text = when {
            done -> t("✓ Room average ready — from $n spots, about ±$acc° (keep walking to refine)",
                      "✓ متوسط الغرفة جاهز — من $n أماكن، بدقة ±$acc° تقريبًا (واصل المشي لتحسينها)")
            buckets.isEmpty() && quality == Q.BAD -> t("Room average: paused — too much interference right here. Move a little.",
                                                     "متوسط الغرفة: متوقف — التشويش قوي هنا. تحرّك قليلًا.")
            n < 5 -> t("Room average $pct% — walk 2–3 steps to another spot ($n of 5 spots)",
                       "متوسط الغرفة $pct٪ — امشِ خطوتين أو ثلاثًا إلى مكان آخر ($n من 5 أماكن)")
            else -> t("Room average $pct% — readings still differ, try 1–2 more spots",
                      "متوسط الغرفة $pct٪ — القراءات ما زالت مختلفة، جرّب مكانًا أو مكانين آخرين")
        }
    }

    private fun render() {
        val now = System.currentTimeMillis()
        if (lat.isNaN()) {
            qView.qiblaRel = null; qView.invalidate()
            turnTv.text = t("Finding your location…", "جارٍ تحديد موقعك…")
            setStatus(t("Turn on Location (GPS) and allow it for True Qibla. Being outdoors or near a window helps.",
                        "شغّل الموقع (GPS) واسمح للتطبيق باستخدامه. يساعد الوقوف في مكان مفتوح أو قرب نافذة."), ORANGE, ORANGE_SOFT)
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
            val pct = (frac * 100).toInt()
            meter.fraction = frac
            meter.barColor = when (quality) { Q.GOOD -> GREEN; Q.FAIR -> ORANGE; else -> RED }
            meterTv.text = t("Magnetic field check: $pct% match with Earth's field here", "فحص المجال المغناطيسي: تطابق $pct٪ مع مجال الأرض هنا") +
                if (quality == Q.BAD) t(" — move slowly and watch this bar", " — تحرّك ببطء وراقب هذا الشريط") else ""
        } else {
            meter.fraction = 0f; meter.barColor = MUTED
            meterTv.text = if (magSensor == null) t("This phone has no magnetometer.", "هذا الهاتف لا يحتوي على حسّاس مغناطيسي.")
                           else t("Magnetic field check: measuring…", "فحص المجال المغناطيسي: جارٍ القياس…")
        }
        meter.invalidate()
        val showMeter = detailed || quality != Q.GOOD || src != Src.GOOD
        meter.visibility = if (showMeter) View.VISIBLE else View.GONE
        meterTv.visibility = meter.visibility
        infoTv.visibility = if (detailed) View.VISIBLE else View.GONE
        renderAverageBar()

        val raw = currentHeading()
        if (raw != null) noFixSince = 0L else if (noFixSince == 0L) noFixSince = now
        if (raw == null) {
            qView.qiblaRel = null; qView.sunRel = null; qView.ringColor = RING; qView.invalidate()
            smX = Double.NaN
            aligned = false
            when {
                magSensor == null || rotSensor == null -> {
                    turnTv.text = t("No compass in this phone", "لا توجد بوصلة في هذا الهاتف")
                    setStatus(t("Use the web version's sun or wall lock instead.", "استخدم نسخة الويب (القفل بالشمس أو بالجدار) بدلًا من ذلك."), RED, RED_SOFT)
                }
                !calibrated() -> {
                    turnTv.text = t("Calibrate the compass", "عايِر البوصلة")
                    setStatus(t("Wave the phone slowly in a figure‑8 a few times.", "حرّك الهاتف ببطء على شكل الرقم 8 عدة مرات."), ORANGE, ORANGE_SOFT)
                }
                quality == Q.BAD && now - noFixSince > 8000 -> {
                    turnTv.text = t("Interference here", "يوجد تشويش هنا")
                    setStatus(t("⚠ Strong interference in this area. Try: the middle of the room, near a window, away from desks and appliances, phone at chest height. Or start outside / by a window and walk in — the arrow keeps its direction.",
                                "⚠ تشويش قوي في هذا المكان. جرّب: وسط الغرفة، أو قرب نافذة، بعيدًا عن المكاتب والأجهزة، والهاتف بمستوى الصدر. أو ابدأ في الخارج أو قرب نافذة ثم ادخل — السهم يحتفظ باتجاهه."), RED, RED_SOFT)
                }
                quality == Q.BAD -> {
                    turnTv.text = t("Interference here", "يوجد تشويش هنا")
                    setStatus(t("⚠ Something nearby is bending the magnetic field. Walk 1–2 m away from metal (cars, desks, steel doors or pillars, speakers, laptops). When the bar turns green, the spot is clean.",
                                "⚠ شيء قريب يُشوّه المجال المغناطيسي. ابتعد مترًا أو مترين عن المعادن (السيارات، المكاتب، الأبواب أو الأعمدة الحديدية، السماعات، الحواسيب). عندما يصبح الشريط أخضر يكون المكان نظيفًا."), RED, RED_SOFT)
                }
                method == "strict" && quality == Q.FAIR -> {
                    turnTv.text = t("Waiting for a clean spot…", "بانتظار مكان نظيف…")
                    setStatus(t("Strict mode: the arrow appears only where the field is clean (green bar). Move slowly away from metal, or toward a window.",
                                "الوضع الصارم: يظهر السهم فقط حيث يكون المجال نظيفًا (شريط أخضر). تحرّك ببطء بعيدًا عن المعادن أو نحو نافذة."), ORANGE, ORANGE_SOFT)
                }
                else -> {
                    turnTv.text = t("Checking the magnetic field…", "جارٍ فحص المجال المغناطيسي…")
                    setStatus(t("Hold the phone flat for a moment.", "أمسك الهاتف بشكل مستوٍ للحظة."), ORANGE, ORANGE_SOFT)
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
            qView.ringColor = when { holding -> ORANGE; ok -> GREEN; else -> RING }
            qView.invalidate()
            turnTv.setTextColor(if (ok) GREEN else INK)
            val deg = abs(diff).toInt()
            turnTv.text = when {
                ok -> t("✓ You are facing the Qibla", "✓ أنت متجه إلى القبلة")
                diff > 0 -> t("Turn $deg° right ↻", "استدر $deg° يمينًا ↻")
                else -> t("Turn $deg° left ↺", "استدر $deg° يسارًا ↺")
            }
            guide(diff, offsetFromGood || gameSensor == null || !holding, now)
            aligned = ok

            val approx = src == Src.FAIR && gameSensor != null
            val spots = spotsSeen()
            when {
                !calibrated() -> setStatus(t("Holding the last good direction. Wave the phone in a figure‑8 to recalibrate the compass.",
                                             "يتم الاحتفاظ بآخر اتجاه دقيق. حرّك الهاتف على شكل الرقم 8 لإعادة معايرة البوصلة."), ORANGE, ORANGE_SOFT)
                quality == Q.BAD -> {
                    val mins = (now - lastGoodAt) / 60000
                    setStatus(t("Interference here — the compass is being ignored. The gyroscope is holding the last accurate direction.",
                                "يوجد تشويش هنا — تم تجاهل البوصلة، والجيروسكوب يحتفظ بآخر اتجاه دقيق.") +
                        if (lastGoodAt == 0L || mins >= 2) t(" Step to a clean spot (green bar) to refresh.", " انتقل إلى مكان نظيف (شريط أخضر) للتحديث.") else "",
                        ORANGE, ORANGE_SOFT)
                }
                approx -> setStatus(t("Approximate. Walk slowly around the room with the phone flat — readings from different spots are averaged for accuracy (${min(spots, 5)} of 5 spots).",
                                      "تقريبي. امشِ ببطء في الغرفة والهاتف مستوٍ — يتم دمج القراءات من أماكن مختلفة لزيادة الدقة (${min(spots, 5)} من 5 أماكن)."), ORANGE, ORANGE_SOFT)
                src == Src.AVG -> setStatus(t("✓ Accurate — averaged from $avgSpots readings around this room",
                                              "✓ دقيق — متوسط $avgSpots قراءة من أماكن مختلفة في هذه الغرفة"), GREEN, GREEN_SOFT)
                quality == Q.FAIR -> setStatus(t("✓ Accurate — using the last clean reading (small disturbance here now).",
                                                 "✓ دقيق — باستخدام آخر قراءة نظيفة (يوجد تشويش بسيط هنا الآن)."), GREEN, GREEN_SOFT)
                else -> setStatus(t("✓ Accurate — magnetic field here is clean", "✓ دقيق — المجال المغناطيسي هنا نظيف"), GREEN, GREEN_SOFT)
            }
        }

        val dec = (g?.declination ?: 0f).toDouble()
        val km = String.format(Locale.US, "%,d", Astro.distanceKm(lat, lon).toInt())
        val decTxt = (if (dec >= 0) "+" else "") + f1(dec) + "°"
        infoTv.text = t(
            "Qibla: ${f1(q)}° from true north · $km km to the Kaaba\n" +
            "Location: $locLabel (${f5(lat)}, ${f5(lon)})\n" +
            "True-north correction (WMM): $decTxt\n" +
            "Hold the phone flat. The small sun on the ring shows where the real sun is — a quick check.",
            "القبلة: ${f1(q)}° من الشمال الحقيقي · $km كم إلى الكعبة\n" +
            "الموقع: $locLabel (${f5(lat)}, ${f5(lon)})\n" +
            "تصحيح الشمال الحقيقي (WMM): $decTxt\n" +
            "أمسك الهاتف بشكل مستوٍ. الشمس الصغيرة على الحلقة تُظهر مكان الشمس الحقيقية — للتحقق السريع."
        )
    }
}
