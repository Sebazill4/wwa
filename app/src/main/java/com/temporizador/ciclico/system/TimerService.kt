package com.temporizador.ciclico.system

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.temporizador.ciclico.core.AlarmOutcome
import com.temporizador.ciclico.core.AlertConfig
import com.temporizador.ciclico.core.Mode
import com.temporizador.ciclico.core.SoundType

/**
 * Servicio en primer plano activo mientras haya un temporizador en marcha.
 *
 * NO es el que mide el tiempo ni dispara las alarmas (eso lo hace AlarmManager.setAlarmClock).
 * Su función es:
 *  1. Mantener el proceso con prioridad alta para que HyperOS/MIUI no lo elimine en segundo plano.
 *  2. Mostrar la notificación permanente con la cuenta regresiva hasta el próximo aviso.
 *  3. Reproducir el sonido del aviso durante la duración configurada.
 *
 * Tipo «specialUse» (Android 14+): no tiene límite de tiempo (a diferencia de dataSync o
 * mediaProcessing en Android 15) y puede iniciarse tras el arranque del teléfono.
 */
class TimerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = Graph.engine(this)
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_RING -> ring(outcomeFrom(intent))
            ACTION_SILENCE -> {
                AlarmPlayer.stop()
                Notifications.cancelAlert(this)
            }
        }
        refresh()
        return if (engine.anyActive) START_STICKY else START_NOT_STICKY
    }

    private fun goForeground(): Boolean = try {
        val n = Notifications.buildOngoing(this, Graph.engine(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, Notifications.ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifications.ID_ONGOING, n)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "No se pudo iniciar en primer plano", e)
        false
    }

    /** Suena desde el servicio ya en ejecución (hilo principal). */
    fun ring(o: AlarmOutcome) {
        Notifications.showAlert(this, o)
        AlarmPlayer.play(this, o.alert) { refresh() }
    }

    /** Actualiza la notificación o detiene el servicio si ya no hay nada activo. */
    fun refresh() {
        val engine = Graph.engine(this)
        if (!engine.anyActive && !AlarmPlayer.playing.value) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (Notifications.canPost(this)) {
            try {
                getSystemService(NotificationManager::class.java)
                    .notify(Notifications.ID_ONGOING, Notifications.buildOngoing(this, engine))
            } catch (_: SecurityException) {}
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TimerService"
        const val ACTION_SYNC = "com.temporizador.ciclico.SYNC"
        const val ACTION_RING = "com.temporizador.ciclico.RING"
        const val ACTION_SILENCE = "com.temporizador.ciclico.SILENCE_SERVICE"

        @Volatile
        var instance: TimerService? = null
            private set

        fun ringIntent(context: Context, o: AlarmOutcome): Intent = Intent(context, TimerService::class.java)
            .setAction(ACTION_RING)
            .putExtra("mode", o.mode.name)
            .putExtra("index", o.index)
            .putExtra("title", o.title)
            .putExtra("label", o.label)
            .putExtra("sound", o.alert.sound.name)
            .putExtra("seconds", o.alert.durationSec)
            .putExtra("vibrate", o.alert.vibrate)
            .putExtra("finished", o.finished)

        private fun outcomeFrom(i: Intent) = AlarmOutcome(
            ring = true,
            mode = Mode.entries.firstOrNull { it.name == i.getStringExtra("mode") } ?: Mode.PUMP,
            index = i.getIntExtra("index", 0),
            title = i.getStringExtra("title") ?: "",
            label = i.getStringExtra("label") ?: "",
            alert = AlertConfig(
                SoundType.fromName(i.getStringExtra("sound")),
                i.getIntExtra("seconds", AlertConfig.DEFAULT_ALERT_SECONDS),
                i.getBooleanExtra("vibrate", true),
            ),
            finished = i.getBooleanExtra("finished", false),
        )

        /** Intenta iniciar el servicio en primer plano. Devuelve false si Android lo impide. */
        fun tryStart(context: Context, intent: Intent): Boolean = try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (Android 12+) u otras restricciones
            Log.w(TAG, "Inicio de servicio en primer plano no permitido", e)
            false
        }

        /** Sincroniza el servicio con el estado: lo inicia, actualiza o detiene. */
        fun sync(context: Context) {
            val engine = Graph.engine(context)
            val running = instance
            if (running != null) {
                running.refresh()
            } else if (engine.anyActive) {
                tryStart(context, Intent(context, TimerService::class.java).setAction(ACTION_SYNC))
            }
        }
    }
}
