package com.temporizador.ciclico.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIN = 60_000L

class PumpEngineTest {

    @Test
    fun alarmasCalculadasRespectoDelInicioExacto() {
        val h = Harness()
        val start = h.clock.wall
        h.engine.startPump(PumpConfig(durationMin = 1440))
        val first = h.scheduler.pending[Mode.PUMP]!!
        assertEquals(1, first.index)
        assertEquals(start + 1 * MIN, first.triggerWallMs)

        val rings = h.deliverAll(Mode.PUMP)
        val expected = PumpSchedule.minutes(1440)
        assertEquals(expected.size, rings.size)
        // Cada alarma programada cae exactamente en inicio + minuto del programa
        val triggers = h.scheduler.history.filter { it.mode == Mode.PUMP }.map { (it.triggerWallMs - start) / MIN }
        assertEquals(expected.map { it.toLong() }, triggers.distinct())
        assertEquals(expected.map { "MINUTO $it" + if (it == 1440) " · Prueba de bombeo finalizada" else "" }, rings.map { it.label })
        val s = h.engine.pump.value
        assertEquals(RunState.FINISHED, s.runState)
        assertEquals(44, s.alertsEmitted)
        assertEquals(0, s.alertsMissed)
        assertNull(h.scheduler.pending[Mode.PUMP])
        assertTrue(rings.last().finished)
    }

