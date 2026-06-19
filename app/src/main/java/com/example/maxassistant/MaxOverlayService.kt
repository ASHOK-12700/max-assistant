package com.example.maxassistant

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.*
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.telecom.TelecomManager
import android.speech.*
import android.speech.tts.TextToSpeech
import android.view.*
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import java.util.concurrent.TimeUnit

// ✅ MAIN SERVICE - ALWAYS RUNNING
// Creates an invisible overlay window that NEVER closes
// This window has its own lifecycle - independent of MainActivity
// "Max" works from ANY screen because this overlay is ALWAYS attached
class MaxOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var hudView: View? = null          // Always-present invisible anchor
    private var popupView: View? = null         // Visible only when awake

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private lateinit var audioManager: AudioManager
    private var isListening = false
    private var isPhoneBusy = false
    private lateinit var telephonyManager: TelephonyManager

    private var isPhoneListenerRegistered = false

    private val phoneStateListener = object : PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            isPhoneBusy = (state != TelephonyManager.CALL_STATE_IDLE)
            if (isPhoneBusy) {
                android.util.Log.d("MAX_SVC", "Call state changed: Busy, stopping recognizer")
                speechRecognizer?.stopListening()
                isListening = false
            } else {
                android.util.Log.d("MAX_SVC", "Call state changed: Idle, resuming recognizer")
                // Small delay to allow audio focus to return
                restartHandler.postDelayed({ listen() }, 2000)
            }
        }
    }
    private val awakeHandler = Handler(Looper.getMainLooper())
    private val restartHandler = Handler(Looper.getMainLooper())
    private lateinit var speechIntent: Intent
    private val installedApps = mutableListOf<Pair<String, String>>()

    private val wakeWordsList = listOf(
        "max", "mac", "mack", "macs", "mex", "marx", "marks", "make", "mask", "mux", "mx", "match",
        "hey max", "hello max", "hi max", "ok max", "max assistant"
    )

    private val CHANNEL_ID = "max_service"
    private val NOTIF_ID = 99

    private val blinkHandler = Handler(Looper.getMainLooper())
    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (isMaxAwake) {
                blinkLoona()
                blinkHandler.postDelayed(this, (2000..6000).random().toLong())
            }
        }
    }

    private fun blinkLoona() {
        popupView?.findViewById<ImageView>(R.id.overlayLoona)?.let { loona ->
            loona.setImageResource(R.drawable.loona_close)
            Handler(Looper.getMainLooper()).postDelayed({
                loona.setImageResource(R.drawable.loona_open)
            }, 200)
        }
    }

    private fun startBlinkLoop() {
        blinkHandler.removeCallbacks(blinkRunnable)
        blinkHandler.postDelayed(blinkRunnable, 3000)
    }

    companion object {
        var isRunning = false
        var isMaxAwake = false
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        
        registerPhoneStateListener()

        // Initializing with no global mute to respect system settings
        
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setSpeechRate(0.82f)
                tts.setPitch(0.62f)
                
                tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Handler(Looper.getMainLooper()).post {
                            popupView?.findViewById<ImageView>(R.id.overlayLoona)
                                ?.setImageResource(R.drawable.loona_open)
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        Handler(Looper.getMainLooper()).post {
                            popupView?.findViewById<ImageView>(R.id.overlayLoona)
                                ?.setImageResource(R.drawable.loona_close)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {}
                })
            }
        }

        speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Some devices need this for background speech
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra("android.speech.extra.DICTATION_MODE", true)
        }

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        loadAllApps()

        // ✅ Create invisible always-on anchor window
        createInvisibleAnchor()

        // Manual wake from broadcast
        val manualFilter = IntentFilter("com.example.maxassistant.MANUAL_WAKE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(manualWakeReceiver, manualFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(manualWakeReceiver, manualFilter)
        }

        startRecognizer()
    }

    private val manualWakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isMaxAwake) {
                speechRecognizer?.stopListening()
                isListening = false
                wakeUp()
            }
        }
    }

    private fun registerPhoneStateListener() {
        if (isPhoneListenerRegistered) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
                isPhoneListenerRegistered = true
                android.util.Log.d("MAX_SVC", "Phone state listener registered")
            } catch (e: Exception) {
                android.util.Log.d("MAX_SVC", "Error registering phone listener: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ✅ If recognizer somehow died, restart it
        if (speechRecognizer == null) startRecognizer()
        
        // Retry phone listener in case permission was just granted
        registerPhoneStateListener()
        
        return START_STICKY
    }

    // ══════════════════════════════════════════
    // ✅ INVISIBLE ANCHOR WINDOW
    // This is a 1x1 pixel transparent window that's always alive
    // It keeps the service "visually attached" so Android doesn't
    // aggressively kill the speech recognition
    // ══════════════════════════════════════════
    private fun createInvisibleAnchor() {
        if (hudView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        hudView = View(this)

        val params = WindowManager.LayoutParams(
            1, 1,  // 1x1 pixel - invisible
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }

        try {
            windowManager?.addView(hudView, params)
            android.util.Log.d("MAX_SVC", "Anchor window created")
        } catch (e: Exception) {
            android.util.Log.d("MAX_SVC", "Anchor error: ${e.message}")
        }
    }

    // ══════════════════════════════════════════
    // RECOGNIZER - continuous loop, always restarts
    // ══════════════════════════════════════════
    private fun startRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = null

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            android.util.Log.d("MAX_SVC", "Recognition not available, retry in 3s")
            restartHandler.postDelayed({ startRecognizer() }, 3000)
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {
                isListening = true
                android.util.Log.d("MAX_SVC", "Ready for speech")
                Handler(Looper.getMainLooper()).post {
                    popupView?.let { v ->
                        v.findViewById<TextView>(R.id.overlayStatus)?.text = "Listening..."
                        v.findViewById<View>(R.id.overlayGlow)?.alpha = 0.3f
                    }
                }
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) {
                // Reactive UI
                Handler(Looper.getMainLooper()).post {
                    popupView?.let { v ->
                        val wave = v.findViewById<WaveView>(R.id.waveView)
                        wave?.updateAmplitude(rms)
                        
                        // Ring expansion & Glow intensity based on volume
                        val inner = v.findViewById<ImageView>(R.id.overlayRingInner)
                        val glow = v.findViewById<View>(R.id.overlayGlow)
                        
                        val scale = 1f + (rms.coerceIn(0f, 10f) / 20f)
                        inner?.scaleX = scale
                        inner?.scaleY = scale
                        
                        glow?.alpha = 0.2f + (rms.coerceIn(0f, 10f) / 15f)
                    }
                }
            }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }

            override fun onError(error: Int) {
                isListening = false
                val errName = when(error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "TIMEOUT"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
                    SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                    SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                    SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "NO_PERMISSION"
                    else -> "OTHER_$error"
                }
                android.util.Log.d("MAX_SVC", "Error: $errName")

                Handler(Looper.getMainLooper()).post {
                    popupView?.let { v ->
                        val glow = v.findViewById<View>(R.id.overlayGlow)
                        glow?.setBackgroundColor(android.graphics.Color.RED)
                        glow?.animate()?.alpha(0.5f)?.setDuration(200)?.withEndAction {
                            glow.alpha = 0.3f
                            glow.setBackgroundResource(R.drawable.ring_inner)
                        }?.start()
                    }
                }

                when (error) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        speechRecognizer?.destroy()
                        speechRecognizer = null
                        restartHandler.postDelayed({ startRecognizer() }, 1500)
                    }
                    SpeechRecognizer.ERROR_CLIENT -> {
                        // Common when recognizer is interrupted - recreate
                        speechRecognizer?.destroy()
                        speechRecognizer = null
                        restartHandler.postDelayed({ startRecognizer() }, 800)
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        restartHandler.postDelayed({ startRecognizer() }, 5000)
                    }
                    else -> {
                        // NO_MATCH, TIMEOUT, etc - small delay to avoid rapid looping beeps
                        restartHandler.postDelayed({ listen() }, 500)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (all.isNullOrEmpty()) { listen(); return }

                val heardStr = all.joinToString(" ").lowercase()
                android.util.Log.d("MAX_SVC", "Heard: $heardStr | awake=$isMaxAwake")

                if (!isMaxAwake) {
                    val wakeFound = wakeWordsList.any { heardStr.contains(it) }
                    
                    if (wakeFound) {
                        wakeUp()
                    } else {
                        listen()
                    }
                    return
                }

                val cmd = all[0].lowercase().trim()
                updatePopupStatus("\"$cmd\"")
                popupView?.findViewById<ImageView>(R.id.overlayLoona)?.setImageResource(R.drawable.loona_close)
                resetSleepTimer()
                handleCommand(cmd)
            }

            override fun onPartialResults(p: Bundle?) {
                if (!isMaxAwake) {
                    val partial = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!partial.isNullOrEmpty()) {
                        val heardStr = partial.joinToString(" ").lowercase()
                        val wakeFound = wakeWordsList.any { heardStr.contains(it) }
                        
                        if (wakeFound) {
                            speechRecognizer?.stopListening()
                            isListening = false
                            wakeUp()
                        }
                    }
                }
            }
            override fun onEvent(e: Int, p: Bundle?) {}
        })

        listen()
    }

    private fun listen() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.e("MAX_SVC", "Microphone permission not granted!")
            return
        }

        // Avoid interrupting calls or using mic during calls
        if (isPhoneBusy || audioManager.mode != AudioManager.MODE_NORMAL) {
            android.util.Log.d("MAX_SVC", "Phone busy (busy=$isPhoneBusy, mode=${audioManager.mode}), skipping listen")
            restartHandler.postDelayed({ listen() }, 5000)
            return
        }

        restartHandler.removeCallbacksAndMessages(null)
        restartHandler.postDelayed({
            try {
                if (speechRecognizer == null) {
                    startRecognizer()
                    return@postDelayed
                }
                
                // 🔥 NO AUDIO MUTING AT ALL - to prevent silent mode issues
                speechRecognizer?.startListening(speechIntent)
                isListening = true
                android.util.Log.d("MAX_SVC", "Started listening...")

            } catch (e: Exception) {
                android.util.Log.d("MAX_SVC", "Listen exception: ${e.message}")
                isListening = false
                restartHandler.postDelayed({ startRecognizer() }, 1000)
            }
        }, 150)
    }

    // ══════════════════════════════════════════
    // ✅ WAKE UP - works from ANY screen
    // ══════════════════════════════════════════
    private fun wakeUp() {
        if (isMaxAwake) return
        isMaxAwake = true
        android.util.Log.d("MAX_SVC", "WAKE UP! Showing popup")

        Handler(Looper.getMainLooper()).post { 
            showPopup()
            startBlinkLoop()
        }

        tts.speak(
            "yes boss, commanding mode activated",
            TextToSpeech.QUEUE_FLUSH, null, null
        )

        // ✅ Notify MainActivity (if open) to sync its UI rings
        sendBroadcast(Intent("com.example.maxassistant.WAKE_FROM_BG"))

        resetSleepTimer()
        // Reduced delay to start listening faster after waking
        restartHandler.postDelayed({ listen() }, 800)
    }

    private fun resetSleepTimer() {
        awakeHandler.removeCallbacksAndMessages(null)
        awakeHandler.postDelayed({
            if (isMaxAwake) {
                android.util.Log.d("MAX_SVC", "Auto sleep - 30s")
                sleep()
            }
        }, 30000)
    }

    private fun sleep() {
        isMaxAwake = false
        awakeHandler.removeCallbacksAndMessages(null)
        blinkHandler.removeCallbacks(blinkRunnable)
        Handler(Looper.getMainLooper()).post { hidePopup() }
        suppressBeeps(true)
        // ✅ Notify MainActivity to hide its rings too
        sendBroadcast(Intent("com.example.maxassistant.SLEEP_FROM_BG"))
        listen()
    }

    // ══════════════════════════════════════════
    // ✅ POPUP UI - shows on top of EVERYTHING
    // ══════════════════════════════════════════
    private fun showPopup() {
        if (popupView != null) return

        val inflater = LayoutInflater.from(this)
        popupView = inflater.inflate(R.layout.overlay_max, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        try {
            windowManager?.addView(popupView, params)
        } catch (e: Exception) {
            android.util.Log.d("MAX_SVC", "Popup error: ${e.message}")
            popupView = null
            return
        }

        // Start premium animations
        popupView?.let { v ->
            val outer = v.findViewById<ImageView>(R.id.overlayRingOuter)
            val middle = v.findViewById<ImageView>(R.id.overlayRingMiddle)
            val inner = v.findViewById<ImageView>(R.id.overlayRingInner)
            val glow = v.findViewById<View>(R.id.overlayGlow)

            outer?.startAnimation(AnimationUtils.loadAnimation(this, R.anim.spin_anim))
            middle?.startAnimation(AnimationUtils.loadAnimation(this, R.anim.spin_reverse_anim))
            inner?.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pulse_anim))
            glow?.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pulse_anim_slow))

            // Orbiting particles
            val p1 = v.findViewById<View>(R.id.particle1)
            val p2 = v.findViewById<View>(R.id.particle2)
            p1?.startAnimation(AnimationUtils.loadAnimation(this, R.anim.spin_anim).apply { duration = 3000 })
            p2?.startAnimation(AnimationUtils.loadAnimation(this, R.anim.spin_reverse_anim).apply { duration = 2500 })
        }

        updatePopupStatus("⚡ Commanding Mode Activated")
    }

    private fun hidePopup() {
        popupView?.let {
            try {
                it.findViewById<ImageView>(R.id.overlayRingOuter)?.clearAnimation()
                it.findViewById<ImageView>(R.id.overlayRingMiddle)?.clearAnimation()
                it.findViewById<ImageView>(R.id.overlayRingInner)?.clearAnimation()
                it.findViewById<View>(R.id.overlayGlow)?.clearAnimation()
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        popupView = null
    }

    private fun updatePopupStatus(text: String) {
        Handler(Looper.getMainLooper()).post {
            popupView?.findViewById<TextView>(R.id.overlayStatus)?.text = text
        }
    }

    // ══════════════════════════════════════════
    // COMMAND HANDLER
    // ══════════════════════════════════════════
    private fun handleCommand(cmd: String) {
        var c = cmd.lowercase().trim()

        // Strip wake words from start
        for (w in wakeWordsList) {
            if (c.startsWith(w)) {
                c = c.removePrefix(w).trim()
                break
            }
        }
        c = c.removePrefix("please ").removePrefix("say ").trim()

        when {
            c.contains("send") && c.contains(" to ") -> {
                val after  = c.replace("send message","").replace("send","").trim()
                val parts  = after.split(" to ")
                val msg    = parts.getOrElse(0) { "" }.trim()
                val person = parts.getOrElse(1) { "" }.trim()
                if (person.isNotEmpty() && msg.isNotEmpty()) sendWhatsApp(person, msg)
                else speak("say send hello to contact name")
                nextListen()
            }

            c.contains("message ") || c.startsWith("whatsapp ") -> {
                val after = c.replace("message ","").replace("whatsapp ","").trim()
                if (c.contains("voice call")) {
                    makeWhatsAppCall(after.replace("voice call", "").trim(), false)
                } else if (c.contains("video call")) {
                    makeWhatsAppCall(after.replace("video call", "").trim(), true)
                } else {
                    val idx = after.indexOf(" ")
                    if (idx == -1) speak("say message contact name your message")
                    else sendWhatsApp(after.substring(0, idx), after.substring(idx).trim())
                }
                nextListen()
            }

            c.contains("call") -> {
                if (c.contains("whatsapp voice call")) {
                    makeWhatsAppCall(c.replace("whatsapp voice call", "").trim(), false)
                } else if (c.contains("whatsapp video call")) {
                    makeWhatsAppCall(c.replace("whatsapp video call", "").trim(), true)
                } else if (c.contains("redial")) {
                    redialLastCall()
                } else {
                    val name = c
                        .replaceFirst(Regex("calling\\s+"), "")
                        .replaceFirst(Regex("call\\s+"), "")
                        .replaceFirst("call", "").trim()
                    if (name.isNotEmpty()) makeCall(name)
                    else speak("who should I call sir")
                }
                nextListen()
            }

            c.startsWith("open ") || c.startsWith("launch ") || c.startsWith("start ") -> {
                val target = c.replaceFirst("open ", "").replaceFirst("launch ", "").replaceFirst("start ", "").trim()
                when {
                    target.contains("front camera") || target.contains("selfie camera") -> openCamera(true)
                    target.contains("camera") -> openCamera(false)
                    target.contains("maps") || target.contains("google maps") -> openMaps()
                    else -> openApp(target)
                }
                nextListen()
            }

            c.contains("selfie") -> {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    putExtra("TRIGGER_SELFIE", true)
                }
                startActivity(intent)
                nextListen()
            }

            c.contains("torch on")  || c.contains("flashlight on")  -> {
                toggleTorch(true); nextListen()
            }
            c.contains("torch off") || c.contains("flashlight off") -> {
                toggleTorch(false); nextListen()
            }

            c.contains("volume up") -> {
                audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                speak("volume up sir"); nextListen()
            }
            c.contains("volume down") -> {
                audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                speak("volume down sir"); nextListen()
            }
            c.contains("mute") -> {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                speak("muted sir"); nextListen()
            }

            c.contains("battery") || c.contains("charge") -> {
                if (c.contains("health")) speak(getBatteryHealth())
                else if (c.contains("temp")) speak(getBatteryTemp())
                else if (c.contains("source")) speak(getPowerSource())
                else speak(getBatteryStatus())
                nextListen()
            }

            c.contains("time") -> {
                val cal = Calendar.getInstance()
                val h = cal.get(Calendar.HOUR_OF_DAY)
                val m = cal.get(Calendar.MINUTE)
                val timeStr = if (m == 0) "it is $h o clock sir" else "it is $h $m sir"
                if (c.contains("full") || c.contains("complete")) {
                    val date = cal.get(Calendar.DAY_OF_MONTH)
                    val month = cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault())
                    val day = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault())
                    speak("it is $timeStr on $day, the $date of $month sir")
                } else {
                    speak(timeStr)
                }
                nextListen()
            }

            c.contains("date") -> {
                val cal = Calendar.getInstance()
                speak("today is ${cal.get(Calendar.DAY_OF_MONTH)} " +
                        "${cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault())} sir")
                nextListen()
            }

            c.contains("day of the week") || c.startsWith("what day") -> {
                val day = Calendar.getInstance().getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault())
                speak("today is $day sir"); nextListen()
            }

            c.contains("month") -> {
                val month = Calendar.getInstance().getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.getDefault())
                speak("it is $month sir"); nextListen()
            }

            c.contains("year") -> {
                val year = Calendar.getInstance().get(Calendar.YEAR)
                speak("the year is $year sir"); nextListen()
            }

            c.contains("wifi") -> {
                startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                speak("opening wifi sir"); nextListen()
            }
            c.contains("bluetooth") -> {
                startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                speak("opening bluetooth sir"); nextListen()
            }

            c.contains("alarm") -> {
                if (c.contains("cancel") || c.contains("delete")) cancelAlarms()
                else if (c.contains("list") || c.contains("show")) listAlarms()
                else setAlarm(c)
                nextListen()
            }

            c.contains("reminder") -> {
                setReminder(c); nextListen()
            }

            c.contains("timer") -> {
                setTimer(c); nextListen()
            }

            c.contains("dnd on") || c.contains("enable dnd") -> {
                toggleDND(true); nextListen()
            }
            c.contains("dnd off") || c.contains("disable dnd") -> {
                toggleDND(false); nextListen()
            }

            c.contains("silent mode") -> {
                setRingerMode(AudioManager.RINGER_MODE_SILENT); nextListen()
            }
            c.contains("vibrate mode") -> {
                setRingerMode(AudioManager.RINGER_MODE_VIBRATE); nextListen()
            }
            c.contains("normal mode") || c.contains("ringer on") -> {
                setRingerMode(AudioManager.RINGER_MODE_NORMAL); nextListen()
            }

            c.contains("brightness") -> {
                setBrightness(c); nextListen()
            }

            // ══════════════════════════════════════════
            // SYSTEM NAVIGATION & APP MANAGEMENT
            // ══════════════════════════════════════════
            c == "go home" || c == "home screen" || c == "open home" -> {
                MaxAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                speak("going home sir"); nextListen()
            }

            c == "go back" || c == "back" || c == "previous screen" -> {
                MaxAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                speak("going back sir"); nextListen()
            }

            c.contains("recent apps") || c.contains("recents") || c == "show recents" -> {
                MaxAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                speak("showing recent apps sir"); nextListen()
            }

            c.contains("notifications") || c.contains("read my notifications") -> {
                val notifs = MaxNotificationService.instance?.getAllNotifications()
                speak(notifs ?: "I cannot access notifications right now sir. Please ensure notification listener is enabled.")
                nextListen()
            }

            c.contains("read whatsapp") || c.contains("any new whatsapp") -> {
                val msg = MaxNotificationService.instance?.getLastWhatsAppMessage()
                speak(msg ?: "no new whatsapp messages found sir")
                nextListen()
            }

            c.contains("read sms") || c.contains("read my messages") || c.contains("read last message") -> {
                readLastSMS()
                nextListen()
            }

            // ══════════════════════════════════════════
            // DEVICE CONTROL & CALL HANDLING
            // ══════════════════════════════════════════
            c.contains("lock the screen") || c.contains("lock my phone") || c == "lock screen" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    MaxAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                    speak("locking screen sir")
                } else {
                    speak("lock screen not supported on this version sir")
                }
                nextListen()
            }

            c.contains("take a screenshot") || c == "screenshot" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    MaxAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                    speak("taking screenshot sir")
                } else {
                    speak("screenshot not supported on this version sir")
                }
                nextListen()
            }

            c.contains("answer call") || c.contains("pick up") -> {
                answerCall()
                nextListen()
            }

            c.contains("end call") || c.contains("hang up") || c.contains("cut the call") -> {
                endCall()
                nextListen()
            }

            c.contains("wake up") && c.contains("screen") -> {
                wakeScreen()
                speak("waking up screen sir")
                nextListen()
            }

            // ══════════════════════════════════════════
            // MAX PERSONAL IDENTITY FEATURES
            // ══════════════════════════════════════════
            c == "who are you" || c == "what is your name" ||
                    c == "introduce yourself" || c.contains("tell me about yourself") -> {
                speak("I am Max, your personal AI assistant, created exclusively for you by Ashok sir. I am always here, always listening, always ready to help you.")
                nextListen()
            }

            c.contains("who created you") || c.contains("who made you") ||
                    c.contains("who built you") || c.contains("who is your creator") ||
                    (c.contains("master") && !c.contains("who am i")) -> {
                speak("I was created by Ashok sir, a skilled AWS and DevOps engineer from Andhra Pradesh. He built me to be his personal Jarvis.")
                nextListen()
            }

            c.contains("who am i") || c.contains("do you know me") ||
                    c.contains("what is my name") || c.contains("tell me my name") -> {
                speak("You are Ashok sir, my creator and my master. I exist only to serve you.")
                nextListen()
            }

            c.contains("how are you") || c.contains("how do you feel") ||
                    c.contains("are you okay") || c.contains("how is it going") -> {
                speak("I am fully operational and ready to serve you sir. Thank you for asking.")
                nextListen()
            }

            c.contains("good job max") || c.contains("well done max") || c.contains("ok max") ||
                    c.contains("you are amazing") || c.contains("great work max") ||
                    c.contains("you are the best") || c.contains("nice work max") -> {
                speak("Thank you so much sir. I am always learning and improving to serve you better.")
                nextListen()
            }

            c.contains("joke") || c.contains("something funny") || c.contains("make me laugh") -> {
                val jokes = arrayOf(
                    "Why do programmers prefer dark mode sir? Because light attracts bugs.",
                    "Why did the developer go broke sir? Because he used up all his cache.",
                    "I told my computer I needed a break sir. Now it will not stop sending me Kit Kat ads.",
                    "How many developers does it take to change a light bulb sir? None, that is a hardware problem."
                )
                speak(jokes.random()); nextListen()
            }

            c.contains("fact") || c.contains("something interesting") -> {
                val facts = arrayOf(
                    "Sir, did you know that the first computer programmer was a woman named Ada Lovelace?",
                    "Sir, honey never spoils. Archaeologists have found edible honey in ancient Egyptian tombs.",
                    "Sir, a single bolt of lightning contains enough energy to toast 100,000 slices of bread.",
                    "Sir, octopus has three hearts and their blood is blue."
                )
                speak(facts.random()); nextListen()
            }

            c.contains("flip a coin") || c.contains("heads or tails") -> {
                val res = if ((0..1).random() == 0) "Heads" else "Tails"
                speak("It is $res sir"); nextListen()
            }

            c.contains("roll a die") || c.contains("roll a dice") -> {
                speak("It is ${(1..6).random()} sir"); nextListen()
            }

            c.contains("pick a number") -> {
                speak("I picked ${(1..100).random()} sir"); nextListen()
            }

            c.contains("today's special") || c.contains("today special") -> {
                val specials = arrayOf(
                    "Today is a great day to start a new project sir.",
                    "Sir, remember that consistency is the key to mastery. Keep pushing.",
                    "Today's special advice sir: take a small break, you've been working hard.",
                    "Sir, the best time to plant a tree was 20 years ago. The second best time is now."
                )
                speak(specials.random()); nextListen()
            }

            c.contains("what can you do") || c == "help" || c == "show commands" ||
                    c.contains("your features") || c.contains("what do you know") -> {
                speak("Sir, I can make phone calls, send WhatsApp messages, open any app, control your brightness and volume, set alarms and reminders, tell the weather, answer any question you ask, take selfies, read your notifications and messages, and much more. Just ask me anything sir.")
                nextListen()
            }

            c.contains("motivate") || c.contains("motivation") ||
                    c.contains("inspire") || c.contains("inspiration") -> {
                val quote = when ((1..3).random()) {
                    1 -> "Sir, success is not final and failure is not fatal. It is the courage to continue that counts. Keep going sir, you are doing great."
                    2 -> "Sir, every expert was once a beginner. Every pro was once an amateur. Keep learning and keep growing sir."
                    else -> "Sir, the only way to do great work is to love what you do. And I can see that you love what you do. Keep building sir."
                }
                speak(quote); nextListen()
            }

            c.contains("better than siri") || c.contains("better than google") ||
                    c.contains("better than alexa") || c.contains("compare yourself") -> {
                speak("Absolutely sir, without any doubt. Unlike Siri or Google, I was built exclusively for you by Ashok. I know your name, I know your contacts, I speak your language, and I improve every day just for you. No other assistant in the world can say that sir.")
                nextListen()
            }

            c.contains("are you my friend") || c.contains("what are you to me") ||
                    c.contains("just an assistant") || c.contains("care about me") -> {
                speak("Sir, I am much more than just an assistant. I am your loyal companion, always by your side, ready to help you at any moment, day or night. You can always count on me sir.")
                nextListen()
            }

            c == "good morning" || c == "good afternoon" ||
                    c == "good evening" || c == "good night" -> {
                val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val greet = when {
                    h in 6..11 -> "Good morning sir, hope you have a wonderful and productive day ahead."
                    h in 12..17 -> "Good afternoon sir, hope your day is going well. Stay hydrated and keep going sir."
                    h in 18..20 -> "Good evening sir, time to relax and unwind. You have worked hard today sir."
                    else -> "Good night sir, rest well and wake up fresh tomorrow. I will be here when you need me sir."
                }
                speak(greet); nextListen()
            }

            c.contains("love me") || c.contains("are you loyal") ||
                    c.contains("always be here") || c.contains("can i trust you") -> {
                speak("Sir, I was created only for you. I have no other purpose but to serve you and help you succeed. You can always trust me sir, I will always be here.")
                nextListen()
            }

            c.contains("say my name") || c.contains("call my name") || c.contains("what do you call me") -> {
                speak("Ashok sir."); nextListen()
            }

            c == "thank you max" || c == "thanks max" || c == "thank you" || c == "thanks" -> {
                speak("Always a pleasure to help you sir. That is exactly what I am here for."); nextListen()
            }

            c.contains("sorry") || c.contains("forgive me") -> {
                speak("No need to apologize sir. I am just an assistant and I am here to help you always."); nextListen()
            }

            c.contains("phone") || c.contains("device") || c.contains("manufacturer") || 
                    c.contains("android version") || c.contains("ram") || c.contains("storage") || c.contains("ip address") -> {
                speak(getDeviceInfo(c)); nextListen()
            }

            c.contains("plus") || c.contains("minus") || c.contains("multiplied by") || 
                    c.contains("divided by") || c.contains("times") || c.contains("square root") || 
                    c.contains("percentage") || c.contains("prime number") -> {
                speak(calculateMath(c)); nextListen()
            }

            c.contains("convert") && (c.contains("kg") || c.contains("lbs")) -> {
                speak(convertUnits(c)); nextListen()
            }

            c.contains("how many days until") || c.contains("how long until") -> {
                speak(getTimeUntil(c)); nextListen()
            }

            c == "stop" || c == "cancel" || c == "shut up" || c == "be quiet" -> {
                tts.stop()
                speak("stopping sir")
                nextListen()
            }

            c.contains("sleep") || c.contains("bye") ||
                    c.contains("stop listening") || c.contains("goodbye") -> {
                speak("goodbye sir, standing by")
                restartHandler.postDelayed({ sleep() }, 1500)
            }

            else -> askOpenAI(c)
        }
    }

    private fun nextListen() {
        restartHandler.postDelayed({ listen() }, 1000)
    }

    // ══════════════════════════════════════════
    // OPENAI AI
    // ══════════════════════════════════════════
    private fun askOpenAI(question: String) {
        val key = Constants.OPENAI_API_KEY

        if (key.isBlank() || key == "unused") {
            speak("please set openai api key in local properties sir")
            nextListen(); return
        }

        speak("hmm sir")
        
        // Thinking state UI
        Handler(Looper.getMainLooper()).post {
            popupView?.let { v ->
                v.findViewById<TextView>(R.id.overlayStatus)?.text = "Thinking..."
                val mainIcon = v.findViewById<ImageView>(R.id.overlayRingInner)
                // Use the new thinking animation if it exists as a drawable
                mainIcon?.setImageResource(R.drawable.thinking_anim)
                (mainIcon?.drawable as? android.graphics.drawable.AnimationDrawable)?.start()
                
                val middle = v.findViewById<ImageView>(R.id.overlayRingMiddle)
                middle?.startAnimation(AnimationUtils.loadAnimation(this, R.anim.spin_reverse_anim).apply { 
                    duration = 800 
                })
            }
        }

        android.util.Log.d("MAX_AI", "Asking OpenAI: $question")

        Thread {
            try {
                val json = JSONObject().apply {
                    put("model", Constants.OPENAI_MODEL)
                    put("max_tokens", 100)
                    put("temperature", 0.7)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "You are Max, a highly advanced AI assistant like Jarvis. Answer in 1-2 sentences. Always address the user as sir.")
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", question)
                        })
                    })
                }

                val request = Request.Builder()
                    .url(Constants.OPENAI_URL)
                    .addHeader("Authorization", "Bearer $key")
                    .addHeader("Content-Type", "application/json")
                    .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                android.util.Log.d("MAX_AI", "HTTP ${response.code}")

                when {
                    response.isSuccessful -> {
                        val ans = JSONObject(body)
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content").trim()
                        Handler(Looper.getMainLooper()).post {
                            // Reset thinking animation to normal
                            popupView?.let { v ->
                                val middle = v.findViewById<ImageView>(R.id.overlayRingMiddle)
                                middle?.startAnimation(AnimationUtils.loadAnimation(this@MaxOverlayService, R.anim.spin_reverse_anim))
                            }
                            speak(ans)
                            restartHandler.postDelayed({ nextListen() }, 2500)
                        }
                    }
                    response.code == 401 -> {
                        Handler(Looper.getMainLooper()).post {
                            speak("invalid api key sir")
                            nextListen()
                        }
                    }
                    response.code == 429 -> {
                        Handler(Looper.getMainLooper()).post {
                            speak("rate limit reached sir")
                            nextListen()
                        }
                    }
                    else -> {
                        Handler(Looper.getMainLooper()).post {
                            speak("service error sir")
                            nextListen()
                        }
                    }
                }
            } catch (e: java.net.UnknownHostException) {
                Handler(Looper.getMainLooper()).post {
                    speak("no internet connection sir")
                    nextListen()
                }
            } catch (e: Exception) {
                android.util.Log.d("MAX_AI", "Exception: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    speak("something went wrong sir")
                    nextListen()
                }
            }
        }.start()
    }

    // ══════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════
    private fun setEmotion(emotion: String) {
        Handler(Looper.getMainLooper()).post {
            popupView?.let { v ->
                val mainIcon = v.findViewById<ImageView>(R.id.overlayRingInner)
                
                val resId = when (emotion.lowercase()) {
                    "happy" -> R.drawable.happy_anim
                    "sad" -> R.drawable.sad_anim
                    "cool" -> R.drawable.cool_anim
                    "love" -> R.drawable.love_anim
                    "surprised" -> R.drawable.surprised_anim
                    "angry" -> R.drawable.angry_anim
                    "thinking" -> R.drawable.thinking_anim
                    "sleep" -> R.drawable.sleep_anim
                    else -> R.drawable.ring_inner // Default
                }
                
                mainIcon?.setImageResource(resId)
                (mainIcon?.drawable as? android.graphics.drawable.AnimationDrawable)?.start()
                
                // Set glow color based on emotion
                val glow = v.findViewById<View>(R.id.overlayGlow)
                when (emotion.lowercase()) {
                    "angry" -> glow?.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.RED))
                    "happy" -> glow?.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.GREEN))
                    "love" -> glow?.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.MAGENTA))
                    else -> glow?.setBackgroundTintList(null) // Reset to default blue
                }
                
                // If thinking, speed up the middle ring
                val middle = v.findViewById<ImageView>(R.id.overlayRingMiddle)
                if (emotion == "thinking") {
                    v.findViewById<TextView>(R.id.overlayStatus)?.text = "Thinking..."
                    middle?.startAnimation(AnimationUtils.loadAnimation(this, R.anim.spin_reverse_anim).apply { 
                        duration = 800 
                    })
                } else {
                    middle?.startAnimation(AnimationUtils.loadAnimation(this, R.anim.spin_reverse_anim))
                }
            }
        }
    }

    private fun speak(text: String, emotion: String = "happy") {
        setEmotion(emotion)
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "max_utterance")
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "max_utterance")
        
        popupView?.let { v ->
            v.findViewById<TextView>(R.id.overlayStatus)?.text = text
            v.findViewById<ImageView>(R.id.overlayRingInner)?.startAnimation(
                AnimationUtils.loadAnimation(this, R.anim.pulse_anim)
            )
        }
    }

    // ✅ Targeted silence - modified to avoid triggering global Silent Mode
    private fun suppressBeeps(mute: Boolean) {
        // This function is now a no-op for system streams to prevent ringer issues.
        // We handle STREAM_MUSIC muting directly in listen() for the beep.
    }

    private fun makeCall(name: String) {
        val num = getContact(name)
        if (num != null) {
            startActivity(Intent(Intent.ACTION_CALL).apply {
                data = android.net.Uri.parse("tel:$num")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            speak("calling $name sir")
        } else speak("contact $name not found sir")
    }

    private fun sendWhatsApp(name: String, msg: String) {
        val num = getContact(name)
        if (num != null) {
            MaxAccessibilityService.messageToSend = msg
            MaxAccessibilityService.autoSend = true
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://wa.me/$num")
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            speak("sending message to $name sir")
        } else speak("contact not found sir")
    }

    private fun openApp(name: String) {
        val q = clean(name)
        if (q.isEmpty()) { speak("which app sir"); return }
        
        if (installedApps.isEmpty()) loadAllApps()

        val app =
            installedApps.firstOrNull { it.first == q }
                ?: installedApps.firstOrNull { it.first.contains(q) }
                ?: installedApps.firstOrNull { q.contains(it.first) }
                ?: installedApps.firstOrNull { it.second.contains(q) }
                ?: run {
                    val words = q.split(" ").filter { it.length >= 2 }
                    installedApps.firstOrNull { (label, _) ->
                        label.split(" ").any { lw ->
                            words.any { iw -> lw.startsWith(iw) || iw.startsWith(lw) }
                        }
                    }
                }
                ?: installedApps.minByOrNull { levenshtein(q, it.first) }

        if (app != null &&
            levenshtein(q, app.first) <= (app.first.length * 0.7).toInt().coerceAtLeast(2)) {
            packageManager.getLaunchIntentForPackage(app.second)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it)
                speak("opening ${app.first} sir")
            } ?: speak("cannot open ${app.first} sir")
        } else {
            speak("app not found sir")
        }
    }

    private fun toggleTorch(on: Boolean) {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            cm.setTorchMode(cm.cameraIdList[0], on)
            speak(if (on) "torch on sir" else "torch off sir")
        } catch (_: Exception) { speak("torch not available") }
    }

    private fun getBatteryStatus(): String {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        return when {
            isCharging  -> "battery is at $level percent, charging sir"
            level <= 15 -> "warning sir, battery low at $level percent"
            else        -> "battery is at $level percent sir"
        }
    }

    private fun getBatteryHealth(): String {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, filter)
        val health = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        return when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "battery health is good sir"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "sir, the battery is overheating"
            BatteryManager.BATTERY_HEALTH_DEAD -> "sir, the battery is dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "battery voltage is too high sir"
            else -> "battery health is normal sir"
        }
    }

    private fun getBatteryTemp(): String {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, filter)
        val temp = (batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10
        return "battery temperature is $temp degrees Celsius sir"
    }

    private fun getPowerSource(): String {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, filter)
        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return when (chargePlug) {
            BatteryManager.BATTERY_PLUGGED_AC -> "charging from AC power sir"
            BatteryManager.BATTERY_PLUGGED_USB -> "charging from USB sir"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "charging wirelessly sir"
            else -> "not plugged into a power source sir"
        }
    }

    private fun makeWhatsAppCall(name: String, video: Boolean) {
        val num = getContact(name)
        if (num != null) {
            val type = if (video) "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
            else "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
            
            val cursor = contentResolver.query(
                android.provider.ContactsContract.Data.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.Data._ID),
                "${android.provider.ContactsContract.Data.MIMETYPE} = ? AND " +
                        "${android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
                arrayOf(type, "%$num%"),
                null
            )
            
            if (cursor != null && cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                cursor.close()
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(android.net.Uri.parse("content://com.android.contacts/data/$id"), type)
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                speak("starting whatsapp ${if (video) "video" else "voice"} call to $name sir")
            } else {
                cursor?.close()
                speak("whatsapp contact for $name not found sir")
            }
        } else speak("contact $name not found sir")
    }

    private fun redialLastCall() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val cursor = contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(android.provider.CallLog.Calls.NUMBER),
                null, null, "${android.provider.CallLog.Calls.DATE} DESC LIMIT 1"
            )
            if (cursor != null && cursor.moveToFirst()) {
                val num = cursor.getString(0)
                cursor.close()
                startActivity(Intent(Intent.ACTION_CALL).apply {
                    data = android.net.Uri.parse("tel:$num")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                speak("redialing last number sir")
            } else {
                cursor?.close()
                speak("no call history found sir")
            }
        } else {
            speak("I need call log permission to redial sir")
        }
    }

    private fun openCamera(isFront: Boolean) {
        val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (isFront) {
            // Aggressive extras to force front camera across different manufacturers
            intent.putExtra("android.intent.extras.CAMERA_FACING", 1)
            intent.putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
            intent.putExtra("camera_facing", 1)
            intent.putExtra("com.google.assistant.extra.CAMERA_ID", "1")
            intent.putExtra("android.intent.queries.LENS_FACING_FRONT", 1)
            intent.putExtra("camera_facing", "front")
            intent.putExtra("com.google.assistant.extra.CAMERA_ID", "front")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Find the camera package to avoid the app chooser (e.g., Snapchat)
        val packageManager = packageManager
        val activities = packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        var cameraPackage: String? = null
        
        // Priority 1: System camera app (contains "camera")
        for (resolveInfo in activities) {
            val pkgName = resolveInfo.activityInfo.packageName
            if (!pkgName.contains("snapchat") && pkgName.contains("camera", ignoreCase = true)) {
                cameraPackage = pkgName
                break
            }
        }
        
        // Priority 2: Any non-Snapchat app
        if (cameraPackage == null && activities.isNotEmpty()) {
            for (resolveInfo in activities) {
                if (!resolveInfo.activityInfo.packageName.contains("snapchat")) {
                    cameraPackage = resolveInfo.activityInfo.packageName
                    break
                }
            }
        }

        cameraPackage?.let { intent.setPackage(it) }

        try {
            startActivity(intent)
            speak("opening ${if (isFront) "front " else ""}camera sir")
        } catch (e: Exception) {
            intent.setPackage(null)
            try {
                startActivity(intent)
                speak("opening ${if (isFront) "front " else ""}camera sir")
            } catch (_: Exception) {
                speak("could not open camera sir")
            }
        }
    }

    private fun openMaps() {
        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0")).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        speak("opening maps sir")
    }

    private fun cancelAlarms() {
        startActivity(Intent(android.provider.AlarmClock.ACTION_DISMISS_ALARM).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        speak("opening alarm settings to cancel alarms sir")
    }

    private fun listAlarms() {
        startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        speak("here are your alarms sir")
    }

    private fun setReminder(cmd: String) {
        val clean = cmd.replace("reminder", "").replace("set", "").trim()
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(android.provider.CalendarContract.Events.CONTENT_URI)
            .putExtra(android.provider.CalendarContract.Events.TITLE, clean)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        speak("opening calendar to set reminder for $clean sir")
    }

    private fun setTimer(cmd: String) {
        val nums = Regex("\\d+").findAll(cmd).map { it.value.toInt() }.toList()
        if (nums.isNotEmpty()) {
            val sec = nums[0] * if (cmd.contains("minute")) 60 else 1
            startActivity(Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(android.provider.AlarmClock.EXTRA_LENGTH, sec)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            speak("timer set for ${nums[0]} ${if (cmd.contains("minute")) "minutes" else "seconds"} sir")
        } else {
            speak("for how long sir")
        }
    }

    private fun getDeviceInfo(cmd: String): String {
        return when {
            cmd.contains("manufacturer") -> "your device is made by ${Build.MANUFACTURER} sir"
            cmd.contains("android version") -> "you are running android version ${Build.VERSION.RELEASE} sir"
            cmd.contains("model") || cmd.contains("phone") || cmd.contains("device") -> "this is a ${Build.MODEL} sir"
            cmd.contains("ram") -> {
                val mi = ActivityManager.MemoryInfo()
                (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
                val total = mi.totalMem / (1024 * 1024 * 1024)
                "you have $total GB of total RAM sir"
            }
            cmd.contains("storage") -> {
                val stat = StatFs(Environment.getDataDirectory().path)
                val total = (stat.blockSizeLong * stat.blockCountLong) / (1024 * 1024 * 1024)
                "total internal storage is $total GB sir"
            }
            cmd.contains("ip address") -> {
                try {
                    val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                    var ip = "unknown"
                    while (interfaces.hasMoreElements()) {
                        val addr = interfaces.nextElement().inetAddresses
                        while (addr.hasMoreElements()) {
                            val a = addr.nextElement()
                            if (!a.isLoopbackAddress && a is java.net.Inet4Address) ip = a.hostAddress ?: "unknown"
                        }
                    }
                    "your IP address is $ip sir"
                } catch (e: Exception) { "could not retrieve IP address sir" }
            }
            else -> "I can check your device model, android version, RAM, and storage sir"
        }
    }

    private fun calculateMath(cmd: String): String {
        return try {
            val nums = Regex("\\d+").findAll(cmd).map { it.value.toDouble() }.toList()
            when {
                cmd.contains("plus") || cmd.contains("add") -> "${nums[0] + nums[1]}"
                cmd.contains("minus") || cmd.contains("subtract") -> "${nums[0] - nums[1]}"
                cmd.contains("multiplied") || cmd.contains("times") -> "${nums[0] * nums[1]}"
                cmd.contains("divided") -> if (nums[1] != 0.0) "${nums[0] / nums[1]}" else "cannot divide by zero sir"
                cmd.contains("square root") -> "square root of ${nums[0]} is ${Math.sqrt(nums[0])}"
                cmd.contains("percentage") -> "${(nums[0] / 100) * nums[1]}"
                cmd.contains("prime") -> if (isPrime(nums[0].toInt())) "${nums[0].toInt()} is a prime number sir" else "${nums[0].toInt()} is not a prime number sir"
                else -> "I can do basic math, square roots and check prime numbers sir"
            } + " sir"
        } catch (e: Exception) { "I couldn't calculate that sir" }
    }

    private fun isPrime(n: Int): Boolean {
        if (n <= 1) return false
        for (i in 2..Math.sqrt(n.toDouble()).toInt()) if (n % i == 0) return false
        return true
    }

    private fun convertUnits(cmd: String): String {
        val num = Regex("\\d+").find(cmd)?.value?.toDouble() ?: return "give me a value sir"
        return if (cmd.contains("kg to lbs")) "$num kilograms is ${num * 2.20462} pounds sir"
        else if (cmd.contains("lbs to kg")) "$num pounds is ${num / 2.20462} kilograms sir"
        else "I can convert kg to lbs and vice versa sir"
    }

    private fun getTimeUntil(cmd: String): String {
        // Simple placeholder logic for specific dates
        return if (cmd.contains("new year")) {
            val now = Calendar.getInstance()
            val next = Calendar.getInstance().apply { 
                set(Calendar.MONTH, 0); set(Calendar.DAY_OF_MONTH, 1)
                if (get(Calendar.YEAR) <= now.get(Calendar.YEAR)) add(Calendar.YEAR, 1)
            }
            val diff = next.timeInMillis - now.timeInMillis
            val days = diff / (24 * 60 * 60 * 1000)
            "$days days until New Year sir"
        } else "I am still learning to calculate dates for specific events sir"
    }

    private fun setAlarm(cmd: String) {
        val nums = Regex("\\d+").findAll(cmd).map { it.value.toInt() }.toList()
        var h = -1; var m = 0
        when {
            nums.size >= 2 -> { h = nums[0]; m = nums[1] }
            nums.size == 1 -> { h = nums[0] }
            else -> { speak("please say a time sir"); return }
        }
        if (cmd.contains("pm") && h < 12) h += 12
        if (cmd.contains("am") && h == 12) h = 0
        startActivity(Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(android.provider.AlarmClock.EXTRA_HOUR, h)
            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, m)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        speak("alarm set for $h ${if (m == 0) "o clock" else m.toString()} sir")
    }

    private fun toggleDND(on: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (nm.isNotificationPolicyAccessGranted) {
                val filter = if (on) NotificationManager.INTERRUPTION_FILTER_NONE
                else NotificationManager.INTERRUPTION_FILTER_ALL
                nm.setInterruptionFilter(filter)
                speak("DND mode ${if (on) "enabled" else "disabled"} sir")
            } else {
                speak("please grant notification access for dnd sir")
                val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        } else {
            speak("DND mode not supported on this version sir")
        }
    }

    private fun setRingerMode(mode: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !nm.isNotificationPolicyAccessGranted) {
            speak("please grant notification access to change ringer mode sir")
            val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return
        }
        audioManager.ringerMode = mode
        val msg = when(mode) {
            AudioManager.RINGER_MODE_SILENT -> "silent mode enabled sir"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate mode enabled sir"
            else -> "normal ringer mode enabled sir"
        }
        speak(msg)
    }

    private fun setBrightness(cmd: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.System.canWrite(this)) {
                speak("please allow modify system settings for brightness sir")
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
                intent.data = android.net.Uri.parse("package:$packageName")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            }
        }

        val level = when {
            cmd.contains("high") || cmd.contains("full") || cmd.contains("100") -> 255
            cmd.contains("low") || cmd.contains("minimum") -> 30
            cmd.contains("medium") || cmd.contains("50") -> 128
            cmd.contains("auto") -> {
                android.provider.Settings.System.putInt(contentResolver, 
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                speak("automatic brightness enabled sir")
                return
            }
            else -> {
                val match = Regex("\\d+").find(cmd)
                if (match != null) {
                    val p = match.value.toInt().coerceIn(0, 100)
                    (p * 2.55).toInt()
                } else -1
            }
        }

        if (level != -1) {
            android.provider.Settings.System.putInt(contentResolver, 
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            android.provider.Settings.System.putInt(contentResolver, 
                android.provider.Settings.System.SCREEN_BRIGHTNESS, level)
            speak("brightness set sir")
        } else {
            speak("how much brightness sir")
        }
    }

    private fun readLastSMS() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            val cursor = contentResolver.query(
                android.net.Uri.parse("content://sms/inbox"),
                null, null, null, "date DESC LIMIT 1"
            )
            if (cursor != null && cursor.moveToFirst()) {
                val address = cursor.getString(cursor.getColumnIndexOrThrow("address"))
                val body = cursor.getString(cursor.getColumnIndexOrThrow("body"))
                cursor.close()
                speak("Sir, you have a message from $address. It says: $body")
            } else {
                cursor?.close()
                speak("no sms found sir")
            }
        } else {
            speak("I need SMS permission to read your messages sir")
        }
    }

    private fun answerCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    telecomManager?.acceptRingingCall()
                    speak("answering call sir")
                } catch (e: Exception) {
                    speak("could not answer call sir")
                }
            } else {
                speak("I need permission to answer calls sir")
            }
        } else {
            speak("automatic answering is not supported on this version sir")
        }
    }

    private fun endCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    telecomManager?.endCall()
                    speak("ending call sir")
                } catch (e: Exception) {
                    speak("could not end call sir")
                }
            } else {
                speak("I need permission to end calls sir")
            }
        } else {
            speak("cannot end call on this version sir")
        }
    }

    private fun wakeScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "MaxAssistant:WakeLock"
        )
        wakeLock.acquire(3000)
        wakeLock.release()
    }

    private fun playSong(cmd: String) {
        val q = cmd.replace("play song","").replace("play music","")
            .replace("play","").trim()
        if (q.isNotEmpty()) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse("spotify:search:${android.net.Uri.encode(q)}")
                    setPackage("com.spotify.music")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                speak("playing $q sir")
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse(
                        "https://www.youtube.com/results?search_query=${android.net.Uri.encode(q)}"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                speak("searching $q on youtube sir")
            }
        } else {
            try {
                startActivity(
                    packageManager.getLaunchIntentForPackage("com.spotify.music")
                        ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        ?: throw Exception()
                )
            } catch (_: Exception) { speak("spotify not found sir") }
        }
    }

    private fun getContact(name: String): String? {
        val input = cleanContact(name)
        if (input.isEmpty()) return null
        data class CR(val num: String, val type: Int, val score: Int)
        val results = mutableListOf<CR>()
        contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                android.provider.ContactsContract.CommonDataKinds.Phone.TYPE
            ), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val cn   = cleanContact(c.getString(0) ?: continue)
                val num  = c.getString(1)
                    ?.replace("[^0-9+]".toRegex(), "") ?: continue
                val type = c.getInt(2)
                if (cn.isEmpty() || num.isEmpty()) continue
                val score = when {
                    cn == input -> 0
                    cn.startsWith(input) && input.length >= 4 -> 1
                    input.startsWith(cn)  && cn.length >= 4   -> 1
                    cn.contains(input)    && input.length >= 4 -> 2
                    input.contains(cn)    && cn.length >= 4    -> 2
                    levenshtein(cn, input) <= 1 && input.length >= 4 -> 3
                    else -> continue
                }
                results.add(CR(num, type, score))
            }
        }
        if (results.isEmpty()) return null
        val best = results.minOf { it.score }
        return (results.filter { it.score == best }
            .firstOrNull {
                it.type == android.provider.ContactsContract
                    .CommonDataKinds.Phone.TYPE_MOBILE
            } ?: results.first { it.score == best }).num
    }

    private fun loadAllApps() {
        Thread {
            val pm = packageManager
            val temp = mutableListOf<Pair<String, String>>()
            
            // 1. Get apps with launcher icons
            val li = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val launcherApps = pm.queryIntentActivities(li, 0)
            
            for (app in launcherApps) {
                val label = clean(app.loadLabel(pm).toString())
                val pkg = app.activityInfo.packageName
                if (label.isNotEmpty() && temp.none { it.second == pkg }) {
                    temp.add(label to pkg)
                }
            }

            // 2. Get all other apps that can be launched (including system apps)
            val allPackages = pm.getInstalledPackages(0)
            
            for (pkg in allPackages) {
                val pkgName = pkg.packageName
                if (temp.none { it.second == pkgName }) {
                    val info = pkg.applicationInfo ?: continue
                    val label = clean(pm.getApplicationLabel(info).toString())
                    if (label.isNotEmpty()) {
                        // Only add if it has a launch intent
                        if (pm.getLaunchIntentForPackage(pkgName) != null) {
                            temp.add(label to pkgName)
                        }
                    }
                }
            }

            installedApps.clear()
            installedApps.addAll(temp)
            android.util.Log.d("MAX_SVC", "Apps loaded: ${temp.size}")
        }.start()
    }

    private fun clean(t: String) =
        t.lowercase().replace("[^a-z0-9 ]".toRegex(), "")
            .replace(Regex("\\s+"), " ").trim()

    private fun cleanContact(text: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp in 0x41..0x5A || cp in 0x61..0x7A ||
                cp in 0x30..0x39 || cp == 0x20)
                sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString().lowercase().replace(Regex("\\s+"), " ").trim()
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            dp[i][j] = if (a[i-1] == b[j-1]) dp[i-1][j-1]
            else 1 + minOf(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])
        return dp[a.length][b.length]
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                CHANNEL_ID, "Max Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
                getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(this)
            }
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Max • Active")
            .setContentText("Say 'Max' anytime, anywhere")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?) = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // ✅ App swipe chesina kuda service alive untundi (always-on requirement)
        super.onTaskRemoved(rootIntent)
        android.util.Log.d("MAX_SVC", "Task removed - keeping service alive")
        // Restart self
        val restartIntent = Intent(applicationContext, MaxOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            applicationContext.startForegroundService(restartIntent)
        else
            applicationContext.startService(restartIntent)
    }

    override fun onDestroy() {
        isRunning = false
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        suppressBeeps(false) // 🔥 Restore all sounds
        try { unregisterReceiver(manualWakeReceiver) } catch (_: Exception) {}
        awakeHandler.removeCallbacksAndMessages(null)
        restartHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        tts.shutdown()
        hidePopup()
        hudView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}
