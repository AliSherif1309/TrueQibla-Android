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
import android.os.SystemClock
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
 *     may be bending the field: the compass is ignored and the gyroscope holds a recent reference
 *     for at most 30 seconds. Passing the check does not prove azimuth accuracy.
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
    private var accelSensor: Sensor? = null
    private val gravity = FloatArray(3)
    private var accelAt = 0L
    private var fieldAt = 0L
    private var gameAt = 0L
    private var anchorAt = 0L
    private var locAt = 0L
    private var permissionAsked = false
    private var rotAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var lastDisplayRotation = -1
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
    private var gameHeading: Double? = null
    private var magHeading: Double? = null  // magnetic north based (declination not yet added)
    private var magAt = 0L
    private var magAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var bStrength = Double.NaN      // µT, smoothed
    private var bDip = Double.NaN           // degrees (+ = downward), smoothed
    private var latestStrength = Double.NaN
    private var latestDip = Double.NaN

    // fusion
    private var offset: Double? = null      // true heading − gyro heading
    private enum class Src { NONE, FAIR, AVG, GOOD }   // where the current direction came from
    private var src = Src.NONE
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
    private lateinit var sunCard: TextView
    private lateinit var globe: GlobeView
    private lateinit var matBtn: TextView
    private lateinit var matOverlay: FrameLayout
    private lateinit var matDial: QiblaView
    private lateinit var matTv: TextView
    private var matMode = false

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
            ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        if (rotSensor == null) accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
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

        // "sun over the Kaaba" card: only shown around those two days a year
        sunCard = TextView(this).apply {
            textSize = 14.5f; setTextColor(INK); setLineSpacing(0f, 1.15f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (dark) "#3A3015" else "#FFF4D6")); setStroke(dp(1), Color.parseColor("#D4A63A"))
                cornerRadius = dp(12).toFloat()
            }
            visibility = View.GONE
        }
        root.addView(sunCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

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

        matBtn = TextView(this).apply {
            text = t("🕌 Prayer-mat mode", "🕌 وضع سجادة الصلاة")
            textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(GREEN); gravity = Gravity.CENTER
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(1), GREEN); cornerRadius = dp(12).toFloat() }
            isClickable = true
            setOnClickListener { setMatMode(true) }
        }
        root.addView(matBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })

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
        globe = GlobeView(this).apply {
            if (dark) { oceanColor = Color.parseColor("#14262A"); gridColor = Color.parseColor("#22393D"); edgeColor = Color.parseColor("#2F4B50") }
            pathColor = GREEN; textColor = MUTED
        }
        root.addView(globe, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })

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

        // prayer-mat mode: dark full screen, dim live arrow, no vibration; tap anywhere to exit
        matDial = QiblaView(this).apply {
            faceColor = Color.BLACK; tickColor = Color.parseColor("#1E2A26"); markerColor = Color.parseColor("#3A4743")
            arrowColor = Color.parseColor("#2E8F66"); ringColor = Color.parseColor("#1A2420")
        }
        matTv = TextView(this).apply {
            textSize = 15f; setTextColor(Color.parseColor("#55625C")); gravity = Gravity.CENTER
        }
        val matCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(matDial, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(matTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) })
            addView(TextView(this@MainActivity).apply {
                text = t("Tap anywhere to exit", "اضغط في أي مكان للخروج"); textSize = 13f
                setTextColor(Color.parseColor("#3A4743")); gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        matOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
            setOnClickListener { setMatMode(false) }
            addView(matCol, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(matOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })
        if (savedInstanceState?.getBoolean("settingsOpen", false) == true) showSettings(true)

        // show the last saved position immediately (works offline); GPS refines it
        if (prefs.contains("lat")) setLocation(if (prefs.contains("latBits")) Double.fromBits(prefs.getLong("latBits", 0L)) else prefs.getFloat("lat", 0f).toDouble(),
            if (prefs.contains("lonBits")) Double.fromBits(prefs.getLong("lonBits", 0L)) else prefs.getFloat("lon", 0f).toDouble(), 0.0, -1f,
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
        when {
            matMode -> setMatMode(false)
            settingsView.visibility == View.VISIBLE -> showSettings(false)
            else -> super.onBackPressed()
        }
    }

    private fun setMatMode(on: Boolean) {
        matMode = on
        matOverlay.visibility = if (on) View.VISIBLE else View.GONE
        val lp = window.attributes
        lp.screenBrightness = if (on) 0.03f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    private fun fmtEvent(ms: Long): String {
        val loc = if (ar) Locale.forLanguageTag("ar") else Locale.US
        return DateTimeFormatter.ofPattern("EEEE d MMMM yyyy · HH:mm", loc).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
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
                t("Requires passing field checks. Every phone compass still has uncertainty.",
                  "يتطلب اجتياز فحوص المجال. تبقى بوصلة الهاتف عرضة للخطأ.")),
            Triple("average", t("Room average", "متوسط الغرفة"),
                t("Combines several readings. Shared magnetic bias can remain; results are approximate.",
                  "يدمج عدة قراءات. قد يبقى انحياز مغناطيسي مشترك؛ النتيجة تقريبية."))
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

        // Sun over the Kaaba
        val events = Astro.nextSunOverKaaba(System.currentTimeMillis())
        settingsView.addView(sectionTitle(t("SUN OVER THE KAABA", "الشمس فوق الكعبة")))
        settingsView.addView(TextView(this).apply {
            text = t("Twice a year the sun is directly over the Kaaba. At that minute every vertical shadow, wherever the sun is up, points approximately away from the Qibla — a natural check that needs no phone.\n\nNext:\n",
                     "مرتين في السنة تكون الشمس فوق الكعبة مباشرة. في تلك الدقيقة يشير ظل أي شيء عمودي، حيثما كانت الشمس ظاهرة، بعكس اتجاه القبلة تقريبًا — طريقة طبيعية للتحقق لا تحتاج إلى هاتف.\n\nالموعد القادم:\n") +
                events.joinToString("\n") { "• " + fmtEvent(it) }
            textSize = 14f; setTextColor(INK); setLineSpacing(0f, 1.15f); setPadding(dp(4), 0, dp(4), dp(8))
        })
        choiceGroup(t("Reminder", "التذكير"), listOf(
            Triple("on", t("Remind me", "ذكّرني"), t("The evening before, and 15 minutes before.", "في المساء السابق، وقبل الموعد بـ 15 دقيقة.")),
            Triple("off", t("No reminder", "بدون تذكير"), null)
        ), if (Reminders.enabled(this)) "on" else "off") { k ->
            prefs.edit().putBoolean("remind", k == "on").apply()
            if (k == "on" && Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
            }
            Reminders.scheduleNext(this)
            buildSettings()
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
                       "but these checks cannot detect every magnetic disturbance.\n\nFree and open source (MIT License). No ads, no tracking.",
                     "القبلة الدقيقة $ver\n\nيشير إلى الكعبة باتجاه الدائرة العظمى من موقعك عبر GPS، مع التصحيح إلى الشمال الحقيقي. " +
                       "يحافظ الجيروسكوب على ثبات السهم، ويُقارَن المجال المغناطيسي بمجال الأرض المتوقع في موقعك، " +
                       "لكن هذه الفحوص لا تكشف كل تشويش مغناطيسي.\n\nمجاني ومفتوح المصدر (رخصة MIT). بلا إعلانات ولا تتبّع.")
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
        anchorAt = 0L; lastGoodAt = 0L; lastFuse = 0L
        accX = 0.0; accY = 0.0; accW = 0.0; accB = 0.0; accDip = 0.0; accN = 0
        accAllGood = true; bucketStart = 0L; steps = 0; accelHigh = false
        avgCountP = 0.0; avgSpotP = 0.0; avgErrP = 0.0; avgStdErr = Double.NaN; avgSpots = 0
    }

    private fun pulse(ms: Long) {
        try { vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
    }

    private fun register(type: Int): Sensor? = sm.getDefaultSensor(type)?.takeIf {
        sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onResume() {
        super.onResume()
        resetFix()
        gameHeading = null; magHeading = null; haveUp = false
        gameAt = 0L; magAt = 0L; fieldAt = 0L; accelAt = 0L
        bStrength = Double.NaN; bDip = Double.NaN; quality = Q.UNKNOWN
        latestStrength = Double.NaN; latestDip = Double.NaN
        magAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        rotAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        gameSensor = register(Sensor.TYPE_GAME_ROTATION_VECTOR)
        rotSensor = register(Sensor.TYPE_ROTATION_VECTOR) ?: register(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        accelSensor = if (rotSensor == null) register(Sensor.TYPE_ACCELEROMETER) else null
        magSensor = register(Sensor.TYPE_MAGNETIC_FIELD)
        linSensor = register(Sensor.TYPE_LINEAR_ACCELERATION)
        startLocation()
        Reminders.scheduleNext(this)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
        try { lm.removeUpdates(this) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        vibrator?.cancel()
    }

    // ---------------------------------------------------------------- location

    private fun startLocation() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            locAt = 0L
            if (!permissionAsked) {
                permissionAsked = true
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            }
            return
        }
        try { lm.removeUpdates(this) } catch (_: SecurityException) {}
        for (prov in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (prov == LocationManager.GPS_PROVIDER && !fine) continue
            try {
                lm.getLastKnownLocation(prov)?.let { onLocationChanged(it) }
                // Subscribe even while disabled, so enabling Location later recovers without a restart.
                lm.requestLocationUpdates(prov, 2000L, 0f, this)
            } catch (_: SecurityException) {
                // Permission may be revoked between the check and this call.
            } catch (_: IllegalArgumentException) {
                // A missing provider must not prevent trying the other provider.
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                prefs.edit().putBoolean("remind", false).apply()   // can't notify without permission
                Reminders.scheduleNext(this)
                buildSettings()
            }
            return
        }
        if (requestCode == 1 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) startLocation()
    }

    override fun onLocationChanged(l: Location) {
        val now = SystemClock.elapsedRealtime()
        val at = l.elapsedRealtimeNanos / 1_000_000L
        if (!Reliability.coordinates(l.latitude, l.longitude) || !l.hasAccuracy() ||
            !Reliability.acceptLocation(now - at, l.accuracy, if (locAt == 0L) Long.MAX_VALUE else now - locAt, locAcc)) return
        val source = if (l.provider == LocationManager.GPS_PROVIDER) "GPS" else t("Network", "الشبكة")
        if (lat.isFinite() && (abs(lat - l.latitude) > 0.01 || abs(lon - l.longitude) > 0.01)) resetFix()
        locAt = at
        setLocation(l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else 0.0,
            l.accuracy, "$source ±${l.accuracy.toInt()} ${t("m", "م")}")
        // Keep foreground updates running: the user may travel after the first accurate fix.
    }

    // Needed on Android 8–10, where these are not default methods
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun setLocation(la: Double, lo: Double, altM: Double, acc: Float, label: String) {
        if (!Reliability.coordinates(la, lo)) return
        lat = la; lon = lo; locAcc = acc; locLabel = label
        val altitude = if (altM.isFinite()) altM.coerceIn(-12_000.0, 100_000.0) else 0.0
        geo = GeomagneticField(la.toFloat(), lo.toFloat(), altitude.toFloat(), System.currentTimeMillis())
        getSharedPreferences("qibla", MODE_PRIVATE).edit()
            .putLong("latBits", la.toBits()).putLong("lonBits", lo.toBits())
            .putFloat("lat", la.toFloat()).putFloat("lon", lo.toFloat()).apply()
    }

    // ---------------------------------------------------------------- sensors

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) magAccuracy = accuracy
        if (sensor.type == rotSensor?.type) rotAccuracy = accuracy
    }

    @Suppress("DEPRECATION")
    private fun headingOf(r: FloatArray): Double? =
        Reliability.heading(r, windowManager.defaultDisplay.rotation)

    private fun fallbackHeading(now: Long, magnetic: FloatArray) {
        if (!Reliability.fresh(now, accelAt, 500L)) { magHeading = null; return }
        if (!SensorManager.getRotationMatrix(rmRot, null, gravity, magnetic)) {
            magHeading = null
            return
        }
        if (gameSensor == null) {
            up[0] = rmRot[6]; up[1] = rmRot[7]; up[2] = rmRot[8]; haveUp = true
        }
        magHeading = headingOf(rmRot); magAt = now
        rotAccuracy = magAccuracy
    }

    override fun onSensorChanged(ev: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        if (!Reliability.fresh(now, ev.timestamp / 1_000_000L, 500L)) return
        if (ev.values.any { !it.isFinite() }) return
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        if (lastDisplayRotation != rotation) { resetFix(); lastDisplayRotation = rotation }
        when (ev.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rmGame, ev.values)
                up[0] = rmGame[6]; up[1] = rmGame[7]; up[2] = rmGame[8]; haveUp = true
                if (gameAt != 0L && now - gameAt > Reliability.SENSOR_MAX_AGE_MS) resetFix()
                gameHeading = headingOf(rmGame); gameAt = now
                fuse(now)
            }
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                rotAccuracy = ev.accuracy
                // Optional estimated azimuth error is in radians; -1 means unavailable.
                if (ev.values.size >= 5 && ev.values[4] > Math.toRadians(15.0))
                    rotAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
                SensorManager.getRotationMatrixFromVector(rmRot, ev.values)
                if (gameSensor == null) { up[0] = rmRot[6]; up[1] = rmRot[7]; up[2] = rmRot[8]; haveUp = true }
                magHeading = headingOf(rmRot)
                magAt = now
                if (gameSensor == null) fuse(now)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val norm = sqrt(ev.values.take(3).sumOf { (it * it).toDouble() })
                if (norm !in 7.0..12.0) { accelAt = 0L; return }
                for (i in 0..2) gravity[i] = if (accelAt == 0L) ev.values[i] else gravity[i] + 0.2f * (ev.values[i] - gravity[i])
                accelAt = now
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val ax = ev.values[0]; val ay = ev.values[1]; val az = ev.values[2]
                val m = sqrt((ax * ax + ay * ay + az * az).toDouble())
                if (!accelHigh && m > 1.6 && now - lastStepAt > 350) { steps++; lastStepAt = now; accelHigh = true }
                else if (m < 0.8) accelHigh = false
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magAccuracy = ev.accuracy
                if (rotSensor == null) fallbackHeading(now, ev.values)
                if (!haveUp) return
                val bx = ev.values[0].toDouble(); val by = ev.values[1].toDouble(); val bz = ev.values[2].toDouble()
                val b = sqrt(bx * bx + by * by + bz * bz)
                if (b < 1.0) { fieldAt = 0L; return }
                fieldAt = now
                val vUp = bx * up[0] + by * up[1] + bz * up[2]
                val dip = Math.toDegrees(asin((-vUp / b).coerceIn(-1.0, 1.0)))
                latestStrength = b; latestDip = dip
                val k = 0.12
                bStrength = if (bStrength.isNaN()) b else bStrength + k * (b - bStrength)
                bDip = if (bDip.isNaN()) dip else bDip + k * (dip - bDip)
                if (rotSensor == null && gameSensor == null) fuse(now)
            }
        }
    }

    /** How far the measured field is from Earth's expected field here: 0 = perfect. */
    private fun fieldError(): Pair<Double, Double>? {
        val g = geo ?: return null
        if (!Reliability.fresh(SystemClock.elapsedRealtime(), fieldAt, Reliability.SENSOR_MAX_AGE_MS) ||
            bStrength.isNaN() || bDip.isNaN()) return null
        val expected = g.fieldStrength / 1000.0       // nT -> µT
        // An abrupt anomaly must not be hidden by the display smoothing filter.
        return Pair(max(abs(bStrength - expected), abs(latestStrength - expected)) / expected,
            max(abs(bDip - g.inclination), abs(latestDip - g.inclination)))
    }

    private fun evalQuality(): Q {
        val (se, de) = fieldError() ?: return Q.UNKNOWN
        if ((geo?.horizontalStrength ?: 0f) < 5000f) return Q.BAD
        return when {
            se < 0.07 && de < 4.0 -> Q.GOOD
            se < 0.15 && de < 8.0 -> Q.FAIR
            else -> Q.BAD
        }
    }

    // Matching strength and dip cannot prove azimuth accuracy or override calibration flags.
    private fun calibrated() = magAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM &&
        rotAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM

    private fun fuse(now: Long) {
        val g = geo ?: return
        if (offset != null && !Reliability.fresh(now, anchorAt, Reliability.HOLD_MAX_AGE_MS)) resetFix()
        val gh = gameHeading
        val mh = magHeading
        quality = evalQuality()
        if (gh == null || mh == null || !Reliability.fresh(now, magAt, 100L) ||
            !Reliability.fresh(now, gameAt, 100L)) return
        val d = Astro.norm180(Reliability.trueHeading(mh, g.declination.toDouble()) - gh)
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
        if (quality == Q.GOOD && calibrated() || src == Src.FAIR && usableSingle && calibrated()) anchorAt = now
        collectForAverage(d, now)
    }

    /**
     * Indoors the field is slightly bent almost everywhere, but differently at each spot.
     * Because the gyroscope ties every reading to the same reference frame, estimates taken at
     * different motion groups can be averaged. Shared magnetic bias does not cancel.
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
        val newBucket = accN >= 5 && accW > 0
        if (accN >= 5 && accW > 0) {
            val r = sqrt(accX * accX + accY * accY)
            // Motion groups are a heuristic, not proof of independent positions or errors.
            val bAvg = accB / accN; val dAvg = accDip / accN
            val spot = if (linSensor != null) steps / 2 else 0 // field changes alone cannot establish movement
            if (r > 0) buckets.addLast(Bucket(accX / r, accY / r, accW / accN, bAvg, dAvg, accAllGood, spot))
            while (buckets.size > 40) buckets.removeFirst()
        }
        accX = 0.0; accY = 0.0; accW = 0.0; accB = 0.0; accDip = 0.0; accN = 0; accAllGood = true
        if (!newBucket) return // Old averages must never refresh an expired reference.
        if (method == "strict" || src == Src.GOOD || buckets.isEmpty()) return
        // 1) one direction estimate per spot (standing longer at one spot does not count more)
        class SpotEst(val x: Double, val y: Double, val w: Double)
        val perSpot = buckets.groupBy { it.spot }.values.map { g ->
            var gx = 0.0; var gy = 0.0; var gw = 0.0
            for (b in g) { gx += b.w * b.x; gy += b.w * b.y; gw += b.w }
            val r = sqrt(gx * gx + gy * gy).coerceAtLeast(1e-9)
            SpotEst(gx / r, gy / r, gw / g.size)
        }
        // 2) combine motion groups; agreement measures precision, not absolute accuracy
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
            anchorAt = now
            offset = Astro.norm180(Math.toDegrees(atan2(sy, sx)))
            src = Src.AVG
        }
    }

    /** Number of distinct spots visited (for the progress hints). */
    private fun spotsSeen(): Int = buckets.map { it.spot }.distinct().size

    private fun currentHeading(): Double? {
        val now = SystemClock.elapsedRealtime()
        quality = evalQuality()
        if (!Reliability.fresh(now, locAt, Reliability.LOCATION_MAX_AGE_MS) ||
            !Reliability.directionDefined(lat, lon, locAcc)) return null
        if ((geo?.horizontalStrength ?: 0f) < 5000f) return null
        if (!Reliability.fresh(now, magAt, Reliability.SENSOR_MAX_AGE_MS)) return null
        val o = offset
        val gh = gameHeading
        if (gameSensor != null) return if (gh != null && magHeading != null && o != null &&
            Reliability.fresh(now, gameAt, Reliability.SENSOR_MAX_AGE_MS) &&
            Reliability.fresh(now, anchorAt, Reliability.HOLD_MAX_AGE_MS)) Astro.norm360(gh + o) else null
        // phone without gyroscope: plain corrected compass, only when the field looks clean
        if (method == "average") return null
        val mh = magHeading ?: return null
        val g = geo ?: return null
        return if (calibrated() && (quality == Q.GOOD || quality == Q.FAIR && method != "strict")) Reliability.trueHeading(mh, g.declination.toDouble()) else null
    }

    // ---------------------------------------------------------------- render

    private fun setStatus(text: String, fg: Int, bg: Int) {
        statusTv.text = text
        statusTv.setTextColor(fg)
        statusTv.background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(12).toFloat() }
    }

    /** Vibration guidance: short ticks that get faster as you turn toward the Qibla, a double buzz on arrival. */
    private fun guide(diff: Double, trustworthy: Boolean, now: Long) {
        if (!vibOn || !trustworthy || matMode) return
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
        avgTv.setTextColor(if (done) GREEN else MUTED)
        avgTv.text = when {
            done -> t("✓ Room average ready — from $n motion groups; agreement does not prove accuracy",
                      "✓ متوسط الغرفة جاهز — من $n مجموعات حركة؛ الاتفاق لا يثبت الدقة")
            buckets.isEmpty() && quality == Q.BAD -> t("Room average: paused — too much interference right here. Move a little.",
                                                     "متوسط الغرفة: متوقف — التشويش قوي هنا. تحرّك قليلًا.")
            n < 5 -> t("Room average $pct% — walk 2–3 steps to another spot ($n of 5 spots)",
                       "متوسط الغرفة $pct٪ — امشِ خطوتين أو ثلاثًا إلى مكان آخر ($n من 5 أماكن)")
            else -> t("Room average $pct% — readings still differ, try 1–2 more spots",
                      "متوسط الغرفة $pct٪ — القراءات ما زالت مختلفة، جرّب مكانًا أو مكانين آخرين")
        }
    }

    private fun renderSunCard(now: Long) {
        val ev = Astro.nextSunOverKaaba(now, 15 * 60_000L).firstOrNull()
        val show = ev != null && ev - now < 36 * 3600_000L && (lat.isNaN() || Astro.sun(ev, lat, lon).second > 5)
        sunCard.visibility = if (show && !matMode) View.VISIBLE else View.GONE
        if (!show || ev == null) return
        val time = DateTimeFormatter.ofPattern("HH:mm", Locale.US).format(Instant.ofEpochMilli(ev).atZone(ZoneId.systemDefault()))
        val mins = (ev - now) / 60_000L
        val today = Instant.ofEpochMilli(ev).atZone(ZoneId.systemDefault()).toLocalDate() == Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        sunCard.text = when {
            abs(ev - now) <= 10 * 60_000L -> t("☀️ NOW: the sun is over the Kaaba. Every vertical shadow points approximately AWAY from the Qibla — compare it with the arrow!",
                                               "☀️ الآن: الشمس فوق الكعبة. ظل أي شيء عمودي يشير بعكس اتجاه القبلة تقريبًا — قارنه بالسهم!")
            mins in 1..90 -> t("☀️ In $mins min ($time) the sun will be over the Kaaba: shadows will point approximately away from the Qibla.",
                               "☀️ بعد $mins دقيقة ($time) تكون الشمس فوق الكعبة: ستشير الظلال بعكس اتجاه القبلة تقريبًا.")
            today -> t("☀️ Today at $time the sun is over the Kaaba. At that minute every shadow points approximately away from the Qibla — a natural check.",
                       "☀️ اليوم الساعة $time تكون الشمس فوق الكعبة. في تلك الدقيقة يشير كل ظل بعكس اتجاه القبلة تقريبًا — للتحقق الطبيعي.")
            else -> t("☀️ Tomorrow at $time the sun is over the Kaaba — every shadow will point approximately away from the Qibla.",
                      "☀️ غدًا الساعة $time تكون الشمس فوق الكعبة — سيشير كل ظل بعكس اتجاه القبلة تقريبًا.")
        }
    }

    private fun render() {
        val now = SystemClock.elapsedRealtime()
        renderSunCard(System.currentTimeMillis())
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
        globe.visibility = if (detailed && q.isFinite()) View.VISIBLE else View.GONE
        if (detailed && (globe.userLat != lat || globe.userLon != lon)) {
            globe.userLat = lat; globe.userLon = lon
            globe.label = t("Shortest path over the Earth to Makkah · ", "أقصر طريق على سطح الأرض إلى مكة · ") +
                String.format(Locale.US, "%,d", Astro.distanceKm(lat, lon).toInt()) + t(" km", " كم")
            globe.invalidate()
        }
        renderAverageBar()

        val raw = currentHeading()
        if (raw != null) noFixSince = 0L else if (noFixSince == 0L) noFixSince = now
        if (raw == null) {
            qView.qiblaRel = null; qView.sunRel = null; qView.ringColor = RING; qView.invalidate()
            matDial.qiblaRel = null; matDial.invalidate(); matTv.text = t("Finding the direction…", "جارٍ تحديد الاتجاه…")
            smX = Double.NaN
            turnTv.setTextColor(INK)
            aligned = false
            when {
                !Reliability.fresh(now, locAt, Reliability.LOCATION_MAX_AGE_MS) -> {
                    turnTv.text = t("Location needs updating", "يجب تحديث الموقع")
                    setStatus(t("Enable Location and allow access in Android settings. Saved coordinates may be out of date.",
                        "فعّل الموقع واسمح بالوصول إليه في إعدادات أندرويد. قد تكون الإحداثيات المحفوظة قديمة."), ORANGE, ORANGE_SOFT)
                }
                !Reliability.directionDefined(lat, lon, locAcc) -> {
                    turnTv.text = t("Direction is uncertain here", "الاتجاه غير مؤكد هنا")
                    setStatus(t("Too close to the Kaaba, its opposite point, or a geographic pole for this location accuracy. Use a local reference.",
                        "الموقع قريب من الكعبة أو النقطة المقابلة أو القطب. استخدم مرجعًا محليًا."), ORANGE, ORANGE_SOFT)
                }
                (geo?.horizontalStrength ?: 0f) < 5000f -> {
                    turnTv.text = t("Magnetic north is unreliable here", "الشمال المغناطيسي غير موثوق هنا")
                    setStatus(t("The horizontal magnetic field is too weak for reliable compass guidance. Use a local reference.",
                        "المجال المغناطيسي الأفقي ضعيف جدًا لتوجيه موثوق. استخدم مرجعًا محليًا."), ORANGE, ORANGE_SOFT)
                }
                magSensor == null || rotSensor == null && accelSensor == null -> {
                    turnTv.text = t("No compass in this phone", "لا توجد بوصلة في هذا الهاتف")
                    setStatus(t("Use the web version's sun or wall lock instead.", "استخدم نسخة الويب (القفل بالشمس أو بالجدار) بدلًا من ذلك."), RED, RED_SOFT)
                }
                gameSensor == null && method == "average" -> {
                    turnTv.text = t("Room average unavailable", "متوسط الغرفة غير متاح")
                    setStatus(t("This mode requires a game rotation vector. Choose Smart or Strict in Settings for the available compass.",
                        "يتطلب هذا الوضع حساس دوران نسبي. اختر الوضع الذكي أو الصارم في الإعدادات."), ORANGE, ORANGE_SOFT)
                }
                magHeading == null -> {
                    turnTv.text = t("Hold the phone flat", "أمسك الهاتف بشكل مستوٍ")
                    setStatus(t("Keep the screen facing up, away from metal and magnets.", "اجعل الشاشة لأعلى بعيدًا عن المعادن والمغناطيس."), ORANGE, ORANGE_SOFT)
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
                    setStatus(t("⚠ Something nearby is bending the magnetic field. Walk 1–2 m away from metal (cars, desks, steel doors or pillars, speakers, laptops). A green bar cannot rule out every source of interference.",
                                "⚠ شيء قريب يُشوّه المجال المغناطيسي. ابتعد مترًا أو مترين عن المعادن (السيارات، المكاتب، الأبواب أو الأعمدة الحديدية، السماعات، الحواسيب). الشريط الأخضر لا يستبعد جميع مصادر التشويش."), RED, RED_SOFT)
                }
                gameSensor != null && offset != null && !Reliability.fresh(now, anchorAt, Reliability.HOLD_MAX_AGE_MS) -> {
                    turnTv.text = t("Direction reference expired", "انتهت صلاحية مرجع الاتجاه")
                    setStatus(t("Gyroscope-only direction can drift. Move away from metal to acquire a new compass reference.",
                        "قد ينحرف اتجاه الجيروسكوب. ابتعد عن المعادن للحصول على مرجع جديد للبوصلة."), ORANGE, ORANGE_SOFT)
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
            val holding = quality != Q.GOOD || !calibrated()
            val trustworthy = !holding && src != Src.AVG && src != Src.FAIR
            val ok = abs(diff) <= 2.0 && trustworthy
            qView.qiblaRel = diff.toFloat()
            matDial.qiblaRel = diff.toFloat()
            matDial.ringColor = Color.parseColor(if (ok) "#1F5C43" else "#1A2420")
            matDial.invalidate()
            matTv.text = if (ok) t("✓ Facing the Qibla", "✓ متجه إلى القبلة")
                         else t("Turn ${abs(diff).toInt()}° ${if (diff > 0) "right" else "left"}", "استدر ${abs(diff).toInt()}° ${if (diff > 0) "يمينًا" else "يسارًا"}")
            val (sunAz, sunAlt) = Astro.sun(System.currentTimeMillis(), lat, lon)
            qView.sunRel = if (sunAlt > 0 && sunAlt < 80) Astro.norm180(sunAz - h).toFloat() else null

            qView.ringColor = when { !trustworthy -> ORANGE; ok -> GREEN; else -> RING }
            qView.invalidate()
            turnTv.setTextColor(if (ok) GREEN else INK)
            val deg = abs(diff).toInt()
            turnTv.text = when {
                ok -> t("✓ You are facing the Qibla", "✓ أنت متجه إلى القبلة")
                abs(diff) <= 2.0 -> t("Near the estimated Qibla — verify", "قريب من اتجاه القبلة التقديري — تحقق")
                diff > 0 -> t("Turn $deg° right ↻", "استدر $deg° يمينًا ↻")
                else -> t("Turn $deg° left ↺", "استدر $deg° يسارًا ↺")
            }
            guide(diff, trustworthy, now)
            aligned = ok

            val approx = src == Src.FAIR && gameSensor != null
            val spots = spotsSeen()
            when {
                quality == Q.UNKNOWN -> setStatus(t("Recent reference only — field checks unavailable; direction may drift.",
                    "مرجع حديث فقط — فحص المجال غير متاح وقد ينحرف الاتجاه."), ORANGE, ORANGE_SOFT)
                !calibrated() -> setStatus(t("Holding the last good direction. Wave the phone in a figure‑8 to recalibrate the compass.",
                                             "يتم الاحتفاظ باتجاه حديث قد ينحرف. حرّك الهاتف على شكل الرقم 8 لإعادة معايرة البوصلة."), ORANGE, ORANGE_SOFT)
                quality == Q.BAD -> {
                    val mins = (now - lastGoodAt) / 60000
                    setStatus(t("Interference here — the compass is being ignored. The gyroscope is holding a recent direction (may drift).",
                                "يوجد تشويش هنا — تم تجاهل البوصلة، والجيروسكوب يحتفظ باتجاه حديث قد ينحرف.") +
                        if (lastGoodAt == 0L || mins >= 2) t(" Step to a clean spot (green bar) to refresh.", " انتقل إلى مكان نظيف (شريط أخضر) للتحديث.") else "",
                        ORANGE, ORANGE_SOFT)
                }
                approx -> setStatus(t("Approximate. Walk slowly around the room with the phone flat — readings from motion groups are averaged (shared bias may remain) (${min(spots, 5)} of 5 spots).",
                                      "تقريبي. امشِ ببطء في الغرفة والهاتف مستوٍ — يتم دمج القراءات وقد يبقى انحياز مشترك (${min(spots, 5)} من 5 أماكن)."), ORANGE, ORANGE_SOFT)
                src == Src.AVG -> setStatus(t("Approximate — averaged from $avgSpots readings around this room",
                                              "تقريبي — متوسط $avgSpots قراءة من أماكن مختلفة في هذه الغرفة"), ORANGE, ORANGE_SOFT)
                quality == Q.FAIR -> setStatus(t("Estimate — using a recent reference (small disturbance here now).",
                                                 "تقدير — باستخدام مرجع حديث (يوجد تشويش بسيط هنا الآن)."), ORANGE, ORANGE_SOFT)
                else -> setStatus(t("Field checks passed — verify away from metal", "اجتاز فحص المجال — تحقق بعيدًا عن المعادن"), GREEN, GREEN_SOFT)
            }
        }

        val dec = (g?.declination ?: 0f).toDouble()
        val km = String.format(Locale.US, "%,d", Astro.distanceKm(lat, lon).toInt())
        val decTxt = (if (dec >= 0) "+" else "") + f1(dec) + "°"
        val qTxt = if (q.isFinite()) f1(q) + "°" else "—"
        infoTv.text = t(
            "Qibla: $qTxt from true north · $km km to the Kaaba\n" +
            "Location: $locLabel (${f5(lat)}, ${f5(lon)})\n" +
            "True-north correction (Android model): $decTxt — model age depends on OS\n" +
            "Hold the phone flat. The small sun on the ring shows where the real sun is — a quick check.",
            "القبلة: $qTxt من الشمال الحقيقي · $km كم إلى الكعبة\n" +
            "الموقع: $locLabel (${f5(lat)}, ${f5(lon)})\n" +
            "تصحيح الشمال الحقيقي: $decTxt — عمر النموذج يعتمد على النظام\n" +
            "أمسك الهاتف بشكل مستوٍ. الشمس الصغيرة على الحلقة تُظهر مكان الشمس الحقيقية — للتحقق السريع."
        )
    }
}
