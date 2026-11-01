package com.temporizador.ciclico.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Programa alarmas del sistema. En Android: AlarmManager.setAlarmClock(). */
interface AlarmScheduler {
    /** Programa (o reemplaza) la única alarma pendiente del modo [mode]. */
    fun schedule(mode: Mode, sessionId: Long, index: Int, triggerWallMs: Long)
    fun cancel(mode: Mode)
}

/** Resultado de procesar una alarma recibida. */
data class AlarmOutcome(
    val ring: Boolean,
    val mode: Mode,
    val index: Int = 0,
    val title: String = "",
    val label: String = "",
    val alert: AlertConfig = AlertConfig(),
    val finished: Boolean = false,
)

/** Datos derivados para mostrar en pantalla. */
data class SessionView(
    val elapsedMs: Long,
    val nextIndex: Int?,
    val nextOffsetMs: Long?,
    val countdownMs: Long?,
    val totalCount: Int?,
)

enum class RestoreReason { BOOT, APP_OPEN, TIME_CHANGED, PACKAGE_REPLACED }

/**
 * Lógica central de los temporizadores, independiente de Android.
 *
 * Principios:
 *  - Cada aviso i tiene un desfase fijo desde el inicio (minuto exacto del programa).
 *  - Solo hay UNA alarma del sistema pendiente por modo (la del próximo aviso): no se acumulan.
 *  - Al recibir una alarma se suena solo el aviso vencido más reciente. Si por un retraso de
 *    Android hubiera varios vencidos, los anteriores se cuentan como «perdidos» y no se reproducen.
 *  - Tras reinicio / reapertura, los avisos vencidos no se reproducen: se marcan como perdidos y
 *    se programa el siguiente aviso futuro.
 */
