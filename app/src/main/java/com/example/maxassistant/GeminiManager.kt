package com.example.maxassistant

import android.util.Log
import com.example.maxassistant.model.Message
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class GeminiManager : AiProvider {

    private val conversationHistory = mutableListOf<Message>()
    private val MAX_HISTORY = 10

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun addMessage(role: String, content: String) {
        conversationHistory.add(Message(role, content))
        if (conversationHistory.size > MAX_HISTORY) {
            conversationHistory.removeAt(0)
        }
    }

    override fun clearHistory() {
        conversationHistory.clear()
    }

    override suspend fun askAi(question: String): Result<String> = suspendCancellableCoroutine { continuation ->
        val apiKey = Constants.GEMINI_API_KEY
        if (apiKey.isBlank()) {
            continuation.resume(Result.failure(Exception("API_KEY_MISSING")))
            return@suspendCancellableCoroutine
        }

        // Add user message to history
        addMessage("user", question)
        android.util.Log.d("MAX_AI", "GEMINI request started for: $question")

        val contentsJson = JSONArray()
        
        // System prompt as a special message for Gemini (or instructions)
        // Gemini 1.5 Flash supports system instructions, but for simple REST we can prepended to user prompt or use specific field.
        // For simplicity and compatibility, we'll use the 'systemInstruction' field if supported, or just a system message.
        
        val systemPrompt = "You are MAX, a futuristic personal AI assistant like Jarvis. Address user as 'sir'. " +
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
                    "- systemControl(feature: wifi|bluetooth|mobileData|autoRotate|airplaneMode|batterySaver|hotspot, state:bool)\n" +
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
                    "Otherwise, respond with normal text. Address user as 'sir'."

        val bodyJson = JSONObject().apply {
            val contentsArray = JSONArray()
            
            // Add conversation history
            // Gemini roles: 'user', 'model' (assistant)
            for (msg in conversationHistory) {
                contentsArray.put(JSONObject().apply {
                    put("role", if (msg.role == "assistant") "model" else "user")
                    put("parts", JSONArray().put(JSONObject().put("text", msg.content)))
                })
            }
            
            put("contents", contentsArray)
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
                put("maxOutputTokens", 512)
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/${Constants.GEMINI_MODEL}:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                android.util.Log.e("MAX_AI", "GEMINI request failed: ${e.message}")
                continuation.resume(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                android.util.Log.d("MAX_AI", "GEMINI HTTP status: $code")
                val body = response.body?.string()
                
                if (response.isSuccessful && body != null) {
                    try {
                        val jsonRes = JSONObject(body)
                        val content = jsonRes.getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")
                        
                        android.util.Log.d("MAX_AI", "GEMINI response received and parsed")
                        // Add AI response to history
                        addMessage("assistant", content)
                        continuation.resume(Result.success(content))
                    } catch (e: Exception) {
                        android.util.Log.e("MAX_AI", "GEMINI parse error: ${e.message}")
                        continuation.resume(Result.failure(e))
                    }
                } else {
                    android.util.Log.e("MAX_AI", "GEMINI error response: $code - $body")
                    val exception = when (code) {
                        429 -> Exception("RESOURCE_EXHAUSTED")
                        else -> Exception("HTTP_$code")
                    }
                    continuation.resume(Result.failure(exception))
                }
            }
        })
    }
}
