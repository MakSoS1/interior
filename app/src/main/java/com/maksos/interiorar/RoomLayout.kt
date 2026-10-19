package com.maksos.interiorar

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/** These poses are room-local, never reusable ARCore session-world coordinates. */
data class RoomItem(val id:String=UUID.randomUUID().toString(),val assetId:String,
    val x:Float=0f,val y:Float=0f,val z:Float=0f,val yaw:Float=0f,
    val scale:Float=1f,val placed:Boolean=false) {
    fun checked():RoomItem {
        require(listOf(x,y,z,yaw,scale).all {it.isFinite()})
        require(kotlin.math.abs(x)<=100 && kotlin.math.abs(y)<=100 && kotlin.math.abs(z)<=100)
        require(scale in .01f..100f)
        return copy(yaw=PlacementMath.normalizeDegrees(yaw))
    }
}
data class RoomLayout(val items:List<RoomItem>,val lighting:LightSettings=LightSettings()) {
    fun duplicate(id:String):RoomLayout {
        require(items.size<24) {"В комнате допускается не больше 24 групп"}
        val source=items.first {it.id==id}
        return copy(items=items+source.copy(id=UUID.randomUUID().toString(),placed=false))
    }
    fun replace(item:RoomItem)=copy(items=items.map {if(it.id==item.id) item.checked() else it})
    fun add(assetId:String):RoomLayout {
        require(items.size<24) {"Не больше 24 групп в комнате"}
        return copy(items=items+RoomItem(assetId=assetId))
    }
}
object RoomMath {
    fun relative(origin:Point3,yaw:Float,point:Point3):Point3 {
        val dx=point.x-origin.x;val dz=point.z-origin.z
        return Point3(cos(yaw)*dx-sin(yaw)*dz,point.y-origin.y,sin(yaw)*dx+cos(yaw)*dz)
    }
    fun world(origin:Point3,yaw:Float,point:Point3):Point3=Point3(
        origin.x+cos(yaw)*point.x+sin(yaw)*point.z,origin.y+point.y,
        origin.z-sin(yaw)*point.x+cos(yaw)*point.z)
}
class RoomLayoutStore(context:Context) {
    private val root=File(context.filesDir,"room_layouts").apply {mkdirs()}
    private fun file(id:String):AtomicFile {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        return AtomicFile(File(root,"$id.json"))
    }
    fun load(id:String,seeds:List<KitchenProject>):RoomLayout {
        val source=file(id)
        if(!source.baseFile.exists()) return RoomLayout(seeds.take(24).map {RoomItem(assetId=it.id)})
        val text=source.openRead().use {String(it.readAtMost(1024*1024),Charsets.UTF_8)}
        val json=JSONObject(text);require(json.getInt("version")==1) {"Неизвестная версия расстановки"}
        val items=json.getJSONArray("items");require(items.length()<=24)
        val ids=HashSet<String>()
        return RoomLayout((0 until items.length()).map {i ->
            val o=items.getJSONObject(i);require(ids.add(o.getString("id")))
            RoomItem(o.getString("id"),o.getString("assetId"),o.getDouble("x").toFloat(),
                o.getDouble("y").toFloat(),o.getDouble("z").toFloat(),o.getDouble("yaw").toFloat(),
                o.getDouble("scale").toFloat(),o.getBoolean("placed")).checked()
        },LightSettings(json.optBoolean("natural",true),json.optDouble("exposureEv",-.55).toFloat().let {
            require(it.isFinite());it.coerceIn(-2f,1f)
        }))
    }
    @Synchronized
    fun save(id:String,layout:RoomLayout) {
        require(layout.lighting.exposureEv.isFinite() && layout.lighting.exposureEv in -2f..1f)
        require(layout.items.size<=24 && layout.items.map {it.id}.distinct().size==layout.items.size)
        val json=JSONObject().put("version",1).put("coordinateSpace","room-local-requires-realignment")
            .put("natural",layout.lighting.natural).put("exposureEv",layout.lighting.exposureEv.toDouble())
        val items=JSONArray()
        layout.items.forEach {it.checked();items.put(JSONObject().put("id",it.id).put("assetId",it.assetId)
            .put("x",it.x.toDouble()).put("y",it.y.toDouble()).put("z",it.z.toDouble())
            .put("yaw",it.yaw.toDouble()).put("scale",it.scale.toDouble()).put("placed",it.placed))}
        json.put("items",items)
        val target=file(id);val stream=target.startWrite()
        try {stream.write(json.toString().toByteArray(Charsets.UTF_8));target.finishWrite(stream)}
        catch(e:Exception) {target.failWrite(stream);throw e}
    }
}
