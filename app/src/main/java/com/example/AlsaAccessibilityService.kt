package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AlsaAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Active event logging or capturing if required in future workflows
    }

    override fun onInterrupt() {
        // Handle service interruption gracefully
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        LocalEncryptedStorage.saveLog(this, "Accessibility Service: Activated successfully")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        LocalEncryptedStorage.saveLog(this, "Accessibility Service: Deactivated/Destroyed")
    }

    companion object {
        @Volatile
        private var instance: AlsaAccessibilityService? = null

        fun isActive(): Boolean = instance != null

        // Inject text into the currently focused or any editable field
        fun injectText(text: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            
            val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null) {
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                if (success) {
                    LocalEncryptedStorage.saveLog(service, "Text injected into focused element: '$text'")
                    return true
                }
            }
            
            // Fallback: search tree recursively for editable node
            return searchAndInject(root, text)
        }

        private fun searchAndInject(node: AccessibilityNodeInfo?, text: String): Boolean {
            if (node == null) return false
            if (node.isEditable) {
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                if (success) {
                    LocalEncryptedStorage.saveLog(instance!!, "Text injected into editable node: '$text'")
                    return true
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (searchAndInject(child, text)) return true
            }
            return false
        }

        // Simulate click at coordinates
        fun simulateClick(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            val builder = GestureDescription.Builder()
            val path = Path()
            path.moveTo(x, y)
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            val gesture = builder.build()
            
            val success = service.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    LocalEncryptedStorage.saveLog(service, "Gesture success: Click coordinate ($x, $y)")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    LocalEncryptedStorage.saveLog(service, "Gesture cancelled: Click coordinate ($x, $y)")
                }
            }, null)
            
            LocalEncryptedStorage.saveLog(service, "Dispatched click: x=$x, y=$y (Accepted: $success)")
            return success
        }

        // Simulate swipe from start coordinates to end coordinates
        fun simulateSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean {
            val service = instance ?: return false
            val builder = GestureDescription.Builder()
            val path = Path()
            path.moveTo(startX, startY)
            path.lineTo(endX, endY)
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            val gesture = builder.build()
            
            val success = service.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    LocalEncryptedStorage.saveLog(service, "Gesture success: Swipe ($startX, $startY) -> ($endX, $endY)")
                }
            }, null)
            
            LocalEncryptedStorage.saveLog(service, "Dispatched swipe: ($startX, $startY) -> ($endX, $endY) (Accepted: $success)")
            return success
        }

        // Launch app by package name
        fun launchApp(packageName: String): Boolean {
            val service = instance ?: return false
            val pm = service.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                service.startActivity(intent)
                LocalEncryptedStorage.saveLog(service, "Launched app package: $packageName")
                return true
            }
            LocalEncryptedStorage.saveLog(service, "Failed to launch app: package '$packageName' not found")
            return false
        }

        // Route URL using system implicit intent (default browser or deep links)
        fun resolveUrl(url: String): Boolean {
            val service = instance ?: return false
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                service.startActivity(intent)
                LocalEncryptedStorage.saveLog(service, "Resolved secure URL: $url")
                true
            } catch (e: Exception) {
                LocalEncryptedStorage.saveLog(service, "Error launching URL workflow: ${e.message}")
                false
            }
        }

        // Search text on screen and perform action click on it
        fun clickNodeWithText(text: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (node.isClickable) {
                        val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (success) {
                            LocalEncryptedStorage.saveLog(service, "Clicked element matching text '$text'")
                            return true
                        }
                    }
                    // Bubble up search to clickable parent
                    var parent = node.parent
                    while (parent != null) {
                        if (parent.isClickable) {
                            val success = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (success) {
                                LocalEncryptedStorage.saveLog(service, "Clicked clickable parent element for text '$text'")
                                return true
                            }
                        }
                        parent = parent.parent
                    }
                }
            }
            LocalEncryptedStorage.saveLog(service, "No clickable element matching '$text' visible")
            return false
        }
    }
}
