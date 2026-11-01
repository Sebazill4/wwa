package com.temporizador.ciclico.core

/** Almacén clave-valor mínimo (SharedPreferences en Android, memoria en pruebas). */
interface KeyValueStore {
    fun getString(key: String): String?

    /** Escritura atómica y síncrona. Un valor null elimina la clave. */
    fun putAll(values: Map<String, String?>)
}

class InMemoryStore : KeyValueStore {
    private val map = HashMap<String, String>()
    @Synchronized override fun getString(key: String): String? = map[key]
    @Synchronized override fun putAll(values: Map<String, String?>) {
        values.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
    }
}

/**
 * Serialización del estado y la configuración en claves simples.
 * Sin reflexión, para que funcione igual con R8 y en pruebas JVM.
 */
class StateStore(private val kv: KeyValueStore) {

    // ---------- Configuración ----------

    fun loadPumpConfig(): PumpConfig = PumpConfig(
        durationMin = int("cfg.pump.duration", 24 * 60).coerceAtLeast(1),
        alert = loadAlert("cfg.pump"),
    )

    fun savePumpConfig(c: PumpConfig) = kv.putAll(
        mapOf("cfg.pump.duration" to c.durationMin.toString()) + alertMap("cfg.pump", c.alert)
    )

    fun loadCyclicConfig(): CyclicConfig = CyclicConfig(
        activityName = kv.getString("cfg.cyc.name") ?: "Actividad",
        intervalText = kv.getString("cfg.cyc.interval") ?: "5m",
        maxAlerts = int("cfg.cyc.max", 0).coerceAtLeast(0),
        alert = loadAlert("cfg.cyc"),
    )

    fun saveCyclicConfig(c: CyclicConfig) = kv.putAll(
        mapOf(
            "cfg.cyc.name" to c.activityName,
            "cfg.cyc.interval" to c.intervalText,
            "cfg.cyc.max" to c.maxAlerts.toString(),
        ) + alertMap("cfg.cyc", c.alert)
    )

    var lastScreen: String?
        get() = kv.getString("ui.lastScreen")
        set(v) = kv.putAll(mapOf("ui.lastScreen" to v))

    private fun loadAlert(p: String) = AlertConfig(
        sound = SoundType.fromName(kv.getString("$p.sound")),
        durationSec = int("$p.alertSec", AlertConfig.DEFAULT_ALERT_SECONDS)
            .coerceIn(AlertConfig.MIN_ALERT_SECONDS, AlertConfig.MAX_ALERT_SECONDS),
        vibrate = kv.getString("$p.vibrate")?.toBooleanStrictOrNull() ?: true,
    )

    private fun alertMap(p: String, a: AlertConfig) = mapOf(
        "$p.sound" to a.sound.name,
        "$p.alertSec" to a.durationSec.toString(),
        "$p.vibrate" to a.vibrate.toString(),
    )

    // ---------- Estado de sesión ----------

    fun loadSession(mode: Mode): SessionState {
        val p = "st.${mode.name}"
        val runState = kv.getString("$p.run")?.let { n -> RunState.entries.firstOrNull { it.name == n } }
            ?: return SessionState(mode)
        val anchor = kv.getString("$p.anchor")?.let { decodeAnchor(it) }
        return SessionState(
            mode = mode,
            runState = runState,
            sessionId = long("$p.id"),
            startWallMs = long("$p.start"),
            stopwatch = Stopwatch(long("$p.acc"), anchor),
            lastHandledIndex = int("$p.handled", 0),
            alertsEmitted = int("$p.emitted", 0),
            alertsMissed = int("$p.missed", 0),
            nextAlarmWallMs = long("$p.next"),
            lastAlertIndex = int("$p.lastIdx", 0),
            lastAlertWallMs = long("$p.lastWall"),
            lastAlertDelayMs = long("$p.lastDelay"),
            finishedWallMs = long("$p.finished"),
            pumpDurationMin = int("$p.pumpDur", 0),
            cyclicIntervalsMs = kv.getString("$p.intervals")
                ?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList(),
            cyclicMaxAlerts = int("$p.max", 0),
            activityName = kv.getString("$p.name") ?: "",
            alert = loadAlert("$p.alert"),
        )
    }

    fun saveSession(s: SessionState) {
        val p = "st.${s.mode.name}"
        kv.putAll(
            mapOf(
                "$p.run" to s.runState.name,
                "$p.id" to s.sessionId.toString(),
                "$p.start" to s.startWallMs.toString(),
                "$p.acc" to s.stopwatch.accumulatedMs.toString(),
                "$p.anchor" to s.stopwatch.runningSince?.let { encodeAnchor(it) },
                "$p.handled" to s.lastHandledIndex.toString(),
                "$p.emitted" to s.alertsEmitted.toString(),
                "$p.missed" to s.alertsMissed.toString(),
                "$p.next" to s.nextAlarmWallMs.toString(),
                "$p.lastIdx" to s.lastAlertIndex.toString(),
                "$p.lastWall" to s.lastAlertWallMs.toString(),
                "$p.lastDelay" to s.lastAlertDelayMs.toString(),
                "$p.finished" to s.finishedWallMs.toString(),
                "$p.pumpDur" to s.pumpDurationMin.toString(),
                "$p.intervals" to s.cyclicIntervalsMs.joinToString(","),
                "$p.max" to s.cyclicMaxAlerts.toString(),
                "$p.name" to s.activityName,
            ) + alertMap("$p.alert", s.alert)
        )
    }

    fun loadTest(): TestAlarmState = TestAlarmState(
        scheduledWallMs = long("test.scheduled"),
        sessionId = long("test.id"),
        firedWallMs = long("test.fired"),
        pending = kv.getString("test.pending")?.toBooleanStrictOrNull() ?: false,
    )

    fun saveTest(t: TestAlarmState) = kv.putAll(
        mapOf(
            "test.scheduled" to t.scheduledWallMs.toString(),
            "test.id" to t.sessionId.toString(),
            "test.fired" to t.firedWallMs.toString(),
            "test.pending" to t.pending.toString(),
        )
    )

    private fun int(k: String, def: Int) = kv.getString(k)?.toIntOrNull() ?: def
    private fun long(k: String) = kv.getString(k)?.toLongOrNull() ?: 0L

    private fun encodeAnchor(a: TimeAnchor) = "${a.wallMs};${a.elapsedMs};${a.bootCount}"
    private fun decodeAnchor(s: String): TimeAnchor? {
        val p = s.split(';')
        if (p.size != 3) return null
        return TimeAnchor(p[0].toLongOrNull() ?: return null, p[1].toLongOrNull() ?: return null, p[2].toIntOrNull() ?: -1)
    }
}
