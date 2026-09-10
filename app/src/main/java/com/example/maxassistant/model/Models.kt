package com.example.maxassistant.model




data class ChatResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: Message
)