class TimerEngine(
    kv: KeyValueStore,
    private val clock: Clock,
    private val scheduler: AlarmScheduler,
) {
    val store = StateStore(kv)

    private val _pump = MutableStateFlow(store.loadSession(Mode.PUMP))
    private val _cyclic = MutableStateFlow(store.loadSession(Mode.CYCLIC))
    private val _test = MutableStateFlow(store.loadTest())
    val pump: StateFlow<SessionState> = _pump.asStateFlow()
    val cyclic: StateFlow<SessionState> = _cyclic.asStateFlow()
    val test: StateFlow<TestAlarmState> = _test.asStateFlow()

    fun session(mode: Mode): SessionState = when (mode) {
        Mode.PUMP -> _pump.value
        Mode.CYCLIC -> _cyclic.value
        Mode.TEST -> error("TEST no es una sesión")
    }

    val anyActive: Boolean get() = _pump.value.isActive || _cyclic.value.isActive || _test.value.pending

    private fun save(s: SessionState) {
        store.saveSession(s)
        when (s.mode) {
            Mode.PUMP -> _pump.value = s
            Mode.CYCLIC -> _cyclic.value = s
            Mode.TEST -> Unit
        }
    }

    fun program(s: SessionState): AlarmProgram? = when (s.mode) {
        Mode.PUMP -> if (s.pumpDurationMin >= 1) PumpSchedule.program(s.pumpDurationMin) else null
        Mode.CYCLIC -> if (s.cyclicIntervalsMs.isNotEmpty()) CyclicProgram(s.cyclicIntervalsMs, s.cyclicMaxAlerts) else null
        Mode.TEST -> null
    }

    private fun newSessionId(): Long {
        val prev = maxOf(_pump.value.sessionId, _cyclic.value.sessionId, _test.value.sessionId)
        return maxOf(clock.wallNow(), prev + 1)
    }

    // ------------------------------------------------------------------ Prueba de bombeo

    @Synchronized
    fun startPump(config: PumpConfig): SessionState {
        store.savePumpConfig(config)
        scheduler.cancel(Mode.PUMP)
        val s = SessionState(
            mode = Mode.PUMP,
            runState = RunState.RUNNING,
            sessionId = newSessionId(),
            startWallMs = clock.wallNow(),
            stopwatch = Stopwatch.startNow(clock),
            pumpDurationMin = config.durationMin,
            alert = config.alert,
        )
        return scheduleNextAndSave(s)
    }

    // ------------------------------------------------------------------ Temporizador cíclico

    @Synchronized
    fun startCyclic(config: CyclicConfig): SessionState {
        val intervals = IntervalParser.parseSequence(config.intervalText)
            ?: throw IllegalArgumentException("Intervalo no válido")
        store.saveCyclicConfig(config)
        scheduler.cancel(Mode.CYCLIC)
        val s = SessionState(
            mode = Mode.CYCLIC,
            runState = RunState.RUNNING,
            sessionId = newSessionId(),
            startWallMs = clock.wallNow(),
            stopwatch = Stopwatch.startNow(clock),
            cyclicIntervalsMs = intervals,
            cyclicMaxAlerts = config.maxAlerts,
            activityName = config.activityName,
            alert = config.alert,
        )
        return scheduleNextAndSave(s)
    }

    @Synchronized
    fun pause(mode: Mode): SessionState {
        val s = session(mode)
        if (s.runState != RunState.RUNNING) return s
        scheduler.cancel(mode)
        val p = s.copy(runState = RunState.PAUSED, stopwatch = s.stopwatch.pause(clock), nextAlarmWallMs = 0L)
        save(p)
        return p
    }

    @Synchronized
    fun resume(mode: Mode): SessionState {
        val s = session(mode)
        if (s.runState != RunState.PAUSED) return s
        val r = s.copy(runState = RunState.RUNNING, stopwatch = s.stopwatch.resume(clock))
        return scheduleNextAndSave(r)
    }

    /** Detención voluntaria: cancela todas las alarmas pendientes del modo. */
    @Synchronized
    fun stop(mode: Mode): SessionState {
        val s = session(mode)
        scheduler.cancel(mode)
        if (!s.isActive) return s
        val st = s.copy(runState = RunState.IDLE, stopwatch = s.stopwatch.pause(clock), nextAlarmWallMs = 0L)
        save(st)
        return st
    }

    // ------------------------------------------------------------------ Alarmas

    /** Programa la alarma del próximo aviso pendiente según el desfase fijo desde el inicio. */
    private fun scheduleNextAndSave(s: SessionState): SessionState {
        val program = program(s)
        val next = s.lastHandledIndex + 1
        if (program == null || !program.hasIndex(next)) {
            scheduler.cancel(s.mode)
            val f = s.copy(
                runState = RunState.FINISHED,
                stopwatch = s.stopwatch.pause(clock),
                nextAlarmWallMs = 0L,
                finishedWallMs = clock.wallNow(),
            )
            save(f)
            return f
        }
        val elapsed = s.stopwatch.elapsed(clock)
        val trigger = clock.wallNow() + (program.offsetMs(next) - elapsed)
        scheduler.schedule(s.mode, s.sessionId, next, trigger)
        val r = s.copy(nextAlarmWallMs = trigger)
        save(r)
        return r
    }

    /**
     * Procesa una alarma recibida del sistema. Primero reprograma la siguiente alarma y
     * después indica si hay que sonar. Idempotente frente a entregas duplicadas.
     */
    @Synchronized
    fun onAlarm(mode: Mode, sessionId: Long, index: Int): AlarmOutcome {
        if (mode == Mode.TEST) return onTestAlarm(sessionId)
        val s = session(mode)
        if (s.sessionId != sessionId || s.runState != RunState.RUNNING) {
            return AlarmOutcome(ring = false, mode = mode)
        }
        val program = program(s) ?: return AlarmOutcome(ring = false, mode = mode)
        val elapsed = s.stopwatch.elapsed(clock)
        val due = program.dueIndex(elapsed + EARLY_TOLERANCE_MS)
        if (due <= s.lastHandledIndex) {
            // Entrega duplicada o adelantada: solo asegurar la próxima alarma.
            scheduleNextAndSave(s)
            return AlarmOutcome(ring = false, mode = mode)
        }
        val skipped = due - s.lastHandledIndex - 1
        val delay = (elapsed - program.offsetMs(due)).coerceAtLeast(0L)
        val updated = s.copy(
            lastHandledIndex = due,
            alertsEmitted = s.alertsEmitted + 1,
            alertsMissed = s.alertsMissed + skipped,
            lastAlertIndex = due,
            lastAlertWallMs = clock.wallNow(),
            lastAlertDelayMs = delay,
        )
        val after = scheduleNextAndSave(updated)
        return AlarmOutcome(
            ring = true,
            mode = mode,
            index = due,
            title = titleFor(after),
            label = if (after.mode == Mode.PUMP && after.runState == RunState.FINISHED) {
                "MINUTO ${program.offsetMs(due) / PumpSchedule.MINUTE_MS} · $PUMP_FINISHED_TEXT"
            } else {
                labelFor(after, program, due)
            },
            alert = after.alert,
            finished = after.runState == RunState.FINISHED,
        )
    }

    /**
     * Restaura el estado tras reinicio, actualización, cambio de hora o al abrir la app.
     * Los avisos vencidos (más de [GRACE_MS]) no se reproducen: se cuentan como perdidos.
     */
    @Synchronized
    fun restore(reason: RestoreReason) {
        for (mode in listOf(Mode.PUMP, Mode.CYCLIC)) {
            var s = session(mode)
            if (!s.isActive) continue
            val anchor = s.stopwatch.runningSince
            if (anchor != null && !clock.isSameBoot(anchor)) {
                s = s.copy(stopwatch = s.stopwatch.rebase(clock))
            }
            if (s.runState == RunState.PAUSED) {
                save(s)
                continue
            }
            val program = program(s)
            if (program == null) {
                scheduleNextAndSave(s); continue
            }
            val due = program.dueIndex(s.stopwatch.elapsed(clock) - GRACE_MS)
            if (due > s.lastHandledIndex) {
                s = s.copy(alertsMissed = s.alertsMissed + (due - s.lastHandledIndex), lastHandledIndex = due)
            }
            scheduleNextAndSave(s)
        }
        val t = _test.value
        if (t.pending) {
            if (clock.wallNow() - t.scheduledWallMs > GRACE_MS) {
                setTest(t.copy(pending = false))
            } else {
                scheduler.schedule(Mode.TEST, t.sessionId, 1, t.scheduledWallMs)
            }
        }
    }

    // ------------------------------------------------------------------ Prueba de alarma

    @Synchronized
    fun scheduleTest(delayMs: Long): TestAlarmState {
        val id = newSessionId()
        val at = clock.wallNow() + delayMs
        scheduler.schedule(Mode.TEST, id, 1, at)
        return setTest(TestAlarmState(scheduledWallMs = at, sessionId = id, firedWallMs = 0L, pending = true))
    }

    @Synchronized
    fun cancelTest() {
        scheduler.cancel(Mode.TEST)
        setTest(_test.value.copy(pending = false))
    }

    private fun onTestAlarm(sessionId: Long): AlarmOutcome {
        val t = _test.value
        if (!t.pending || t.sessionId != sessionId) return AlarmOutcome(ring = false, mode = Mode.TEST)
        setTest(t.copy(pending = false, firedWallMs = clock.wallNow()))
        val alert = store.loadPumpConfig().alert
        return AlarmOutcome(
            ring = true, mode = Mode.TEST, index = 1,
            title = "Prueba de alarma", label = "La alarma funciona",
            alert = alert.copy(durationSec = alert.durationSec.coerceAtMost(10)),
        )
    }

    private fun setTest(t: TestAlarmState): TestAlarmState {
        store.saveTest(t); _test.value = t; return t
    }

    // ------------------------------------------------------------------ Presentación

    fun view(s: SessionState): SessionView {
        val elapsed = s.stopwatch.elapsed(clock)
        val program = program(s)
        val next = s.lastHandledIndex + 1
        val hasNext = s.isActive && program != null && program.hasIndex(next)
        val nextOffset = if (hasNext) program!!.offsetMs(next) else null
        return SessionView(
            elapsedMs = elapsed,
            nextIndex = if (hasNext) next else null,
            nextOffsetMs = nextOffset,
            countdownMs = nextOffset?.let { (it - elapsed).coerceAtLeast(0L) },
            totalCount = program?.count,
        )
    }

    fun titleFor(s: SessionState): String = when (s.mode) {
        Mode.PUMP -> "Prueba de Bombeo"
        Mode.CYCLIC -> s.activityName.ifBlank { "Temporizador Cíclico" }
        Mode.TEST -> "Prueba de alarma"
    }

    fun labelFor(s: SessionState, program: AlarmProgram, index: Int): String = when (s.mode) {
        Mode.PUMP -> "MINUTO ${program.offsetMs(index) / PumpSchedule.MINUTE_MS}" +
            if (index == program.count) " (final)" else ""
        Mode.CYCLIC -> "Aviso $index" + (program.count?.let { " de $it" } ?: "")
        Mode.TEST -> "Prueba"
    }

    companion object {
        const val PUMP_FINISHED_TEXT = "Prueba de bombeo finalizada"

        /** Una alarma que llega hasta 2 s antes de su hora (ajuste de reloj) se considera puntual. */
        const val EARLY_TOLERANCE_MS = 2_000L

        /** Margen antes de declarar perdido un aviso al restaurar (evita carreras con la alarma real). */
        const val GRACE_MS = 30_000L
    }
}
