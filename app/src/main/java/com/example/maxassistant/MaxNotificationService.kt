package com.example.maxassistant

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MaxNotificationService : NotificationListenerService() {

    companion object {
        var instance: MaxNotificationService? = null
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun getLastWhatsAppMessage(): String? {
        val notifications = activeNotifications
        for (sbn in notifications.reversed()) {
            if (sbn.packageName == "com.whatsapp") {
                val extras = sbn.notification.extras
                val title = extras.getString("android.title")
                val text = extras.getCharSequence("android.text")?.toString()
                if (title != null && text != null) {
                    return "Sir, WhatsApp from $title: $text"
                }
            }
        }
        return null
    }

    fun getAllNotifications(): String {
        val notifications = activeNotifications
        if (notifications.isEmpty()) return "Sir, you have no new notifications."
        
        val sb = StringBuilder("Sir, you have ${notifications.size} notifications. ")
        val limit = if (notifications.size > 3) 3 else notifications.size
        
        for (i in 0 until limit) {
            val sbn = notifications[notifications.size - 1 - i]
            val extras = sbn.notification.extras
            val appName = packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
            val title = extras.getString("android.title") ?: "No title"
            val text = extras.getCharSequence("android.text")?.toString() ?: "No content"
            sb.append("$appName from $title: $text. ")
        }
        return sb.toString()
    }
}
