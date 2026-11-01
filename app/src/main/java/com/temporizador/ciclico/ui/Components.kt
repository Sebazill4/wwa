package com.temporizador.ciclico.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.temporizador.ciclico.core.AlertConfig
import com.temporizador.ciclico.core.RunState
import com.temporizador.ciclico.core.SoundType

@Composable
fun ScreenScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Palette.Text)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Column {
                    Text(title, style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
                    }
                }
            }
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.Card),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, letterSpacing = 1.sp)
}

@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Palette.Turquoise,
    contentColor: Color = Palette.OnTurquoise,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 60.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = contentColor),
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, fontSize = 19.sp)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, if (enabled) Palette.Turquoise else Palette.Outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Turquoise),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            val isSel = opt == selected
            FilledTonalButton(
                onClick = { onSelect(opt) },
                enabled = enabled,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (isSel) Palette.Turquoise else Palette.CardHigh,
                    contentColor = if (isSel) Palette.OnTurquoise else Palette.Text,
                    disabledContainerColor = if (isSel) Palette.TurquoiseDark else Palette.CardHigh,
                    disabledContentColor = Palette.TextSecondary,
                ),
                modifier = Modifier.heightIn(min = 46.dp),
            ) {
                Text(label(opt), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun StatusPill(state: RunState, finishedText: String = "Finalizado") {
    val (text, color) = when (state) {
        RunState.RUNNING -> "En ejecución" to Palette.Turquoise
        RunState.PAUSED -> "Pausado" to Palette.Amber
        RunState.FINISHED -> finishedText to Palette.Green
        RunState.IDLE -> "Detenido" to Palette.TextSecondary
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.sp)
    }
}

@Composable
fun LabeledValue(label: String, value: String, big: Boolean = false, color: Color = Palette.Text) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Palette.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            color = color,
            style = ClockStyle,
            fontSize = if (big) 54.sp else 30.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = Palette.Text)
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Palette.OnTurquoise, checkedTrackColor = Palette.Turquoise),
        )
    }
}

/** Selector de sonido, duración del aviso y vibración, con botón de prueba. */
@Composable
fun AlertSettings(
    alert: AlertConfig,
    onChange: (AlertConfig) -> Unit,
    testLabel: String,
    playing: Boolean,
    onTest: () -> Unit,
    onStopTest: () -> Unit,
    enabled: Boolean = true,
) {
    SectionLabel("Sonido")
    ChoiceChips(SoundType.entries, alert.sound, { it.label }, { onChange(alert.copy(sound = it)) }, enabled)
    SectionLabel("Sonar (segundos)")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StepButton("−", enabled) { onChange(alert.copy(durationSec = stepDuration(alert.durationSec, -1))) }
        Text(
            "${alert.durationSec} s", style = ClockStyle, fontSize = 26.sp, color = Palette.Text,
            modifier = Modifier.widthIn(min = 90.dp), textAlign = TextAlign.Center,
        )
        StepButton("+", enabled) { onChange(alert.copy(durationSec = stepDuration(alert.durationSec, +1))) }
    }
    SwitchRow("Vibración", alert.vibrate, { onChange(alert.copy(vibrate = it)) }, enabled)
    if (playing) {
        SecondaryButton("Detener sonido", onStopTest, Modifier.fillMaxWidth())
    } else {
        SecondaryButton(testLabel, onTest, Modifier.fillMaxWidth())
    }
}

private fun stepDuration(current: Int, dir: Int): Int {
    val step = if (current < 10 || (current == 10 && dir < 0)) 1 else 5
    return (current + dir * step).coerceIn(AlertConfig.MIN_ALERT_SECONDS, AlertConfig.MAX_ALERT_SECONDS)
}

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.size(width = 64.dp, height = 52.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Palette.CardHigh, contentColor = Palette.Text),
    ) { Text(text, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun WarningCard(lines: List<String>, onCheck: () -> Unit) {
    if (lines.isEmpty()) return
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.Red.copy(alpha = 0.14f)),
        border = BorderStroke(1.dp, Palette.Red.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("⚠ Las alarmas podrían no sonar", color = Palette.Red, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            lines.forEach { Text("• $it", color = Palette.Text, style = MaterialTheme.typography.bodyMedium) }
            SecondaryButton("Comprobar alarmas", onCheck, Modifier.fillMaxWidth())
        }
    }
}

fun formatHms(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

/** Para cuentas regresivas: redondea hacia arriba para que muestre 00:00:01 y no 00:00:00 antes de sonar. */
fun formatCountdown(ms: Long): String = formatHms(((ms.coerceAtLeast(0L) + 999) / 1000) * 1000)

fun formatDurationMin(min: Int): String {
    val h = min / 60
    val m = min % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}