    @Test
    fun primerosMinutos1a9() {
        val h = Harness()
        h.engine.startPump(PumpConfig(durationMin = 1440))
        val rings = h.deliverAll(Mode.PUMP, maxAlarms = 7)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), rings.map { it.index })
        assertEquals(
            listOf("MINUTO 1", "MINUTO 2", "MINUTO 3", "MINUTO 4", "MINUTO 5", "MINUTO 7", "MINUTO 9"),
            rings.map { it.label },
        )
        // La siguiente alarma ya está programada para el minuto 11, sin intervención del usuario
        val next = h.scheduler.pending[Mode.PUMP]!!
        assertEquals(h.engine.pump.value.startWallMs + 11 * MIN, next.triggerWallMs)
    }

    @Test
    fun retrasoDeAndroidNoDesplazaLasSiguientes() {
        val h = Harness()
        val start = h.clock.wall
        h.engine.startPump(PumpConfig(durationMin = 1440))
        // Cada alarma llega con 20 s de retraso: la siguiente se sigue programando en el minuto exacto
        val rings = h.deliverAll(Mode.PUMP, deliveryDelayMs = 20_000L, maxAlarms = 10)
        assertEquals((1..10).toList(), rings.map { it.index })
        val next = h.scheduler.pending[Mode.PUMP]!!
        assertEquals(start + 20 * MIN, next.triggerWallMs) // aviso 11 = minuto 20
        assertEquals(20_000L, h.engine.pump.value.lastAlertDelayMs)
    }

    @Test
    fun noReiniciaIntervalosTrasCadaAviso() {
        val h = Harness()
        val start = h.clock.wall
        h.engine.startPump(PumpConfig(durationMin = 1440))
        h.deliverAll(Mode.PUMP, maxAlarms = 5) // hasta minuto 5
        // El aviso 6 es el minuto 7 absoluto (no "5 + 2 desde que sonó")
        assertEquals(start + 7 * MIN, h.scheduler.pending[Mode.PUMP]!!.triggerWallMs)
        assertEquals(5 * MIN, h.engine.view(h.engine.pump.value).elapsedMs)
        assertEquals(2 * MIN, h.engine.view(h.engine.pump.value).countdownMs)
    }

    @Test
    fun entregaDuplicadaNoSuenaDosVeces() {
        val h = Harness()
        h.engine.startPump(PumpConfig(durationMin = 60))
        val p = h.scheduler.pending[Mode.PUMP]!!
        h.clock.advance(MIN)
        assertTrue(h.engine.onAlarm(Mode.PUMP, p.sessionId, p.index).ring)
        assertFalse(h.engine.onAlarm(Mode.PUMP, p.sessionId, p.index).ring)
        assertEquals(1, h.engine.pump.value.alertsEmitted)
    }

    @Test
    fun alarmasAtrasadasNoSeAcumulan() {
        val h = Harness()
        h.engine.startPump(PumpConfig(durationMin = 1440))
        val p = h.scheduler.pending[Mode.PUMP]!!
        // La alarma del minuto 1 llega tarde, en el minuto 4:30 → suena UNA vez (minuto 4)
        h.clock.advance(4 * MIN + 30_000)
        val o = h.engine.onAlarm(Mode.PUMP, p.sessionId, p.index)
        assertTrue(o.ring)
        assertEquals("MINUTO 4", o.label)
        val s = h.engine.pump.value
        assertEquals(1, s.alertsEmitted)
        assertEquals(3, s.alertsMissed)
        assertEquals(5, h.scheduler.pending[Mode.PUMP]!!.index) // siguiente: minuto 5
    }

    @Test
    fun alarmaDeSesionAnteriorSeIgnora() {
        val h = Harness()
        h.engine.startPump(PumpConfig(durationMin = 60))
        val old = h.scheduler.pending[Mode.PUMP]!!
        h.engine.stop(Mode.PUMP)
        h.clock.advance(1000)
        h.engine.startPump(PumpConfig(durationMin = 60))
        h.clock.advance(MIN)
        assertFalse(h.engine.onAlarm(Mode.PUMP, old.sessionId, old.index).ring)
    }

    @Test
    fun detenerCancelaTodasLasAlarmas() {
        val h = Harness()
        h.engine.startPump(PumpConfig(durationMin = 1440))
        h.deliverAll(Mode.PUMP, maxAlarms = 3)
        h.clock.advance(20_000)
        h.engine.stop(Mode.PUMP)
        assertNull(h.scheduler.pending[Mode.PUMP])
        assertEquals(RunState.IDLE, h.engine.pump.value.runState)
        assertFalse(h.engine.anyActive)
        // Una alarma que llegara igualmente tras detener no suena
        val sid = h.engine.pump.value.sessionId
        assertFalse(h.engine.onAlarm(Mode.PUMP, sid, 4).ring)
        // Tras reabrir la app no se reprograma nada
        h.recreateProcess()
        h.engine.restore(RestoreReason.APP_OPEN)
        assertNull(h.scheduler.pending[Mode.PUMP])
    }

    @Test
    fun cerrarYReabrirRecuperaTiempoReal() {
        val h = Harness()
        h.engine.startPump(PumpConfig(durationMin = 1440))
        h.deliverAll(Mode.PUMP, maxAlarms = 2)
        // App en segundo plano 30 min, con las alarmas sonando normalmente
        h.recreateProcess()
        h.deliverAll(Mode.PUMP, maxAlarms = 11) // hasta minuto 30 (avisos 3..13)
        h.clock.advance(5 * MIN) // minuto 35
        h.recreateProcess()
        h.engine.restore(RestoreReason.APP_OPEN)
        val s = h.engine.pump.value
        val v = h.engine.view(s)
        assertEquals(RunState.RUNNING, s.runState)
        assertEquals(35 * MIN, v.elapsedMs)
        assertEquals(13, s.alertsEmitted)
        assertEquals(0, s.alertsMissed)
        assertEquals(14, v.nextIndex)
        assertEquals(40 * MIN, v.nextOffsetMs)
        assertEquals(5 * MIN, v.countdownMs)
    }

    @Test
    fun reinicioDelTelefonoNoReproduceAlarmasPerdidas() {
        val h = Harness()
        val start = h.clock.wall
        h.engine.startPump(PumpConfig(durationMin = 1440))
        h.deliverAll(Mode.PUMP, maxAlarms = 16) // hasta minuto 60
        h.clock.advance(2 * MIN) // minuto 62
        // Teléfono apagado 40 minutos → arranca en el minuto 102
        h.clock.reboot(40 * MIN)
        h.scheduler.pending.clear() // Android borra las alarmas al apagarse
        h.recreateProcess()
        h.engine.restore(RestoreReason.BOOT)
        val s = h.engine.pump.value
        assertEquals(102 * MIN, h.engine.view(s).elapsedMs)
        assertEquals(16, s.alertsEmitted)
        assertEquals(2, s.alertsMissed) // minutos 75 y 90 perdidos, no se reproducen
        val next = h.scheduler.pending[Mode.PUMP]!!
        assertEquals(start + 105 * MIN, next.triggerWallMs)
        // Tras el reinicio el tiempo sigue siendo correcto con el nuevo reloj monotónico
        val rings = h.deliverAll(Mode.PUMP, maxAlarms = 1)
        assertEquals("MINUTO 105", rings.single().label)
    }

    @Test
    fun cambioDeHoraNoAlteraElPrograma() {
        val h = Harness()
        h.engine.startPump(PumpConfig(durationMin = 1440))
        h.deliverAll(Mode.PUMP, maxAlarms = 5) // minuto 5
        h.clock.advance(30_000) // 5:30
        // El usuario adelanta el reloj 1 hora (o cambia horario de verano)
        h.clock.shiftWall(60 * MIN)
        h.engine.restore(RestoreReason.TIME_CHANGED)
        val s = h.engine.pump.value
        assertEquals(5 * MIN + 30_000, h.engine.view(s).elapsedMs) // no salta 1 hora
        assertEquals(0, s.alertsMissed)
        // La alarma del minuto 7 se reprograma en la nueva hora de pared: 1:30 después de ahora
        assertEquals(h.clock.wall + 90_000, h.scheduler.pending[Mode.PUMP]!!.triggerWallMs)
    }

    @Test
    fun noHayAlarmasDuplicadasPendientes() {
        val h = Harness()
        h.engine.startPump(PumpConfig(durationMin = 1440))
        repeat(5) { h.engine.restore(RestoreReason.APP_OPEN) }
        assertEquals(1, h.scheduler.pending.size)
        val indices = h.deliverAll(Mode.PUMP).map { it.index }
        assertEquals(indices.distinct(), indices)
    }

    @Test
    fun pruebaCortaFinalizaEnLaDuracionElegida() {
        val h = Harness()
        h.engine.startPump(PumpConfig(durationMin = 100))
        val rings = h.deliverAll(Mode.PUMP)
        assertEquals("MINUTO 100 · Prueba de bombeo finalizada", rings.last().label)
        assertEquals(19, rings.size)
        assertEquals(RunState.FINISHED, h.engine.pump.value.runState)
    }
}

