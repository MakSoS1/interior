package com.maksos.interiorar

import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** Portable ZIP contract. Never follows a manifest URI or extracts outside staging. */
object InteriorPackage {
    const val MAX_ASSETS=24
    const val MAX_EXPANDED=256L*1024*1024
    private const val MAX_MANIFEST=1024*1024
    data class Item(val name:String,val file:File,val origin:String="floor")
    fun unpack(archive:File,stage:File):List<Item> {
        require(archive.length()<=MAX_EXPANDED) {"Пакет больше 256 МБ"}
        stage.mkdirs();val root=stage.canonicalFile
        ZipFile(archive).use {zip ->
            val entries=zip.entries().toList()
            require(entries.size<=128) {"Слишком много файлов в пакете"}
            val seen=HashSet<String>()
            entries.forEach {e ->
                val name=e.name
                require(name.isNotEmpty() && name.length<=200 && !name.startsWith('/') &&
                    !name.contains('\\') && !name.contains(':') && !name.contains('\u0000') &&
                    name.split('/').none {it==".." || it=="."}) {"Небезопасный путь в ZIP"}
                require(seen.add(name)) {"Повторяющийся путь в ZIP"}
            }
            val entry=zip.getEntry("manifest.json") ?: error("В ZIP нет manifest.json")
            require(entry.size in 1..MAX_MANIFEST.toLong()) {"Некорректный manifest.json"}
            val bytes=zip.getInputStream(entry).use {input ->
                val data=input.readAtMost(MAX_MANIFEST+1);require(data.size<=MAX_MANIFEST);data
            }
            val manifest=JSONObject(String(bytes,Charsets.UTF_8))
            require(manifest.optString("format")=="interior-ar" && manifest.optInt("version")==1) {"Ожидается пакет Interior AR версии 1"}
            require(manifest.optString("units")=="m") {"Экспортируйте модель в метрах (units=m)"}
            val array=manifest.getJSONArray("assets")
            require(array.length() in 1..MAX_ASSETS) {"В пакете должно быть 1–24 объекта или группы"}
            val ids=HashSet<String>();val paths=HashSet<String>();var total=0L
            return (0 until array.length()).map {i ->
                val data=array.getJSONObject(i)
                val id=data.getString("id");val path=data.getString("file")
                require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && ids.add(id)) {"Некорректный или повторяющийся id"}
                require(paths.add(path) && path.endsWith(".glb",true) && path in seen) {"Не найден уникальный GLB: $path"}
                val origin=data.optString("origin","floor")
                require(origin=="floor" || origin=="authored") {"Неизвестный тип опоры модели"}
                val target=File(root,path).canonicalFile
                require(target.path.startsWith(root.path+File.separator)) {"Выход за каталог пакета"}
                val e=zip.getEntry(path)
                require(!e.isDirectory && e.size in 28..(200L*1024*1024)) {"Недопустимый размер GLB"}
                target.parentFile!!.mkdirs()
                zip.getInputStream(e).use {input -> target.outputStream().use {output ->
                    val buffer=ByteArray(64*1024);var fileBytes=0L
                    while(true) {
                        val n=input.read(buffer);if(n<0) break
                        total+=n;fileBytes+=n
                        require(total<=MAX_EXPANDED && fileBytes<=200L*1024*1024) {"Распакованный пакет слишком большой"}
                        output.write(buffer,0,n)
                    }
                    output.fd.sync()
                }}
                val expected=data.optString("sha256","")
                require(expected.isEmpty() || (expected.matches(Regex("[a-fA-F0-9]{64}")) && AssetDigest.sha256(target).equals(expected,true))) {"Контрольная сумма GLB не совпадает"}
                GlbInspector.inspect(target)
                val name=data.optString("name",id).filter {it>=' '}.trim().take(160).ifEmpty {id}
                Item(name,target,origin)
            }
        }
    }
}
