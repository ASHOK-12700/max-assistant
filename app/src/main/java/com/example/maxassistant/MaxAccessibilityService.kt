package com.example.maxassistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MaxAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MaxAccessibilityService? = null
        var messageToSend = ""
        var targetContact = ""
        var autoSend = false
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // Handle WhatsApp automation
        if (autoSend && event.packageName == "com.whatsapp") {
            val rootNode = rootInActiveWindow ?: return
            
            // Find message box
            val messageBoxList = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry")
            if (messageBoxList.isNotEmpty()) {
                val messageBox = messageBoxList[0]
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, messageToSend)
                messageBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                
                // Click send
                val sendButtonList = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
                if (sendButtonList.isNotEmpty()) {
                    sendButtonList[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    autoSend = false // Reset after sending
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun performAction(action: Int): Boolean {
        return performGlobalAction(action)
    }
}
