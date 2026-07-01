package com.voicebot.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
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
    fun openQuickSettings() = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    fun openPowerDialog() = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)

    fun lockScreen(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false

    fun takeScreenshot(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) else false

    /** Open the app drawer with a swipe-up from the bottom (best effort). */
    fun openAllApps(): Boolean {
        val m = resources.displayMetrics
        val path = Path().apply {
            moveTo(m.widthPixels / 2f, m.heightPixels * 0.9f)
            lineTo(m.widthPixels / 2f, m.heightPixels * 0.25f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 200)
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(), null, null
        )
    }

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
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val path = Path()
        when (direction) {
            // Swipe direction is opposite to content movement: to scroll DOWN
            // (see lower content) we swipe up.
            Direction.DOWN -> { path.moveTo(cx, h * 0.75f); path.lineTo(cx, h * 0.25f) }
            Direction.UP -> { path.moveTo(cx, h * 0.25f); path.lineTo(cx, h * 0.75f) }
            Direction.RIGHT -> { path.moveTo(w * 0.75f, cy); path.lineTo(w * 0.25f, cy) }
            Direction.LEFT -> { path.moveTo(w * 0.25f, cy); path.lineTo(w * 0.75f, cy) }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 250)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(), null, null
        )
    }

    /** Tap in the middle of the screen (a simple "click" with no target). */
    fun tapCenter(): Boolean = tapAt(0.5f, 0.5f)

    /** Tap at a point given as fractions (0..1) of screen width/height. */
    fun tapAt(fx: Float, fy: Float): Boolean {
        val m = resources.displayMetrics
        val x = (m.widthPixels * fx).coerceIn(1f, m.widthPixels - 1f)
        val y = (m.heightPixels * fy).coerceIn(1f, m.heightPixels - 1f)
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(), null, null
        )
    }

    /** Tap one of the four screen corners (slightly inset). */
    fun tapCorner(corner: Corner): Boolean = when (corner) {
        Corner.TOP_LEFT -> tapAt(0.12f, 0.12f)
        Corner.TOP_RIGHT -> tapAt(0.88f, 0.12f)
        Corner.BOTTOM_LEFT -> tapAt(0.12f, 0.88f)
        Corner.BOTTOM_RIGHT -> tapAt(0.88f, 0.88f)
    }

    /**
     * Tap the N-th row (1-based) of the main scrollable list on screen — used to
     * open "the 3rd chat" etc. Rows are ordered top-to-bottom.
     */
    fun tapListItem(index: Int): Boolean {
        if (index < 1) return false
        val root = rootInActiveWindow ?: return false
        val list = findScrollable(root) ?: root
        val rows = ArrayList<AccessibilityNodeInfo>()
        collectRows(list, rows)
        val sorted = rows
            .map { node -> node to Rect().also { node.getBoundsInScreen(it) }.top }
            .sortedBy { it.second }
            .map { it.first }
        val target = sorted.getOrNull(index - 1) ?: return false
        var n: AccessibilityNodeInfo? = target
        while (n != null) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        return false
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findScrollable(child)?.let { return it }
        }
        return null
    }

    /** Collect top-level clickable rows (don't descend into a clickable one). */
    private fun collectRows(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable) {
            out.add(node)
            return
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectRows(child, out)
        }
    }

    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /** Tap the first on-screen element whose visible text contains [label]. */
    fun tapByText(label: String): Boolean = actByText(label, AccessibilityNodeInfo.ACTION_CLICK)

    fun longPressByText(label: String): Boolean =
        actByText(label, AccessibilityNodeInfo.ACTION_LONG_CLICK)

    /** Double-tap by text (two quick clicks on the matching element). */
    fun doubleTapByText(label: String): Boolean {
        if (!tapByText(label)) return false
        return tapByText(label)
    }

    private fun actByText(label: String, action: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val query = label.trim().lowercase()
        // Prefer exact-ish matches from the text index, then fall back to a manual
        // case-insensitive walk (some nodes expose contentDescription, not text).
        val candidates = root.findAccessibilityNodeInfosByText(label).toMutableList()
        if (candidates.isEmpty()) collectByText(root, query, candidates)
        for (node in candidates) {
            var n: AccessibilityNodeInfo? = node
            while (n != null) {
                if (n.isClickable && n.performAction(action)) return true
                n = n.parent
            }
        }
        return false
    }

    private fun collectByText(
        node: AccessibilityNodeInfo, query: String, out: MutableList<AccessibilityNodeInfo>
    ) {
        val text = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
        if (text.lowercase().contains(query)) out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectByText(child, query, out)
        }
    }

    /**
     * Try to tap [label]; if it's not on screen, scroll down and retry a few
     * times, then scroll back up and retry. For "нажми на Пользователи/Тикеты".
     */
    fun tapByTextScrolling(label: String, maxScrolls: Int = 6): Boolean {
        if (tapByText(label)) return true
        repeat(maxScrolls) {
            scroll(Direction.DOWN)
            Thread.sleep(400)
            if (tapByText(label)) return true
        }
        repeat(maxScrolls * 2) { scroll(Direction.UP); Thread.sleep(120) }
        Thread.sleep(300)
        repeat(maxScrolls) {
            if (tapByText(label)) return true
            scroll(Direction.UP)
            Thread.sleep(400)
        }
        return false
    }

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    companion object {
        private const val TAG = "BotA11y"

        @Volatile
        var instance: BotAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
