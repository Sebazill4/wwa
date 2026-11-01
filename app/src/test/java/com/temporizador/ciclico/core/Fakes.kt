package com.temporizador.ciclico.core

/** Reloj simulado: permite avanzar el tiempo, cambiar la hora del sistema y simular reinicios. */
class FakeClock(var wall: Long = 1_750_000_000_000L, var elapsed: Long = 5_000_000L, var boot: Int = 7) : Clock {
    override fun wallNow() = wall
    override fun elapsedNow() = elapsed
    override fun bootCount() = boot

    fun advance(ms: Long) {
        wall += ms; elapsed += ms
    }

    /** Teléfono apagado durante [offMs]: el reloj monotónico vuelve a empezar. */
    fun reboot(offMs: Long) {
        wall += offMs
        elapsed = 12_000L
        boot += 1
    }

    /** El usuario (o la red) cambia la hora de pared; el reloj monotónico no cambia. */
    fun shiftWall(deltaMs: Long) {
        wall += deltaMs
    }
}

data class Scheduled(val mode: Mode, val sessionId: Long, val index: Int, val triggerWallMs: Long)

class FakeScheduler : AlarmScheduler {
    val pending = LinkedHashMap<Mode, Scheduled>()
    val history = ArrayList<Scheduled>()
    var cancels = 0

    override fun schedule(mode: Mode, sessionId: Long, index: Int, triggerWallMs: Long) {
        val s = Scheduled(mode, sessionId, index, triggerWallMs)
        pending[mode] = s // un único PendingIntent por modo: reemplaza
        history += s
    }

    override fun cancel(mode: Mode) {
        pending.remove(mode)
        cancels++
    }
}

class Harness {
    val kv = InMemoryStore()
    val clock = FakeClock()
    val scheduler = FakeScheduler()
    var engine = TimerEngine(kv, clock, scheduler)

    /** Simula cerrar la app (proceso destruido) y volver a abrirla. */
    fun recreateProcess() {
        engine = TimerEngine(kv, clock, scheduler)
    }

    /**
     * Simula a Android entregando cada alarma pendiente a su hora (más [deliveryDelayMs]),
     * hasta que no haya más o se supere [untilElapsedFromNowMs]. Devuelve los avisos que sonaron.
     */
    fun deliverAll(mode: Mode, deliveryDelayMs: Long = 0L, maxAlarms: Int = 10_000): List<AlarmOutcome> {
        val rings = ArrayList<AlarmOutcome>()
        repeat(maxAlarms) {
            val p = scheduler.pending[mode] ?: return rings
            val target = p.triggerWallMs + deliveryDelayMs
            if (target > clock.wall) clock.advance(target - clock.wall)
            scheduler.pending.remove(mode)
            val o = engine.onAlarm(mode, p.sessionId, p.index)
            if (o.ring) rings += o
        }
        return rings
    }
}
