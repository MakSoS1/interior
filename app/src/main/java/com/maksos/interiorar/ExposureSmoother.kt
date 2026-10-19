package com.maksos.interiorar

import kotlin.math.exp
import kotlin.math.ln

/** No multiplication by previous-frame gain: output converges to a target, not to black. */
class ExposureSmoother(initial: Float = .82f, private val tauSeconds: Float = .8f) {
    var value: Float = initial.coerceIn(.05f,4f)
        private set
    fun update(target: Float, seconds: Float): Float {
        if (!target.isFinite() || target<=0f || !seconds.isFinite() || seconds<=0f) return value
        val dt=seconds.coerceAtMost(.25f)
        val delta=(ln(target.coerceIn(.05f,4f).toDouble())-ln(value.toDouble())).toFloat()
        val proposed=delta*(1f-exp(-dt/tauSeconds))
        val bounded=proposed.coerceIn(-.8f*dt,.8f*dt)
        value=(value*exp(bounded)).coerceIn(.05f,4f)
        return value
    }
}

/** Robust camera-luma estimate: ignore extreme windows and deep black borders. */
object LumaMatching {
    fun gain(samples: FloatArray): Float {
        val valid=samples.filter { it.isFinite() && it in .04f.. .88f }.sorted()
        if(valid.size<16) return 1f
        val median=valid[valid.size/2]
        return kotlin.math.pow((median/.45f).toDouble(),.18).toFloat().coerceIn(.72f,1.03f)
    }
}
