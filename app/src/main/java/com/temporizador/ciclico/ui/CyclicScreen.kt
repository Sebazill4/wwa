package com.temporizador.ciclico.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.temporizador.ciclico.core.CyclicConfig
import com.temporizador.ciclico.core.IntervalParser
import com.temporizador.ciclico.core.Mode
import com.temporizador.ciclico.core.RunState
import com.temporizador.ciclico.system.AlarmPlayer
import com.temporizador.ciclico.system.Graph
import com.temporizador.ciclico.system.Notifications
import com.temporizador.ciclico.system.TimerService

private val QUICK_MINUTES = listOf(1, 5, 10, 15, 30)

@Composable
fun CyclicScreen(onBack: () -> Unit, onCheck: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { Graph.engine(context) }
    val state by engine.cyclic.collectAsStateWithLifecycle()
    val playing by AlarmPlayer.playing.collectAsStateWithLifecycle()
    val warnings = rememberCriticalWarnings()
    var config by remember { mutableStateOf(engine.store.loadCyclicConfig()) }
    val update: (CyclicConfig) -> Unit = { config = it; engine.store.saveCyclicConfig(it) }
    val guardStart = rememberStartGuard(onCheck)
    val parsed = IntervalParser.parseSequence(config.intervalText)
    val active = state.isActive
    rememberUiTick()
    val v = engine.view(state)

    ScreenScaffold("Temporizador Cíclico", "Avisos repetidos sin volver a programar", onBack) {
        WarningCard(warnings, onCheck)
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { StatusPill(state.runState) }
            if (active) {
                Text(
                    state.activityName, color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                )
                LabeledValue(
                    if (state.runState == RunState.PAUSED) "Próximo aviso (en pausa)" else "Próximo aviso en",
                    v.countdownMs?.let { formatCountdown(it) } ?: "--:--:--", big = true, color = Palette.Turquoise,
                )
                LabeledValue("Tiempo transcurrido", formatHms(v.elapsedMs))
            } else {
                val first = parsed?.firstOrNull()
                LabeledValue("Intervalo", first?.let { formatHms(it) } ?: "--:--:--", big = true)
            }
            HorizontalDivider(color = Palette.Outline)
            val limit = if (active) state.cyclicMaxAlerts else config.maxAlerts
            InfoRow("Avisos emitidos", if (limit > 0) "${state.alertsEmitted} de $limit" else "${state.alertsEmitted}")
            if (state.alertsMissed > 0) InfoRow("Avisos perdidos", "${state.alertsMissed}", Palette.Amber)
            if (active) {
                InfoRow("Secuencia", state.cyclicIntervalsMs.joinToString(" → ") { IntervalParser.format(it) })
            }
        }

        if (playing && active) {
            BigButton("Silenciar aviso", onClick = {
                AlarmPlayer.stop(); Notifications.cancelAlert(context)
            }, color = Palette.Amber, contentColor = Color.Black)
        }

        when (state.runState) {
            RunState.RUNNING -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("Pausar", { engine.pause(Mode.CYCLIC); TimerService.sync(context) }, Modifier.weight(1f), Palette.Amber, Color.Black)
                BigButton("Detener", { stopCyclic(context) }, Modifier.weight(1f), Palette.Red, Color.White)
            }
            RunState.PAUSED -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("Reanudar", { engine.resume(Mode.CYCLIC); TimerService.sync(context) }, Modifier.weight(1f))
                BigButton("Detener", { stopCyclic(context) }, Modifier.weight(1f), Palette.Red, Color.White)
            }
            else -> BigButton("Iniciar", enabled = parsed != null, onClick = {
                guardStart {
                    engine.startCyclic(config)
                    TimerService.sync(context)
                }
            })
        }

        Panel {
            SectionLabel("Actividad")
            OutlinedTextField(
                value = config.activityName,
                onValueChange = { update(config.copy(activityName = it.take(40))) },
                label = { Text("Nombre de la actividad") },
                singleLine = true, enabled = !active,
                modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
            )
            SectionLabel("Intervalo")
            val quickSel = QUICK_MINUTES.firstOrNull { parsed == listOf(it * 60_000L) }
            ChoiceChips(QUICK_MINUTES, quickSel, { "$it min" }, { update(config.copy(intervalText = "${it}m")) }, enabled = !active)
            OutlinedTextField(
                value = config.intervalText,
                onValueChange = { update(config.copy(intervalText = it)) },
                label = { Text("Personalizado o secuencia") },
                singleLine = true, enabled = !active,
                isError = parsed == null,
                supportingText = {
                    Text(
                        if (parsed == null) "No válido. Ejemplos: 30s · 2m · 1m30s · 1h · secuencia: 1m, 30s, 2m (mín. 5 s)"
                        else "Secuencia: " + parsed.joinToString(" → ") { IntervalParser.format(it) } +
                            if (parsed.size > 1) " (se repite)" else ""
                    )
                },
                modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
            )
            SectionLabel("Número máximo de avisos")
            SwitchRow("Sin límite", config.maxAlerts == 0, { unlimited ->
                update(config.copy(maxAlerts = if (unlimited) 0 else 10))
            }, enabled = !active)
            if (config.maxAlerts > 0) {
                var maxText by remember(config.maxAlerts == 0) { mutableStateOf(config.maxAlerts.toString()) }
                OutlinedTextField(
                    value = maxText,
                    onValueChange = { t ->
                        maxText = t.filter { it.isDigit() }.take(5)
                        maxText.toIntOrNull()?.takeIf { it > 0 }?.let { update(config.copy(maxAlerts = it)) }
                    },
                    label = { Text("Máximo de avisos") },
                    singleLine = true, enabled = !active,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
                )
            }
        }
        Panel {
            AlertSettings(
                alert = config.alert,
                onChange = { update(config.copy(alert = it)) },
                testLabel = "Probar sonido",
                playing = playing,
                onTest = { AlarmPlayer.play(context, config.alert) },
                onStopTest = { AlarmPlayer.stop() },
                enabled = !active,
            )
            if (active) Text("Detén el temporizador para cambiar la configuración.", color = Palette.TextSecondary)
        }
        BatteryPanel()
    }
}

private fun stopCyclic(context: android.content.Context) {
    Graph.engine(context).stop(Mode.CYCLIC)
    AlarmPlayer.stop()
    Notifications.cancelAlert(context)
    TimerService.sync(context)
}
