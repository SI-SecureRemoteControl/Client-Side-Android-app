package ba.unsa.etf.si.secureremotecontrol.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Intent
import android.os.Bundle

class RemoteControlAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RemoteControlAccessibilityService? = null
        private const val TAG = "RemoteControlAccessibility"
    }

    private var currentPackage: String? = null
    private var isServiceEnabled = false
    private var currentText = StringBuilder()
    private var lastFocusedNode: AccessibilityNodeInfo? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceEnabled = true
        Log.d(TAG, "Service connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                event.packageName?.toString()?.let { packageName ->
                    if (currentPackage != packageName) {
                        currentPackage = packageName
                        currentText.clear()
                        lastFocusedNode = null
                        Log.d(TAG, "Switched to app: $packageName")
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val source = event.source
                if (source?.isEditable == true) {
                    lastFocusedNode = source
                    val text = source.text?.toString() ?: ""
                    currentText.clear()
                    currentText.append(text)
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted.")
    }

    fun performClick(x: Float, y: Float) {
        if (!isServiceEnabled) {
            Log.e(TAG, "Service is not enabled")
            return
        }

        val path = Path().apply {
            moveTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Click performed at: ($x, $y) in package: $currentPackage")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d(TAG, "Click cancelled at: ($x, $y)")
            }
        }, null)
    }

    fun performSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ) {
        if (!isServiceEnabled) {
            Log.e(TAG, "Service is not enabled")
            return
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Swipe completed from ($startX, $startY) to ($endX, $endY)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d(TAG, "Swipe cancelled from ($startX, $startY) to ($endX, $endY)")
            }
        }, null)
    }

    fun inputCharacter(char: String) {
        when (char) {
            "Backspace" -> {
                if (currentText.isNotEmpty()) {
                    currentText.deleteCharAt(currentText.length - 1)
                }
            }
            "Enter" -> {
                lastFocusedNode?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        currentText.toString() + "\n"
                    )
                })
                currentText.clear()
                return
            }
            else -> {
                if (char.length == 1) {
                    currentText.append(char)
                }
            }
        }

        val rootNode = rootInActiveWindow
        val inputNode = findFirstEditableNode(rootNode)

        if (inputNode != null) {
            lastFocusedNode = inputNode
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    currentText.toString()
                )
            }
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            // Move cursor to end
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, currentText.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentText.length)
            })
        } else {
            Log.w(TAG, "No editable field found")
        }
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        if (node.isEditable) {
            return node
        }

        if (node.isFocused && node.isEditable) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findFirstEditableNode(child)
            if (result != null) {
                return result
            }
            child?.recycle()
        }

        return null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceEnabled = false
        instance = null
        return super.onUnbind(intent)
    }
}