package au.buzz.ryzewave.workout

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * The one text-to-speech engine of the process, shared by the workout cues ([WorkoutService] / [StateAnnouncer])
 * and the stuck-workout detector ([StuckWorkoutMonitor]) — the latter must speak even when no workout service is
 * running (a watch-started workout). The engine is created lazily on the first [speak] and kept; utterances that
 * arrive while it is still initialising are queued (a few) and spoken once it is ready, so the very first cue
 * of a session is not lost. Alarm-usage audio: audible from a pocket, over music. Every utterance is logged at
 * INFO whether or not the engine is available.
 */
class AndroidSpeaker(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var lastRebuildMs = 0L
    private val pending = ArrayDeque<String>()

    fun speak(text: String) {
        Log.i(TAG, "announce: \"$text\"")
        synchronized(lock) {
            if (failed) return
            if (ready) {
                utter(text)
                return
            }
            pending += text
            while (pending.size > MAX_PENDING) pending.removeFirst()
            if (tts == null) main.post { create() }
        }
    }

    private fun create() {
        synchronized(lock) {
            if (tts != null || failed) return
            try {
                tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }
            } catch (e: Exception) {
                Log.w(TAG, "TTS unavailable: ${e.message}")
                failed = true
                pending.clear()
            }
        }
    }

    private fun onInit(status: Int) {
        synchronized(lock) {
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                Log.w(TAG, "TTS init failed (status $status); ${pending.size} queued utterance(s) dropped")
                failed = true
                pending.clear()
                runCatching { engine?.shutdown() }
                tts = null
                return
            }
            runCatching {
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }
            runCatching { engine.language = Locale.getDefault() }
            ready = true
            Log.i(TAG, "TTS ready; speaking ${pending.size} queued utterance(s)")
            while (ready && pending.isNotEmpty()) utter(pending.removeFirst())
        }
    }

    /** Under [lock], engine ready. */
    private fun utter(text: String) {
        val engine = tts ?: return
        val result = try {
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "ryzewave:$text")
        } catch (e: Exception) {
            Log.w(TAG, "speak threw: ${e.message}")
            TextToSpeech.ERROR
        }
        if (result != TextToSpeech.SUCCESS) rebuild(text)
    }

    /**
     * Under [lock]. In a long-lived process the engine's binding to the TTS service dies silently
     * (`speak` returns ERROR, framework logs "not bound to TTS engine" — seen 2026-09-12 after days of
     * uptime): rebuild the engine and re-queue the utterance, at most once per cooldown so an engine
     * that stays broken cannot rebuild-loop.
     */
    private fun rebuild(text: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRebuildMs < REBUILD_COOLDOWN_MS) {
            Log.w(TAG, "speak failed within rebuild cooldown; dropped \"$text\"")
            return
        }
        lastRebuildMs = now
        Log.w(TAG, "speak failed (TTS binding lost); rebuilding engine and re-queueing \"$text\"")
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        pending.addFirst(text)
        main.post { create() }
    }

    companion object {
        const val TAG = "WorkoutSpeech"
        const val MAX_PENDING = 4
        const val REBUILD_COOLDOWN_MS = 30_000L
    }
}
