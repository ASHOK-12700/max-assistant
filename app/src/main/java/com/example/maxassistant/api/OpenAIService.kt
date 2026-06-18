package com.example.maxassistant.api

import com.example.maxassistant.model.ChatRequest
import com.example.maxassistant.model.ChatResponse
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface OpenAIService {

    @POST("v1/chat/completions")
    suspend fun chat(
        @retrofit2.http.Header("Authorization") auth: String,
        @Body request: ChatRequest
    ): ChatResponse
}