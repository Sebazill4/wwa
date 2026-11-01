package com.temporizador.ciclico.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.temporizador.ciclico.system.AlarmPlayer
import com.temporizador.ciclico.system.CheckStatus
import com.temporizador.ciclico.system.DeviceChecks
import com.temporizador.ciclico.system.Graph
import com.temporizador.ciclico.system.TimerService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CheckScreen(onBack: () -> Unit, requestNotifications: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { Graph.engine(context) }
    var version by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    val tick = rememberUiTick(500L)
    val items = remember(version, tick / 3000L) { DeviceChecks.items(context) }
    val test by engine.test.collectAsStateWithLifecycle()
    val playing by AlarmPlayer.playing.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("HH:mm:ss.S", Locale.getDefault()) }

    ScreenScaffold("Comprobar alarmas", "Permisos y prueba con pantalla bloqueada", onBack) {
        Panel {
            SectionLabel("Estado del teléfono")
            items.forEachIndexed { i, item ->
                if (i > 0) Spacer(Modifier.padding(2.dp))
                Row(verticalAlignment = Alignment.Top) {
                    val color = when (item.status) {
                        CheckStatus.OK -> Palette.Green
                        CheckStatus.WARNING -> Palette.Amber
                        CheckStatus.ERROR -> Palette.Red
                        CheckStatus.MANUAL -> Palette.Turquoise
                    }
                    Box(Modifier.padding(top = 6.dp).size(12.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.title, color = Palette.Text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text(item.detail, color = Palette.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        if (item.actionLabel != null) {
                            SecondaryButton(item.actionLabel, onClick = {
                                if (item.id == "notifications" && item.status == CheckStatus.ERROR) {
                                    requestNotifications()
                                    DeviceChecks.openAppNotificationSettings(context)
                                } else {
                                    DeviceChecks.openFor(context, item.id)
                                }
                            })
                        }
                    }
                }
            }
        }

        Panel {
            SectionLabel("Prueba real con pantalla bloqueada")
            Text(
                "1) Programa la prueba. 2) Bloquea el teléfono y deja la pantalla apagada. " +
                    "3) Debe sonar a la hora indicada. Al volver verás el retraso medido.",
                color = Palette.Text, style = MaterialTheme.typography.bodyMedium,
            )
            if (test.pending) {
                val remaining = test.scheduledWallMs - System.currentTimeMillis()
                Text(
                    "Prueba programada para las ${fmt.format(Date(test.scheduledWallMs)).dropLast(2)}",
                    color = Palette.Turquoise, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                )
                LabeledValue("Faltan", formatCountdown(remaining), color = Palette.Turquoise)
                SecondaryButton("Cancelar prueba", {
                    engine.cancelTest(); TimerService.sync(context)
                }, Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton("En 1 min", {
                        engine.scheduleTest(60_000L); TimerService.sync(context)
                    }, Modifier.weight(1f))
                    BigButton("En 5 min", {
                        engine.scheduleTest(5 * 60_000L); TimerService.sync(context)
                    }, Modifier.weight(1f))
                }
            }
            if (test.firedWallMs > 0) {
                val delay = (test.firedWallMs - test.scheduledWallMs) / 1000.0
                InfoRow("Última prueba programada", fmt.format(Date(test.scheduledWallMs)))
                InfoRow("Sonó a las", fmt.format(Date(test.firedWallMs)))
                InfoRow(
                    "Retraso", "%.1f s".format(delay),
                    if (delay <= 5.0) Palette.Green else Palette.Amber,
                )
            }
            Text(
                "La prueba usa el sonido y la vibración configurados en Prueba de Bombeo (máx. 10 s).",
                color = Palette.TextSecondary, style = MaterialTheme.typography.bodySmall,
            )
            if (playing) SecondaryButton("Detener sonido", { AlarmPlayer.stop() }, Modifier.fillMaxWidth())
        }

        Panel {
            SectionLabel("Xiaomi HyperOS — pasos recomendados")
            listOf(
                "Ajustes › Apps › Administrar apps › Temporizador Cíclico › Inicio automático: ACTIVADO.",
                "En la misma pantalla › Ahorro de batería: «Sin restricciones».",
                "Notificaciones: permitir todas, incluidas «Pantalla de bloqueo» y «Notificaciones flotantes».",
                "Otros permisos: «Mostrar en pantalla de bloqueo» y «Abrir ventanas nuevas mientras se ejecuta en segundo plano».",
                "Alarmas y recordatorios: permitido (normalmente ya viene concedido).",
                "En Recientes, mantén pulsada la tarjeta de la app y toca el candado para que «Limpiar» no la cierre.",
                "Sube el volumen de ALARMA (no solo el multimedia) y revisa que «No molestar» permita alarmas.",
                "No uses «Ahorro de batería extremo» durante una prueba larga.",
                "No fuerces la detención de la app: Android cancela sus alarmas si se fuerza la detención.",
            ).forEachIndexed { i, s ->
                Text("${i + 1}. $s", color = Palette.Text, style = MaterialTheme.typography.bodyMedium)
            }
            SecondaryButton("Abrir ajustes de la app", { DeviceChecks.openAppDetails(context) }, Modifier.fillMaxWidth())
        }

        Panel {
            SectionLabel("Cómo funcionan las alarmas")
            Text(
                "Cada aviso se programa con la alarma de reloj de Android (setAlarmClock), la misma que usan los " +
                    "despertadores: suena a la hora exacta aunque el teléfono esté bloqueado, en reposo (Doze) " +
                    "o sin internet. Mientras hay un temporizador activo se mantiene una notificación permanente " +
                    "para que HyperOS no cierre la app.\n\n" +
                    "Limitaciones reales: no puede sonar con el teléfono apagado; si el teléfono se reinicia, " +
                    "las alarmas se restauran al desbloquearlo por primera vez; si el sistema fuerza la detención " +
                    "de la app o el volumen de alarma está en 0, no habrá sonido.",
                color = Palette.TextSecondary, style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
