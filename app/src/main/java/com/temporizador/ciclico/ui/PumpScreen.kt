package com.temporizador.ciclico.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.temporizador.ciclico.core.PumpConfig
import com.temporizador.ciclico.core.PumpSchedule
import com.temporizador.ciclico.core.RunState
import com.temporizador.ciclico.core.SessionState
import com.temporizador.ciclico.system.AlarmPlayer
import com.temporizador.ciclico.system.Graph
import com.temporizador.ciclico.system.Notifications
import com.temporizador.ciclico.system.TimerService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PumpScreen(onBack: () -> Unit, onCheck: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { Graph.engine(context) }
    val state by engine.pump.collectAsStateWithLifecycle()
    val playing by AlarmPlayer.playing.collectAsStateWithLifecycle()
    val warnings = rememberCriticalWarnings()
    var config by remember { mutableStateOf(engine.store.loadPumpConfig()) }
    val update: (PumpConfig) -> Unit = { config = it; engine.store.savePumpConfig(it) }
    val guardStart = rememberStartGuard(onCheck)

    ScreenScaffold("Prueba de Bombeo", "Temporizador de mediciones — Gasto Constante", onBack) {
        WarningCard(warnings, onCheck)
        if (state.isActive) {
            RunningPanel(state, playing)
        } else {
            if (state.sessionId != 0L) LastRunSummary(state)
            PumpConfigPanel(config, update, playing, onStartTest = {
                AlarmPlayer.play(context, config.alert)
            })
            BigButton("Iniciar", onClick = {
                guardStart {
                    engine.startPump(config)
                    TimerService.sync(context)
                }
            })
            SchedulePreview(config.durationMin, null)
            BatteryPanel()
        }
    }
}

@Composable
private fun RunningPanel(state: SessionState, playing: Boolean) {
    val context = LocalContext.current
    val engine = remember { Graph.engine(context) }
    rememberUiTick()
    val v = engine.view(state)
    var confirmStop by remember { mutableStateOf(false) }
    val program = engine.program(state)
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { StatusPill(state.runState) }
        LabeledValue("Tiempo transcurrido", formatHms(v.elapsedMs), big = true)
        HorizontalDivider(color = Palette.Outline)
        val nextLabel = if (v.nextIndex != null && program != null) engine.labelFor(state, program, v.nextIndex) else "—"
        Text("Próxima medición", color = Palette.TextSecondary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(
            nextLabel, color = Palette.Turquoise, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
        if (state.nextAlarmWallMs > 0) {
            Text(
                "a las ${fmt.format(Date(state.nextAlarmWallMs))}", color = Palette.TextSecondary,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
        }
        LabeledValue("Cuenta regresiva", v.countdownMs?.let { formatCountdown(it) } ?: "--:--:--", color = Palette.Turquoise)
        HorizontalDivider(color = Palette.Outline)
        InfoRow("Duración total", formatDurationMin(state.pumpDurationMin))
        InfoRow("Inicio", SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(state.startWallMs)))
        InfoRow("Avisos emitidos", "${state.alertsEmitted} de ${v.totalCount ?: "-"}")
        if (state.alertsMissed > 0) InfoRow("Avisos perdidos", "${state.alertsMissed}", Palette.Amber)
        if (state.lastAlertIndex > 0 && program != null) {
            InfoRow(
                "Último aviso",
                "min ${program.offsetMs(state.lastAlertIndex) / PumpSchedule.MINUTE_MS} · retraso ${"%.1f".format(state.lastAlertDelayMs / 1000.0)} s",
            )
        }
    }
    if (playing) {
        BigButton("Silenciar aviso", onClick = {
            AlarmPlayer.stop(); Notifications.cancelAlert(context)
        }, color = Palette.Amber, contentColor = Color.Black)
    }
    BigButton("Detener prueba", onClick = { confirmStop = true }, color = Palette.Red, contentColor = Color.White)
    SchedulePreview(state.pumpDurationMin, state.lastHandledIndex)

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("¿Detener la prueba?") },
            text = { Text("Se cancelarán todas las alarmas pendientes. Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    engine.stop(state.mode)
                    AlarmPlayer.stop()
                    Notifications.cancelAlert(context)
                    TimerService.sync(context)
                }) { Text("DETENER", color = Palette.Red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Continuar prueba") } },
            containerColor = Palette.CardHigh,
        )
    }
}

