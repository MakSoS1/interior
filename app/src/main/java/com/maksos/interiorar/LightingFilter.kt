package com.maksos.interiorar

/** Baseline before temporal adaptation (test-first). */
class LightingFilter {
    fun update(target: Float, timestampNanos: Long): Float = target
    fun reset() = Unit
}
