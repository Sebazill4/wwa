package com.temporizador.ciclico.core

/**
 * Programa de avisos: cada aviso i (1..count) tiene un desfase fijo respecto del
 * instante de inicio. El desfase NO depende de cuándo sonó realmente el aviso anterior,
 * así un retraso de Android nunca desplaza los avisos siguientes.
 */
interface AlarmProgram {
    /** null = sin límite. */
    val count: Int?

    /** Desfase en ms del aviso [index] (1-based) respecto del inicio. */
    fun offsetMs(index: Int): Long

    /** Cantidad de avisos cuyo desfase es <= [elapsedMs] (= índice del último aviso vencido). */
    fun dueIndex(elapsedMs: Long): Int

    fun hasIndex(index: Int): Boolean = index >= 1 && (count == null || index <= count!!)
}

object PumpSchedule {
    const val MINUTE_MS = 60_000L

    /** Duraciones rápidas (horas). */
    val PRESET_HOURS = listOf(6, 12, 24, 48, 72)

    /**
     * Frecuencia de medición según el tiempo transcurrido (minutos).
     * 0–5: 1 · 5–15: 2 · 15–30: 5 · 30–60: 10 · 60–120: 15 · 120–240: 30 · ≥240: 60
     */
    fun stepAt(minute: Int): Int = when {
        minute < 5 -> 1
        minute < 15 -> 2
        minute < 30 -> 5
        minute < 60 -> 10
        minute < 120 -> 15
        minute < 240 -> 30
        else -> 60
    }

    /**
     * Minutos de medición para una prueba de [durationMin] minutos.
     * Si la duración no coincide con un punto del programa, se agrega un único aviso
     * final exactamente en la duración elegida. Nunca hay duplicados.
     */
    fun minutes(durationMin: Int): List<Int> {
        require(durationMin >= 1) { "La duración debe ser de al menos 1 minuto" }
        val out = ArrayList<Int>()
        var t = 0
        while (true) {
            t += stepAt(t)
            if (t >= durationMin) {
                out.add(durationMin)
                break
            }
            out.add(t)
        }
        return out
    }

    const val MAX_DURATION_MIN = 30 * 24 * 60

    /**
     * Duración personalizada: número solo = horas ("36", "1.5"); con unidad: "36h", "90m", "90 min",
     * "2h30m". Devuelve minutos enteros, o null si no es válida.
     */
    fun parseDuration(raw: String): Int? {
        val text = raw.trim().lowercase().replace(',', '.').replace(" ", "")
        if (text.isEmpty()) return null
        text.toDoubleOrNull()?.let { h -> return validDuration(Math.round(h * 60).toInt()) }
        val m = Regex("""^(?:(\d+(?:\.\d+)?)(?:h|hr|hrs|hora|horas))?(?:(\d+)(?:m|min|mins|minuto|minutos))?$""").matchEntire(text)
            ?: return null
        val h = m.groupValues[1].takeIf { it.isNotEmpty() }?.toDouble() ?: 0.0
        val mi = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        if (m.groupValues[1].isEmpty() && m.groupValues[2].isEmpty()) return null
        return validDuration(Math.round(h * 60).toInt() + mi)
    }

    private fun validDuration(min: Int): Int? = if (min in 1..MAX_DURATION_MIN) min else null

    fun program(durationMin: Int): AlarmProgram = ListProgram(minutes(durationMin).map { it * MINUTE_MS })
}

/** Programa finito definido por una lista ordenada de desfases. */
class ListProgram(private val offsets: List<Long>) : AlarmProgram {
    init {
        for (i in 1 until offsets.size) require(offsets[i] > offsets[i - 1]) { "Desfases no crecientes" }
    }

    override val count: Int get() = offsets.size
    override fun offsetMs(index: Int): Long = offsets[index - 1]

