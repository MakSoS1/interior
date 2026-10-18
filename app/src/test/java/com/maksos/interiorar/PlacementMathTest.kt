package com.maksos.interiorar

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class PlacementMathTest {
    @Test fun horizontalWallKeepsXForward() {
        val p=PlacementMath.wallFromPoints(Point3(0f,0f,0f),Point3(2f,0f,0f))!!
        assertEquals(0f,p.yawRadians,0.00001f)
        assertEquals(2f,p.lengthMeters,0.00001f)
    }
    @Test fun zWallRotatesMinus90() {
        val p=PlacementMath.wallFromPoints(Point3(0f,0f,0f),Point3(0f,0f,1f))!!
        assertEquals((-PI/2).toFloat(),p.yawRadians,0.00001f)
    }
    @Test fun veryClosePointsAreRejected() {
        assertNull(PlacementMath.wallFromPoints(Point3(0f,0f,0f),Point3(.4f,0f,0f)))
    }
    @Test fun invalidNumbersAreRejected() {
        assertNull(PlacementMath.wallFromPoints(Point3(Float.NaN,0f,0f),Point3(1f,0f,0f)))
    }
    @Test fun yawIsNormalized() { assertEquals(-170f,PlacementMath.normalizeDegrees(190f),0f) }
}
