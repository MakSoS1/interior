package com.maksos.interiorar

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ScenePackAdapterTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private fun temp()=File(context.cacheDir,"qa-"+System.nanoTime()).apply{mkdirs()}
    private fun asset(name:String,dir:File):File=File(dir,name).also {file->context.assets.open(name).use {src->file.outputStream().use{src.copyTo(it)}}}
    private fun packageFile(dir:File,groups:JSONArray,extra:String?=null):File {
        val model=asset("demo_kitchen.glb",dir)
        val out=File(dir,"pack.zip")
        ZipOutputStream(out.outputStream()).use {z->
            z.putNextEntry(ZipEntry("models/item.glb"));model.inputStream().use{it.copyTo(z)};z.closeEntry()
            z.putNextEntry(ZipEntry("interior.json"));z.write(JSONObject().put("format","interior-ar").put("schemaVersion",1).put("name","QA").put("groups",groups).toString().toByteArray());z.closeEntry()
            if(extra!=null){z.putNextEntry(ZipEntry(extra));z.write(byteArrayOf(1,2,3));z.closeEntry()}
        }
        return out
    }
    private fun group(id:String="a",path:String="models/item.glb")=JSONObject().put("id",id).put("name","Group").put("asset",path)
    @Test fun generatedPortableDemoLoadsDifferentOriginsAsSeparateGroups() {
        val dir=temp()
        try {
            val doc=ScenePackAdapter.importFile(asset("adapter_demo.interior.zip",dir),"demo",File(dir,"assets"))
            assertEquals(2,doc.groups.size)
            val cabinet=doc.groups.first()
            assertEquals(.6f,cabinet.dimensions.width,.001f)
            assertEquals(2f,cabinet.pivot.y,.001f)
            assertEquals(.8f,cabinet.physicalDimensions().height,.001f)
            assertTrue(File(cabinet.file).exists())
            assertNotEquals(doc.groups[0].id,doc.groups[1].id)
        } finally {dir.deleteRecursively()}
    }
    @Test fun exportRoundTripPreservesSettingsPivotAndPhysicalScale() {
        val dir=temp()
        try {
            val base=ScenePackAdapter.importFile(asset("adapter_demo.interior.zip",dir),"demo",File(dir,"assets"))
            val first=base.groups.first().copy(metresPerUnit=.01f,transform=GroupTransform(.12f,.45f,-.04f,75f,2f))
            val doc=base.copy(groups=listOf(first,base.groups[1]),lighting=LightingSettings(true,-.75f,true))
            val exported=File(dir,"out.zip");exported.outputStream().use{ScenePackAdapter.export(doc,it)}
            val round=ScenePackAdapter.importFile(exported,"round",File(dir,"round-assets"))
            assertEquals(doc.lighting,round.lighting)
            assertEquals(first.transform,round.groups[0].transform)
            assertEquals(first.pivot,round.groups[0].pivot)
            assertEquals(first.physicalDimensions().width,round.groups[0].physicalDimensions().width,1e-5f)
            assertEquals(AssetDigest.sha256(File(first.file)),AssetDigest.sha256(File(round.groups[0].file)))
        } finally {dir.deleteRecursively()}
    }
    @Test fun zipSlipFailsWithoutWritingOutsideStagingOrCatalog() {
        val dir=temp()
        try {
            val pack=packageFile(dir,JSONArray().put(group()),"../escape.glb")
            val assets=File(dir,"assets")
            assertThrows(IllegalArgumentException::class.java){ScenePackAdapter.importFile(pack,"bad",assets)}
            assertFalse(File(dir,"escape.glb").exists())
            assertEquals(0,assets.listFiles()?.size ?: 0)
        } finally {dir.deleteRecursively()}
    }
    @Test fun duplicateIdsAreRejectedAndStageRemoved() {
        val dir=temp()
        try {
            val pack=packageFile(dir,JSONArray().put(group()).put(group()))
            val assets=File(dir,"assets")
            assertThrows(IllegalArgumentException::class.java){ScenePackAdapter.importFile(pack,"bad",assets)}
            assertEquals(0,assets.listFiles()?.size ?: 0)
        } finally {dir.deleteRecursively()}
    }
    @Test fun corruptDigestAndMissingAssetRejected() {
        val dir=temp()
        try {
            val pack=packageFile(dir,JSONArray().put(group().put("sha256","0".repeat(64))))
            assertThrows(IllegalArgumentException::class.java){ScenePackAdapter.importFile(pack,"bad",File(dir,"assets"))}
            val missing=packageFile(dir,JSONArray().put(group(path="models/missing.glb")))
            assertThrows(IllegalArgumentException::class.java){ScenePackAdapter.importFile(missing,"bad",File(dir,"assets"))}
        } finally {dir.deleteRecursively()}
    }
    @Test fun unsupportedVersionRejected() {
        val dir=temp()
        try {
            val f=File(dir,"bad.zip")
            ZipOutputStream(f.outputStream()).use{z->z.putNextEntry(ZipEntry("interior.json"));z.write("{\"format\":\"interior-ar\",\"schemaVersion\":999}".toByteArray());z.closeEntry()}
            assertThrows(IllegalArgumentException::class.java){ScenePackAdapter.importFile(f,"bad",File(dir,"assets"))}
        } finally{dir.deleteRecursively()}
    }
    @Test fun truncatedGlbIsNotAcceptedJustBecauseItHasAHeader() {
        val dir=temp()
        try {
            val glb=asset("demo_kitchen.glb",dir)
            val bytes=glb.readBytes().dropLast(30).toByteArray();glb.writeBytes(bytes)
            assertThrows(IllegalArgumentException::class.java){GlbBounds.inspect(glb)}
        } finally{dir.deleteRecursively()}
    }
    @Test fun unsafePathsAreRejectedIncludingBackslash() {
        for(path in listOf("../x.glb","/x.glb","C:/x.glb","a\\x.glb","a/./x.glb","a//x.glb"))
            assertThrows(IllegalArgumentException::class.java){ScenePackAdapter.safeRelative(path)}
    }
    @Test fun floorPivotIsInvariantUnderRotationAndUnitConversion() {
        val g=ArGroup("g","Object","unused",ModelDimensions(600f,800f,600f),Point3(4000f,2000f,-2000f),.001f,GroupTransform(.1f,0f,.2f,90f,1f))
        val r=GroupPlacement.root(g)
        val pivot=g.pivot
        val actualX=r.position.x+r.scale*pivot.z
        val actualY=r.position.y+r.scale*pivot.y
        val actualZ=r.position.z-r.scale*pivot.x
        assertEquals(.1f,actualX,1e-5f);assertEquals(0f,actualY,1e-5f);assertEquals(.2f,actualZ,1e-5f)
        assertEquals(.6f,g.physicalDimensions().width,1e-5f)
    }
    @Test fun paddedLumaPlaneIsSampledWithoutChangingBufferPosition() {
        val b=ByteBuffer.allocate(16*8)
        for(y in 0..7)for(x in 0..7)b.put(y*16+x,128.toByte())
        val pos=b.position()
        val samples=RuntimeLightController.sampleLuma(b,8,8,16,1)
        assertTrue(samples.size>100);assertEquals(pos,b.position())
        assertTrue(samples.all{kotlin.math.abs(it-128/255f)<1e-5f})
    }
    @Test fun overexposedWindowDoesNotForceAnOverbrightModel() {
        val base=FloatArray(600){.35f};val mixed=base+FloatArray(150){1f}+FloatArray(30){Float.NaN}
        assertEquals(LumaMatching.gain(base),LumaMatching.gain(mixed),.0001f)
    }
}
