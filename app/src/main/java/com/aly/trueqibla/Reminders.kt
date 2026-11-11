package com.aly.trueqibla

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Optional reminders for the two yearly "sun over the Kaaba" moments:
 * one the evening before (20:00) and one 15 minutes before.
 * Uses inexact alarms (no special permission). Rescheduled on every app start and after a reboot.
 */
object Reminders {
    private const val CHANNEL = "sun_over_kaaba"

    fun enabled(ctx: Context) = ctx.getSharedPreferences("qibla", Context.MODE_PRIVATE).getBoolean("remind", false)

    private fun arabic(ctx: Context): Boolean {
        val saved = ctx.getSharedPreferences("qibla", Context.MODE_PRIVATE).getString("lang", null)
        return if (saved != null) saved == "ar" else ctx.resources.configuration.locales[0].language == "ar"
    }

    private fun pending(ctx: Context, code: Int, ev: Long, kind: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("ev", ev).putExtra("kind", kind)
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pending(ctx, 1, 0L, "eve"))
        am.cancel(pending(ctx, 2, 0L, "soon"))
        if (!enabled(ctx)) return
        val now = System.currentTimeMillis()
        val ev = Astro.nextSunOverKaaba(now).firstOrNull { it - 15 * 60_000L > now } ?: return
        val zone = ZoneId.systemDefault()
        val evening = Instant.ofEpochMilli(ev).atZone(zone).toLocalDate().minusDays(1)
            .atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
        if (evening > now) am.setWindow(AlarmManager.RTC_WAKEUP, evening, 15 * 60_000L, pending(ctx, 1, ev, "eve"))
        am.setWindow(AlarmManager.RTC_WAKEUP, ev - 15 * 60_000L, 5 * 60_000L, pending(ctx, 2, ev, "soon"))
    }

    fun notify(ctx: Context, ev: Long, kind: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val ar = arabic(ctx)
        nm.createNotificationChannel(NotificationChannel(CHANNEL,
            if (ar) "الشمس فوق الكعبة" else "Sun over the Kaaba", NotificationManager.IMPORTANCE_DEFAULT))
        val time = DateTimeFormatter.ofPattern("HH:mm", Locale.US).format(Instant.ofEpochMilli(ev).atZone(ZoneId.systemDefault()))
        val title = if (ar) "☀️ الشمس فوق الكعبة" else "☀️ Sun over the Kaaba"
        val text = when {
            kind == "eve" && ar -> "غدًا الساعة $time تكون الشمس فوق الكعبة مباشرة، وكل ظل يشير بعكس اتجاه القبلة تمامًا. فرصة للتحقق من القبلة."
            kind == "eve" -> "Tomorrow at $time the sun is directly over the Kaaba: every shadow points exactly away from the Qibla. A perfect natural check."
            ar -> "الساعة $time (بعد 15 دقيقة) تكون الشمس فوق الكعبة. ظل أي شيء عمودي يشير بعكس اتجاه القبلة تمامًا."
            else -> "At $time (in 15 minutes) the sun is over the Kaaba. Any vertical shadow will point exactly away from the Qibla."
        }
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(if (kind == "eve") 1 else 2, n) } catch (_: SecurityException) {}
    }
}

/** Fires the reminder, then schedules the next one. Also reschedules after the phone restarts. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            val ev = intent.getLongExtra("ev", 0L)
            if (ev > 0 && Reminders.enabled(context)) Reminders.notify(context, ev, intent.getStringExtra("kind") ?: "soon")
        }
        Reminders.scheduleNext(context)
    }
}
