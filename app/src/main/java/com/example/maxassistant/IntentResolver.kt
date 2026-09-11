package com.example.maxassistant

import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

enum class IntentType {
    OPEN_APP,
    MAKE_CALL,
    SEND_WHATSAPP,
    TOGGLE_TORCH,
    SET_ALARM,
    SET_TIMER,
    VOLUME_CONTROL,
    BRIGHTNESS_CONTROL,
    NAVIGATION,
    BATTERY_STATUS,
    TIME_DATE,
    IDENTITY,
    EMOTION,
    NONE
}

data class ResolvedIntent(
    val type: IntentType,
    val action: String? = null,
    val target: String? = null,
    val value: Any? = null,
    val contact: String? = null,
    val message: String? = null
)

class IntentResolver(private val context: android.content.Context) {

    private val teluguActionMap = mapOf(
        "open cheyyi" to "open",
        "open chey" to "open",
        "teeyi" to "open",
        "thiyy" to "open",
        "pettu" to "set",
        "call cheyyi" to "call",
        "on cheyyi" to "on",
        "off cheyyi" to "off",
        "penchu" to "increase",
        "tagginchu" to "decrease",
        "ekkuva chei" to "increase",
        "thakkuva chei" to "decrease",
        "cheppu" to "tell",
        "pampu" to "send",
        "pampinchu" to "send",
        "vellu" to "go",
        "tesukellu" to "take",
        "chupinchu" to "show",
        "oka song play cheyyi" to "play",
        "music pettu" to "play",
        "paata play chey" to "play",
        "music play chey" to "play",
        "song play chey" to "play"
    )

    fun resolve(input: String): ResolvedIntent {
        var clean = input.lowercase().trim()
        
        // Remove wake words
        val wakeWords = listOf("max", "hey max", "ok max", "hello max")
        for (w in wakeWords) {
            if (clean.startsWith(w)) {
                clean = clean.removePrefix(w).trim()
                break
            }
        }

        if (clean.isEmpty()) return ResolvedIntent(IntentType.NONE)

        // Pre-normalization for common Tanglish structures
        var normalized = clean
            .replace(" ni open chey", " open")
            .replace(" ni open cheyyi", " open")
            .replace(" lo ", " ")
            .replace(" ki ", " ")
            .replace(" open chesi ", " ")

        // Basic Telugu to English normalization for key verbs
        for ((tel, eng) in teluguActionMap) {
            normalized = normalized.replace(tel, eng)
        }

        // 1. Identity & Personality
        if (isIdentity(normalized)) return ResolvedIntent(IntentType.IDENTITY)
        if (isEmotion(normalized)) return resolveEmotion(normalized)

        // 2. Navigation
        if (isHome(normalized)) return ResolvedIntent(IntentType.NAVIGATION, action = "home")
        if (isBack(normalized)) return ResolvedIntent(IntentType.NAVIGATION, action = "back")
        if (isRecents(normalized)) return ResolvedIntent(IntentType.NAVIGATION, action = "recents")

        // 3. Device Control
        if (isTorch(normalized)) return resolveTorch(normalized)
        if (isVolume(normalized)) return resolveVolume(normalized)
        if (isBrightness(normalized)) return resolveBrightness(normalized)
        if (isBattery(normalized)) return ResolvedIntent(IntentType.BATTERY_STATUS)

        // 4. Communication
        if (isCall(normalized)) return resolveCall(normalized)
        if (isWhatsApp(normalized)) return resolveWhatsApp(normalized)

        // 5. Utilities
        if (isAlarm(normalized)) return resolveAlarm(normalized)
        if (isTimer(normalized)) return resolveTimer(normalized)
        if (isTimeDate(normalized)) return ResolvedIntent(IntentType.TIME_DATE)

        // 6. App Launching & Targeted App Actions
        if (isOpenApp(normalized)) return resolveOpenApp(normalized)
        if (isAppAction(normalized)) return resolveAppAction(normalized)

        return ResolvedIntent(IntentType.NONE)
    }

    private fun isIdentity(s: String) = s.contains("who are you") || s.contains("nee peru enti") || s.contains("what is your name") || s.contains("who created you") || s.contains("what can you do") || s == "max" || s == "hello" || s.contains("introduce") || s.contains("evarnivi") || s.contains("em cheyagalavu") || s.contains("help") || s.contains("sahayam")

    private fun isEmotion(s: String) = (s.contains("smile") || s.contains("happy") || s.contains("sad") || s.contains("angry") || s.contains("surprised") || s.contains("love") || s.contains("cool") || s.contains("excited") || s.contains("sleep") || s.contains("blink") || s.contains("నవ్వు") || s.contains("కోపం") || s.contains("ఏడుపు"))

    private fun resolveEmotion(s: String): ResolvedIntent {
        val emotion = when {
            s.contains("smile") || s.contains("happy") -> "happy"
            s.contains("sad") -> "sad"
            s.contains("angry") -> "angry"
            s.contains("surprised") -> "surprised"
            s.contains("love") -> "love"
            s.contains("cool") -> "cool"
            s.contains("excited") -> "excited"
            s.contains("sleep") -> "sleep"
            s.contains("blink") -> "blink"
            else -> "idle"
        }
        return ResolvedIntent(IntentType.EMOTION, target = emotion)
    }

    private fun isHome(s: String) = s == "go home" || s == "home ki vellu" || s == "home" || s == "intiki vellu" || s.contains("home screen") || s.contains("హోమ్")
    private fun isBack(s: String) = s == "go back" || s == "back ki vellu" || s == "venakki vellu" || s == "back" || s.contains("వెనక్కి")
    private fun isRecents(s: String) = s.contains("recent apps") || s == "recents" || s.contains("tabs open chey") || s.contains("రీసెంట్")

