package com.maksos.interiorar

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device-level smoke test: no Compose test-clock / Espresso idling dependency.
 * Filament continuously renders, so Compose idle waiting is not reliable.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)

    @Test
    fun galleryStartsWithoutCameraPermission() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("GLB import is missing", device.wait(Until.hasObject(By.text("Загрузить GLB")), 20_000))
            assertTrue("Test kitchen is missing", device.hasObject(By.text("Тестовая кухня")))
            assertTrue("Reference kitchen is missing",
                device.hasObject(By.text("Кухня из твоего референса · остров + 3 стула")))
        }
    }

    @Test
    fun importedBlenderGroupDisplaysInNative3DPreview() {
        val context=instrumentation.targetContext
        val pack=File(context.cacheDir,"android-blender-qa.interior.zip")
        context.assets.open("adapter_demo.interior.zip").use { input ->
            pack.outputStream().use { output -> input.copyTo(output) }
        }
        val store=ProjectStore(context)
        val assets=try { store.importFile(pack,"Blender QA") } finally { pack.delete() }
        assertEquals("Expected two independent furniture groups",2,assets.size)
        assertTrue("Metric model dimensions incorrect",
            assets.all { it.dimensions.height in .59f..1.01f })
        val selected=assets.first()
        val directory=File(context.getExternalFilesDir(null),"qa").apply {mkdirs()}

        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("Gallery not visible",
                device.wait(Until.hasObject(By.text("Загрузить GLB")),20_000))
            val tile=device.wait(Until.findObject(By.text(selected.name)),20_000)
                ?: error("Imported Blender group not listed: "+selected.name)
            val rectangle=tile.visibleBounds
            assertTrue("Imported group is off screen",rectangle.width()>0 && rectangle.height()>0)
            // Actual pointer coordinates go through the Card's click dispatch rather than
            // accessibility action on its nonclickable child text.
            device.click(rectangle.centerX(),rectangle.centerY())

            val preview=device.wait(Until.hasObject(By.text("Разместить в AR · 1:1")),35_000)
            if(!preview) {
                device.takeScreenshot(File(directory,"navigation-failed.png"))
                device.dumpWindowHierarchy(File(directory,"navigation-failed.xml"))
            }
            assertTrue("Preview screen didn't open",preview)
            val loaded=device.wait(Until.hasObject(By.text("Модель загружена")),45_000)
            if(!loaded) {
                device.takeScreenshot(File(directory,"render-failed.png"))
                device.dumpWindowHierarchy(File(directory,"render-failed.xml"))
            }
            assertTrue("GLB didn't load in Filament scene",loaded)
            Thread.sleep(1400)
            val screenshot=File(directory,"blender-native-preview.png")
            assertTrue("Screenshot could not be taken",device.takeScreenshot(screenshot))
            val bitmap=BitmapFactory.decodeFile(screenshot.path)
                ?: error("Native-renderer screenshot couldn't be decoded")
            val sampled=HashSet<Int>()
            for(y in bitmap.height/5 until bitmap.height*2/3 step 7)
                for(x in bitmap.width/5 until bitmap.width*4/5 step 7)
                    sampled.add(bitmap.getPixel(x,y))
            File(directory,"preview-check.txt").writeText(
                "asset="+selected.name+"\\ndimensions="+selected.dimensions.label()+
                "\\nuniqueColors="+sampled.size+"\\n")
            Log.i("Interior3DQA","preview unique colors="+sampled.size)
            // The emulator may disappear before adb pull on instrumentation failure.
            // Emit a small JPEG thumbnail as bounded Base64 logcat chunks, allowing
            // exact visual diagnosis from GitHub Actions log artifacts.
            val thumbnail=Bitmap.createScaledBitmap(bitmap,240,530,true)
            val bytes=ByteArrayOutputStream()
            thumbnail.compress(Bitmap.CompressFormat.JPEG,70,bytes)
            val encoded=Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP)
            val parts=encoded.chunked(900)
            parts.forEachIndexed { index,part ->
                Log.i("Interior3DQA","FRAME_PART "+index+"/"+parts.size+" "+part)
            }
            thumbnail.recycle()
            bitmap.recycle()
            assertTrue("Native 3D preview appears uniform", sampled.size>20)
            device.pressBack()
            assertTrue("Back navigation failed",
                device.wait(Until.hasObject(By.text("Загрузить GLB")),15_000))
        }
        for(a in assets) store.delete(a)
    }
}