class CyclicEngineTest {

    @Test
    fun avisosRepetidosCadaIntervalo() {
        val h = Harness()
        val start = h.clock.wall
        h.engine.startCyclic(CyclicConfig(intervalText = "5m", maxAlerts = 0))
        val rings = h.deliverAll(Mode.CYCLIC, maxAlarms = 6)
        assertEquals(6, rings.size)
        assertEquals((1..6).map { start + it * 5 * MIN }, h.scheduler.history.map { it.triggerWallMs }.take(6))
        assertEquals(RunState.RUNNING, h.engine.cyclic.value.runState)
        assertEquals(start + 35 * MIN, h.scheduler.pending[Mode.CYCLIC]!!.triggerWallMs)
    }

    @Test
    fun segundosYSecuencias() {
        val h = Harness()
        val start = h.clock.wall
        h.engine.startCyclic(CyclicConfig(intervalText = "1m, 30s", maxAlerts = 5))
        val rings = h.deliverAll(Mode.CYCLIC)
        assertEquals(5, rings.size)
        val offsets = h.scheduler.history.map { (it.triggerWallMs - start) / 1000 }
        assertEquals(listOf(60L, 90L, 150L, 180L, 240L), offsets)
        assertEquals(RunState.FINISHED, h.engine.cyclic.value.runState)
        assertTrue(rings.last().finished)
    }

    @Test
    fun pausaReanudacionYDetencion() {
        val h = Harness()
        val start = h.clock.wall
        h.engine.startCyclic(CyclicConfig(intervalText = "10m"))
        h.clock.advance(4 * MIN)
        h.engine.pause(Mode.CYCLIC)
        assertEquals(RunState.PAUSED, h.engine.cyclic.value.runState)
        assertNull(h.scheduler.pending[Mode.CYCLIC])
        // 30 min en pausa: no corre el tiempo
        h.clock.advance(30 * MIN)
        h.recreateProcess()
        h.engine.restore(RestoreReason.APP_OPEN)
        assertEquals(4 * MIN, h.engine.view(h.engine.cyclic.value).elapsedMs)
        assertEquals(6 * MIN, h.engine.view(h.engine.cyclic.value).countdownMs)
        assertNull(h.scheduler.pending[Mode.CYCLIC])

        h.engine.resume(Mode.CYCLIC)
        assertEquals(RunState.RUNNING, h.engine.cyclic.value.runState)
        // Faltaban 6 min: la alarma queda a 6 min de ahora
        assertEquals(h.clock.wall + 6 * MIN, h.scheduler.pending[Mode.CYCLIC]!!.triggerWallMs)
        val r = h.deliverAll(Mode.CYCLIC, maxAlarms = 2)
        assertEquals(2, r.size)
        assertEquals(start + 50 * MIN, h.clock.wall) // avisos en 40 y 50 min de reloj (34 + 6, + 10)

        h.engine.stop(Mode.CYCLIC)
        assertEquals(RunState.IDLE, h.engine.cyclic.value.runState)
        assertNull(h.scheduler.pending[Mode.CYCLIC])
        assertEquals(2, h.engine.cyclic.value.alertsEmitted)
    }

