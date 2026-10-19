package com.maksos.interiorar

import kotlin.math.cos
import kotlin.math.sin

/** Position of the glTF root relative to a floor anchor, after pivot/units correction. */
data class PlacedRoot(val position:Point3,val scale:Float,val yaw:Float)
object GroupPlacement {
    fun root(group:ArGroup):PlacedRoot {
        val t=group.transform;val s=group.metresPerUnit*t.scale
        val angle=Math.toRadians(t.yaw.toDouble());val c=cos(angle).toFloat();val sn=sin(angle).toFloat()
        val p=group.pivot
        return PlacedRoot(Point3(t.x-s*(c*p.x+sn*p.z),t.y-s*p.y,t.z-s*(-sn*p.x+c*p.z)),s,t.yaw)
    }
}
