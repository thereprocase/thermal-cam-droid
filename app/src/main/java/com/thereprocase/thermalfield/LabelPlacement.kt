package com.thereprocase.thermalfield

internal data class LabelRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun intersects(other: LabelRect): Boolean = left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

internal fun placeLabel(bounds: LabelRect, width: Float, height: Float, anchorX: Float, anchorY: Float,
                        occupied: List<LabelRect>, gap: Float): LabelRect? {
    if (width <= 0 || height <= 0 || bounds.right-bounds.left < width || bounds.bottom-bounds.top < height) return null
    val xs = mutableListOf((anchorX+gap).coerceIn(bounds.left,bounds.right-width),
        (anchorX-width-gap).coerceIn(bounds.left,bounds.right-width))
    val ys = mutableListOf((anchorY-height-gap).coerceIn(bounds.top,bounds.bottom-height),
        (anchorY+gap).coerceIn(bounds.top,bounds.bottom-height))
    var x = bounds.left
    while (x <= bounds.right-width) { xs += x; x += width+gap }
    var y = bounds.top
    while (y <= bounds.bottom-height) { ys += y; y += height+gap }
    var nearest: LabelRect? = null
    var distance = Float.POSITIVE_INFINITY
    for (left in xs) for (top in ys) {
        val candidate = LabelRect(left,top,left+width,top+height)
        val anchor = LabelRect(anchorX-gap,anchorY-gap,anchorX+gap,anchorY+gap)
        if (candidate.intersects(anchor)) continue
        val clearance = LabelRect(left-gap,top-gap,left+width+gap,top+height+gap)
        if (occupied.any { clearance.intersects(it) }) continue
        val dx = anchorX.coerceIn(candidate.left,candidate.right)-anchorX
        val dy = anchorY.coerceIn(candidate.top,candidate.bottom)-anchorY
        val score = dx*dx+dy*dy
        if (score < distance) { nearest = candidate; distance = score }
    }
    return nearest
}
