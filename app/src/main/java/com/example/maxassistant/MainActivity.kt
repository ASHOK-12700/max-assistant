package com.example.maxassistant

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.*
import android.speech.tts.TextToSpeech
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.*

// ✅ MainActivity - App open chesappudu matrame active
// All actual command processing → MaxOverlayService chestundi
// App lo unnappudu: Loona show
// Background lo: MaxOverlayService handles everything
class MainActivity : AppCompatActivity() {

    private lateinit var tts: TextToSpeech
    private lateinit var robot: ImageView
    private lateinit var statusText: TextView
    private lateinit var glowRing: android.view.View
    private lateinit var ringOuter: ImageView
    private lateinit var ringInner: ImageView
    private lateinit var jarvisRing: ImageView

    private var isAwake = false
    private val awakeHandler = Handler(Looper.getMainLooper())

    private val CAMERA_REQ = 100

    // Receiver - MaxOverlayService wake cheyyadam
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isAwake) jarvisWake()
        }
    }

    // Receiver - MaxOverlayService sleep ayyaka UI hide
    private val sleepReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isAwake) jarvisSleep()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        robot      = findViewById(R.id.robot)
        statusText = findViewById(R.id.statusText)
        glowRing   = findViewById(R.id.glowRing)
        ringOuter  = findViewById(R.id.ringOuter)
        ringInner  = findViewById(R.id.ringInner)
        jarvisRing = findViewById(R.id.jarvisRing)

        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)

        tts = TextToSpeech(this) {
            tts.setLanguage(Locale.US)
            tts.setSpeechRate(0.82f)
            tts.setPitch(0.62f)
        }

        // Wake receiver register - service tho communicate cheyyadaniki
        val wakeFilter = IntentFilter("com.example.maxassistant.WAKE_FROM_BG")
        val sleepFilter = IntentFilter("com.example.maxassistant.SLEEP_FROM_BG")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wakeReceiver, wakeFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(sleepReceiver, sleepFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(wakeReceiver, wakeFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(sleepReceiver, sleepFilter)
        }

        // App open lo Loona show, jarvisRing hide
        robot.visibility      = android.view.View.VISIBLE
        jarvisRing.visibility = android.view.View.GONE

        // ✅ MaxOverlayService - THE ONLY recognizer
        // App lo unna, background lo unna - idi work chestundi
        startMaxOverlayService()

        // Handle selfie trigger from overlay service
        if (intent?.getBooleanExtra("TRIGGER_SELFIE", false) == true) {
            takeSelfie("selfie")
        }

        findViewById<Button>(R.id.micBtn).setOnClickListener {
            // Manual trigger - service ki broadcast
            sendBroadcast(Intent("com.example.maxassistant.MANUAL_WAKE"))
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("wake_triggered", false) == true)
            Handler(Looper.getMainLooper()).postDelayed({ jarvisWake() }, 300)
        
        if (intent?.getBooleanExtra("TRIGGER_SELFIE", false) == true) {
            takeSelfie("selfie")
        }
    }

    // ✅ App foreground - Loona show
    // MaxOverlayService already listening - no separate recognizer needed
    override fun onResume() {
        super.onResume()
        robot.visibility      = android.view.View.VISIBLE
        jarvisRing.visibility = android.view.View.GONE
        jarvisRing.clearAnimation()
    }

    // ✅ App background - Jarvis ring show (visual only)
    // MaxOverlayService continues listening regardless
    override fun onPause() {
        super.onPause()
        robot.visibility      = android.view.View.GONE
        jarvisRing.visibility = android.view.View.VISIBLE
        jarvisRing.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.spin_anim)
        )
    }


    // ══════════════════════════════════════════
    // JARVIS WAKE - app open lo
    // ══════════════════════════════════════════
    private fun jarvisWake() {
        isAwake = true
        blink()
        tts.speak("yes boss, command mode activated", TextToSpeech.QUEUE_FLUSH, null, null)

        // UI
        statusText.text = "⚡  COMMAND MODE ACTIVATED"
        statusText.animate().alpha(1f).setDuration(400).start()
        
        // Update drawable to premium rings
        ringOuter.setImageResource(R.drawable.ring_outer_premium)
        ringInner.setImageResource(R.drawable.ring_middle_premium)
        glowRing.setBackgroundResource(R.drawable.ring_inner_premium)

        ringOuter.animate().alpha(0.6f).setDuration(500).start()
        ringInner.animate().alpha(0.8f).setDuration(400).start()
        glowRing.animate().alpha(0.4f).setDuration(300).start()
        
        ringOuter.startAnimation(AnimationUtils.loadAnimation(this, R.anim.spin_anim))
        ringInner.startAnimation(AnimationUtils.loadAnimation(this, R.anim.spin_reverse_anim))
        glowRing.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pulse_anim_slow))

        startBlinkLoop()
    }

    private var blinkHandler = Handler(Looper.getMainLooper())
    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (isAwake) {
                blink()
                blinkHandler.postDelayed(this, (2000..6000).random().toLong())
            }
        }
    }

    private fun startBlinkLoop() {
        blinkHandler.removeCallbacks(blinkRunnable)
        blinkHandler.postDelayed(blinkRunnable, 3000)
    }

    private fun jarvisSleep() {
        isAwake = false
        awakeHandler.removeCallbacksAndMessages(null)
        blinkHandler.removeCallbacks(blinkRunnable)
        jarvisUIHide()
        robot.setImageResource(R.drawable.loona_open)
    }

    private fun jarvisUIHide() {
        statusText.animate().alpha(0f).setDuration(500).start()
        ringOuter.animate().alpha(0f).setDuration(500).withEndAction {
            ringOuter.clearAnimation()
        }.start()
        ringInner.animate().alpha(0f).setDuration(500).withEndAction {
            ringInner.clearAnimation()
        }.start()
        glowRing.animate().alpha(0f).setDuration(500).withEndAction {
            glowRing.clearAnimation()
        }.start()
    }

    // ══════════════════════════════════════════
    // VISUAL COMMANDS - app open lo unna, MaxOverlayService
    // already processes the command. Idi just UI react chestundi.
    // wakeReceiver ni "Max" detect ainappudu MaxOverlayService
    // broadcast pampistundi -> jarvisWake() call avutundi.
    // ══════════════════════════════════════════

    // ══════════════════════════════════════════
    // SELFIE
    // ══════════════════════════════════════════
    private fun takeSelfie(cmd: String) {
        when {
            cmd.contains("do you see me") || cmd.contains("can you see me") -> {
                if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT))
                    tts.speak("yes sir, I can see you", TextToSpeech.QUEUE_FLUSH, null, null)
                else
                    tts.speak("sorry sir, front camera not available", TextToSpeech.QUEUE_FLUSH, null, null)
            }
            else -> {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                    tts.speak("taking your selfie sir", TextToSpeech.QUEUE_FLUSH, null, null)
                    try {
                        val selfieIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                            putExtra("android.intent.extras.CAMERA_FACING", 1)
                            putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                            putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                            putExtra("camera_facing", 1)
                            putExtra("android.intent.extra.CAMERA_FACING", 1)
                            putExtra("com.google.assistant.extra.CAMERA_ID", "1")
                        }
                        
                        // Try to find the system camera package to avoid Snapchat
                        val packageManager = packageManager
                        val activities = packageManager.queryIntentActivities(selfieIntent, PackageManager.MATCH_DEFAULT_ONLY)
                        var cameraPackage: String? = null
                        for (resolveInfo in activities) {
                            val pkgName = resolveInfo.activityInfo.packageName
                            if (pkgName != "com.snapchat.android" && pkgName.contains("camera", ignoreCase = true)) {
                                cameraPackage = pkgName
                                break
                            }
                        }
                        
                        if (cameraPackage != null) {
                            selfieIntent.setPackage(cameraPackage)
                        } else if (activities.isNotEmpty()) {
                             for (resolveInfo in activities) {
                                if (resolveInfo.activityInfo.packageName != "com.snapchat.android") {
                                    selfieIntent.setPackage(resolveInfo.activityInfo.packageName)
                                    break
                                }
                             }
                        }
                        
                        startActivityForResult(selfieIntent, CAMERA_REQ)
                    } catch (_: Exception) {
                        val fallbackIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                            putExtra("android.intent.extras.CAMERA_FACING", 1)
                            putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                            putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                            putExtra("camera_facing", 1)
                        }
                        startActivity(fallbackIntent)
                    }
                } else {
                    ActivityCompat.requestPermissions(
                        this, arrayOf(Manifest.permission.CAMERA), 3
                    )
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAMERA_REQ && resultCode == RESULT_OK) {
            val photo = data?.extras?.get("data") as? Bitmap
            if (photo != null) {
                robot.visibility = android.view.View.VISIBLE
                robot.setImageBitmap(photo)
                jarvisUIHide()
                
                // Add a cool entrance animation for the selfie
                robot.startAnimation(AnimationUtils.loadAnimation(this, R.anim.zoom_in))
                
                tts.speak("there you are sir", TextToSpeech.QUEUE_FLUSH, null, null)
                Handler(Looper.getMainLooper()).postDelayed({
                    robot.setImageResource(R.drawable.loona_open)
                    // If we were awake, restore UI, otherwise stay quiet
                    if (MaxOverlayService.isMaxAwake) jarvisWake()
                }, 5000)
            }
        }
    }

    // ══════════════════════════════════════════
    // SERVICE START
    // ══════════════════════════════════════════
    private fun startMaxOverlayService() {
        // Overlay permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)) {
            startActivity(Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
            Toast.makeText(this,
                "Allow 'Display over other apps' for Max overlay",
                Toast.LENGTH_LONG).show()
            return
        }

        if (!MaxOverlayService.isRunning) {
            val intent = Intent(this, MaxOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(intent)
            else
                startService(intent)
        }
    }

    private fun blink() {
        robot.setImageResource(R.drawable.loona_close)
        Handler(Looper.getMainLooper()).postDelayed({
            robot.setImageResource(R.drawable.loona_open)
        }, 200)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            2 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                tts.speak("call permission granted sir", TextToSpeech.QUEUE_FLUSH, null, null)
            3 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                tts.speak("camera permission granted sir", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(wakeReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(sleepReceiver) } catch (_: Exception) {}
        awakeHandler.removeCallbacksAndMessages(null)
        tts.shutdown()
        super.onDestroy()
    }
}
