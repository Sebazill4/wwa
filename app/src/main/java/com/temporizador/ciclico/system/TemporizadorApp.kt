package com.temporizador.ciclico.system

import android.app.Application
import android.content.Context
import com.temporizador.ciclico.core.TimerEngine

class TemporizadorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifications.createChannels(this)
    }
}

/** Contenedor de dependencias de proceso. */
object Graph {
    @Volatile
    private var engineRef: TimerEngine? = null

    val engine: TimerEngine get() = engineRef ?: error("Graph no inicializado")

    @Synchronized
    fun init(context: Context) {
        if (engineRef != null) return
        val app = context.applicationContext
        engineRef = TimerEngine(SharedPrefsStore(app), AndroidClock(app), AndroidAlarmScheduler(app))
    }

    fun engine(context: Context): TimerEngine {
        init(context)
        return engine
    }
}
