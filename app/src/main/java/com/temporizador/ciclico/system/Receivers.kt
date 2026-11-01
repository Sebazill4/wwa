package com.temporizador.ciclico.system

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.temporizador.ciclico.core.AlarmOutcome
import com.temporizador.ciclico.core.Mode
import com.temporizador.ciclico.core.RestoreReason

/** Recibe las alarmas de AlarmManager. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ALARM -> handleAlarm(context, intent)
            ACTION_SILENCE -> {
                AlarmPlayer.stop()
                Notifications.cancelAlert(context)
                TimerService.instance?.refresh()
            }
        }
    }

    private fun handleAlarm(context: Context, intent: Intent) {
        val mode = Mode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_MODE) } ?: return
        val session = intent.getLongExtra(EXTRA_SESSION, -1L)
        val index = intent.getIntExtra(EXTRA_INDEX, -1)
        val engine = Graph.engine(context)
        // 1) Actualizar estado y programar YA la siguiente alarma (antes de cualquier otra cosa).
        val outcome = engine.onAlarm(mode, session, index)
        Log.i(TAG, "Alarma $mode #$index → ring=${outcome.ring} idx=${outcome.index}")
        if (!outcome.ring) {
            TimerService.sync(context)
            return
        }
        // 2) Sonar. Preferentemente desde el servicio en primer plano (sobrevive al receptor).
        val running = TimerService.instance
        if (running != null) {
            running.ring(outcome)
            running.refresh()
            return
        }
        val viaService = TimerService.tryStart(context, TimerService.ringIntent(context, outcome))
        if (!viaService) ringFallback(context, outcome)
    }

    /** Si Android no deja iniciar el servicio, se suena directamente desde el receptor. */
    private fun ringFallback(context: Context, outcome: AlarmOutcome) {
        val pending = goAsync()
        Notifications.showAlert(context, outcome)
        AlarmPlayer.play(context, outcome.alert)
        // Un receptor dispone de ~10 s; el sonido sigue mientras el proceso viva.
        Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, 9_000L)
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_ALARM = "com.temporizador.ciclico.ALARM"
        const val ACTION_SILENCE = "com.temporizador.ciclico.SILENCE"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SESSION = "session"
        const val EXTRA_INDEX = "index"
    }
}

/**
 * Reinicio del teléfono, actualización de la app, cambio de hora/zona horaria y cambio
 * del permiso de alarmas exactas: se restaura el estado y se reprograma la próxima alarma.
 * Las alarmas que debieron sonar con el teléfono apagado NO se reproducen todas juntas.
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> RestoreReason.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> RestoreReason.PACKAGE_REPLACED
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> RestoreReason.TIME_CHANGED
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> RestoreReason.TIME_CHANGED
            else -> return
        }
        Log.i("SystemEventReceiver", "Restaurando por $reason")
        Graph.engine(context).restore(reason)
        TimerService.sync(context)
    }
}
