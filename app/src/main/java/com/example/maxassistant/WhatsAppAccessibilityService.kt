package com.example.maxassistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhatsAppAccessibilityService? = null
        var messageToSend = ""
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    fun performGlobal(action: Int): Boolean {
        return performGlobalAction(action)
    }

    fun readCurrentScreen(): String {
        val rootNode = rootInActiveWindow ?: return "I cannot see the screen content right now, sir."
        val sb = StringBuilder()
        traverseNodes(rootNode, sb)
        return if (sb.isEmpty()) "The screen seems empty, sir." else sb.toString()
    }

    private fun traverseNodes(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if (!text.isNullOrEmpty()) sb.append(text).append(". ")
        else if (!desc.isNullOrEmpty()) sb.append(desc).append(". ")
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverseNodes(child, sb)
            }
        }
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