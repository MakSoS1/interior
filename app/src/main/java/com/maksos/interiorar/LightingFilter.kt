package com.maksos.interiorar

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** Radiance smoothing in log space, with a bounded slew rate in stops/second. */
class LightingFilter(private val tau:Double=1.0,private val stopsPerSecond:Double=1.25) {
    private var value:Double?=null
    private var last=0L
    fun update(target:Float,timestampNanos:Long):Float {
        val previous=value
        if(!target.isFinite() || target<0f) return (previous ?: 1.0).toFloat()
        val safe=target.toDouble().coerceIn(.005,20.0)
        if(previous==null) {value=safe;last=timestampNanos;return safe.toFloat()}
        if(timestampNanos<=last) return previous.toFloat()
        val dt=((timestampNanos-last)/1e9).coerceAtMost(.25)
        last=timestampNanos
        val delta=(ln(safe)-ln(previous))*(1-exp(-dt/tau))
        val bound=stopsPerSecond*ln(2.0)*dt
        value=exp(ln(previous)+delta.coerceIn(-bound,bound))
        return value!!.toFloat()
    }
    fun reset(){value=null;last=0L}
}

data class LightSettings(val natural:Boolean=true,val exposureEv:Float=-.55f) {
    fun gain():Float=2.0.pow(exposureEv.coerceIn(-2f,1f).toDouble()).toFloat()
}

/** Clipped windows and dark pixels do not create fake screen-space shading. */
object CameraBrightness {
    fun gain(samples:IntArray):Float {
        val useful=samples.filter {it in 24..229}.sorted()
        if(useful.size<12) return 1f
        val median=useful[useful.size/2]/255f
        return (.88f+median*.22f).coerceIn(.90f,1.02f)
    }
}
