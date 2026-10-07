package com.thereprocase.thermalfield

import org.junit.Assert.*
import org.junit.Test

class LabelPlacementTest {
    @Test fun denseAnchorsUseAvailableColumnsWithoutOverlap() {
        val bounds = LabelRect(10f,20f,510f,420f)
        val occupied = mutableListOf<LabelRect>()
        repeat(16) {
            val rectangle = placeLabel(bounds,100f,30f,250f,200f,occupied,4f)
            assertNotNull(rectangle)
            rectangle!!
            assertTrue(rectangle.left >= bounds.left && rectangle.top >= bounds.top && rectangle.right <= bounds.right && rectangle.bottom <= bounds.bottom)
            assertFalse(occupied.any { it.intersects(rectangle) })
            assertFalse(rectangle.intersects(LabelRect(246f,196f,254f,204f)))
            occupied += rectangle
        }
    }

    @Test fun edgesRemainInsideImageAndInsufficientSpaceIsExplicit() {
        val bounds = LabelRect(0f,0f,100f,40f)
        val rectangle = placeLabel(bounds,100f,40f,-20f,200f,emptyList(),4f)!!
        assertEquals(bounds,rectangle)
        assertNull(placeLabel(bounds,100f,40f,0f,0f,listOf(rectangle),4f))
        assertNull(placeLabel(bounds,110f,40f,0f,0f,emptyList(),4f))
        assertNull(placeLabel(LabelRect(0f,0f,100f,-5f),20f,20f,0f,0f,emptyList(),4f))
    }
}
