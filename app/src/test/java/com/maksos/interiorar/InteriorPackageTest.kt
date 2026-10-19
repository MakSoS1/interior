package com.maksos.interiorar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class InteriorPackageTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private fun zip(path:String="models/chair.glb",units:String="m",secondBad:Boolean=false,hash:String?=null):File {
        val f=File.createTempFile("group-",".zip",context.cacheDir)
        val one=JSONObject().put("id","chair").put("name","Chair group").put("file",path)
        if(hash!=null)one.put("sha256",hash)
        val items=JSONArray().put(one)
        if(secondBad)items.put(JSONObject().put("id","bad").put("file","models/bad.glb"))
        val m=JSONObject().put("format","interior-ar").put("version",1).put("units",units).put("assets",items)
        ZipOutputStream(f.outputStream()).use {z ->
            z.putNextEntry(ZipEntry("manifest.json"));z.write(m.toString().toByteArray());z.closeEntry()
            z.putNextEntry(ZipEntry(path));context.assets.open("demo_kitchen.glb").use {it.copyTo(z)};z.closeEntry()
            if(secondBad){z.putNextEntry(ZipEntry("models/bad.glb"));z.write(ByteArray(80));z.closeEntry()}
        }
        return f
    }
    @Test fun normalGroupImportsAndRetainsRealMetres() {
        val f=zip();val store=ProjectStore(context);val result=store.importFile(f,"Set");f.delete()
        assertEquals(1,result.size);assertEquals("Chair group",result.first().name)
        assertEquals(2.6f,result.first().dimensions.width,.001f)
        assertTrue(File(android.net.Uri.parse(result.first().modelUri).path!!).isFile)
    }
    @Test fun failedSecondAssetCannotPartiallyImportABatch() {
        val store=ProjectStore(context);val before=store.all().map {it.id};val f=zip(secondBad=true)
        assertThrows(Exception::class.java){store.importFile(f,"Bad")}
        assertEquals(before,store.all().map {it.id});f.delete()
    }
    @Test fun zipTraversalCannotWriteOutsideStagingArea() {
        val f=zip("../escaped.glb");val dest=File(context.cacheDir,"stage-"+System.nanoTime())
        assertThrows(Exception::class.java){InteriorPackage.unpack(f,dest)}
        assertFalse(File(context.cacheDir,"escaped.glb").exists());f.delete();dest.deleteRecursively()
    }
    @Test fun nonMetricPackageRejectedRatherThanSilentlyRescaled() {
        val f=zip(units="cm");val dest=File(context.cacheDir,"units-test")
        assertThrows(Exception::class.java){InteriorPackage.unpack(f,dest)};f.delete();dest.deleteRecursively()
    }
    @Test fun wrongChecksumRejected() {
        val f=zip(hash="0".repeat(64));val dest=File(context.cacheDir,"hash-test")
        assertThrows(Exception::class.java){InteriorPackage.unpack(f,dest)};f.delete();dest.deleteRecursively()
    }
}
