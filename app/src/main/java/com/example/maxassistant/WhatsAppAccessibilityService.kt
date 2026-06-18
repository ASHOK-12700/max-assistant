package com.example.maxassistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        var messageToSend = ""
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        if (event == null) return

        val rootNode = rootInActiveWindow ?: return

        // 🔥 TYPE MESSAGE
        val messageBoxList = rootNode.findAccessibilityNodeInfosByViewId(
            "com.whatsapp:id/entry"
        )

        if (messageBoxList.isNotEmpty()) {

            val messageBox = messageBoxList[0]

            val args = android.os.Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                messageToSend
            )

            messageBox.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                args
            )

            // 🔥 CLICK SEND BUTTON
            val sendButtonList = rootNode.findAccessibilityNodeInfosByViewId(
                "com.whatsapp:id/send"
            )

            if (sendButtonList.isNotEmpty()) {
                sendButtonList[0].performAction(
                    AccessibilityNodeInfo.ACTION_CLICK
                )
            }
        }
    }

    override fun onInterrupt() {}
}