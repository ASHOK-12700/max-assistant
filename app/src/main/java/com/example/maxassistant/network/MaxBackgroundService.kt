package com.example.maxassistant

import android.app.*
import android.content.Intent
import android.media.AudioManager
import android.os.*
import android.speech.*
import androidx.core.app.NotificationCompat
import java.util.Locale

// ✅ BACKGROUND SERVICE - ONLY wake word detect chestundi
// NO sound, NO mic indicator blink, NO TTS here
// Just silent listening - "Max" detect chesthe MainActivity ki broadcast
class MaxBackgroundService : Service() {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val CHANNEL_ID = "max_bg_channel"
    private lateinit var audioManager: AudioManager

    companion object {
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        // STOP THIS SERVICE - MaxOverlayService is now the primary listener
        isRunning = false
        stopSelf()
    }

    // ✅ FIX: Only DTMF suppress - phone silent problem solve
    private fun suppressMicSounds(mute: Boolean) {
        try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_DTMF,
                if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0
            )
        } catch (_: Exception) {}
    }

    private fun startWakeWordListening() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // ✅ Sound off - beep sounds disable
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { isListening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(r: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }

            override fun onError(error: Int) {
                isListening = false
                val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 2000L else 1000L
                Handler(Looper.getMainLooper()).postDelayed({ restartListening() }, delay)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (all.isNullOrEmpty()) { restartListening(); return }

                val wakeWords = setOf(
                    "max","macs","mac","mack","mex","marx",
                    "marks","make","mask","mux","mx","match"
                )

                val wakeFound = all.any { r ->
                    r.lowercase().trim().split(" ").any { it in wakeWords }
                }

                if (wakeFound) {
                    android.util.Log.d("MAX_BG", "Wake word! Broadcasting...")

                    // ✅ Sound unmute - MainActivity response chestundi
                    suppressMicSounds(false)

                    // ✅ Broadcast to MainActivity - adhe speak chestundi
                    sendBroadcast(Intent("com.example.maxassistant.WAKE_FROM_BG"))

                    // ✅ MainActivity open - foreground ki vasthundi
                    packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                        launch.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        )
                        launch.putExtra("wake_triggered", true)
                        startActivity(launch)
                    }

                    // 3s pause - MainActivity response ayyaka
                    Handler(Looper.getMainLooper()).postDelayed({
                        suppressMicSounds(true)
                        restartListening()
                    }, 3000)

                } else {
                    restartListening()
                }
            }

            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(e: Int, p: Bundle?) {}
        })

        Handler(Looper.getMainLooper()).post {
            try {
                speechRecognizer?.startListening(intent)
                isListening = true
            } catch (_: Exception) {
                Handler(Looper.getMainLooper()).postDelayed({ restartListening() }, 1000)
            }
        }
    }

    private fun restartListening() {
        Handler(Looper.getMainLooper()).postDelayed({ startWakeWordListening() }, 600)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Max Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Max wake word listener"
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Max • Ready")
            .setContentText("Say 'Max' to activate")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        isRunning = false
        suppressMicSounds(false) // restore sounds
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}
