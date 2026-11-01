package com.temporizador.ciclico.system

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

enum class CheckStatus { OK, WARNING, ERROR, MANUAL }

data class CheckItem(
    val id: String,
    val title: String,
    val detail: String,
    val status: CheckStatus,
    val actionLabel: String? = null,
)

/** Comprobaciones de permisos y configuración que afectan a las alarmas. */
object DeviceChecks {

    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.equals("Xiaomi", true) || Build.BRAND.equals("Redmi", true) ||
            Build.BRAND.equals("POCO", true) || Build.BRAND.equals("Xiaomi", true)

    fun alarmVolume(context: Context): Pair<Int, Int> {
        val am = context.getSystemService(AudioManager::class.java)
        return am.getStreamVolume(AudioManager.STREAM_ALARM) to am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
    }

    /** null si el modo No molestar no bloquea alarmas; texto de advertencia si podría hacerlo. */
    fun dndWarning(context: Context): String? {
        val nm = context.getSystemService(NotificationManager::class.java)
        return when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE ->
                "«No molestar» está en Silencio total: Android bloqueará el sonido de las alarmas."
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> {
                val allowsAlarms = try {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                        (nm.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) != 0
                } catch (_: Exception) { true }
                if (allowsAlarms) null else "«No molestar» está activo y no permite alarmas: no sonarán."
            }
            else -> null
        }
    }

    fun isIgnoringBattery(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun isBackgroundRestricted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.getSystemService(ActivityManager::class.java).isBackgroundRestricted

    fun canFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun isPowerSaveMode(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isPowerSaveMode

    fun items(context: Context): List<CheckItem> {
        val list = ArrayList<CheckItem>()
        val notif = Notifications.canPost(context)
        list += CheckItem(
            "notifications", "Permiso de notificaciones",
            if (notif) "Concedido. Se mostrará la cuenta regresiva y el aviso en pantalla bloqueada."
            else "Sin este permiso no verás el aviso ni la cuenta regresiva (el sonido igualmente se intenta).",
            if (notif) CheckStatus.OK else CheckStatus.ERROR,
            if (notif) null else "Conceder",
        )
        val exact = AndroidAlarmScheduler.canScheduleExact(context)
        list += CheckItem(
            "exact", "Alarmas exactas",
            if (exact) "Concedido. Las alarmas se programan con setAlarmClock (hora exacta, también en reposo)."
            else "Sin permiso las alarmas pueden retrasarse varios minutos.",
            if (exact) CheckStatus.OK else CheckStatus.ERROR,
            if (exact) null else "Conceder",
        )
        val battery = isIgnoringBattery(context)
        list += CheckItem(
            "battery", "Sin restricciones de batería",
            if (battery) "La app está excluida de la optimización de batería."
            else "Recomendado: permite que Android no restrinja la app en segundo plano.",
            if (battery) CheckStatus.OK else CheckStatus.WARNING,
            if (battery) null else "Permitir",
        )
        if (isBackgroundRestricted(context)) {
            list += CheckItem(
                "bgrestricted", "Actividad en segundo plano restringida",
                "Android tiene restringida esta app en segundo plano. Quita la restricción en Ajustes › Apps › Batería.",
                CheckStatus.ERROR, "Abrir ajustes",
            )
        }
        val full = canFullScreen(context)
        list += CheckItem(
            "fullscreen", "Aviso a pantalla completa",
            if (full) "Permitido: el aviso enciende la pantalla aunque esté bloqueada."
            else "Opcional: sin él solo verás una notificación emergente (el sonido no depende de esto).",
            if (full) CheckStatus.OK else CheckStatus.WARNING,
            if (full) null else "Permitir",
        )
        val (vol, max) = alarmVolume(context)
        list += CheckItem(
            "volume", "Volumen de alarma",
            if (vol == 0) "El volumen de ALARMA está en 0: los avisos no se oirán. Súbelo con los botones de volumen o en Ajustes › Sonido."
            else "Volumen de alarma: $vol de $max. La app no modifica tu volumen.",
            if (vol == 0) CheckStatus.ERROR else if (vol * 2 < max) CheckStatus.WARNING else CheckStatus.OK,
            "Ajustes de sonido",
        )
        val dnd = dndWarning(context)
        list += CheckItem(
            "dnd", "Modo «No molestar»",
            dnd ?: "No bloquea las alarmas.",
            if (dnd == null) CheckStatus.OK else CheckStatus.ERROR,
            if (dnd == null) null else "Abrir ajustes",
        )
        if (isPowerSaveMode(context)) {
            list += CheckItem(
                "powersave", "Ahorro de batería activo",
                "Las alarmas de reloj (setAlarmClock) siguen funcionando, pero el ahorro extremo de HyperOS puede cerrar apps. Evítalo durante la prueba.",
                CheckStatus.WARNING,
            )
        }
        if (isXiaomi) {
            list += CheckItem(
                "autostart", "Xiaomi: Inicio automático",
                "HyperOS no permite comprobarlo desde la app. Actívalo para que las alarmas se restauren tras reiniciar y la app no sea cerrada.",
                CheckStatus.MANUAL, "Abrir",
            )
            list += CheckItem(
                "miuibattery", "Xiaomi: Ahorro de batería «Sin restricciones»",
                "Ajustes › Apps › Administrar apps › Temporizador Cíclico › Ahorro de batería › Sin restricciones.",
                CheckStatus.MANUAL, "Abrir",
            )
            list += CheckItem(
                "lock", "Xiaomi: Bloquear la app en Recientes",
                "Abre Recientes, mantén pulsada la tarjeta de la app y toca el candado. Así «Limpiar todo» no la cierra.",
                CheckStatus.MANUAL,
            )
        }
        return list
    }

    // ------------------------------------------------------------------ Acciones

    fun openFor(context: Context, id: String) {
        when (id) {
            "notifications" -> openAppNotificationSettings(context)
            "exact" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                start(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri(context)))
            }
            "battery" -> requestIgnoreBattery(context)
            "bgrestricted", "miuibattery" -> openMiuiBattery(context)
            "fullscreen" -> if (Build.VERSION.SDK_INT >= 34) {
                start(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri(context)))
            }
            "volume" -> start(context, Intent(Settings.ACTION_SOUND_SETTINGS))
            "dnd" -> start(context, Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)) ||
                start(context, Intent(Settings.ACTION_SOUND_SETTINGS))
            "autostart" -> openXiaomiAutostart(context)
        }
    }

    fun openAppNotificationSettings(context: Context) {
        start(
            context,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        ) || openAppDetails(context)
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBattery(context: Context) {
        start(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(context))) ||
            start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ||
            openAppDetails(context)
    }

    fun openXiaomiAutostart(context: Context) {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
        )
        for (i in candidates) if (start(context, i)) return
        openAppDetails(context)
    }

    fun openMiuiBattery(context: Context) {
        val i = Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY")
            .putExtra("package_name", context.packageName)
            .putExtra("package_label", "Temporizador Cíclico")
        if (isXiaomi && start(context, i)) return
        openAppDetails(context)
    }

    fun openAppDetails(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(context)))

    private fun pkgUri(context: Context) = Uri.parse("package:${context.packageName}")

    private fun start(context: Context, intent: Intent): Boolean = try {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
