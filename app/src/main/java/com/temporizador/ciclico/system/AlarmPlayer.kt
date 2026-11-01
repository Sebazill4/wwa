package com.temporizador.ciclico.system

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.temporizador.ciclico.core.AlertConfig
import com.temporizador.ciclico.core.SoundType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Reproduce el aviso sonoro por el canal de ALARMA (USAGE_ALARM), que respeta el volumen
 * de alarma elegido por el usuario. Nunca modifica el volumen ni el modo «No molestar».
 * Se detiene solo al cumplirse la duración configurada.
 */
object AlarmPlayer {
    private const val TAG = "AlarmPlayer"
    private val main = Handler(Looper.getMainLooper())

    private var track: AudioTrack? = null
    private var media: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null
    private var onFinished: (() -> Unit)? = null
    private val stopRunnable = Runnable { stop() }

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    private val alarmAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Debe llamarse en el hilo principal. */
    fun play(context: Context, alert: AlertConfig, onDone: (() -> Unit)? = null) {
        stopInternal(notify = true)
        val app = context.applicationContext
        val durationMs = alert.durationSec.coerceIn(AlertConfig.MIN_ALERT_SECONDS, AlertConfig.MAX_ALERT_SECONDS) * 1000L
        onFinished = onDone

        val pm = app.getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "temporizador:aviso").apply {
            setReferenceCounted(false)
            acquire(durationMs + 5_000L)
        }
        requestFocus(app)

        val ok = when (alert.sound) {
            SoundType.SISTEMA -> startSystemSound(app) || startTone(SoundType.ALARMA)
            else -> startTone(alert.sound)
        }
        if (!ok) Log.e(TAG, "No se pudo iniciar el sonido")
        if (alert.vibrate) startVibration(app)
        _playing.value = true
        main.postDelayed(stopRunnable, durationMs)
    }

    fun stop() = stopInternal(notify = true)

    private fun stopInternal(notify: Boolean) {
        main.removeCallbacks(stopRunnable)
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        try { media?.stop() } catch (_: Exception) {}
        try { media?.release() } catch (_: Exception) {}
        media = null
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
        abandonFocus()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        val wasPlaying = _playing.value
        _playing.value = false
        val cb = onFinished
        onFinished = null
        if (notify && wasPlaying) cb?.invoke()
    }

    private fun startTone(sound: SoundType): Boolean = try {
        val pcm = ToneSynth.pattern(sound)
        val t = AudioTrack.Builder()
            .setAudioAttributes(alarmAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(ToneSynth.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, pcm.size, -1)
        t.play()
        track = t
        true
    } catch (e: Exception) {
        Log.e(TAG, "Error AudioTrack", e)
        false
    }

    private fun startSystemSound(context: Context): Boolean {
        val uri: Uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return false
        return try {
            media = MediaPlayer().apply {
                setAudioAttributes(alarmAttributes)
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Sonido del sistema no disponible", e)
            try { media?.release() } catch (_: Exception) {}
            media = null
            false
        }
    }

    private fun startVibration(context: Context) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        if (!v.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, alarmAttributes)
            }
            vibrator = v
        } catch (e: Exception) {
            Log.e(TAG, "Vibración no disponible", e)
        }
    }

    private fun requestFocus(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        audioManager = am
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(alarmAttributes)
            .build()
        focusRequest = req
        try { am.requestAudioFocus(req) } catch (_: Exception) {}
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        try { audioManager?.abandonAudioFocusRequest(req) } catch (_: Exception) {}
        focusRequest = null
    }
}

/** Genera los sonidos Pip, Alarma y Suave por síntesis (sin archivos de audio). */
object ToneSynth {
    const val SAMPLE_RATE = 44_100

    fun pattern(sound: SoundType): ShortArray = when (sound) {
        SoundType.PIP -> pip()
        SoundType.SUAVE -> soft()
        else -> alarm()
    }

    /** «Pip-pip» agudo cada segundo. */
    private fun pip(): ShortArray {
        val buf = FloatArray(SAMPLE_RATE) // 1 s
        beep(buf, 0.00, 0.12, 1760.0, 0.9)
        beep(buf, 0.20, 0.12, 1760.0, 0.9)
        return toPcm(buf)
    }

    /** Dos tonos alternados, insistente. Ciclo de 0,5 s. */
    private fun alarm(): ShortArray {
        val buf = FloatArray(SAMPLE_RATE / 2)
        beep(buf, 0.00, 0.22, 988.0, 1.0, harmonics = true)
        beep(buf, 0.25, 0.22, 1319.0, 1.0, harmonics = true)
        return toPcm(buf)
    }

    /** Campanilla suave con decaimiento. Ciclo de 2 s. */
    private fun soft(): ShortArray {
        val buf = FloatArray(SAMPLE_RATE * 2)
        chime(buf, 0.0, 659.25, 0.55)
        chime(buf, 0.35, 880.0, 0.45)
        return toPcm(buf)
    }

    private fun beep(buf: FloatArray, startSec: Double, lenSec: Double, freq: Double, amp: Double, harmonics: Boolean = false) {
        val start = (startSec * SAMPLE_RATE).toInt()
        val len = (lenSec * SAMPLE_RATE).toInt()
        val fade = (0.006 * SAMPLE_RATE).toInt()
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= buf.size) break
            val t = i.toDouble() / SAMPLE_RATE
            var s = sin(2 * PI * freq * t)
            if (harmonics) s = 0.75 * s + 0.2 * sin(2 * PI * freq * 3 * t) + 0.05 * sin(2 * PI * freq * 5 * t)
            val env = min(1.0, min(i.toDouble() / fade, (len - i).toDouble() / fade))
            buf[idx] += (s * amp * env).toFloat()
        }
    }

    private fun chime(buf: FloatArray, startSec: Double, freq: Double, amp: Double) {
        val start = (startSec * SAMPLE_RATE).toInt()
        val len = (1.5 * SAMPLE_RATE).toInt()
        val attack = (0.01 * SAMPLE_RATE).toInt()
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= buf.size) break
            val t = i.toDouble() / SAMPLE_RATE
            val env = min(1.0, i.toDouble() / attack) * exp(-3.0 * t)
            val s = sin(2 * PI * freq * t) + 0.3 * sin(2 * PI * freq * 2 * t)
            buf[idx] += (s * amp * env / 1.3).toFloat()
        }
    }

    private fun toPcm(buf: FloatArray): ShortArray = ShortArray(buf.size) { i ->
        (buf[i].coerceIn(-1f, 1f) * Short.MAX_VALUE * 0.95f).toInt().toShort()
    }
}
