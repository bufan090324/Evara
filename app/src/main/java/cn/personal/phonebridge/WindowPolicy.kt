package cn.personal.phonebridge

// Pure geometry and metadata policy, also exercised without an Android device.
data class WindowBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun intersects(other: WindowBox) = left < other.right && right > other.left && top < other.bottom && bottom > other.top
}
data class WindowMeta(val type: Int, val title: String, val pkg: String?, val active: Boolean, val focused: Boolean, val bounds: WindowBox)
object WindowPolicy {
    const val APPLICATION = 1
    const val INPUT_METHOD = 2
    const val SYSTEM = 3
    const val ACCESSIBILITY_OVERLAY = 4
    // Window TYPE_SYSTEM is privileged. Null roots occur for non-interactive bars;
    // Require a known identity AND thin edge geometry, never geometry alone.
    fun passiveBar(w: WindowMeta, screen: WindowBox): Boolean {
        if (w.type != SYSTEM || w.active || w.focused || screen.width <= 0 || screen.height <= 0) return false
        val b = w.bounds
        if (b.width <= 0 || b.height <= 0 || b.left < screen.left || b.top < screen.top || b.right > screen.right || b.bottom > screen.bottom) return false
        // Redmi Note 10 Pro / MIUI 14.0.8 feedback: an untitled, passive
        // TYPE_SYSTEM window owned by com.miui.home at [0,2332,1080,2400].
        // Recognize only the full-width portrait bottom strip (<=3% height).
        // It is excluded from screenshots and coordinate gestures below.
        if (w.pkg == "com.miui.home") return w.title.isEmpty() && screen.height > screen.width &&
            b.left == screen.left && b.right == screen.right && b.bottom == screen.bottom &&
            b.height.toLong() * 100 <= screen.height.toLong() * 3
        if (w.pkg != null && w.pkg != "com.android.systemui") return false
        // MIUI can omit SystemUI bar titles (observed top strip: 96/2400px).
        // Null-root windows still need known titles. For an untitled window,
        // insist on SystemUI ownership, a full edge and the stricter 5% limit.
        if (w.pkg == "com.android.systemui" && w.title.isEmpty()) {
            val edgeHorizontal = b.left == screen.left && b.right == screen.right &&
                b.height.toLong() * 100 <= screen.height.toLong() * 5 && (b.top == screen.top || b.bottom == screen.bottom)
            val edgeVertical = b.top == screen.top && b.bottom == screen.bottom &&
                b.width.toLong() * 100 <= screen.width.toLong() * 5 && (b.left == screen.left || b.right == screen.right)
            return edgeHorizontal || edgeVertical
        }
        val status = w.title in setOf("StatusBar", "Status bar", "状态栏")
        val navigation = w.title in setOf("NavigationBar", "NavigationBar0", "Navigation bar", "导航栏")
        val horizontal = b.left == screen.left && b.right == screen.right && b.height.toLong() * 100 <= screen.height.toLong() * 12
        if (status) return horizontal && b.top == screen.top
        if (!navigation) return false
        return (horizontal && b.bottom == screen.bottom) ||
            (b.top == screen.top && b.bottom == screen.bottom && b.width.toLong() * 100 <= screen.width.toLong() * 12 && (b.left == screen.left || b.right == screen.right))
    }
    fun blocks(w: WindowMeta, area: WindowBox, screen: WindowBox): Boolean =
        w.type == INPUT_METHOD || w.type == ACCESSIBILITY_OVERLAY ||
            (w.type == SYSTEM && !passiveBar(w, screen) && (w.active || w.focused || w.bounds.intersects(area)))

    fun contentArea(area: WindowBox, windows: List<WindowMeta>, screen: WindowBox): WindowBox {
        var a = area
        windows.filter { passiveBar(it, screen) }.forEach { w ->
            val b = w.bounds
            if (b.left == screen.left && b.right == screen.right) {
                a = if (b.top == screen.top) a.copy(top = maxOf(a.top, b.bottom)) else a.copy(bottom = minOf(a.bottom, b.top))
            } else {
                a = if (b.left == screen.left) a.copy(left = maxOf(a.left, b.right)) else a.copy(right = minOf(a.right, b.left))
            }
        }
        return a
    }
}
