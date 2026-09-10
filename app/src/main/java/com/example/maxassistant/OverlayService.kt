package com.example.maxassistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.view.*
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.core.app.NotificationCompat
import java.util.Locale

// ✅ OVERLAY SERVICE - Hey Google style bottom popup
// Any screen meedha vasthundi - home, other apps anni
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private var isListening = false
    private val CHANNEL_ID = "max_overlay_channel"
    private lateinit var speechIntent: Intent

    companion object {
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        // STOP THIS SERVICE - MaxOverlayService is now the primary listener
        isRunning = false
        stopSelf()
    }

    // ════════════════════════════════════════════
    // ✅ OVERLAY UI - bottom lo popup
    // ════════════════════════════════════════════
    private fun showOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_layout, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        windowManager?.addView(overlayView, params)

        // ✅ ANIMATED RINGS - pulse effect
        val ringOuter = overlayView?.findViewById<android.widget.ImageView>(R.id.ring_outer)
        val ringInner = overlayView?.findViewById<android.widget.ImageView>(R.id.ring_inner)

        val pulseOuter = AnimationUtils.loadAnimation(this, R.anim.pulse_anim_slow)
        val pulseInner = AnimationUtils.loadAnimation(this, R.anim.pulse_anim)

        ringOuter?.startAnimation(pulseOuter)
        ringInner?.startAnimation(pulseInner)

        // Close button
        overlayView?.findViewById<android.widget.ImageView>(R.id.overlay_close)
            ?.setOnClickListener { stopSelf() }

        // Speech start
        setupSpeech()
    }

    // ════════════════════════════════════════════
    // SPEECH RECOGNITION
    // ════════════════════════════════════════════
    private fun setupSpeech() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {
                isListening = true
                updateStatus("Listening...")
                updateLoona(R.drawable.loona_open)
            }
            override fun onBeginningOfSpeech() {
                updateStatus("Hearing you...")
            }
            override fun onRmsChanged(rms: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
                updateStatus("Processing...")
            }

            override fun onError(error: Int) {
                isListening = false
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                    Handler(Looper.getMainLooper()).postDelayed({ setupSpeech() }, 1000)
                } else {
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            speechRecognizer?.startListening(speechIntent)
                            isListening = true
                        } catch (_: Exception) {}
                    }, 700)
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (all.isNullOrEmpty()) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        speechRecognizer?.startListening(speechIntent)
                        isListening = true
                    }, 500)
                    return
                }

                val cmd = all[0].lowercase().trim()
                android.util.Log.d("MAX_OVERLAY", "Command: $cmd")
                updateStatus("\"$cmd\"")
                updateLoona(R.drawable.loona_close)

                // ✅ Broadcast to MainActivity - command process cheyyadaniki
                val broadcast = Intent("com.example.maxassistant.OVERLAY_CMD")
                broadcast.putExtra("command", cmd)
                sendBroadcast(broadcast)

                tts.speak("ok", TextToSpeech.QUEUE_FLUSH, null, null)

                // 3 seconds tarvata overlay close
                Handler(Looper.getMainLooper()).postDelayed({ stopSelf() }, 3000)
            }

            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(e: Int, p: Bundle?) {}
        })

        // Start listening
        try {
            speechRecognizer?.startListening(speechIntent)
            isListening = true
        } catch (_: Exception) {}
    }

    private fun updateStatus(text: String) {
        Handler(Looper.getMainLooper()).post {
            overlayView?.findViewById<TextView>(R.id.overlay_status)?.text = text
        }
    }

    private fun updateLoona(drawable: Int) {
        Handler(Looper.getMainLooper()).post {
            overlayView?.findViewById<android.widget.ImageView>(R.id.overlay_loona)
                ?.setImageResource(drawable)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Max Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setSound(null, null) }
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
            .setContentTitle("Max is listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        speechRecognizer?.destroy()
        tts.shutdown()
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
