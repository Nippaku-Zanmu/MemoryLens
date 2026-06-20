package com.memorylens.app

import android.app.Application
import android.speech.tts.TextToSpeech
import android.util.Log
import com.memorylens.app.utils.LatencyTracker
import com.memorylens.app.utils.TtsManager
import java.util.Locale

/**
 * Application class — initialises singletons that must outlive any single Activity:
 * TTS engine and LatencyTracker. Keeping TTS here prevents re-init on screen rotation.
 */
class MemoryLensApp : Application() {

    companion object {
        private const val TAG = "MemoryLensApp"
        lateinit var instance: MemoryLensApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        LatencyTracker.reset()
        TtsManager.init(this)
        Log.i(TAG, "MemoryLensApp initialised")
    }

    override fun onTerminate() {
        TtsManager.shutdown()
        super.onTerminate()
    }
}
