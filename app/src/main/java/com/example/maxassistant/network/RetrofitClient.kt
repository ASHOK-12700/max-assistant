package com.example.maxassistant.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

import com.example.maxassistant.model.ChatRequest
import com.example.maxassistant.model.ChatResponse

object RetrofitClient {

    private const val BASE_URL = "https://api.openai.com/"

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    interface ApiService {
        @POST("v1/chat/completions")
        suspend fun chat(
            @retrofit2.http.Header("Authorization") auth: String,
            @Body request: ChatRequest
        ): ChatResponse
    }
}