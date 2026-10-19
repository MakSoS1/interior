package com.maksos.interiorar

/** Bounded scene-light multiplier, independent of camera projection. */
class ExposureSmoother {
    var value: Float = .82f
        private set
    fun update(target: Float, seconds: Float): Float {
        value = target
        return value
    }
}