    private fun isTorch(s: String) = s.contains("torch") || s.contains("flashlight") || s.contains("light") || s.contains("టార్చ్")
    private fun resolveTorch(s: String): ResolvedIntent {
        val state = !s.contains("off") && !s.contains("apeseyi") && !s.contains("ఆపు")
        return ResolvedIntent(IntentType.TOGGLE_TORCH, value = state)
    }

    private fun isVolume(s: String) = s.contains("volume") || s.contains("sound") || s.contains("penchu") || s.contains("tagginchu") || s.contains("శబ్దం")
    private fun resolveVolume(s: String): ResolvedIntent {
        val action = if (s.contains("up") || s.contains("penchu") || s.contains("increase") || s.contains("ekkuva") || s.contains("ఎక్కువ")) "up" else "down"
        return ResolvedIntent(IntentType.VOLUME_CONTROL, action = action)
    }

    private fun isBrightness(s: String) = s.contains("brightness") || s.contains("కాంతి")
    private fun resolveBrightness(s: String): ResolvedIntent {
        return ResolvedIntent(IntentType.BRIGHTNESS_CONTROL, target = s)
    }

    private fun isBattery(s: String) = s.contains("battery") || s.contains("charge") || s.contains("బ్యాటరీ")

    private fun isCall(s: String) = s.contains("call") || s.contains("piluvu") || s.contains("కాల్")
    private fun resolveCall(s: String): ResolvedIntent {
        if (s.contains("redial")) return ResolvedIntent(IntentType.MAKE_CALL, contact = "emergency")
        val name = s.replace("calling", "").replace("call", "").replace("cheyyi", "").replace("chey", "").replace("piluvu", "").replace("కి", "").trim()
        return ResolvedIntent(IntentType.MAKE_CALL, contact = name)
    }

    private fun isWhatsApp(s: String) = s.contains("whatsapp") || s.contains("message") || s.contains("pampu") || s.contains("pampinchu") || s.contains("వాట్సాప్")
    private fun resolveWhatsApp(s: String): ResolvedIntent {
        var contact = ""
        var msg = ""
        
        when {
            s.contains(" to ") -> {
                val parts = s.split(" to ")
                contact = parts.last().trim()
                msg = parts.first().replace("send message", "").replace("send", "").replace("whatsapp", "").trim()
            }
            s.contains(" ki ") -> {
                val parts = s.split(" ki ")
                contact = parts.first().replace("whatsapp", "").trim()
                val rem = parts.last()
                msg = rem.replace("pampinchu", "").replace("pampu", "").replace("message", "").replace("ani", "").replace("cheppu", "").replace("send", "").trim()
            }
            s.startsWith("whatsapp ") || s.startsWith("message ") -> {
                val after = s.replaceFirst("whatsapp ", "").replaceFirst("message ", "").trim()
                val idx = after.indexOf(" ")
                if (idx != -1) {
                    contact = after.substring(0, idx)
                    msg = after.substring(idx).trim()
                }
            }
        }
        
        return ResolvedIntent(IntentType.SEND_WHATSAPP, contact = contact, message = msg)
    }

    private fun isAlarm(s: String) = s.contains("alarm") || s.contains("wake me up") || s.contains("pettu") || s.contains("అలారం")
    private fun resolveAlarm(s: String): ResolvedIntent {
        return ResolvedIntent(IntentType.SET_ALARM, target = s)
    }

    private fun isTimer(s: String) = s.contains("timer") || s.contains("టైమర్")
    private fun resolveTimer(s: String): ResolvedIntent {
        return ResolvedIntent(IntentType.SET_TIMER, target = s)
    }

    private fun isTimeDate(s: String) = s.contains("time") || s.contains("date") || s.contains("day") || s.contains("month") || s.contains("year") || s.contains("సమయం") || s.contains("తేదీ")

    private fun isOpenApp(s: String) = s.startsWith("open ") || s.startsWith("launch ") || s.startsWith("start ") || s.contains(" open") || s.contains(" vellu") || s.contains("ఓపెన్")
    private fun resolveOpenApp(s: String): ResolvedIntent {
        val name = s
            .replace("open ", "")
            .replace("launch ", "")
            .replace("start ", "")
            .replace(" open", "")
            .replace(" vellu", "")
            .replace(" ni ", "")
            .replace("ఓపెన్", "")
            .trim()
        return ResolvedIntent(IntentType.OPEN_APP, target = name)
    }

    private fun isAppAction(s: String) = (s.contains("spotify") || s.contains("instagram") || s.contains("youtube")) && (s.contains("play") || s.contains("music") || s.contains("reels") || s.contains("search") || s.contains("song"))

    private fun resolveAppAction(s: String): ResolvedIntent {
        val app = when {
            s.contains("spotify") -> "spotify"
            s.contains("instagram") -> "instagram"
            s.contains("youtube") -> "youtube"
            else -> ""
        }
        val action = when {
            s.contains("play") || s.contains("music") || s.contains("song") -> "play"
            s.contains("reels") -> "reels"
            s.contains("search") -> "search"
            else -> "open"
        }
        val query = s
            .replace("spotify", "")
            .replace("instagram", "")
            .replace("youtube", "")
            .replace("play", "")
            .replace("music", "")
            .replace("song", "")
            .replace("search", "")
            .replace("reels", "")
            .replace(" ni ", "")
            .trim()
            
        return ResolvedIntent(IntentType.OPEN_APP, action = action, target = app, value = query)
    }
}