@Composable
private fun LastRunSummary(state: SessionState) {
    val finished = state.runState == RunState.FINISHED
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (finished) "Última prueba finalizada" else "Última prueba detenida",
                Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Palette.Text,
            )
            StatusPill(state.runState, finishedText = "Finalizada")
        }
        InfoRow("Tiempo registrado", formatHms(state.stopwatch.accumulatedMs))
        InfoRow("Avisos emitidos", "${state.alertsEmitted}")
        if (state.alertsMissed > 0) InfoRow("Avisos perdidos", "${state.alertsMissed}", Palette.Amber)
    }
}

@Composable
private fun PumpConfigPanel(config: PumpConfig, update: (PumpConfig) -> Unit, playing: Boolean, onStartTest: () -> Unit) {
    var customText by remember { mutableStateOf(if (config.durationMin % 60 == 0 && config.durationMin / 60 in PumpSchedule.PRESET_HOURS) "" else formatCustom(config.durationMin)) }
    val customValid = customText.isBlank() || PumpSchedule.parseDuration(customText) != null
    Panel {
        SectionLabel("Duración de la prueba")
        val presetSelected = PumpSchedule.PRESET_HOURS.firstOrNull { it * 60 == config.durationMin && customText.isBlank() }
        ChoiceChips(PumpSchedule.PRESET_HOURS, presetSelected, { "$it h" }, {
            customText = ""
            update(config.copy(durationMin = it * 60))
        })
        OutlinedTextField(
            value = customText,
            onValueChange = { t ->
                customText = t
                PumpSchedule.parseDuration(t)?.let { update(config.copy(durationMin = it)) }
            },
            label = { Text("Duración personalizada (ej. 36, 8h, 90m, 2h30m)") },
            singleLine = true,
            isError = !customValid,
            supportingText = {
                Text(if (customValid) "Un número sin unidad se interpreta en horas." else "Duración no válida (1 min a 30 días).")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
        )
        Text(
            "Duración seleccionada: ${formatDurationMin(config.durationMin)} · ${PumpSchedule.minutes(config.durationMin).size} avisos",
            color = Palette.Turquoise, fontWeight = FontWeight.SemiBold,
        )
    }
    Panel {
        AlertSettings(
            alert = config.alert,
            onChange = { update(config.copy(alert = it)) },
            testLabel = "Probar alarma",
            playing = playing,
            onTest = onStartTest,
            onStopTest = { AlarmPlayer.stop() },
        )
    }
}

private fun formatCustom(min: Int): String = if (min % 60 == 0) "${min / 60}h" else if (min > 60) "${min / 60}h${min % 60}m" else "${min}m"

/** Programa de mediciones; marca las ya realizadas y la próxima. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchedulePreview(durationMin: Int, handledIndex: Int?) {
    val minutes = remember(durationMin) { PumpSchedule.minutes(durationMin) }
    Panel {
        SectionLabel("Programa de mediciones (minutos)")
        Text(
            "0–5: c/1 · 5–15: c/2 · 15–30: c/5 · 30–60: c/10 · 60–120: c/15 · 120–240: c/30 · desde 240: c/60",
            color = Palette.TextSecondary, style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            minutes.forEachIndexed { i, m ->
                val idx = i + 1
                val (bg, fg) = when {
                    handledIndex == null -> Palette.CardHigh to Palette.Text
                    idx <= handledIndex -> Palette.TurquoiseDark to Palette.TextSecondary
                    idx == handledIndex + 1 -> Palette.Turquoise to Palette.OnTurquoise
                    else -> Palette.CardHigh to Palette.Text
                }
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(bg)
                        .widthIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("$m", color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Palette.Text) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Palette.TextSecondary, style = MaterialTheme.typography.bodyLarge)
        Text(value, color = valueColor, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End)
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Turquoise,
    focusedLabelColor = Palette.Turquoise,
    cursorColor = Palette.Turquoise,
    unfocusedBorderColor = Palette.Outline,
)
