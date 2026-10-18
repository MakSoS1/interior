package com.maksos.interiorar

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** GLB 2.0 header and hierarchy AABB reader. Coordinates are meters and Y is up. */
object GlbInspector {
    fun inspect(file: File): ModelDimensions {
        require(file.length() in 20..(200L * 1024 * 1024)) { "Размер GLB не поддерживается" }
        val header = ByteArray(20)
        file.inputStream().use { check(it.read(header) == 20) { "Повреждённый GLB" } }
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        require(bb.int == 0x46546c67 && bb.int == 2) { "Требуется бинарный glTF 2.0 (.glb)" }
        require(bb.int.toLong() == file.length()) { "Неверная длина GLB" }
        val jsonLength = bb.int
        require(bb.int == 0x4e4f534a && jsonLength > 0 && jsonLength < file.length()-20) { "Отсутствует JSON GLB" }
        val jsonBytes = ByteArray(jsonLength)
        file.inputStream().use {
            var remaining = (20 + jsonLength).toLong()
            // streaming read JSON at known file offset
            var skip = 20L
            while(skip > 0) { val n = it.skip(skip); if(n == 0L) throw IllegalArgumentException("Повреждённый GLB"); skip -= n }
            var off = 0
            while(off < jsonLength) {
                val n = it.read(jsonBytes,off,jsonLength-off)
                if(n < 0) throw IllegalArgumentException("Неожиданный конец файла")
                off += n
            }
        }
        val root = JSONObject(String(jsonBytes, Charsets.UTF_8).trim())
        require(root.getJSONObject("asset").getString("version") == "2.0") { "Требуется glTF 2.0" }
        val meshes = root.optJSONArray("meshes") ?: throw IllegalArgumentException("В модели нет геометрии")
        val accessors = root.optJSONArray("accessors") ?: throw IllegalArgumentException("В модели нет вершин")
        val nodes = root.optJSONArray("nodes") ?: throw IllegalArgumentException("Нет объектов модели")
        require(meshes.length() > 0 && nodes.length() > 0) { "Пустая модель" }
        // For security, reject glTF external resources: GLB should be self-contained.
        root.optJSONArray("buffers")?.let { arr ->
            for (i in 0 until arr.length()) require(!arr.getJSONObject(i).has("uri")) { "Внешние буферы не поддерживаются" }
        }
        root.optJSONArray("images")?.let { arr ->
            for (i in 0 until arr.length()) require(!arr.getJSONObject(i).has("uri")) { "Используйте встроенные текстуры GLB" }
        }

        var minX = Double.POSITIVE_INFINITY; var minY = minX; var minZ = minX
        var maxX = Double.NEGATIVE_INFINITY; var maxY = maxX; var maxZ = maxX
        var hasBounds = false
        val active = mutableSetOf<Int>()
        fun minMaxArray(accessorIndex: Int): Pair<DoubleArray, DoubleArray> {
            val a=accessors.getJSONObject(accessorIndex)
            val lo=a.optJSONArray("min") ?: throw IllegalArgumentException("Отсутствуют границы POSITION")
            val hi=a.optJSONArray("max") ?: throw IllegalArgumentException("Отсутствуют границы POSITION")
            require(lo.length() == 3 && hi.length() == 3) { "Неправильный VEC3" }
            return Pair(DoubleArray(3){lo.getDouble(it)},DoubleArray(3){hi.getDouble(it)})
        }
        // glTF transform composition for node hierarchy (column-major 4x4).
        fun multiply(a: DoubleArray,b:DoubleArray):DoubleArray {
            val out=DoubleArray(16)
            for(c in 0..3) for(r in 0..3) for(k in 0..3) out[c*4+r] += a[k*4+r]*b[c*4+k]
            return out
        }
        fun walk(index:Int,parent:DoubleArray,depth:Int) {
            require(depth<64 && active.add(index)) { "Цикл в иерархии GLB" }
            val n=nodes.getJSONObject(index)
            val m=DoubleArray(16){if(it%5==0) 1.0 else 0.0}
            if(n.has("matrix")) {
                val arr=n.getJSONArray("matrix")
                require(arr.length()==16) { "Некорректная матрица GLB" }
                for(i in 0..15) m[i]=arr.getDouble(i)
            } else {
                val t=n.optJSONArray("translation")
                val s=n.optJSONArray("scale")
                val q=n.optJSONArray("rotation")
                val x=q?.getDouble(0) ?: 0.0; val y=q?.getDouble(1) ?: 0.0
                val z=q?.getDouble(2) ?: 0.0; val w=q?.getDouble(3) ?: 1.0
                val sx=s?.getDouble(0) ?: 1.0; val sy=s?.getDouble(1) ?: 1.0; val sz=s?.getDouble(2) ?: 1.0
                m[0]=(1-2*(y*y+z*z))*sx; m[1]=(2*(x*y+z*w))*sx; m[2]=(2*(x*z-y*w))*sx
                m[4]=(2*(x*y-z*w))*sy; m[5]=(1-2*(x*x+z*z))*sy; m[6]=(2*(y*z+x*w))*sy
                m[8]=(2*(x*z+y*w))*sz; m[9]=(2*(y*z-x*w))*sz; m[10]=(1-2*(x*x+y*y))*sz
                m[12]=t?.getDouble(0) ?: 0.0; m[13]=t?.getDouble(1) ?: 0.0; m[14]=t?.getDouble(2) ?: 0.0
            }
            require(m.all { it.isFinite() }) { "Некорректные координаты модели" }
            val world=multiply(parent,m)
            if(n.has("mesh")) {
                val primitives=meshes.getJSONObject(n.getInt("mesh")).getJSONArray("primitives")
                for(p in 0 until primitives.length()) {
                    val a=primitives.getJSONObject(p).getJSONObject("attributes")
                    if(!a.has("POSITION")) continue
                    val (lo,hi)=minMaxArray(a.getInt("POSITION"))
                    for(bx in listOf(lo[0],hi[0])) for(by in listOf(lo[1],hi[1])) for(bz in listOf(lo[2],hi[2])) {
                        val px=world[0]*bx+world[4]*by+world[8]*bz+world[12]
                        val py=world[1]*bx+world[5]*by+world[9]*bz+world[13]
                        val pz=world[2]*bx+world[6]*by+world[10]*bz+world[14]
                        require(px.isFinite() && py.isFinite() && pz.isFinite()) { "Некорректные границы модели" }
                        minX=min(minX,px); minY=min(minY,py); minZ=min(minZ,pz)
                        maxX=max(maxX,px); maxY=max(maxY,py); maxZ=max(maxZ,pz)
                        hasBounds=true
                    }
                }
            }
            n.optJSONArray("children")?.let { a -> for(i in 0 until a.length()) walk(a.getInt(i),world,depth+1) }
            active.remove(index)
        }
        val sceneIdx=root.optInt("scene",0)
        val roots=root.getJSONArray("scenes").getJSONObject(sceneIdx).getJSONArray("nodes")
        val identity=DoubleArray(16){if(it%5==0) 1.0 else 0.0}
        for(i in 0 until roots.length()) walk(roots.getInt(i),identity,0)
        require(hasBounds) { "Нет вершин в сцене" }
        val w=(maxX-minX).toFloat(); val h=(maxY-minY).toFloat(); val d=(maxZ-minZ).toFloat()
        require(w in 0.001f..100f && h in 0.001f..100f && d in 0.001f..100f) { "Размеры модели выглядят неверными: проверьте метры в Blender" }
        return ModelDimensions(w,h,d)
    }
}