    @Test
    fun alarmaRecibidaEnPausaNoSuena() {
        val h = Harness()
        h.engine.startCyclic(CyclicConfig(intervalText = "1m"))
        val p = h.scheduler.pending[Mode.CYCLIC]!!
        h.clock.advance(59_000)
        h.engine.pause(Mode.CYCLIC)
        h.clock.advance(5_000)
        assertFalse(h.engine.onAlarm(Mode.CYCLIC, p.sessionId, p.index).ring)
    }

    @Test
    fun modosIndependientes() {
        val h = Harness()
        h.engine.startPump(PumpConfig(durationMin = 1440))
        h.engine.startCyclic(CyclicConfig(intervalText = "30s"))
        assertEquals(2, h.scheduler.pending.size)
        h.engine.stop(Mode.CYCLIC)
        assertEquals(RunState.RUNNING, h.engine.pump.value.runState)
        assertTrue(h.scheduler.pending.containsKey(Mode.PUMP))
    }

    @Test(expected = IllegalArgumentException::class)
    fun intervaloInvalidoNoInicia() {
        Harness().engine.startCyclic(CyclicConfig(intervalText = "abc"))
    }
}

class PersistenceTest {

    @Test
    fun configuracionPersistente() {
        val h = Harness()
        val pump = PumpConfig(durationMin = 48 * 60, alert = AlertConfig(SoundType.SUAVE, 35, false))
        val cyc = CyclicConfig("Purga pozo 3", "1m, 30s", 12, AlertConfig(SoundType.PIP, 7, true))
        h.engine.store.savePumpConfig(pump)
        h.engine.store.saveCyclicConfig(cyc)
        h.recreateProcess()
        assertEquals(pump, h.engine.store.loadPumpConfig())
        assertEquals(cyc, h.engine.store.loadCyclicConfig())
    }

    @Test
    fun valoresPredeterminados() {
        val h = Harness()
        val p = h.engine.store.loadPumpConfig()
        assertEquals(1440, p.durationMin)
        assertEquals(20, p.alert.durationSec)
        assertEquals(20, h.engine.store.loadCyclicConfig().alert.durationSec)
    }

    @Test
    fun estadoDeSesionPersistente() {
        val h = Harness()
        h.engine.startPump(PumpConfig(durationMin = 720, alert = AlertConfig(SoundType.PIP, 15, true)))
        h.deliverAll(Mode.PUMP, maxAlarms = 3)
        val before = h.engine.pump.value
        h.recreateProcess()
        assertEquals(before, h.engine.pump.value)
        assertEquals(720, h.engine.pump.value.pumpDurationMin)
        assertEquals(SoundType.PIP, h.engine.pump.value.alert.sound)
        assertEquals(3, h.engine.pump.value.alertsEmitted)
        assertTrue(h.engine.pump.value.nextAlarmWallMs > 0)
    }

    @Test
    fun pruebaDeAlarma() {
        val h = Harness()
        val t = h.engine.scheduleTest(60_000)
        assertTrue(t.pending)
        val p = h.scheduler.pending[Mode.TEST]!!
        h.clock.advance(60_400)
        val o = h.engine.onAlarm(Mode.TEST, p.sessionId, 1)
        assertTrue(o.ring)
        assertTrue(o.alert.durationSec <= 10)
        assertEquals(400L, h.engine.test.value.firedWallMs - h.engine.test.value.scheduledWallMs)
        assertFalse(h.engine.onAlarm(Mode.TEST, p.sessionId, 1).ring)
    }
}
