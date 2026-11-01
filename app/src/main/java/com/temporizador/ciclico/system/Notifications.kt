package com.temporizador.ciclico.system

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.temporizador.ciclico.R
import com.temporizador.ciclico.core.AlarmOutcome
import com.temporizador.ciclico.core.RunState
import com.temporizador.ciclico.core.TimerEngine
import com.temporizador.ciclico.ui.AlarmActivity
import com.temporizador.ciclico.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Notifications {
    const val CHANNEL_RUNNING = "en_ejecucion"
    const val CHANNEL_ALERT = "avisos"
    const val ID_ONGOING = 1
    const val ID_ALERT = 2

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val running = NotificationChannel(CHANNEL_RUNNING, "Temporizador en ejecución", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Muestra el temporizador activo y la próxima alarma"
            setShowBadge(false)
        }
        // El sonido lo reproduce la app por el canal de alarma; la notificación es silenciosa
        // para no sonar dos veces.
        val alert = NotificationChannel(CHANNEL_ALERT, "Avisos de medición", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Aviso visible cuando corresponde una medición"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(running)
        nm.createNotificationChannel(alert)
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 10,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun silenceIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 11,
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_SILENCE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Notificación permanente del servicio en primer plano, con cuenta regresiva. */
    fun buildOngoing(context: Context, engine: TimerEngine): Notification {
        val lines = ArrayList<String>()
        var nextWall = Long.MAX_VALUE
        var title = "Temporizador Cíclico"
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        for (s in listOf(engine.pump.value, engine.cyclic.value)) {
            if (!s.isActive) continue
            val v = engine.view(s)
            val program = engine.program(s)
            val name = engine.titleFor(s)
            if (s.runState == RunState.PAUSED) {
                lines += "$name: pausado"
                continue
            }
            val label = if (v.nextIndex != null && program != null) engine.labelFor(s, program, v.nextIndex) else "—"
            lines += "$name · próximo: $label (${fmt.format(Date(s.nextAlarmWallMs))})"
            if (s.nextAlarmWallMs in 1 until nextWall) {
                nextWall = s.nextAlarmWallMs
                title = "$name · próximo $label"
            }
        }
        if (engine.test.value.pending) {
            lines += "Prueba de alarma a las ${fmt.format(Date(engine.test.value.scheduledWallMs))}"
            if (engine.test.value.scheduledWallMs < nextWall) {
                nextWall = engine.test.value.scheduledWallMs
                title = "Prueba de alarma"
            }
        }
        val b = NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: "Preparando…")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
        if (nextWall != Long.MAX_VALUE) {
            b.setWhen(nextWall).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        }
        return b.build()
    }

    /** Aviso visible de cada alarma (el sonido lo emite AlarmPlayer). */
    fun showAlert(context: Context, outcome: AlarmOutcome) {
        if (!canPost(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        val full = PendingIntent.getActivity(
            context, 12,
            Intent(context, AlarmActivity::class.java)
                .putExtra(AlarmActivity.EXTRA_TITLE, outcome.title)
                .putExtra(AlarmActivity.EXTRA_LABEL, outcome.label)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (outcome.finished && !outcome.label.contains("finalizada")) "${outcome.label} — programa finalizado" else outcome.label
        val n = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(outcome.title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter((outcome.alert.durationSec * 1000L).coerceAtLeast(30_000L))
            .setContentIntent(openAppIntent(context))
            .setFullScreenIntent(full, true)
            .addAction(0, "Silenciar", silenceIntent(context))
            .build()
        try { nm.notify(ID_ALERT, n) } catch (_: SecurityException) {}
    }

    fun cancelAlert(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_ALERT)
    }
}
