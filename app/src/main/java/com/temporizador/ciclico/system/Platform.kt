package com.temporizador.ciclico.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.temporizador.ciclico.core.AlarmScheduler
import com.temporizador.ciclico.core.Clock
import com.temporizador.ciclico.core.KeyValueStore
import com.temporizador.ciclico.core.Mode
import com.temporizador.ciclico.ui.MainActivity

class AndroidClock(private val context: Context) : Clock {
    override fun wallNow(): Long = System.currentTimeMillis()
    override fun elapsedNow(): Long = SystemClock.elapsedRealtime()
    override fun bootCount(): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Exception) {
        -1
    }
}

class SharedPrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("temporizador_estado", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putAll(values: Map<String, String?>) {
        val e = prefs.edit()
        values.forEach { (k, v) -> if (v == null) e.remove(k) else e.putString(k, v) }
        // commit() síncrono: el estado queda en disco antes de que el proceso pueda morir.
        e.commit()
    }
}

/**
 * Programa las alarmas con AlarmManager.setAlarmClock():
 *  - Es la API pensada para alarmas visibles al usuario (despertadores/temporizadores).
 *  - Suena a la hora exacta incluso en Doze y con la pantalla apagada; el sistema sale de
 *    Doze poco antes de la alarma. No sufre el límite de «una alarma cada ~9 minutos» que
 *    afecta a setExactAndAllowWhileIdle() en reposo.
 *  - Muestra el icono de alarma en la barra de estado.
 * Si el permiso de alarmas exactas no estuviera concedido, se recurre a una alarma
 * inexacta (avisando en «Comprobar alarmas»), nunca se falla en silencio.
 */
class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    private val am = context.getSystemService(AlarmManager::class.java)

    override fun schedule(mode: Mode, sessionId: Long, index: Int, triggerWallMs: Long) {
        val pi = alarmPendingIntent(mode, sessionId, index)
        val show = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            if (canScheduleExact(context)) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerWallMs, show), pi)
            } else {
                Log.w(TAG, "Sin permiso de alarmas exactas: se usa alarma inexacta")
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerWallMs, pi)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "No se pudo programar alarma exacta", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerWallMs, pi)
        }
    }

    override fun cancel(mode: Mode) {
        val pi = PendingIntent.getBroadcast(
            context, mode.requestCode,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_ALARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    /** Un único PendingIntent por modo: reprogramar reemplaza la alarma anterior (sin duplicados). */
    private fun alarmPendingIntent(mode: Mode, sessionId: Long, index: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_ALARM)
            .putExtra(AlarmReceiver.EXTRA_MODE, mode.name)
            .putExtra(AlarmReceiver.EXTRA_SESSION, sessionId)
            .putExtra(AlarmReceiver.EXTRA_INDEX, index)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        return PendingIntent.getBroadcast(
            context, mode.requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val TAG = "AlarmScheduler"

        fun canScheduleExact(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
}
