package com.maksos.interiorar

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Static posed bounds. Animation is deliberately disabled in the furniture renderer. */
data class GlbBounds(val minimum: Point3, val maximum: Point3) {
    val dimensions get()=ModelDimensions(maximum.x-minimum.x,maximum.y-minimum.y,maximum.z-minimum.z)
    companion object {
        const val MAX_BYTES=200L*1024*1024
        fun inspect(file:File):GlbBounds {
            require(file.length() in 28..MAX_BYTES) { "GLB должен быть не более 200 МБ" }
            val root:JSONObject
            RandomAccessFile(file,"r").use { f ->
                val header=ByteArray(20);f.readFully(header)
                val b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                require(b.int==0x46546c67 && b.int==2) { "Нужен бинарный glTF 2.0 (.glb)" }
                require(b.int.toLong()==file.length()) { "Повреждена длина GLB" }
                val n=b.int
                require(b.int==0x4e4f534a && n in 2..(8*1024*1024) && n.toLong()+20<file.length()) { "Повреждён JSON GLB" }
                val data=ByteArray(n);f.readFully(data);root=JSONObject(data.toString(Charsets.UTF_8).trim())
                require(root.getJSONObject("asset").getString("version")=="2.0")
                var binBytes=0L
                while(f.filePointer<file.length()) {
                    require(file.length()-f.filePointer>=8) { "Обрезанный chunk GLB" }
                    val ch=ByteArray(8);f.readFully(ch);val c=ByteBuffer.wrap(ch).order(ByteOrder.LITTLE_ENDIAN)
                    val size=c.int.toLong();val kind=c.int
                    require(size>=0 && size%4==0L && size<=file.length()-f.filePointer) { "Неверный BIN chunk" }
                    if(kind==0x004e4942) binBytes+=size
                    f.seek(f.filePointer+size)
                }
                root.optJSONArray("buffers")?.let { a ->
                    require(a.length()==1) { "Поддерживается один встроенный GLB-буфер" }
                    val buffer=a.getJSONObject(0)
                    require(!buffer.has("uri")) { "Внешние ресурсы запрещены; встройте данные в GLB" }
                    require(buffer.getLong("byteLength") in 1..binBytes) { "GLB BIN отсутствует или обрезан" }
                }
                root.optJSONArray("images")?.let { a -> for(i in 0 until a.length())
                    require(!a.getJSONObject(i).has("uri")) { "Текстуры должны быть встроены в GLB" }
                }
                root.optJSONArray("bufferViews")?.let { a -> for(i in 0 until a.length()) {
                    val v=a.getJSONObject(i);val o=v.optLong("byteOffset",0);val l=v.getLong("byteLength")
                    require(v.optInt("buffer",0)==0 && o>=0 && l>=0 && o<=binBytes-l) { "bufferView вне BIN" }
                } }
            }
            val nodes=root.getJSONArray("nodes");val meshes=root.getJSONArray("meshes");val access=root.getJSONArray("accessors")
            require(nodes.length() in 1..50000)
            val lo=DoubleArray(3){Double.POSITIVE_INFINITY};val hi=DoubleArray(3){Double.NEGATIVE_INFINITY}
            val active=HashSet<Int>();var visits=0;var found=false
            fun ident()=DoubleArray(16){if(it%5==0)1.0 else 0.0}
            fun multiply(a:DoubleArray,b:DoubleArray):DoubleArray {
                val o=DoubleArray(16)
                for(c in 0..3)for(r in 0..3)for(k in 0..3)o[c*4+r]+=a[k*4+r]*b[c*4+k]
                return o
            }
            fun local(n:JSONObject):DoubleArray {
                val m=ident()
                n.optJSONArray("matrix")?.let { a ->
                    require(a.length()==16);for(i in 0..15)m[i]=a.getDouble(i)
                    require(m.all { it.isFinite() });return m
                }
                val q=n.optJSONArray("rotation");val s=n.optJSONArray("scale");val t=n.optJSONArray("translation")
                require(q==null||q.length()==4);require(s==null||s.length()==3);require(t==null||t.length()==3)
                val x=q?.getDouble(0)?:0.0;val y=q?.getDouble(1)?:0.0;val z=q?.getDouble(2)?:0.0;val w=q?.getDouble(3)?:1.0
                val sx=s?.getDouble(0)?:1.0;val sy=s?.getDouble(1)?:1.0;val sz=s?.getDouble(2)?:1.0
                m[0]=(1-2*(y*y+z*z))*sx;m[1]=2*(x*y+z*w)*sx;m[2]=2*(x*z-y*w)*sx
                m[4]=2*(x*y-z*w)*sy;m[5]=(1-2*(x*x+z*z))*sy;m[6]=2*(y*z+x*w)*sy
                m[8]=2*(x*z+y*w)*sz;m[9]=2*(y*z-x*w)*sz;m[10]=(1-2*(x*x+y*y))*sz
                m[12]=t?.getDouble(0)?:0.0;m[13]=t?.getDouble(1)?:0.0;m[14]=t?.getDouble(2)?:0.0
                require(m.all { it.isFinite() }) { "Нечисловая геометрия" };return m
            }
            fun visit(id:Int,parent:DoubleArray,depth:Int) {
                require(depth<64 && ++visits<=50000 && active.add(id)) { "Циклическая/слишком большая иерархия" }
                val n=nodes.getJSONObject(id);val world=multiply(parent,local(n))
                if(n.has("mesh")) {
                    val ps=meshes.getJSONObject(n.getInt("mesh")).getJSONArray("primitives")
                    for(i in 0 until ps.length()) {
                        val a=access.getJSONObject(ps.getJSONObject(i).getJSONObject("attributes").getInt("POSITION"))
                        val mn=a.getJSONArray("min");val mx=a.getJSONArray("max")
                        require(mn.length()==3 && mx.length()==3 && a.getString("type")=="VEC3" && a.getLong("count")>0)
                        for(j in 0..2)require(mn.getDouble(j).isFinite()&&mx.getDouble(j).isFinite()&&mn.getDouble(j)<=mx.getDouble(j))
                        for(mask in 0..7) {
                            val p=DoubleArray(3){if(mask and (1 shl it)==0)mn.getDouble(it) else mx.getDouble(it)}
                            for(r in 0..2) {
                                val v=world[r]*p[0]+world[4+r]*p[1]+world[8+r]*p[2]+world[12+r]
                                require(v.isFinite()&&kotlin.math.abs(v)<1e7)
                                lo[r]=min(lo[r],v);hi[r]=max(hi[r],v)
                            }
                            found=true
                        }
                    }
                }
                n.optJSONArray("children")?.let { a -> for(i in 0 until a.length())visit(a.getInt(i),world,depth+1) }
                active.remove(id)
            }
            val roots=root.getJSONArray("scenes").getJSONObject(root.optInt("scene",0)).getJSONArray("nodes")
            for(i in 0 until roots.length())visit(roots.getInt(i),ident(),0)
            require(found && (0..2).any { hi[it]-lo[it]>1e-7 }) { "Пустая геометрия GLB" }
            return GlbBounds(Point3(lo[0].toFloat(),lo[1].toFloat(),lo[2].toFloat()),Point3(hi[0].toFloat(),hi[1].toFloat(),hi[2].toFloat()))
        }
    }
}
