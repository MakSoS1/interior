package com.maksos.interiorar

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** An AR placement is deliberately NOT persisted: ARCore world poses belong to a session. */
data class GroupTransform(
    val x: Float = 0f, val y: Float = 0f, val z: Float = 0f,
    val yaw: Float = 0f, val scale: Float = 1f
) {
    init {
        require(listOf(x,y,z,yaw,scale).all { it.isFinite() }) { "Некорректное преобразование" }
        require(scale in .001f..1000f) { "Недопустимый масштаб" }
        require(listOf(x,y,z).all { kotlin.math.abs(it) <= 100f }) { "Смещение вне диапазона" }
    }
    fun json() = JSONObject().put("position", JSONArray(listOf(x,y,z)))
        .put("yawDegrees",yaw.toDouble()).put("scale",scale.toDouble())
    companion object {
        fun parse(j: JSONObject?): GroupTransform {
            if (j == null) return GroupTransform()
            val p=j.optJSONArray("position") ?: JSONArray(listOf(0,0,0))
            require(p.length()==3) { "position должна содержать 3 числа" }
            return GroupTransform(p.getDouble(0).toFloat(),p.getDouble(1).toFloat(),p.getDouble(2).toFloat(),
                j.optDouble("yawDegrees",0.0).toFloat(),j.optDouble("scale",1.0).toFloat())
        }
    }
}

data class LightingSettings(val natural: Boolean = true, val exposureStops: Float = 0f, val lock: Boolean = false) {
    init { require(exposureStops.isFinite() && exposureStops in -2f..1f) }
    fun json()=JSONObject().put("natural",natural).put("exposureStops",exposureStops.toDouble()).put("lock",lock)
    companion object {
        fun parse(j:JSONObject?)=if(j==null) LightingSettings() else LightingSettings(
            j.optBoolean("natural",true),j.optDouble("exposureStops",0.0).toFloat(),j.optBoolean("lock",false))
    }
}

data class ArGroup(
    val id: String, val label: String, val file: String,
    val dimensions: ModelDimensions, val pivot: Point3 = Point3(0f,0f,0f),
    val metresPerUnit: Float = 1f, val transform: GroupTransform = GroupTransform()
) {
    init {
        require(id.isNotBlank() && id.length<=100 && label.isNotBlank() && label.length<=160)
        require(metresPerUnit.isFinite() && metresPerUnit in .000001f..1000f)
        require(listOf(pivot.x,pivot.y,pivot.z).all { it.isFinite() })
    }
    fun json()=JSONObject().put("id",id).put("label",label).put("file",file)
        .put("dimensions",JSONArray(listOf(dimensions.width,dimensions.height,dimensions.depth)))
        .put("pivot",JSONArray(listOf(pivot.x,pivot.y,pivot.z)))
        .put("metresPerUnit",metresPerUnit.toDouble()).put("transform",transform.json())
    fun physicalDimensions(): ModelDimensions {
        val s=metresPerUnit*transform.scale
        return ModelDimensions(dimensions.width*s,dimensions.height*s,dimensions.depth*s)
    }
    companion object {
        fun parse(j:JSONObject):ArGroup {
            val d=j.getJSONArray("dimensions"); val p=j.getJSONArray("pivot")
            require(d.length()==3 && p.length()==3)
            return ArGroup(j.getString("id"),j.getString("label"),j.getString("file"),
                ModelDimensions(d.getDouble(0).toFloat(),d.getDouble(1).toFloat(),d.getDouble(2).toFloat()),
                Point3(p.getDouble(0).toFloat(),p.getDouble(1).toFloat(),p.getDouble(2).toFloat()),
                j.optDouble("metresPerUnit",1.0).toFloat(),GroupTransform.parse(j.optJSONObject("transform")))
        }
    }
}

data class ArDocument(
    val id: String = UUID.randomUUID().toString(), val title: String,
    val groups: List<ArGroup>, val lighting: LightingSettings = LightingSettings()
) {
    init {
        require(title.isNotBlank() && title.length<=160)
        require(groups.size in 1..32) { "В проекте допустимо от 1 до 32 групп" }
        require(groups.map { it.id }.distinct().size==groups.size) { "Повторяющиеся ID групп" }
    }
    fun json()=JSONObject().put("version",1).put("id",id).put("title",title)
        .put("groups",JSONArray(groups.map { it.json() })).put("lighting",lighting.json())
    companion object {
        fun parse(j:JSONObject):ArDocument {
            require(j.getInt("version")==1) { "Неизвестная версия проекта" }
            val groups=j.getJSONArray("groups")
            return ArDocument(j.getString("id"),j.getString("title"),
                (0 until groups.length()).map { ArGroup.parse(groups.getJSONObject(it)) },
                LightingSettings.parse(j.optJSONObject("lighting")))
        }
    }
}
