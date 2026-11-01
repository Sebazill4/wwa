package com.temporizador.ciclico.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PumpScheduleTest {

    private val expected24h = listOf(
        1, 2, 3, 4, 5, 7, 9, 11, 13, 15, 20, 25, 30, 40, 50, 60, 75, 90, 105, 120,
        150, 180, 210, 240, 300, 360, 420, 480, 540, 600, 660, 720, 780, 840, 900,
        960, 1020, 1080, 1140, 1200, 1260, 1320, 1380, 1440,
    )

    @Test
    fun secuenciaExactaDe24Horas() {
        assertEquals(expected24h, PumpSchedule.minutes(24 * 60))
        assertEquals(44, PumpSchedule.minutes(24 * 60).size)
    }

    @Test
    fun sinDuplicadosYEstrictamenteCreciente() {
        for (d in listOf(1, 2, 5, 6, 14, 15, 16, 59, 60, 61, 100, 239, 240, 241, 360, 720, 1440, 2880, 4320, 43200)) {
            val m = PumpSchedule.minutes(d)
            assertEquals("duplicados en $d", m.size, m.toSet().size)
            for (i in 1 until m.size) assertTrue("no creciente en $d", m[i] > m[i - 1])
            assertEquals("último aviso debe ser la duración $d", d, m.last())
        }
    }

    @Test
    fun intervalosPorTramo() {
        val m = listOf(0) + PumpSchedule.minutes(72 * 60)
        for (i in 1 until m.size) {
            val step = m[i] - m[i - 1]
            val start = m[i - 1]
            val expected = when {
                start < 5 -> 1
                start < 15 -> 2
                start < 30 -> 5
                start < 60 -> 10
                start < 120 -> 15
                start < 240 -> 30
                else -> 60
            }
            assertEquals("tramo desde minuto $start", expected, step)
        }
    }

    @Test
    fun masDe24HorasContinuaCada60Minutos() {
        val m48 = PumpSchedule.minutes(48 * 60)
        assertEquals(expected24h, m48.take(44))
        assertEquals((25..48).map { it * 60 }, m48.drop(44))
        val m72 = PumpSchedule.minutes(72 * 60)
        assertEquals(4320, m72.last())
        assertEquals(44 + 48, m72.size)
    }

    @Test
    fun duracionesPredefinidas() {
        assertEquals(expected24h.filter { it <= 360 }, PumpSchedule.minutes(6 * 60))
        assertEquals(expected24h.filter { it <= 720 }, PumpSchedule.minutes(12 * 60))
    }

    @Test
    fun duracionMenorConAvisoFinalUnico() {
        // 100 min: ... 75, 90 y aviso final en 100 (no en 105)
        assertEquals(listOf(1, 2, 3, 4, 5, 7, 9, 11, 13, 15, 20, 25, 30, 40, 50, 60, 75, 90, 100), PumpSchedule.minutes(100))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), PumpSchedule.minutes(6))
        assertEquals(listOf(1, 2, 3, 4, 5), PumpSchedule.minutes(5))
        assertEquals(listOf(1), PumpSchedule.minutes(1))
        // 250 min: ..., 240 y final 250
        assertEquals(listOf(210, 240, 250), PumpSchedule.minutes(250).takeLast(3))
    }

    @Test
    fun programaYIndiceVencido() {
        val p = PumpSchedule.program(1440)
        assertEquals(44, p.count)
        assertEquals(60_000L, p.offsetMs(1))
        assertEquals(1440 * 60_000L, p.offsetMs(44))
        assertEquals(0, p.dueIndex(59_999))
        assertEquals(1, p.dueIndex(60_000))
        assertEquals(5, p.dueIndex(6 * 60_000L + 59_000)) // minuto 6:59 → último vencido = minuto 5
        assertEquals(6, p.dueIndex(7 * 60_000L))
        assertEquals(44, p.dueIndex(10_000 * 60_000L))
    }

    @Test
    fun duracionPersonalizada() {
        assertEquals(36 * 60, PumpSchedule.parseDuration("36"))
        assertEquals(90, PumpSchedule.parseDuration("1.5"))
        assertEquals(90, PumpSchedule.parseDuration("1,5"))
        assertEquals(8 * 60, PumpSchedule.parseDuration("8h"))
        assertEquals(90, PumpSchedule.parseDuration("90m"))
        assertEquals(90, PumpSchedule.parseDuration("90 min"))
        assertEquals(150, PumpSchedule.parseDuration("2h30m"))
        assertEquals(150, PumpSchedule.parseDuration("2 h 30 min"))
        assertNull(PumpSchedule.parseDuration(""))
        assertNull(PumpSchedule.parseDuration("abc"))
        assertNull(PumpSchedule.parseDuration("0"))
        assertNull(PumpSchedule.parseDuration("1000"))
    }
}
