package com.bobcar.vehicle.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/** Says one sentence at a time. A new sentence cuts off the one still playing. */
interface Speaker {
    /** [label] names the sentence in logs (the text may contain a driver's name). */
    fun say(text: String, label: String)

    /** Stops what is playing (and drops a sentence waiting for initialization). */
    fun silence()
}

/**
 * The only entry point to the Pleos TextToSpeech SDK, on-device mode only (no network, no app
 * registration, no credentials in the app; CLAUDE.md rule 2).
 * TextToSpeech(context, Mode.ON_DEVICE) → initialize(listener) → (on success) addEventListener → speak / stop
 * → release(). Needs pleos.car.permission.TTS_SERVICE (normal). Names come from the SDK class definitions of
 * TextToSpeech 3.3.1.
 *
 * Voice is an extra: any failure (initialization, engine, playback) is logged and the app goes on
 * with the screen only. All SDK calls run on one thread; release() waits until initialization has
 * answered (results are not dropped by an early release).
 */
class VoiceGateway(private val context: Context) : Speaker {

    private enum class State { IDLE, INITIALIZING, READY, FAILED, RELEASED }

    private val thread = HandlerThread("bobcar-voice").apply { start() }
    private val handler = Handler(thread.looper)

    // --- touched only on the voice thread ---
    private var tts: TextToSpeech? = null
    private var state = State.IDLE
    private var releaseRequested = false
    private var listening = false

    /** Sentence asked for before initialization finished: said once ready (latest only). */
    private var waiting: Pair<String, String>? = null

    private val events = object : EventListener {
        override fun onStart() {
            Log.d(TAG, "speech start")
        }

        override fun onReady() = Unit

        override fun onDone() {
            Log.i(TAG, "speech done")
        }

        override fun onError(type: ErrorType) {
            Log.w(TAG, "speech error $type")
        }

        override fun onStop() {
            Log.d(TAG, "speech stopped")
        }

        override fun onUpdatedRms(rms: Double) = Unit
    }

    fun initialize() = handler.post {
        if (state != State.IDLE) return@post
        state = State.INITIALIZING
        try {
            val engine = TextToSpeech(context, Mode.ON_DEVICE)
            tts = engine
            // Event listeners can be added only after initialization succeeded (the SDK throws before that)
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    handler.post { onInitialized(null) }
                }

                override fun onFailure(type: ErrorType) {
                    handler.post { onInitialized(type) }
                }
            })
            Log.i(TAG, "initializing (ON_DEVICE)")
        } catch (e: Exception) {
            Log.e(TAG, "initialize failed, screen only", e)
            fail()
        }
    }

    override fun say(text: String, label: String) {
        handler.post {
            when (state) {
                State.READY -> speakNow(text, label)
                State.IDLE, State.INITIALIZING -> {
                    waiting = text to label
                    Log.i(TAG, "\"$label\" waits for initialization")
                }
                State.FAILED, State.RELEASED -> Log.i(TAG, "\"$label\" not spoken (voice unavailable, screen only)")
            }
        }
    }

    override fun silence() {
        handler.post {
            waiting = null
            if (state != State.READY) return@post
            runCatching { tts?.stop() }.onFailure { Log.w(TAG, "stop failed", it) }
        }
    }

    /** Debug: behaves as if initialization had failed (the app must go on with the screen only). */
    fun simulateFailure() = handler.post {
        Log.w(TAG, "failure simulated")
        fail()
    }

    fun release() = handler.post {
        releaseRequested = true
        if (state == State.INITIALIZING) return@post // released once initialization answers
        releaseNow()
    }

    // ---------------------------------------------------------------- voice thread

    private fun onInitialized(error: ErrorType?) {
        if (state != State.INITIALIZING) return
        if (error != null) {
            Log.w(TAG, "initialize failed: $error, screen only")
            fail()
        } else {
            try {
                tts?.addEventListener(events)
                listening = true
                state = State.READY
                Log.i(TAG, "ready (ON_DEVICE)")
            } catch (e: Exception) {
                Log.e(TAG, "event listener failed, screen only", e)
                fail()
            }
        }
        if (releaseRequested) {
            releaseNow()
            return
        }
        waiting?.let { (text, label) -> if (state == State.READY) speakNow(text, label) }
        waiting = null
    }

    private fun speakNow(text: String, label: String) {
        val engine = tts ?: return
        try {
            // A new sentence replaces the one playing
            engine.stop()
            engine.speak(text, true)
            Log.i(TAG, "speak \"$label\"")
        } catch (e: Exception) {
            Log.e(TAG, "speak \"$label\" failed", e)
        }
    }

    private fun fail() {
        waiting = null
        releaseEngine()
        if (state != State.RELEASED) state = State.FAILED
    }

    private fun releaseNow() {
        waiting = null
        releaseEngine()
        state = State.RELEASED
        thread.quitSafely()
        Log.i(TAG, "released")
    }

    private fun releaseEngine() {
        val engine = tts ?: return
        tts = null
        if (listening) runCatching { engine.removeEventListener(events) }.onFailure { Log.w(TAG, "listener removal failed", it) }
        listening = false
        runCatching { engine.release() }.onFailure { Log.w(TAG, "release failed", it) }
    }

    private companion object {
        const val TAG = "BOBCar.Voice"
    }
}
