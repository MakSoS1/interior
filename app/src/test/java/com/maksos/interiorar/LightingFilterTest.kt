package com.maksos.interiorar

import org.junit.Assert.*
import org.junit.Test

class LightingFilterTest {
    @Test fun aSingleBrightFrameCannotCauseAFlash() {
        val f=LightingFilter()
        repeat(30) { f.update(.5f,(it+1)*33_333_333L) }
        assertTrue(f.update(3f,1_050_000_000L) < .8f)
    }
    @Test fun nanAndNegativeValuesCannotPoisonTheRenderer() {
        val f=LightingFilter()
        f.update(.6f,1L)
        assertEquals(.6f,f.update(Float.NaN,30_000_000L),.01f)
        assertEquals(.6f,f.update(-1f,60_000_000L),.01f)
    }
    @Test fun steadyLightDoesNotCompoundToBlack() {
        val f=LightingFilter()
        var v=0f
        repeat(500) { v=f.update(.8f,1L+it*33_333_333L) }
        assertEquals(.8f,v,.002f)
    }
    @Test fun sustainedLightChangeConverges() {
        val f=LightingFilter(); f.update(.5f,1L)
        var v=0f
        repeat(300) { v=f.update(1.2f,(it+1)*33_333_333L) }
        assertEquals(1.2f,v,.03f)
    }
    @Test fun smoothingIsNotTiedToFrameRate() {
        fun sample(fps:Int):Float {
            val f=LightingFilter(); f.update(.5f,1L)
            var v=0f
            repeat(fps) { v=f.update(1.5f,(it+1)*1_000_000_000L/fps) }
            return v
        }
        assertEquals(sample(30),sample(60),.035f)
        assertTrue(sample(30) < 1.49f)
    }
}
