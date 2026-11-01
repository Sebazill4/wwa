package com.temporizador.ciclico.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.temporizador.ciclico.system.DeviceChecks

/** Configuración de batería: «Quitar límite» y «Abrir ajustes». */
@Composable
fun BatteryPanel() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    val unrestricted = remember(version) { DeviceChecks.isIgnoringBattery(context) }
    Panel {
        SectionLabel("Batería")
        Text(
            if (unrestricted) "Sin límite de batería para esta app."
            else "La app tiene límite de batería: HyperOS podría cerrarla en segundo plano.",
            color = if (unrestricted) Palette.Green else Palette.Amber,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (DeviceChecks.isXiaomi) {
            Text(
                "En Xiaomi, en «Abrir ajustes» elige Ahorro de batería › «Sin restricciones» y activa «Inicio automático».",
                color = Palette.TextSecondary, style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Quitar límite", { DeviceChecks.requestIgnoreBattery(context) }, Modifier.weight(1f), enabled = !unrestricted)
            SecondaryButton("Abrir ajustes", { DeviceChecks.openMiuiBattery(context) }, Modifier.weight(1f))
        }
    }
}

/** Problemas que impedirían avisos puntuales y audibles. */
fun startIssues(context: Context): List<String> {
    val out = ArrayList(criticalWarnings(context))
    if (!DeviceChecks.isIgnoringBattery(context)) {
        out += "La app tiene límite de batería: HyperOS podría cerrarla y retrasar o impedir avisos. Usa «Quitar límite»."
    }
    if (!DeviceChecks.canFullScreen(context)) {
        out += "Aviso a pantalla completa no permitido: el sonido funcionará, pero la pantalla no se encenderá."
    }
    return out
}

/**
 * Antes de iniciar, si hay permisos o restricciones que impiden asegurar los avisos,
 * muestra una advertencia clara. Devuelve la función que envuelve la acción de inicio.
 */
@Composable
fun rememberStartGuard(onCheck: () -> Unit): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var issues by remember { mutableStateOf(emptyList<String>()) }

    pending?.let { action ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Los avisos podrían no ser puntuales") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    issues.forEach { Text("• $it") }
                }
            },
            confirmButton = {
                TextButton(onClick = { pending = null; action() }) {
                    Text("INICIAR IGUALMENTE", color = Palette.Turquoise, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null; onCheck() }) { Text("Comprobar alarmas") }
            },
            containerColor = Palette.CardHigh,
        )
    }
    return { action ->
        val found = startIssues(context)
        if (found.isEmpty()) action() else {
            issues = found
            pending = action
        }
    }
}
