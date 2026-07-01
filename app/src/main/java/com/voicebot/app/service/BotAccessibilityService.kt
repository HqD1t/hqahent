package com.voicebot.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The hands of the bot. Everything that touches the screen — tapping, scrolling,
 * navigating, and typing text into the focused field — goes through here.
 *
 * Held as a singleton so [VoiceService] / CommandRouter can reach it without binding.
 */
class BotAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // We don't react to UI events; we only act on demand.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---- Global navigation --------------------------------------------------

    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // ---- Typing -------------------------------------------------------------

    /**
     * Insert [text] into the currently focused editable field.
     * Appends to whatever is already there so dictation feels natural.
     * @return true if a field was found and updated.
     */
    fun typeIntoFocusedField(text: String): Boolean {
        val node = findFocusedEditable() ?: return false
        val existing = node.text?.toString() ?: ""
        val combined = if (existing.isBlank()) text else "$existing $text"
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                combined
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Find the first editable field on screen and focus it (placing the cursor).
     * Used by the "текст" command to prepare for dictation.
     */
    fun focusEditable(): Boolean {
        val node = rootInActiveWindow?.let { findEditable(it) } ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Current contents of the focused editable field, or null if none. */
    fun getFocusedText(): String? = findFocusedEditable()?.text?.toString()

    /** Replace the focused field's contents entirely with [text]. */
    fun setFocusedText(text: String): Boolean {
        val node = findFocusedEditable() ?: return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Clear the focused field. */
    fun clearFocusedField(): Boolean = setFocusedText("")

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.let { if (it.isEditable) return it }
        // Fallback: walk the tree for the first editable node.
        return rootInActiveWindow?.let { findEditable(it) }
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditable(child)?.let { return it }
        }
        return null
    }

    // ---- Scrolling / gestures ----------------------------------------------

    fun scroll(direction: Direction) {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val cx = w / 2f
        val (startY, endY) = when (direction) {
            Direction.DOWN -> h * 0.75f to h * 0.25f
            Direction.UP -> h * 0.25f to h * 0.75f
        }
        val path = Path().apply {
            moveTo(cx, startY)
            lineTo(cx, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 250)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            null
        )
    }

    /** Tap in the middle of the screen (a simple "click" with no target). */
    fun tapCenter(): Boolean {
        val m = resources.displayMetrics
        val path = Path().apply { moveTo(m.widthPixels / 2f, m.heightPixels / 2f) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(), null, null
        )
    }

    /** Tap the first on-screen element whose visible text contains [label]. */
    fun tapByText(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByText(label)
        for (node in matches) {
            var n: AccessibilityNodeInfo? = node
            while (n != null) {
                if (n.isClickable) {
                    return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                n = n.parent
            }
        }
        return false
    }

    enum class Direction { UP, DOWN }

    companion object {
        private const val TAG = "BotA11y"

        @Volatile
        var instance: BotAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
