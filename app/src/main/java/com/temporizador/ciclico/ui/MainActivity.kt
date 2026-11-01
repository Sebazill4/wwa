package com.temporizador.ciclico.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.temporizador.ciclico.core.RestoreReason
import com.temporizador.ciclico.system.AndroidAlarmScheduler
import com.temporizador.ciclico.system.DeviceChecks
import com.temporizador.ciclico.system.Graph
import com.temporizador.ciclico.system.Notifications
import com.temporizador.ciclico.system.TimerService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Palette.Background.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Palette.Background.toArgb()),
        )
        setContent { AppTheme { AppRoot() } }
    }

    override fun onResume() {
        super.onResume()
        // Recalcula todo a partir del instante de inicio guardado y asegura la próxima alarma.
        val engine = Graph.engine(this)
        engine.restore(RestoreReason.APP_OPEN)
        TimerService.sync(this)
    }
}

enum class Screen { HOME, CYCLIC, PUMP, CHECK }

/** Reloj de la interfaz: solo provoca el redibujado; los tiempos se calculan siempre desde el ancla guardada. */
@Composable
fun rememberUiTick(periodMs: Long = 200L): Long {
    val t by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(periodMs)
        }
    }
    return t
}

/** Advertencias críticas que impedirían oír una alarma. Se reevalúan al volver a la app. */
@Composable
fun rememberCriticalWarnings(): List<String> {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    val tick = rememberUiTick(5_000L)
    return remember(version, tick / 5_000L) { criticalWarnings(context) }
}

fun criticalWarnings(context: Context): List<String> {
    val out = ArrayList<String>()
    if (DeviceChecks.alarmVolume(context).first == 0) out += "El volumen de ALARMA del teléfono está en 0."
    DeviceChecks.dndWarning(context)?.let { out += it }
    if (!AndroidAlarmScheduler.canScheduleExact(context)) out += "Falta el permiso de alarmas exactas."
    if (!Notifications.canPost(context)) out += "Faltan los permisos de notificación."
    if (DeviceChecks.isBackgroundRestricted(context)) out += "La app tiene restringida la actividad en segundo plano."
    return out
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val engine = remember { Graph.engine(context) }
    val initial = remember {
        when {
            engine.pump.value.isActive -> Screen.PUMP
            engine.cyclic.value.isActive -> Screen.CYCLIC
            else -> Screen.entries.firstOrNull { it.name == engine.store.lastScreen && it != Screen.CHECK } ?: Screen.HOME
        }
    }
    var screen by rememberSaveable { mutableStateOf(initial) }
    val go: (Screen) -> Unit = {
        screen = it
        engine.store.lastScreen = it.name
    }

    // Solicitar permiso de notificaciones al primer uso (Android 13+).
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.canPost(context)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = screen != Screen.HOME) { go(Screen.HOME) }

    when (screen) {
        Screen.HOME -> HomeScreen(onOpen = go)
        Screen.CYCLIC -> CyclicScreen(onBack = { go(Screen.HOME) }, onCheck = { go(Screen.CHECK) })
        Screen.PUMP -> PumpScreen(onBack = { go(Screen.HOME) }, onCheck = { go(Screen.CHECK) })
        Screen.CHECK -> CheckScreen(onBack = { go(Screen.HOME) }, requestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        })
    }
}

@Composable
fun HomeScreen(onOpen: (Screen) -> Unit) {
    val context = LocalContext.current
    val engine = remember { Graph.engine(context) }
    val warnings = rememberCriticalWarnings()
    val pump by engine.pump.collectAsStateWithLifecycle()
    val cyclic by engine.cyclic.collectAsStateWithLifecycle()
    ScreenScaffold(title = "Temporizador Cíclico", subtitle = "Recordatorios sonoros para trabajos en terreno") {
        WarningCard(warnings) { onOpen(Screen.CHECK) }
        HomeOption(
            title = "TEMPORIZADOR CÍCLICO",
            text = "Avisos repetidos con intervalos configurables.",
            running = cyclic.isActive,
            onClick = { onOpen(Screen.CYCLIC) },
        )
        HomeOption(
            title = "PRUEBA DE BOMBEO – GASTO CONSTANTE",
            text = "Recordatorios automáticos para mediciones de gasto constante.",
            running = pump.isActive,
            onClick = { onOpen(Screen.PUMP) },
        )
        Card(
            modifier = Modifier.fillMaxWidth().clickable { onOpen(Screen.CHECK) },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Palette.Card),
        ) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Comprobar alarmas", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                    Text(
                        "Permisos, ajustes de Xiaomi HyperOS y prueba con pantalla bloqueada.",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary,
                    )
                }
                Text("›", color = Palette.Turquoise, fontSize = 32.sp)
            }
        }
    }
}

@Composable
private fun HomeOption(title: String, text: String, running: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.Card),
        border = BorderStroke(2.dp, if (running) Palette.Turquoise else Color.Transparent),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Palette.Turquoise, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Text(text, color = Palette.Text, style = MaterialTheme.typography.bodyLarge)
            if (running) Text("● En ejecución", color = Palette.Turquoise, fontWeight = FontWeight.Bold)
        }
    }
}
