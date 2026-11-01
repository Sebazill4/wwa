package com.temporizador.ciclico.core

/**
 * Ancla temporal: guarda simultáneamente el reloj de pared, el reloj monotónico
 * (elapsedRealtime, que sigue contando con la pantalla apagada y en Doze y no se ve
 * afectado por cambios de hora) y el número de arranque del dispositivo.
 */
data class TimeAnchor(val wallMs: Long, val elapsedMs: Long, val bootCount: Int)

interface Clock {
    /** System.currentTimeMillis() */
    fun wallNow(): Long

    /** SystemClock.elapsedRealtime() */
    fun elapsedNow(): Long

    /** Settings.Global.BOOT_COUNT, o -1 si no está disponible. */
    fun bootCount(): Int

    fun anchor(): TimeAnchor = TimeAnchor(wallNow(), elapsedNow(), bootCount())

    /**
     * Milisegundos transcurridos desde el ancla. Si el teléfono no se ha reiniciado,
     * se usa el reloj monotónico (inmune a cambios de hora). Tras un reinicio solo
     * queda el reloj de pared.
     */
    fun millisSince(a: TimeAnchor): Long {
        val now = elapsedNow()
        val sameBoot = now >= a.elapsedMs && (a.bootCount == -1 || bootCount() == -1 || a.bootCount == bootCount())
        val diff = if (sameBoot) now - a.elapsedMs else wallNow() - a.wallMs
        return diff.coerceAtLeast(0L)
    }

    fun isSameBoot(a: TimeAnchor): Boolean {
        val now = elapsedNow()
        return now >= a.elapsedMs && (a.bootCount == -1 || bootCount() == -1 || a.bootCount == bootCount())
    }
}
