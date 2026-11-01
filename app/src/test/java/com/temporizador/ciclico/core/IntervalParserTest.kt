package com.temporizador.ciclico.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntervalParserTest {

    @Test
    fun unidades() {
        assertEquals(30_000L, IntervalParser.parseOne("30s"))
        assertEquals(30_000L, IntervalParser.parseOne("30 seg"))
        assertEquals(300_000L, IntervalParser.parseOne("5m"))
        assertEquals(300_000L, IntervalParser.parseOne("5 min"))
        assertEquals(300_000L, IntervalParser.parseOne("5"))
        assertEquals(90_000L, IntervalParser.parseOne("1m30s"))
        assertEquals(90_000L, IntervalParser.parseOne("1:30"))
        assertEquals(90_000L, IntervalParser.parseOne("1.5m"))
        assertEquals(3_600_000L, IntervalParser.parseOne("1h"))
        assertEquals(5_400_000L, IntervalParser.parseOne("1h30m"))
        assertEquals(120_000L, IntervalParser.parseOne("2 minutos"))
    }

    @Test
    fun invalidos() {
        assertNull(IntervalParser.parseOne(""))
        assertNull(IntervalParser.parseOne("abc"))
        assertNull(IntervalParser.parseOne("2s")) // menor que 5 s
        assertNull(IntervalParser.parseOne("25h"))
        assertNull(IntervalParser.parseOne("1:75"))
        assertNull(IntervalParser.parseSequence("1m, x"))
        assertNull(IntervalParser.parseSequence(" , "))
    }

    @Test
    fun secuencias() {
        assertEquals(listOf(60_000L, 30_000L, 120_000L), IntervalParser.parseSequence("1m, 30s, 2m"))
        assertEquals(listOf(300_000L, 600_000L), IntervalParser.parseSequence("5,10"))
        assertEquals(listOf(60_000L, 30_000L), IntervalParser.parseSequence("1m;30s"))
    }

    @Test
    fun formato() {
        assertEquals("30s", IntervalParser.format(30_000))
        assertEquals("5m", IntervalParser.format(300_000))
        assertEquals("1m30s", IntervalParser.format(90_000))
        assertEquals("1h", IntervalParser.format(3_600_000))
    }

    @Test
    fun programaCiclico() {
        val p = CyclicProgram(listOf(60_000L, 30_000L), 0)
        assertNull(p.count)
        assertEquals(listOf(60_000L, 90_000L, 150_000L, 180_000L, 240_000L), (1..5).map { p.offsetMs(it) })
        assertEquals(0, p.dueIndex(59_999))
        assertEquals(1, p.dueIndex(60_000))
        assertEquals(2, p.dueIndex(149_999))
        assertEquals(3, p.dueIndex(150_000))
        assertEquals(4, p.dueIndex(180_000))
        val limited = CyclicProgram(listOf(60_000L), 3)
        assertEquals(3, limited.dueIndex(10 * 60_000L))
        assertEquals(3, limited.count)
    }
}
