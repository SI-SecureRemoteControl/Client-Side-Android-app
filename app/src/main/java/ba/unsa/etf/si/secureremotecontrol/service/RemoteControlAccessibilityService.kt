package ba.unsa.etf.si.secureremotecontrol.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RemoteControlAccessibilityService : AccessibilityService() {

    private var currentInputText: String = ""
    private var lastFocusedNode: AccessibilityNodeInfo? = null
    companion object {
        var instance: RemoteControlAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("Accessibility", "Service connected.")
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val source = event.source
                if (source != null && source.isEditable) {
                    if (lastFocusedNode == null || !isSameNode(source, lastFocusedNode)) {
                        Log.d("Accessibility", "Focus changed to new input field. Resetting buffer.")
                        currentInputText = ""
                        lastFocusedNode = AccessibilityNodeInfo.obtain(source)  // track this node
                    }
                }
            }
        }
    }
    private fun isSameNode(a: AccessibilityNodeInfo?, b: AccessibilityNodeInfo?): Boolean {
        return a != null && b != null &&
                a.viewIdResourceName == b.viewIdResourceName &&
                a.className == b.className &&
                a.packageName == b.packageName
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

    fun inputText(textToSet: String) {
        val node = findFirstEditableNode(rootInActiveWindow)
        if (node != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToSet)
            }
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d("Accessibility", if (success) "Set text: \"$textToSet\"" else "Failed to set text.")
        } else {
            Log.w("Accessibility", "No editable field found for inputText.")
        }
    }
    fun performBackspace() {
        val node = findFirstEditableNode(rootInActiveWindow)
        if (node != null && currentInputText.isNotEmpty()) {
            currentInputText = currentInputText.dropLast(1)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, currentInputText)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d("Accessibility", "Performed backspace. New text: \"$currentInputText\"")
        }
    }
    fun performEnter() {
        findFirstEditableNode(rootInActiveWindow)?.let { node ->
            var actionPerformed = false // Keep track if any action succeeded

            if (node.isMultiLine) {
                appendText("\n") // Append a newline character for multiline fields
                Log.d("Accessibility", "Appended newline for Enter key on multiline field.")
                actionPerformed = true
            } else {
                // For single-line fields, "Enter" usually means "submit" or "go".

                // 1. Try performing the IME action ID specified by the EditText itself
                val imeActionId = node.extras.getInt("android.view.accessibility.AccessibilityNodeInfo.imeActionId", 0)
                if (imeActionId != 0) {
                    if (node.performAction(imeActionId)) {
                        Log.d("Accessibility", "Performed EditorInfo IME action ID: $imeActionId for Enter.")
                        actionPerformed = true
                    }
                }

                // 2. If not performed, and on API 30+, try the specific ACTION_IME_ENTER accessibility action
                if (!actionPerformed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Check if the node's actionList *actually contains* ACTION_IME_ENTER
                    // before trying to perform it.
                    val actionImeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
                    if (node.actionList.contains(actionImeEnter)) {
                        if (node.performAction(actionImeEnter.id)) {
                            Log.d("Accessibility", "Performed AccessibilityAction.ACTION_IME_ENTER.")
                            actionPerformed = true
                        }
                    } else {
                        Log.d("Accessibility", "Node does not list ACTION_IME_ENTER in its actions.")
                    }
                }

            }

            if (!actionPerformed && !node.isMultiLine) { // Only log warning if it was a single-line field and no action worked
                val imeActionIdForLog = node.extras.getInt("android.view.accessibility.AccessibilityNodeInfo.imeActionId", 0)
                Log.w("Accessibility", "Could not perform Enter on single-line field. IME Action ID from extras: $imeActionIdForLog. Field: ${node.className}, Text: '${node.text}'")
            }

        } ?: Log.w("Accessibility", "No editable field found for Enter key")
    }




    fun appendText(textToAppend: String) {
        val node = findFirstEditableNode(rootInActiveWindow)
        if (node != null) {
            currentInputText += textToAppend  // Keep track manually
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, currentInputText)
            }
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d("Accessibility", if (success) "Appended \"$textToAppend\". Full text: \"$currentInputText\"" else "Failed to append text.")
        } else {
            Log.w("Accessibility", "No editable field found for appendText.")
        }
    }


    fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstEditableNode(child)
            if (result != null) return result
        }
        return null
    }




}