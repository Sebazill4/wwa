package com.temporizador.ciclico.core

/** Módulos independientes de la aplicación. TEST = alarma de comprobación. */
enum class Mode(val requestCode: Int) {
    PUMP(1001),
    CYCLIC(1002),
    TEST(1003);
}

enum class RunState { IDLE, RUNNING, PAUSED, FINISHED }

enum class SoundType(val label: String) {
    PIP("Pip"),
    ALARMA("Alarma"),
    SUAVE("Suave"),
    SISTEMA("Sistema");

    companion object {
        fun fromName(name: String?): SoundType = entries.firstOrNull { it.name == name } ?: ALARMA
    }
}

/** Configuración del aviso sonoro, compartida por ambos módulos. */
data class AlertConfig(
    val sound: SoundType = SoundType.ALARMA,
    val durationSec: Int = DEFAULT_ALERT_SECONDS,
    val vibrate: Boolean = true,
) {
    companion object {
        const val DEFAULT_ALERT_SECONDS = 20
        const val MIN_ALERT_SECONDS = 1
        const val MAX_ALERT_SECONDS = 300
    }
}

data class PumpConfig(
    val durationMin: Int = 24 * 60,
    val alert: AlertConfig = AlertConfig(),
)

data class CyclicConfig(
    val activityName: String = "Actividad",
    /** Texto introducido por el usuario: "5m", "30s", "1m, 30s, 2m"... */
    val intervalText: String = "5m",
    /** 0 = sin límite. */
    val maxAlerts: Int = 0,
    val alert: AlertConfig = AlertConfig(),
)

/**
 * Cronómetro persistible. El tiempo transcurrido se calcula siempre a partir
 * de un ancla (reloj monotónico + reloj de pared), nunca sumando ticks de la UI.
 */
data class Stopwatch(
    val accumulatedMs: Long = 0L,
    val runningSince: TimeAnchor? = null,
) {
    val isRunning: Boolean get() = runningSince != null

    fun elapsed(clock: Clock): Long =
        accumulatedMs + (runningSince?.let { clock.millisSince(it) } ?: 0L)

    fun pause(clock: Clock) = Stopwatch(elapsed(clock), null)
    fun resume(clock: Clock) = if (isRunning) this else Stopwatch(accumulatedMs, clock.anchor())

    /** Consolida el tiempo y re-ancla en "ahora" (tras reinicio del teléfono). */
    fun rebase(clock: Clock) = if (isRunning) Stopwatch(elapsed(clock), clock.anchor()) else this

    companion object {
        fun startNow(clock: Clock) = Stopwatch(0L, clock.anchor())
    }
}

/**
 * Estado persistido de una sesión (prueba de bombeo o temporizador cíclico).
 * Los índices de aviso empiezan en 1. lastHandledIndex = último aviso ya tratado
 * (sonado o marcado como perdido).
 */
data class SessionState(
    val mode: Mode,
    val runState: RunState = RunState.IDLE,
    val sessionId: Long = 0L,
    val startWallMs: Long = 0L,
    val stopwatch: Stopwatch = Stopwatch(),
    val lastHandledIndex: Int = 0,
    val alertsEmitted: Int = 0,
    val alertsMissed: Int = 0,
    /** Instante de pared programado para la próxima alarma (informativo). */
    val nextAlarmWallMs: Long = 0L,
    val lastAlertIndex: Int = 0,
    val lastAlertWallMs: Long = 0L,
    /** Retraso real del último aviso respecto del instante programado. */
    val lastAlertDelayMs: Long = 0L,
    val finishedWallMs: Long = 0L,
    // Instantánea de la configuración usada en esta sesión
    val pumpDurationMin: Int = 0,
    val cyclicIntervalsMs: List<Long> = emptyList(),
    val cyclicMaxAlerts: Int = 0,
    val activityName: String = "",
    val alert: AlertConfig = AlertConfig(),
) {
    val isActive: Boolean get() = runState == RunState.RUNNING || runState == RunState.PAUSED
}

/** Prueba de alarma desde la pantalla «Comprobar alarmas». */
data class TestAlarmState(
    val scheduledWallMs: Long = 0L,
    val sessionId: Long = 0L,
    val firedWallMs: Long = 0L,
    val pending: Boolean = false,
)
