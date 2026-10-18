package com.maksos.interiorar

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class PlacementTarget { WALL, ISLAND, FRIDGE }

/** A=back-left floor corner, B=direction of +X along an edge,
 * C=positive +Z side in the room. Coordinates and distances are in metres.
 */
data class FloorCalibration(
    val origin: Point3,
    val yawRadians: Float,
    val baselineMetres: Float,
    val forwardMetres: Float
) {
    fun xAxis() = Point3(cos(yawRadians), 0f, -sin(yawRadians))
    fun zAxis() = Point3(sin(yawRadians), 0f, cos(yawRadians))
}

sealed interface CalibrationUpdate {
    data class NeedNext(val pointsCollected: Int, val instruction: String) : CalibrationUpdate
    data class Invalid(val reason: String) : CalibrationUpdate
    data class Ready(val calibration: FloorCalibration) : CalibrationUpdate
}

class FloorCalibrationMachine(private val target: PlacementTarget) {
    private val observations = ArrayList<Point3>(3)
    val count get() = observations.size
    fun reset() = observations.clear()
    fun getPoints(): List<Point3> = observations.toList()

    fun add(point: Point3): CalibrationUpdate {
        if (!point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite()) {
            return CalibrationUpdate.Invalid("ARCore вернул некорректную точку пола")
        }
        if (observations.any { abs(it.y - point.y) > .10f }) {
            return CalibrationUpdate.Invalid("Точки лежат на разных высотах. Выберите один и тот же пол")
        }
        if (observations.isEmpty()) {
            observations.add(point)
            return CalibrationUpdate.NeedNext(1,
                "Точка A. Наведите прицел вдоль края мебели и отметьте B (вправо)")
        }
        val a = observations[0]
        if (observations.size == 1) {
            val minimum = if (target == PlacementTarget.FRIDGE) .30f else .50f
            val d = PlacementMath.wallFromPoints(a, point, minimum)
                ?: return CalibrationUpdate.Invalid(
                    "B должна находиться достаточно далеко от A и указывать вправо вдоль мебели"
                )
            observations.add(point)
            return CalibrationUpdate.NeedNext(2,
                "B зафиксирована: " + "%.2f".format(d.lengthMeters) +
                " м от A. Отметьте C в комнате перед мебелью")
        }
        val b = observations[1]
        val direction = PlacementMath.wallFromPoints(a, b, .30f)
            ?: return CalibrationUpdate.Invalid("Направление A→B потеряно")
        val dx = point.x - a.x; val dz = point.z - a.z
        val signedForward = dx * sin(direction.yawRadians) + dz * cos(direction.yawRadians)
        if (signedForward < .25f) {
            return CalibrationUpdate.Invalid(
                "C должна быть перед мебелью минимум на 25 см. Если C за стеной — переставьте A и B местами"
            )
        }
        val lateral = dx * cos(direction.yawRadians) - dz * sin(direction.yawRadians)
        if (abs(lateral) > 5.0f) {
            return CalibrationUpdate.Invalid("Точка C слишком далеко от A")
        }
        val result = FloorCalibration(a, direction.yawRadians, direction.lengthMeters, signedForward)
        reset()
        return CalibrationUpdate.Ready(result)
    }
}

data class ModuleAdjustment(
    val xMeters: Float = 0f,
    val zMeters: Float = 0f,
    val yawDegrees: Float = 0f
) {
    fun adjusted(dx: Float = 0f, dz: Float = 0f, dyaw: Float = 0f): ModuleAdjustment =
        copy(xMeters = xMeters + dx, zMeters = zMeters + dz,
             yawDegrees = PlacementMath.normalizeDegrees(yawDegrees + dyaw))
}
