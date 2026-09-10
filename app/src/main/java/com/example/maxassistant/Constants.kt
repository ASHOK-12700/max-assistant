package com.example.maxassistant

// ✅ API KEYS - ikkad oka place lo pettు
// console.groq.com lo free key teesuko
object Constants {
    const val GROQ_API_KEY = BuildConfig.GROQ_API_KEY
    const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    const val GROQ_MODEL = "llama3-8b-8192"

    const val NVIDIA_API_KEY = BuildConfig.NVIDIA_API_KEY
    const val NVIDIA_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
    const val NVIDIA_MODEL = "deepseek-ai/deepseek-v4-flash-0731"
}