    override fun dueIndex(elapsedMs: Long): Int {
        // Búsqueda binaria: cantidad de desfases <= elapsedMs
        var lo = 0
        var hi = offsets.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (offsets[mid] <= elapsedMs) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/**
 * Programa cíclico: repite indefinidamente (o hasta [maxAlerts]) la secuencia de
 * intervalos. Ej.: [5 min] → 5, 10, 15... ; [1 min, 30 s] → 1:00, 1:30, 2:30, 3:00...
 */
class CyclicProgram(intervalsMs: List<Long>, private val maxAlerts: Int) : AlarmProgram {
    private val intervals = intervalsMs.toList()
    private val prefix: LongArray // prefix[r] = suma de los r primeros intervalos
    private val cycle: Long

    init {
        require(intervals.isNotEmpty()) { "Sin intervalos" }
        require(intervals.all { it > 0 }) { "Intervalo no válido" }
        prefix = LongArray(intervals.size + 1)
        for (i in intervals.indices) prefix[i + 1] = prefix[i] + intervals[i]
        cycle = prefix[intervals.size]
    }

    override val count: Int? get() = if (maxAlerts > 0) maxAlerts else null

    override fun offsetMs(index: Int): Long {
        val n = intervals.size
        val q = index / n
        val r = index % n
        return q * cycle + prefix[r]
    }

    override fun dueIndex(elapsedMs: Long): Int {
        if (elapsedMs < 0) return 0
        val n = intervals.size
        val q = elapsedMs / cycle
        val rem = elapsedMs - q * cycle
        var r = 0
        while (r + 1 < n && prefix[r + 1] <= rem) r++
        val idx = q * n + r
        val capped = if (maxAlerts > 0) minOf(idx, maxAlerts.toLong()) else idx
        return capped.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

/** Interpreta intervalos escritos por el usuario. */
object IntervalParser {
    const val MIN_INTERVAL_MS = 5_000L
    const val MAX_INTERVAL_MS = 24 * 60 * 60_000L

    private val token = Regex("""(\d+(?:\.\d+)?)\s*(horas|hora|hrs|hr|h|minutos|minuto|mins|min|m|segundos|segundo|segs|seg|s)?""")

    /**
     * Un intervalo: "30s", "5m", "5", "1m30s", "1h", "1:30" (mm:ss), "1.5m".
     * Un número sin unidad se interpreta como minutos. Devuelve null si no es válido.
     */
    fun parseOne(raw: String): Long? {
        val text = raw.trim().lowercase()
        if (text.isEmpty()) return null
        if (text.matches(Regex("""\d{1,3}:\d{1,2}"""))) {
            val (m, s) = text.split(":").map { it.toLong() }
            if (s >= 60) return null
            return validate((m * 60 + s) * 1000)
        }
        var pos = 0
        var total = 0.0
        var any = false
        val compact = text.replace(" ", "")
        while (pos < compact.length) {
            val m = token.matchAt(compact, pos) ?: return null
            if (m.value.isEmpty()) return null
            val value = m.groupValues[1].toDoubleOrNull() ?: return null
            val unitMs = when (m.groupValues[2]) {
                "h", "hr", "hrs", "hora", "horas" -> 3_600_000.0
                "s", "seg", "segs", "segundo", "segundos" -> 1_000.0
                else -> 60_000.0
            }
            total += value * unitMs
            any = true
            pos = m.range.last + 1
        }
        if (!any) return null
        return validate(Math.round(total))
    }

    /** Secuencia separada por comas, punto y coma o "+": "1m, 30s, 2m". */
    fun parseSequence(raw: String): List<Long>? {
        val parts = raw.split(',', ';', '+', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return parts.map { parseOne(it) ?: return null }
    }

    private fun validate(ms: Long): Long? = if (ms in MIN_INTERVAL_MS..MAX_INTERVAL_MS) ms else null

    fun format(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return buildString {
            if (h > 0) append("${h}h")
            if (m > 0) append("${m}m")
            if (s > 0 || isEmpty()) append("${s}s")
        }
    }
}
