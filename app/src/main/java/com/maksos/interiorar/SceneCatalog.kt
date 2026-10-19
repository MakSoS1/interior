package com.maksos.interiorar

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** UI dispatches all storage work to IO; legacy files remain untouched during migration. */
class SceneCatalog(private val context:Context) {
    private val database=AtomicFile(File(context.filesDir,"ar_documents_v3.json"))
    private val assets=File(context.filesDir,"ar_assets_v3").apply { mkdirs() }
    @Synchronized fun list():List<ArDocument> {
        if(!database.baseFile.exists()) {
            val legacy=ProjectStore(context).all().map { old ->
                val group=if(old.modelUri.contains("reference_kitchen")) {
                    referenceGroups().map {g->
                        val a=when(g.id) {
                            "island"->old.islandAdjust
                            "fridge"->old.fridgeAdjust
                            else->ModuleAdjustment(old.offsetX,old.offsetZ,old.rotationDegrees)
                        }
                        g.copy(transform=GroupTransform(a.xMeters,0f,a.zMeters,a.yawDegrees))
                    }
                } else {
                    val path=Uri.parse(old.modelUri).path ?: old.modelUri
                    val bounds=GlbBounds.inspect(File(path))
                    listOf(ArGroup("object",old.name,path,bounds.dimensions,bounds.minimum,
                        transform=GroupTransform(old.offsetX,0f,old.offsetZ,old.rotationDegrees)))
                }
                ArDocument(id=old.id,title=old.name,groups=group)
            }
            write(legacy);return legacy
        }
        val array=database.openRead().use { JSONArray(it.readBytes().toString(Charsets.UTF_8)) }
        return (0 until array.length()).map { ArDocument.parse(array.getJSONObject(it)) }
    }
    private fun write(documents:List<ArDocument>) {
        val stream=database.startWrite()
        try {
            stream.write(JSONArray(documents.map { it.json() }).toString().toByteArray(Charsets.UTF_8))
            database.finishWrite(stream)
        } catch(e:Exception) {database.failWrite(stream);throw e}
    }
    @Synchronized fun put(document:ArDocument):ArDocument {
        val existing=list();write(existing.filterNot { it.id==document.id }+document);return document
    }
    @Synchronized fun delete(id:String) { write(list().filterNot { it.id==id }) }
    fun import(uri:Uri):ArDocument {
        val file=File.createTempFile("incoming-",".bin",context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { ScenePackAdapter.copyLimited(input,it,ScenePackAdapter.MAX_TOTAL_BYTES);it.fd.sync() }
            } ?: error("Не удалось открыть файл")
            return put(ScenePackAdapter.importFile(file,displayName(uri),assets))
        } finally {file.delete()}
    }
    fun importFile(file:File,name:String):ArDocument=put(ScenePackAdapter.importFile(file,name,assets))
    @Synchronized fun append(id:String,uri:Uri):ArDocument {
        val original=list().first { it.id==id }
        val tmp=File.createTempFile("addition-",".bin",context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { src -> tmp.outputStream().use { ScenePackAdapter.copyLimited(src,it,ScenePackAdapter.MAX_TOTAL_BYTES) } } ?: error("Файл недоступен")
            val extra=ScenePackAdapter.importFile(tmp,displayName(uri),assets)
            val all=original.groups+extra.groups.map { it.copy(id=UUID.randomUUID().toString()) }
            return put(original.copy(groups=all))
        } finally {tmp.delete()}
    }
    private fun displayName(uri:Uri):String {
        var name="3D-проект"
        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
            if(it.moveToFirst()) name=it.getString(0) ?: name
        }
        return name.take(160)
    }
    private fun builtin(fileName:String,label:String,id:String,pivot:Point3?=null):ArGroup {
        val out=File(assets,fileName)
        val temp=File.createTempFile("builtin-",".glb",assets)
        try {
            context.assets.open(fileName).use { src -> temp.outputStream().use { src.copyTo(it) } }
            if(!out.exists() || AssetDigest.sha256(temp)!=AssetDigest.sha256(out)) {
                require(temp.renameTo(out)) { "Не удалось обновить встроенную модель" }
            }
        } finally {temp.delete()}
        val b=GlbBounds.inspect(out)
        return ArGroup(id,label,out.absolutePath,b.dimensions,pivot ?: b.minimum)
    }
    private fun referenceGroups()=listOf(
        builtin("reference_wall.glb","Гарнитур под окном","wall"),
        builtin("reference_island.glb","Остров и три стула","island"),
        builtin("fridge_opposite_wall.glb","Холодильник","fridge")
    )
    fun reference()=put(ArDocument(title="Моя кухня · три группы",groups=referenceGroups()))
    fun sample()=put(ArDocument(title="Тестовая кухня",groups=listOf(builtin("demo_kitchen.glb","Тестовый гарнитур","demo"))))
    fun demoPack():ArDocument {
        val tmp=File.createTempFile("demo-pack-",".zip",context.cacheDir)
        try {
            context.assets.open("adapter_demo.interior.zip").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            return importFile(tmp,"Пример пакета")
        } finally {tmp.delete()}
    }
}
