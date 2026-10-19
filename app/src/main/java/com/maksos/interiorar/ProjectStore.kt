package com.maksos.interiorar

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class KitchenProject(val id:String,val name:String,val modelUri:String,val dimensions:ModelDimensions,
    val offsetX:Float=0f,val offsetZ:Float=0f,val rotationDegrees:Float=0f,
    val islandAdjust:ModuleAdjustment=ModuleAdjustment(),val fridgeAdjust:ModuleAdjustment=ModuleAdjustment(),
    val originMode:String="floor",val archived:Boolean=false)

data class ModelDimensions(val width:Float,val height:Float,val depth:Float) {
    fun label()="%.2f × %.2f × %.2f м".format(width,height,depth)
}

class ProjectStore(private val context:Context) {
    private val prefs=context.getSharedPreferences("projects_v1",Context.MODE_PRIVATE)
    private val assets=File(context.filesDir,"model_assets").apply {mkdirs()}
    @Synchronized
    fun all():List<KitchenProject> = catalogIncludingArchived().filterNot {it.archived}
    @Synchronized
    fun catalogIncludingArchived():List<KitchenProject> {
        val array=try {JSONArray(prefs.getString("projects","[]"))} catch(_:Exception){JSONArray()}
        return (0 until array.length()).mapNotNull {i ->
            try {
                val j=array.getJSONObject(i)
                KitchenProject(j.getString("id"),j.getString("name"),j.getString("uri"),
                    ModelDimensions(j.getDouble("w").toFloat(),j.getDouble("h").toFloat(),j.getDouble("d").toFloat()),
                    j.optDouble("x",0.0).toFloat(),j.optDouble("z",0.0).toFloat(),j.optDouble("yaw",0.0).toFloat(),
                    ModuleAdjustment(j.optDouble("ix",0.0).toFloat(),j.optDouble("iz",0.0).toFloat(),j.optDouble("iyaw",0.0).toFloat()),
                    ModuleAdjustment(j.optDouble("fx",0.0).toFloat(),j.optDouble("fz",0.0).toFloat(),j.optDouble("fyaw",0.0).toFloat()),
                    j.optString("origin","floor"),j.optBoolean("archived",false))
            } catch(_:Exception){null}
        }
    }
    @Synchronized
    private fun save(projects:List<KitchenProject>) {
        val array=JSONArray()
        projects.forEach {p -> array.put(JSONObject().apply {
            put("id",p.id);put("name",p.name);put("uri",p.modelUri)
            put("w",p.dimensions.width.toDouble());put("h",p.dimensions.height.toDouble());put("d",p.dimensions.depth.toDouble())
            put("x",p.offsetX.toDouble());put("z",p.offsetZ.toDouble());put("yaw",p.rotationDegrees.toDouble())
            put("origin",p.originMode);put("archived",p.archived)
            put("ix",p.islandAdjust.xMeters.toDouble());put("iz",p.islandAdjust.zMeters.toDouble());put("iyaw",p.islandAdjust.yawDegrees.toDouble())
            put("fx",p.fridgeAdjust.xMeters.toDouble());put("fz",p.fridgeAdjust.zMeters.toDouble());put("fyaw",p.fridgeAdjust.yawDegrees.toDouble())
        })}
        require(prefs.edit().putString("projects",array.toString()).commit()) {"Не удалось сохранить проект"}
    }
    fun import(uri:Uri):KitchenProject=importAll(uri).first()
    @Synchronized
    fun importAll(uri:Uri):List<KitchenProject> {
        val incoming=File.createTempFile("incoming-",".bin",context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use {input -> incoming.outputStream().use {out ->
                val buffer=ByteArray(64*1024);var bytes=0L
                while(true) {
                    val n=input.read(buffer);if(n<0) break
                    bytes+=n;require(bytes<=InteriorPackage.MAX_EXPANDED) {"Файл больше 256 МБ"}
                    out.write(buffer,0,n)
                }
                out.fd.sync()
            }} ?: error("Не удалось открыть файл")
            return importFile(incoming,readName(uri))
        } finally {incoming.delete()}
    }
    /** Validate every group before publishing the batch in one metadata transaction. */
    @Synchronized
    fun importFile(file:File,title:String):List<KitchenProject> {
        val stage=java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(),"import-").toFile()
        val newlyCreated=mutableListOf<File>()
        try {
            val magic=file.inputStream().use {it.readAtMost(4)}
            val packaged=magic.size==4 && magic[0]==0x50.toByte() && magic[1]==0x4b.toByte()
            val input=if(packaged) InteriorPackage.unpack(file,stage) else {
                GlbInspector.inspect(file);listOf(InteriorPackage.Item(title,file,"floor"))
            }
            val projects=input.map {item ->
                val dimensions=GlbInspector.inspect(item.file);val sha=AssetDigest.sha256(item.file)
                val dest=File(assets,"$sha.glb")
                if(!dest.exists()) {
                    val temp=File.createTempFile("copy-",".glb",assets)
                    try {
                        item.file.inputStream().use {source -> temp.outputStream().use {out ->source.copyTo(out,64*1024);out.fd.sync()}}
                        check(temp.renameTo(dest)) {"Не удалось сохранить GLB"};newlyCreated.add(dest)
                    } finally {temp.delete()}
                }
                KitchenProject(UUID.randomUUID().toString(),item.name,Uri.fromFile(dest).toString(),dimensions,originMode=item.origin)
            }
            save(catalogIncludingArchived()+projects)
            return projects
        } catch(e:Exception){newlyCreated.forEach {it.delete()};throw e}
        finally {stage.deleteRecursively()}
    }
    private fun readName(uri:Uri):String {
        var title="Моя кухня"
        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
            if(it.moveToFirst()) title=it.getString(0) ?: title
        }
        return title.take(100)
    }
    fun createSample()=createBundledSample("demo_kitchen.glb","Демонстрационная кухня")
    fun createReferenceSample()=createBundledSample("reference_kitchen.glb","Кухня по референсу · оценочные размеры")
    fun createOppositeWallFridge()=createBundledSample("fridge_opposite_wall.glb","Холодильник · стена напротив панорамных окон")
    fun placementAssets(project:KitchenProject):List<KitchenProject> {
        // One migration for the original example. Rendering itself is entirely generic.
        if(project.modelUri.substringAfterLast('/')=="reference_kitchen.glb") return listOf(
            ensureBundled("reference_wall.glb","Гарнитур у стены"),
            ensureBundled("reference_island.glb","Остров со стульями"),
            ensureBundled("fridge_opposite_wall.glb","Холодильник"))
        return listOf(project)
    }
    @Synchronized
    private fun ensureBundled(file:String,name:String):KitchenProject =
        all().firstOrNull {it.modelUri.substringAfterLast('/')==file} ?: createBundledSample(file,name)
    @Synchronized
    private fun createBundledSample(assetName:String,title:String):KitchenProject {
        val file=File(assets,assetName)
        if(!file.exists()) {
            val temp=File.createTempFile("import-",".glb",assets)
            try {
                context.assets.open(assetName).use {input -> temp.outputStream().use {out ->input.copyTo(out);out.fd.sync()}}
                GlbInspector.inspect(temp)
                check(temp.renameTo(file)) {"Не удалось сохранить модель"}
            } finally {temp.delete()}
        }
        val project=KitchenProject(UUID.randomUUID().toString(),title,Uri.fromFile(file).toString(),GlbInspector.inspect(file))
        save(catalogIncludingArchived()+project);return project
    }
    @Synchronized
    fun update(project:KitchenProject) {save(catalogIncludingArchived().map {if(it.id==project.id)project else it})}
    @Synchronized
    fun delete(project:KitchenProject) {
        // Soft delete: existing rooms retain a resolvable model reference.
        save(catalogIncludingArchived().map {if(it.id==project.id)it.copy(archived=true) else it})
    }
}
