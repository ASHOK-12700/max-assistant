package com.example.maxassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.accessibilityservice.AccessibilityService
import org.json.JSONObject
import java.util.Calendar
import java.util.Date
import java.util.Locale

import com.example.maxassistant.model.ToolResult
import com.example.maxassistant.model.ToolStatus

/**
 * Registry of whitelisted tools that MAX can execute.
 */
class ToolRegistry(private val context: Context, private val overlayService: MaxOverlayService) {
    private val dataManager = DataManager(context)

    fun executeTool(toolName: String, args: JSONObject): Boolean {
        val result = executeToolV2(toolName, args)
        return result.status == ToolStatus.SUCCESS
    }

    private fun executeToolV2(toolName: String, args: JSONObject): ToolResult {
        return when (toolName) {
            "openApp" -> {
                val appName = args.optString("appName")
                overlayService.openApp(appName)
                ToolResult(ToolStatus.SUCCESS)
            }
            "makeCall" -> {
                val contactName = args.optString("contactName")
                if (contactName == "emergency") {
                    overlayService.redialLastCall()
                } else {
                    overlayService.makeCall(contactName)
                }
                ToolResult(ToolStatus.SUCCESS)
            }
            "sendWhatsApp" -> {
                val contactName = args.optString("contactName")
                val message = args.optString("message")
                overlayService.sendWhatsApp(contactName, message)
                ToolResult(ToolStatus.SUCCESS)
            }
            "toggleTorch" -> {
                val state = args.optBoolean("state", true)
                overlayService.toggleTorch(state)
                ToolResult(ToolStatus.SUCCESS)
            }
            "setAlarm" -> {
                val hour = args.optInt("hour", -1)
                val minute = args.optInt("minute", 0)
                if (hour != -1) {
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    overlayService.speak("Setting alarm for $hour:$minute, sir.")
                }
                ToolResult(ToolStatus.SUCCESS)
            }
            "setTimer" -> {
                val seconds = args.optInt("seconds", 60)
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                overlayService.speak("Timer set for $seconds seconds, sir.")
                ToolResult(ToolStatus.SUCCESS)
            }
            "openMaps" -> {
                val query = args.optString("query")
                val uri = if (query.isNotEmpty()) "geo:0,0?q=${Uri.encode(query)}" else "geo:0,0"
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                overlayService.speak("Opening maps, sir.")
                ToolResult(ToolStatus.SUCCESS)
            }
            "navigation" -> {
                val action = args.optString("action")
                var accAction = when (action) {
                    "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                    "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                    "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                    "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    "quickSettings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                    "lockScreen" -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else -1
                    "screenshot" -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else -1
                    "closeApp" -> AccessibilityService.GLOBAL_ACTION_BACK
                    "closeAllApps" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                    else -> -1
                }
                
                if (accAction != -1) {
                    val handled = WhatsAppAccessibilityService.instance?.performGlobal(accAction) ?: false
                    if (handled) {
                        overlayService.speak("Done, sir.")
                        ToolResult(ToolStatus.SUCCESS)
                    } else {
                        overlayService.speak("Sir, please enable Accessibility Service for Max.")
                        ToolResult(ToolStatus.PERMISSION_REQUIRED)
                    }
                } else {
                    overlayService.speak("Sir, this navigation action is not supported on your Android version.")
                    ToolResult(ToolStatus.NOT_SUPPORTED)
                }
            }
            "systemControl" -> {
                val feature = args.optString("feature")
                val state = args.optBoolean("state", true)
                when (feature) {
                    "wifi" -> {
                        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        overlayService.speak("Opening WiFi settings, sir.")
                    }
                    "bluetooth" -> {
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        overlayService.speak("Opening Bluetooth settings, sir.")
                    }
                    "mobileData" -> {
                        context.startActivity(Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        overlayService.speak("Opening Mobile Data settings, sir.")
                    }
                    "autoRotate" -> {
                        if (Settings.System.canWrite(context)) {
                            Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (state) 1 else 0)
                            overlayService.speak("Auto rotate turned ${if (state) "on" else "off"}, sir.")
                        } else {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            overlayService.speak("Sir, please allow modify system settings for rotate control.")
                            return ToolResult(ToolStatus.PERMISSION_REQUIRED)
                        }
                    }
                    "airplaneMode" -> {
                        context.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        overlayService.speak("Opening Airplane mode settings, sir.")
                    }
                    "batterySaver" -> {
                        context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        overlayService.speak("Opening Battery Saver settings, sir.")
                    }
                    "hotspot" -> {
                        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        overlayService.speak("Opening Wireless settings for hotspot, sir.")
                    }
                }
                ToolResult(ToolStatus.SUCCESS)
            }
            "mediaControl" -> {
                val action = args.optString("action")
                val event = when (action) {
                    "play", "resume" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                    "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                    "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                    "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
                    else -> -1
                }
                if (event != -1) {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, event))
                    am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, event))
                    overlayService.speak("Media $action, sir.")
                    ToolResult(ToolStatus.SUCCESS)
                } else {
                    ToolResult(ToolStatus.NOT_SUPPORTED)
                }
            }
            "readNotifications" -> {
                val count = args.optInt("count", 3)
                val notifs = MaxNotificationListener.instance?.getLatestNotifications(count)
                if (notifs.isNullOrEmpty()) {
                    overlayService.speak("Sir, I found no active notifications, or you need to grant me notification access.")
                    ToolResult(ToolStatus.FAILED)
                } else {
                    overlayService.speak("Sir, here are your latest notifications: " + notifs.joinToString(". "))
                    ToolResult(ToolStatus.SUCCESS)
                }
            }
            "readSMS" -> {
                val info = overlayService.getLastSMS()
                overlayService.speak(info)
                ToolResult(ToolStatus.SUCCESS)
            }
            "smartSearch" -> {
                val provider = args.optString("provider")
                val query = args.optString("query")
                val intent = when (provider) {
                    "amazon" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.amazon.in/s?k=${Uri.encode(query)}"))
                    "flipkart" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.flipkart.com/search?q=${Uri.encode(query)}"))
                    "youtube" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"))
                    "google" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))
                    "lens" -> {
                        Intent().apply {
                            action = Intent.ACTION_VIEW
                            setPackage("com.google.android.googlequicksearchbox")
                            data = Uri.parse("googlelens://")
                        }
                    }
                    else -> null
                }
                if (intent != null) {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    overlayService.speak("Searching for $query on $provider, sir.")
                    ToolResult(ToolStatus.SUCCESS)
                } else {
                    ToolResult(ToolStatus.NOT_SUPPORTED)
                }
            }
            "productivity" -> {
                val category = args.optString("category") // notes, tasks, shopping
                val action = args.optString("action") // add, read, clear
                val content = args.optString("content")

                when (category) {
                    "notes" -> {
                        if (action == "add") {
                            dataManager.addNote(content)
                            overlayService.speak("Note saved, sir.")
                        } else if (action == "read") {
                            val notes = dataManager.getNotes()
                            if (notes.isEmpty()) overlayService.speak("Sir, you have no notes.")
                            else overlayService.speak("Your notes are: " + notes.joinToString(". "))
                        } else if (action == "clear") {
                            dataManager.clearNotes()
                            overlayService.speak("Notes cleared, sir.")
                        }
                    }
                    "tasks" -> {
                        if (action == "add") {
                            dataManager.addTask(content)
                            overlayService.speak("Task added, sir.")
                        } else if (action == "read") {
                            val tasks = dataManager.getTasks()
                            if (tasks.isEmpty()) overlayService.speak("Sir, your task list is empty.")
                            else overlayService.speak("Your tasks are: " + tasks.joinToString(". "))
                        } else if (action == "clear") {
                            dataManager.clearTasks()
                            overlayService.speak("Tasks cleared, sir.")
                        }
                    }
                    "shopping" -> {
                        if (action == "add") {
                            dataManager.addShoppingItem(content)
                            overlayService.speak("Added to shopping list, sir.")
                        } else if (action == "read") {
                            val items = dataManager.getShoppingList()
                            if (items.isEmpty()) overlayService.speak("Sir, your shopping list is empty.")
                            else overlayService.speak("Your shopping list includes: " + items.joinToString(", "))
                        } else if (action == "clear") {
                            dataManager.clearShoppingList()
                            overlayService.speak("Shopping list cleared, sir.")
                        }
                    }
                }
                ToolResult(ToolStatus.SUCCESS)
            }
            "utility" -> {
                val action = args.optString("action")
                when (action) {
                    "bmi" -> {
                        val weight = args.optDouble("weight") // kg
                        val height = args.optDouble("height") // cm
                        val bmi = weight / ((height / 100) * (height / 100))
                        val category = when {
                            bmi < 18.5 -> "underweight"
                            bmi < 25 -> "normal weight"
                            bmi < 30 -> "overweight"
                            else -> "obese"
                        }
                        overlayService.speak(String.format("Sir, your B M I is %.1f, which is considered %s.", bmi, category))
                    }
                    "splitBill" -> {
                        val amount = args.optDouble("amount")
                        val people = args.optInt("people", 1)
                        val split = amount / people
                        overlayService.speak(String.format("Sir, each person should pay %.2f.", split))
                    }
                    "age" -> {
                        val birthYear = args.optInt("birthYear")
                        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                        overlayService.speak("Sir, you are ${currentYear - birthYear} years old.")
                    }
                }
                ToolResult(ToolStatus.SUCCESS)
            }
            "phoneInfo" -> {
                val info = overlayService.getDeviceInfo(args.optString("query", "device"))
                overlayService.speak(info)
                ToolResult(ToolStatus.SUCCESS)
            }
            "missedCalls" -> {
                val info = overlayService.getMissedCalls()
                overlayService.speak(info)
                ToolResult(ToolStatus.SUCCESS)
            }
            "fileOp" -> {
                val action = args.optString("action")
                when (action) {
                    "openDownloads" -> {
                        val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        overlayService.speak("Opening downloads, sir.")
                    }
                    "openDocuments" -> {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        overlayService.speak("Opening documents, sir.")
                    }
                }
                ToolResult(ToolStatus.SUCCESS)
            }
            "health" -> {
                val action = args.optString("action")
                when (action) {
                    "breathing" -> {
                        overlayService.speak("Sir, let's start a breathing exercise. Inhale slowly... hold... and exhale. Repeat three times.")
                    }
                    "hydration" -> {
                        overlayService.speak("Sir, remember to drink water. Staying hydrated is essential for your health.")
                    }
                }
                ToolResult(ToolStatus.SUCCESS)
            }
            "automation" -> {
                val mode = args.optString("mode")
                when (mode) {
                    "goodMorning" -> {
                        val greeting = "Good morning, sir. It is ${java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())}. Your battery is ${overlayService.getDeviceInfo("battery")}. You have no urgent alerts."
                        overlayService.speak(greeting)
                    }
                    "goodNight" -> {
                        overlayService.toggleTorch(false)
                        overlayService.speak("Good night, sir. D N D is active. Rest well.")
                        // Logic to set DND could be added here
                    }
                    "studyMode" -> {
                        overlayService.speak("Study mode activated, sir. I will silence all distractions.")
                    }
                }
                ToolResult(ToolStatus.SUCCESS)
            }
            "readScreen" -> {
                val content = WhatsAppAccessibilityService.instance?.readCurrentScreen() ?: "Accessibility service is not active, sir."
                overlayService.speak(content)
                ToolResult(ToolStatus.SUCCESS)
            }
            "socialApps" -> {
                val app = args.optString("app")
                val pkg = when (app) {
                    "gpay" -> "com.google.android.apps.nbu.paisa.user"
                    "phonepe" -> "com.phonepe.app"
                    "paytm" -> "net.one97.paytm"
                    "instagram" -> "com.instagram.android"
                    "twitter", "x" -> "com.twitter.android"
                    "linkedin" -> "com.linkedin.android"
                    "telegram" -> "org.telegram.messenger"
                    "snapchat" -> "com.snapchat.android"
                    "uber" -> "com.ubercab"
                    "ola" -> "com.olacabs.customer"
                    "swiggy" -> "in.swiggy.android"
                    "zomato" -> "com.application.zomato"
                    "netflix" -> "com.netflix.mediaclient"
                    "spotify" -> "com.spotify.music"
                    else -> null
                }
                if (pkg != null) {
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        overlayService.speak("Opening $app, sir.")
                        ToolResult(ToolStatus.SUCCESS)
                    } else {
                        overlayService.speak("Sir, $app is not installed on this device.")
                        ToolResult(ToolStatus.NOT_FOUND)
                    }
                } else {
                    ToolResult(ToolStatus.NOT_SUPPORTED)
                }
            }
            "travel" -> {
                val query = when (args.optString("type")) {
                    "restaurants" -> "restaurants near me"
                    "hospitals" -> "hospitals near me"
                    "petrol" -> "petrol pumps near me"
                    else -> args.optString("query")
                }
                val uri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                overlayService.speak("Finding nearby $query, sir.")
                ToolResult(ToolStatus.SUCCESS)
            }
            else -> ToolResult(ToolStatus.NOT_SUPPORTED)
        }
    }
}

