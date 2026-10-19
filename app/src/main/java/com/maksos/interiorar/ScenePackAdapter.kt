package com.maksos.interiorar

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

/** Portable interchange, not an Android/ARCore world-map format. No network fetching. */
object ScenePackAdapter {
    const val MAX_TOTAL_BYTES=512L*1024*1024
    const val MAX_ENTRIES=128
    private val safeId=Regex("[A-Za-z0-9_-]{1,80}")
    fun copyLimited(input:InputStream,output:OutputStream,limit:Long):Long {
        val buffer=ByteArray(65536);var total=0L
        while(true) {
            val n=input.read(buffer);if(n<0)break
            total+=n;require(total<=limit) { "Превышен допустимый размер файла" }
            output.write(buffer,0,n)
        }
        return total
    }
    fun safeRelative(name:String):String {
        require(name.isNotBlank() && name.length<=240 && !name.contains('\\') && !name.contains(':') && !name.startsWith('/')) { "Недопустимый путь в пакете" }
        val parts=name.split('/')
        require(parts.none { it.isEmpty() || it=="." || it==".." || it.any { c -> c.code<32 } }) { "Выход за пределы пакета запрещён" }
        return name
    }
    fun importFile(input:File,title:String,assets:File):ArDocument {
        assets.mkdirs()
        val signature=input.inputStream().use { it.readNBytes(4) }
        return if(signature.contentEquals(byteArrayOf(0x67,0x6c,0x54,0x46))) importGlb(input,title,assets)
        else {
            require(signature.size==4 && signature[0]==0x50.toByte() && signature[1]==0x4b.toByte()) { "Нужен .glb или .interior.zip" }
            importPack(input,assets)
        }
    }
    fun importGlb(input:File,title:String,assets:File):ArDocument {
        val bounds=GlbBounds.inspect(input)
        val sha=AssetDigest.sha256(input)
        val dest=File(assets,"$sha.glb")
        if(!dest.exists()) {
            val temp=File.createTempFile("asset-",".glb",assets)
            try {
                input.inputStream().use { source -> FileOutputStream(temp).use { out -> copyLimited(source,out,GlbBounds.MAX_BYTES);out.fd.sync() } }
                require(temp.renameTo(dest)) { "Не удалось сохранить GLB" }
            } finally {temp.delete()}
        }
        return ArDocument(title=title.take(160).ifBlank { "3D-модель" },groups=listOf(
            ArGroup("object",title.take(160).ifBlank { "Объект" },dest.absolutePath,bounds.dimensions,bounds.minimum)
        ))
    }
    private fun importPack(input:File,assets:File):ArDocument {
        val stage=File(assets,"pending-"+UUID.randomUUID()).apply { mkdirs() }
        var installed:File?=null
        try {
            var bytes=0L;var entries=0;val names=HashSet<String>()
            ZipInputStream(input.inputStream().buffered()).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    require(++entries<=MAX_ENTRIES) { "В пакете слишком много файлов" }
                    if(entry.isDirectory) { safeRelative(entry.name.trimEnd('/'));zip.closeEntry();continue }
                    val name=safeRelative(entry.name)
                    require(names.add(name.lowercase())) { "Повторяющийся путь в архиве" }
                    require(name=="interior.json" || name.endsWith(".glb",ignoreCase=true)) { "Пакет содержит посторонний файл: $name" }
                    val cap=if(name=="interior.json") 1024L*1024 else GlbBounds.MAX_BYTES
                    if(entry.size>=0)require(entry.size<=cap && entry.size<=MAX_TOTAL_BYTES-bytes)
                    val output=File(stage,name).apply { parentFile?.mkdirs() }
                    output.outputStream().use { out -> bytes+=copyLimited(zip,out,minOf(cap,MAX_TOTAL_BYTES-bytes)) }
                    zip.closeEntry()
                }
            }
            val file=File(stage,"interior.json")
            require(file.isFile) { "В пакете нет interior.json" }
            val manifest=JSONObject(file.readText())
            require(manifest.getInt("schemaVersion")==1 && manifest.getString("format")=="interior-ar") { "Неизвестный формат пакета" }
            val gs=manifest.getJSONArray("groups")
            require(gs.length() in 1..32) { "В пакете допустимо от 1 до 32 групп" }
            val ids=HashSet<String>();val used=HashSet<String>()
            val parsed=(0 until gs.length()).map { i ->
                val g=gs.getJSONObject(i);val id=g.getString("id")
                require(safeId.matches(id)&&ids.add(id)) { "ID группы должен быть уникальным" }
                val relative=safeRelative(g.getString("asset"));require(relative.endsWith(".glb",true))
                val model=File(stage,relative);require(model.isFile) { "Не найдена модель $relative" };used.add(relative.lowercase())
                g.optString("sha256").takeIf { it.isNotEmpty() }?.let { require(it.equals(AssetDigest.sha256(model),true)) { "Контрольная сумма $relative не совпала" } }
                val bounds=GlbBounds.inspect(model)
                val pivotArray=g.optJSONArray("pivot")
                val pivot=if(pivotArray==null) bounds.minimum else {
                    require(pivotArray.length()==3)
                    Point3(pivotArray.getDouble(0).toFloat(),pivotArray.getDouble(1).toFloat(),pivotArray.getDouble(2).toFloat())
                }
                ArGroup(id,g.getString("name"),relative,bounds.dimensions,pivot,
                    g.optDouble("metresPerUnit",1.0).toFloat(),GroupTransform.parse(g.optJSONObject("transform")))
            }
            require(names==used+"interior.json") { "В пакете есть неиспользуемые файлы" }
            val title=manifest.getString("name");val light=LightingSettings.parse(manifest.optJSONObject("lighting"))
            // Validate the document BEFORE publishing its directory.
            val candidate=ArDocument(title=title,groups=parsed,lighting=light)
            val dest=File(assets,"pack-"+candidate.id)
            require(stage.renameTo(dest)) { "Не удалось установить пакет" };installed=dest
            return candidate.copy(groups=parsed.map { it.copy(file=File(dest,it.file).absolutePath) })
        } catch(e:Exception) {
            installed?.deleteRecursively();throw e
        } finally { stage.deleteRecursively() }
    }
    fun export(document:ArDocument,output:OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            val written=HashSet<String>();val groups=JSONArray()
            document.groups.forEachIndexed { index,g ->
                val file=File(g.file);require(file.isFile) { "Исходная модель отсутствует: ${g.label}" }
                GlbBounds.inspect(file)
                val sha=AssetDigest.sha256(file);val asset="models/$sha.glb"
                if(written.add(asset)) {
                    zip.putNextEntry(ZipEntry(asset).apply { time=0L })
                    file.inputStream().use { copyLimited(it,zip,GlbBounds.MAX_BYTES) };zip.closeEntry()
                }
                groups.put(JSONObject().put("id","group_$index").put("name",g.label).put("asset",asset)
                    .put("sha256",sha).put("metresPerUnit",g.metresPerUnit.toDouble())
                    .put("pivot",JSONArray(listOf(g.pivot.x,g.pivot.y,g.pivot.z))).put("transform",g.transform.json()))
            }
            val manifest=JSONObject().put("format","interior-ar").put("schemaVersion",1)
                .put("name",document.title).put("groups",groups).put("lighting",document.lighting.json())
            zip.putNextEntry(ZipEntry("interior.json").apply { time=0L })
            zip.write((manifest.toString(2)+"\n").toByteArray(Charsets.UTF_8));zip.closeEntry()
        }
    }
}
