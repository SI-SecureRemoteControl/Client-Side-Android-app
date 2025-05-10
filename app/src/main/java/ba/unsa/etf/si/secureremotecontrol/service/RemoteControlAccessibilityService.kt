package ba.unsa.etf.si.secureremotecontrol.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RemoteControlAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RemoteControlAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("Accessibility", "Service connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used
    }

    override fun onInterrupt() {
        Log.d("Accessibility", "Service interrupted.")
    }

    fun performClick(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d("Accessibility", "Click performed at: ($x, $y)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d("Accessibility", "Click cancelled at: ($x, $y)")
            }
        }, null)
    }

    fun inputCharacter(char: String) {
        if (char.length != 1) {
            Log.w("Accessibility", "Only single characters supported for input: $char")
            return
        }

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                char
            )
        }

        val rootNode = rootInActiveWindow
        val inputNode = findFirstEditableNode(rootNode)

        inputNode?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            ?: Log.w("Accessibility", "No editable field found")
    }
    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val result = findFirstEditableNode(node.getChild(i))
            if (result != null) return result
        }
        return null
    }

}
