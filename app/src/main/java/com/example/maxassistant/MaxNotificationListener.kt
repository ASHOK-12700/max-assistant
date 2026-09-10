package com.example.maxassistant

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MaxNotificationListener : NotificationListenerService() {

    companion object {
        var instance: MaxNotificationListener? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d("MAX_NOTIF", "Listener Connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    fun getLatestNotifications(count: Int = 3): List<String> {
        val activeNotifs = activeNotifications ?: return emptyList()
        return activeNotifs.sortedByDescending { it.postTime }
            .take(count)
            .map { sbn ->
                val extras = sbn.notification.extras
                val title = extras.getString("android.title") ?: "No Title"
                val text = extras.getCharSequence("android.text")?.toString() ?: "No Content"
                "From $title: $text"
            }
    }
}
