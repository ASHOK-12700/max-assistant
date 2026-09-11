package com.example.maxassistant.model

enum class ToolStatus {
    SUCCESS,
    FAILED,
    NOT_SUPPORTED,
    PERMISSION_REQUIRED,
    NOT_FOUND
}

data class ToolResult(
    val status: ToolStatus,
    val message: String? = null
)
