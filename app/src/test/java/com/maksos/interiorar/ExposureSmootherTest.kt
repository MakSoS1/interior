package com.maksos.interiorar

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

class ExposureSmootherTest {
    @Test fun singleFrameSpikeCannotCauseAFlash() {
        val f = ExposureSmoother()
        val before = f.value
        val after = f.update(4f, 1f/30f)
        assertTrue("one frame must move less than 0.05", abs(after-before)<.05f)
    }
    @Test fun invalidSampleLeavesPreviousValueIntact() {
        val f = ExposureSmoother()
        val before=f.value
        assertEquals(before,f.update(Float.NaN,.1f),0f)
        assertEquals(before,f.update(-1f,.1f),0f)
        assertEquals(before,f.update(1f,Float.POSITIVE_INFINITY),0f)
    }
    @Test fun constantInputDoesNotExponentiallyDarkenTheScene() {
        val f=ExposureSmoother()
        repeat(1000) { f.update(.62f,.033f) }
        assertEquals(.62f,f.value,.005f)
    }
    @Test fun sustainedChangeEventuallyConverges() {
        val f=ExposureSmoother()
        repeat(250) { f.update(.4f,.04f) }
        assertEquals(.4f,f.value,.005f)
    }
    @Test fun reverseClockDoesNotChangeOutput() {
        val f=ExposureSmoother()
        assertEquals(f.value,f.update(1.5f,-.5f),0f)
    }
}
