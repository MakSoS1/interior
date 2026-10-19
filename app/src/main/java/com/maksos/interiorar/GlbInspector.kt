package com.maksos.interiorar

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** GLB 2.0 validation and hierarchy-aware AABB. All glTF transforms remain in metres. */
object GlbInspector {
    fun inspect(file:File):ModelDimensions {
        require(file.length() in 20..(200L*1024*1024)) {"Размер GLB не поддерживается"}
        val header=file.inputStream().use {it.readAtMost(20)}
        require(header.size==20) {"Повреждённый GLB"}
        val bb=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        require(bb.int==0x46546c67 && bb.int==2) {"Требуется бинарный glTF 2.0 (.glb)"}
        require(bb.int.toLong()==file.length()) {"Неверная длина GLB"}
        val jsonLength=bb.int
        require(bb.int==0x4e4f534a && jsonLength in 1..(8*1024*1024) && jsonLength<file.length()-20 && jsonLength%4==0) {"Отсутствует JSON GLB"}
        var binSize=0L
        java.io.RandomAccessFile(file,"r").use {input ->
            var cursor=20L+jsonLength
            while(cursor<file.length()) {
                require(file.length()-cursor>=8) {"Повреждённый заголовок чанка GLB"}
                input.seek(cursor)
                val size=Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()))
                val type=Integer.reverseBytes(input.readInt())
                require(size%4L==0L && size<=file.length()-cursor-8) {"Чанк GLB выходит за границы файла"}
                if(type==0x004e4942) {require(binSize==0L) {"Повторный BIN"};binSize=size}
                cursor+=8+size
            }
        }
        val jsonBytes=ByteArray(jsonLength)
        file.inputStream().use {
            var skip=20L
            while(skip>0){val n=it.skip(skip);require(n>0) {"Повреждённый GLB"};skip-=n}
            var offset=0
            while(offset<jsonLength) {val n=it.read(jsonBytes,offset,jsonLength-offset);require(n>0);offset+=n}
        }
        val root=JSONObject(String(jsonBytes,Charsets.UTF_8).trim())
        require(root.getJSONObject("asset").getString("version")=="2.0") {"Требуется glTF 2.0"}
        val meshes=root.optJSONArray("meshes") ?: error("В модели нет геометрии")
        val accessors=root.optJSONArray("accessors") ?: error("В модели нет вершин")
        val nodes=root.optJSONArray("nodes") ?: error("Нет объектов модели")
        require(meshes.length()>0 && nodes.length()>0) {"Пустая модель"}
        root.optJSONArray("buffers")?.let {array ->
            for(i in 0 until array.length()) require(!array.getJSONObject(i).has("uri")) {"Внешние буферы не поддерживаются"}
            require(array.length()==1) {"Ожидается один встроенный буфер GLB"}
            val declared=array.getJSONObject(0).getLong("byteLength")
            require(declared>0 && declared<=binSize && binSize-declared<=3) {"Неверная длина BIN"}
        }
        root.optJSONArray("images")?.let {array ->
            for(i in 0 until array.length()) require(!array.getJSONObject(i).has("uri")) {"Используйте встроенные текстуры GLB"}
        }
        root.optJSONArray("bufferViews")?.let {views ->
            for(i in 0 until views.length()) {
                val v=views.getJSONObject(i);val start=v.optLong("byteOffset",0);val size=v.getLong("byteLength")
                require(v.optInt("buffer",0)==0 && start>=0 && size>=0 && start<=binSize && size<=binSize-start) {"bufferView выходит за BIN"}
            }
        }
        var minX=Double.POSITIVE_INFINITY;var minY=minX;var minZ=minX
        var maxX=Double.NEGATIVE_INFINITY;var maxY=maxX;var maxZ=maxX
        var hasBounds=false;val active=mutableSetOf<Int>();var visited=0
        fun minMaxArray(index:Int):Pair<DoubleArray,DoubleArray> {
            val a=accessors.getJSONObject(index)
            val lo=a.optJSONArray("min") ?: error("Отсутствуют границы POSITION")
            val hi=a.optJSONArray("max") ?: error("Отсутствуют границы POSITION")
            require(lo.length()==3 && hi.length()==3) {"Неправильный VEC3"}
            val low=DoubleArray(3){lo.getDouble(it)};val high=DoubleArray(3){hi.getDouble(it)}
            require(low.indices.all {low[it].isFinite() && high[it].isFinite() && low[it]<=high[it]}) {"Неверные границы POSITION"}
            return Pair(low,high)
        }
        fun multiply(a:DoubleArray,b:DoubleArray):DoubleArray {
            val out=DoubleArray(16)
            for(c in 0..3)for(r in 0..3)for(k in 0..3)out[c*4+r]+=a[k*4+r]*b[c*4+k]
            return out
        }
        fun walk(index:Int,parent:DoubleArray,depth:Int) {
            require(++visited<=20000 && depth<64 && active.add(index)) {"Слишком сложная или циклическая иерархия GLB"}
            val n=nodes.getJSONObject(index);val m=DoubleArray(16){if(it%5==0)1.0 else 0.0}
            if(n.has("matrix")) {
                val a=n.getJSONArray("matrix");require(a.length()==16)
                for(i in 0..15)m[i]=a.getDouble(i)
            } else {
                val t=n.optJSONArray("translation");val s=n.optJSONArray("scale");val q=n.optJSONArray("rotation")
                val x=q?.getDouble(0) ?: 0.0;val y=q?.getDouble(1) ?: 0.0;val z=q?.getDouble(2) ?: 0.0;val w=q?.getDouble(3) ?: 1.0
                val sx=s?.getDouble(0) ?: 1.0;val sy=s?.getDouble(1) ?: 1.0;val sz=s?.getDouble(2) ?: 1.0
                m[0]=(1-2*(y*y+z*z))*sx;m[1]=2*(x*y+z*w)*sx;m[2]=2*(x*z-y*w)*sx
                m[4]=2*(x*y-z*w)*sy;m[5]=(1-2*(x*x+z*z))*sy;m[6]=2*(y*z+x*w)*sy
                m[8]=2*(x*z+y*w)*sz;m[9]=2*(y*z-x*w)*sz;m[10]=(1-2*(x*x+y*y))*sz
                m[12]=t?.getDouble(0) ?: 0.0;m[13]=t?.getDouble(1) ?: 0.0;m[14]=t?.getDouble(2) ?: 0.0
            }
            require(m[3]==0.0 && m[7]==0.0 && m[11]==0.0 && m[15]==1.0) {"Матрица должна быть аффинной"}
            require(m.all {it.isFinite()}) {"Некорректные координаты модели"}
            val world=multiply(parent,m)
            if(n.has("mesh")) {
                val ps=meshes.getJSONObject(n.getInt("mesh")).getJSONArray("primitives")
                for(p in 0 until ps.length()) {
                    val a=ps.getJSONObject(p).getJSONObject("attributes");if(!a.has("POSITION"))continue
                    val(lo,hi)=minMaxArray(a.getInt("POSITION"))
                    for(bx in listOf(lo[0],hi[0]))for(by in listOf(lo[1],hi[1]))for(bz in listOf(lo[2],hi[2])) {
                        val px=world[0]*bx+world[4]*by+world[8]*bz+world[12]
                        val py=world[1]*bx+world[5]*by+world[9]*bz+world[13]
                        val pz=world[2]*bx+world[6]*by+world[10]*bz+world[14]
                        require(px.isFinite() && py.isFinite() && pz.isFinite())
                        minX=min(minX,px);minY=min(minY,py);minZ=min(minZ,pz)
                        maxX=max(maxX,px);maxY=max(maxY,py);maxZ=max(maxZ,pz);hasBounds=true
                    }
                }
            }
            n.optJSONArray("children")?.let {a ->for(i in 0 until a.length())walk(a.getInt(i),world,depth+1)}
            active.remove(index)
        }
        val roots=root.getJSONArray("scenes").getJSONObject(root.optInt("scene",0)).getJSONArray("nodes")
        val identity=DoubleArray(16){if(it%5==0)1.0 else 0.0}
        for(i in 0 until roots.length())walk(roots.getInt(i),identity,0)
        require(hasBounds) {"Нет вершин в сцене"}
        val w=(maxX-minX).toFloat();val h=(maxY-minY).toFloat();val d=(maxZ-minZ).toFloat()
        require(w in .001f..100f && h in .001f..100f && d in .001f..100f) {"Проверьте реальные размеры в метрах"}
        return ModelDimensions(w,h,d)
    }
}
