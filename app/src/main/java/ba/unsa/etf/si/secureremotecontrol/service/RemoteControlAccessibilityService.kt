package ba.unsa.etf.si.secureremotecontrol.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import kotlin.math.max

class RemoteControlAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RemoteControlAccessibilityService? = null
        private const val TAG = "RemoteControlAccessibility"
    }

    private var currentPackage: String? = null
    private var isServiceEnabled = false
    private var currentText = StringBuilder()
    private var lastFocusedNode: AccessibilityNodeInfo? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceEnabled = true
        val displayMetrics = resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels + getNavigationBarHeight(this)
        Log.d(TAG, "Service connected. Screen size: ${screenWidth}x${screenHeight}")
    }

    private fun getNavigationBarHeight(context: android.content.Context): Int {
        val resources = context.resources
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }

    private fun clampToScreen(x: Float, y: Float): Pair<Float, Float> {
        val clampedX = x.coerceIn(0f, screenWidth.toFloat())
        val clampedY = y.coerceIn(0f, screenHeight.toFloat())
        if (clampedX != x || clampedY != y) {
            Log.d(TAG, "Clamped coordinates from ($x, $y) to ($clampedX, $clampedY)")
        }
        return Pair(clampedX, clampedY)
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
                    currentText.clear() // Clear text when focusing new input field
                    currentText.append(source.text?.toString() ?: "")
                    Log.d(TAG, "Focused on editable field with text: ${currentText}")
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Clear text when input field loses focus or content changes
                if (event.source?.isEditable != true) {
                    currentText.clear()
                    lastFocusedNode = null
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

        val (clampedX, clampedY) = clampToScreen(x, y)

        val path = Path().apply {
            moveTo(clampedX, clampedY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Click performed at: ($clampedX, $clampedY) in package: $currentPackage")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d(TAG, "Click cancelled at: ($clampedX, $clampedY)")
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

        val (clampedStartX, clampedStartY) = clampToScreen(startX, startY)
        val (clampedEndX, clampedEndY) = clampToScreen(endX, endY)
        val adjustedDuration = max(durationMs, 100L)

        val path = Path().apply {
            moveTo(clampedStartX, clampedStartY)
            lineTo(clampedEndX, clampedEndY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, adjustedDuration))
            .build()

        var retryCount = 0
        val maxRetries = 3

        fun attemptGesture() {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Swipe completed from ($clampedStartX, $clampedStartY) to ($clampedEndX, $clampedEndY)")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.d(TAG, "Swipe cancelled from ($clampedStartX, $clampedStartY) to ($clampedEndX, $clampedEndY)")
                    if (retryCount < maxRetries) {
                        retryCount++
                        Log.d(TAG, "Retrying swipe attempt $retryCount of $maxRetries")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            attemptGesture()
                        }, 100)
                    }
                }
            }, null)
        }

        attemptGesture()
    }

    fun inputCharacter(char: String) {
        val inputNode = findFirstEditableNode(rootInActiveWindow)
        if (inputNode == null) {
            Log.w(TAG, "No editable field found")
            return
        }

        // Ako je novi input field, resetuj currentText
        if (lastFocusedNode == null || lastFocusedNode!!.hashCode() != inputNode.hashCode()) {
            currentText.clear()
            lastFocusedNode = inputNode
        }

        when (char) {
            "Backspace" -> {
                if (currentText.isNotEmpty()) {
                    currentText.deleteAt(currentText.length - 1)
                }
            }
            "Enter" -> {
                performEnter(inputNode)
                return
            }
            else -> {
                if (char.length == 1) {
                    currentText.append(char)
                }
            }
        }

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                currentText.toString()
            )
        }
        inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, currentText.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentText.length)
        })

        Log.d(TAG, "Updated field with: '${currentText}', package: $currentPackage")
    }
    
    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        if (node.isEditable) {
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

    private fun performEnter(node: AccessibilityNodeInfo) {
        if (node.isMultiLine) {
            currentText.append("\n")
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    currentText.toString()
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } else {
            var actionPerformed = false

            val imeActionId = node.extras.getInt(
                "android.view.accessibility.AccessibilityNodeInfo.imeActionId",
                0
            )
            if (imeActionId != 0) {
                if (node.performAction(imeActionId)) {
                    actionPerformed = true
                }
            }

            if (!actionPerformed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val actionImeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
                if (node.actionList.contains(actionImeEnter)) {
                    node.performAction(actionImeEnter.id)
                }
            }

            currentText.clear()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceEnabled = false
        instance = null
        currentText.clear()
        lastFocusedNode = null
        return super.onUnbind(intent)
    }
}