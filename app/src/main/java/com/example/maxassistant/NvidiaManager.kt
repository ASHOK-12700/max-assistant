package com.example.maxassistant

import android.util.Log
import com.example.maxassistant.model.ChatRequest
import com.example.maxassistant.model.Message
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NvidiaManager {

    private val conversationHistory = mutableListOf<Message>()
    private val MAX_HISTORY = 10

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun addMessage(role: String, content: String) {
        conversationHistory.add(Message(role, content))
        if (conversationHistory.size > MAX_HISTORY) {
            conversationHistory.removeAt(0)
        }
    }

    fun clearHistory() {
        conversationHistory.clear()
    }

    suspend fun askNvidia(question: String, callback: (Result<String>) -> Unit) {
        val apiKey = Constants.NVIDIA_API_KEY
        if (apiKey.isBlank() || apiKey == "YOUR_NVIDIA_API_KEY_HERE") {
            callback(Result.failure(Exception("API_KEY_MISSING")))
            return
        }

        // Add user message to history
        addMessage("user", question)

        val messagesJson = JSONArray()
        // System prompt
        messagesJson.put(JSONObject().apply {
            put("role", "system")
            put("content", "You are MAX, a futuristic personal AI assistant like Jarvis. Address user as 'sir'. " +
                    "Respond concisely. You understand English, Telugu, and Tanglish. " +
                    "If the user wants you to do something, return a JSON object ONLY: " +
                    "{\"type\": \"tool_call\", \"tool\": \"toolName\", \"arguments\": {...}}. " +
                    "Available tools: \n" +
                    "- openApp(appName)\n" +
                    "- makeCall(contactName)\n" +
                    "- sendWhatsApp(contactName, message)\n" +
                    "- toggleTorch(state:bool)\n" +
                    "- setAlarm(hour, minute)\n" +
                    "- setTimer(seconds)\n" +
                    "- openMaps(query)\n" +
                    "- navigation(action: home|back|recents|notifications|quickSettings|lockScreen|screenshot)\n" +
                    "- systemControl(feature: wifi|bluetooth|mobileData|autoRotate|airplaneMode, state:bool)\n" +
                    "- mediaControl(action: play|pause|next|previous|stop)\n" +
                    "- productivity(category: notes|tasks|shopping, action: add|read|clear, content:string)\n" +
                    "- utility(action: bmi|splitBill|age, weight:double, height:double, amount:double, people:int, birthYear:int)\n" +
                    "- smartSearch(provider: amazon|flipkart|youtube|google|lens, query:string)\n" +
                    "- readNotifications(count:int)\n" +
                    "- fileOp(action: openDownloads|openDocuments)\n" +
                    "- socialApps(app: gpay|phonepe|paytm|instagram|twitter|linkedin|telegram|snapchat|uber|ola|swiggy|zomato|netflix|spotify)\n" +
                    "- travel(type: restaurants|hospitals|petrol, query:string)\n" +
                    "- health(action: breathing|hydration)\n" +
                    "- automation(mode: goodMorning|goodNight|studyMode)\n" +
                    "- readScreen()\n" +
                    "- missedCalls()\n" +
                    "Otherwise, respond with normal text. Address user as 'sir'.")
        })
        
        // Context
        for (msg in conversationHistory) {
            messagesJson.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }

        val json = JSONObject().apply {
            put("model", Constants.NVIDIA_MODEL)
            put("messages", messagesJson)
            put("temperature", 0.5)
            put("max_tokens", 256)
        }

        val request = Request.Builder()
            .url(Constants.NVIDIA_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    callback(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string()
                    if (response.isSuccessful && body != null) {
                        try {
                            val jsonRes = JSONObject(body)
                            val content = jsonRes.getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content")
                            
                            // Add AI response to history
                            addMessage("assistant", content)
                            callback(Result.success(content))
                        } catch (e: Exception) {
                            callback(Result.failure(e))
                        }
                    } else {
                        callback(Result.failure(Exception("HTTP_${response.code}")))
                    }
                }
            })
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }
}
