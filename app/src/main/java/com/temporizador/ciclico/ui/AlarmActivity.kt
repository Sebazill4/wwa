package com.temporizador.ciclico.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.temporizador.ciclico.system.AlarmPlayer
import com.temporizador.ciclico.system.Notifications
import kotlinx.coroutines.delay

/**
 * Pantalla del aviso, mostrada sobre la pantalla de bloqueo (intención a pantalla completa).
 * Se cierra sola cuando termina el sonido para que el teléfono vuelva a bloquearse.
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    private fun render() {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Temporizador"
        val label = intent.getStringExtra(EXTRA_LABEL) ?: ""
        setContent {
            AppTheme {
                val playing by AlarmPlayer.playing.collectAsStateWithLifecycle()
                LaunchedEffect(playing) {
                    if (!playing) {
                        delay(3_000)
                        finish()
                    }
                }
                Column(
                    Modifier.fillMaxSize().background(Palette.Background).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(title.uppercase(), color = Palette.TextSecondary, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Text(label, color = Palette.Turquoise, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    if (!label.contains("finalizada")) Text("Tomar medición", color = Palette.Text, fontSize = 22.sp)
                    Spacer(Modifier.height(48.dp))
                    BigButton("Silenciar", onClick = {
                        AlarmPlayer.stop()
                        Notifications.cancelAlert(this@AlarmActivity)
                        finish()
                    }, color = Palette.Amber, contentColor = Color.Black)
                    Spacer(Modifier.height(12.dp))
                    SecondaryButton("Cerrar", { finish() })
                }
            }
        }
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_LABEL = "label"
    }
}
