package com.temporizador.ciclico.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Escenario obligatorio: prueba iniciada a las 09:00:00 (hora de Chile). */
class NineOClockTest {
    private val zone = ZoneId.of("America/Santiago")
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone)

    private fun startAt9(): Harness {
        val h = Harness()
        h.clock.wall = LocalDateTime.of(2026, 11, 2, 9, 0, 0).atZone(zone).toInstant().toEpochMilli()
        h.engine.startPump(PumpConfig(durationMin = 1440))
        return h
    }

    private fun Harness.ringTimes(n: Int): List<String> {
        val out = ArrayList<String>()
        repeat(n) {
            val p = scheduler.pending[Mode.PUMP]!!
            clock.advance(p.triggerWallMs - clock.wall)
            scheduler.pending.remove(Mode.PUMP)
            assertTrue(engine.onAlarm(Mode.PUMP, p.sessionId, p.index).ring)
            out += hhmm.format(Instant.ofEpochMilli(clock.wall))
        }
        return out
    }

    @Test
    fun suenaA0901_0905_yLuegoCadaDosMinutos() {
        val h = startAt9()
        assertEquals(
            listOf("09:01:00", "09:02:00", "09:03:00", "09:04:00", "09:05:00",
                "09:07:00", "09:09:00", "09:11:00", "09:13:00", "09:15:00",
                "09:20:00", "09:25:00", "09:30:00", "09:40:00", "09:50:00", "10:00:00",
                "10:15:00", "10:30:00", "10:45:00", "11:00:00",
                "11:30:00", "12:00:00", "12:30:00", "13:00:00", "14:00:00"),
            h.ringTimes(25),
        )
    }

    @Test
    fun continua24HorasSinReiniciarElCronometro() {
        val h = startAt9()
        val times = h.ringTimes(44)
        assertEquals("09:00:00", times.last()) // día siguiente, minuto 1440
        val s = h.engine.pump.value
        assertEquals(RunState.FINISHED, s.runState)
        assertEquals(44, s.alertsEmitted)
        assertEquals(1440 * 60_000L, s.stopwatch.elapsed(h.clock))
        assertEquals(44, times.toSet().size) // 44 horas distintas: sin avisos duplicados
    }

    @Test
    fun reabrirLaAppCadaMinutoNoDuplicaNiPierdeTiempo() {
        val h = startAt9()
        var rings = 0
        // 15 minutos, segundo a segundo, reabriendo la app (proceso nuevo) cada 30 s
        for (sec in 1..15 * 60) {
            h.clock.advance(1000)
            if (sec % 30 == 0) {
                h.recreateProcess()
                h.engine.restore(RestoreReason.APP_OPEN)
                assertEquals(sec * 1000L, h.engine.view(h.engine.pump.value).elapsedMs)
            }
            val p = h.scheduler.pending[Mode.PUMP] ?: continue
            if (p.triggerWallMs <= h.clock.wall) {
                h.scheduler.pending.remove(Mode.PUMP)
                if (h.engine.onAlarm(Mode.PUMP, p.sessionId, p.index).ring) rings++
            }
        }
        assertEquals(10, rings) // minutos 1,2,3,4,5,7,9,11,13,15
        assertEquals(0, h.engine.pump.value.alertsMissed)
    }
}
