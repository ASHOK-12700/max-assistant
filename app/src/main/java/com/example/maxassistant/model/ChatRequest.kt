package com.example.maxassistant.model

data class ChatRequest(
    val model: String = "gpt-4o-mini",
    val messages: List<Message>
)