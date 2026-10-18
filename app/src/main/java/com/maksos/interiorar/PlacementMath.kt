package com.maksos.interiorar

import kotlin.math.*

data class Point3(val x: Float, val y: Float, val z: Float)
data class WallDirection(val yawRadians: Float, val lengthMeters: Float)

object PlacementMath {
    /** +X of the Blender scene is aligned with A→B, projected onto floor. */
    fun wallFromPoints(a: Point3, b: Point3, minimumBaselineMetres: Float = .5f): WallDirection? {
        val dx=b.x-a.x; val dz=b.z-a.z
        val length=hypot(dx.toDouble(),dz.toDouble()).toFloat()
        if (!length.isFinite() || length < minimumBaselineMetres || !a.y.isFinite() || !b.y.isFinite()) return null
        val yaw=atan2(-dz,dx)
        if (!yaw.isFinite()) return null
        return WallDirection(yaw,length)
    }
    fun normalizeDegrees(degrees: Float): Float {
        if (!degrees.isFinite()) return 0f
        return ((degrees + 180f) % 360f + 360f) % 360f - 180f
    }
}
