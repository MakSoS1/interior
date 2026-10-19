package com.maksos.interiorar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/** These run on the installed APK. Native 3D preview is exercised, not ignored. */
class AndroidSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun galleryStartsWithoutCameraPermission() {
        compose.onNodeWithText("Загрузить GLB").assertIsDisplayed()
        compose.onNodeWithText("Тестовая кухня").assertIsDisplayed()
        compose.onNodeWithText("Кухня из твоего референса · остров + 3 стула").assertIsDisplayed()
    }
    @Test fun blenderPackageLoadsInActualNativePreviewAndReturnsToLibrary() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val zip=File(context.cacheDir,"adapter-test.zip")
        context.assets.open("adapter_demo.interior.zip").use {input -> zip.outputStream().use {input.copyTo(it)}}
        val store=ProjectStore(context)
        val assets=store.importFile(zip,"Blender test");zip.delete()
        assertEquals(2,assets.size)
        assertTrue(assets.all {it.dimensions.height in .59f..1.01f})
        compose.activityRule.scenario.recreate()
        val asset=assets.first()
        // Filament renders continuously; Compose-Espresso idling is therefore intentionally
        // not used while the native 3D preview is active.
        val device=UiDevice.getInstance(instrumentation)
        val libraryTile=device.wait(Until.findObject(By.text(asset.name)),20_000)
        assertNotNull("Imported furniture must be visible in the library",libraryTile)
        libraryTile!!.click()
        assertTrue("Native preview route did not open",
            device.wait(Until.hasObject(By.text("Разместить в AR · 1:1")),30_000))
        assertTrue("GLB must load in native Filament renderer",
            device.wait(Until.hasObject(By.text("Модель загружена")),40_000))
        Thread.sleep(1500)
        val dir=File(context.getExternalFilesDir(null),"qa").apply {mkdirs()}
        val capture=File(dir,"blender-native-preview.png")
        assertTrue("Screenshot of native scene failed",device.takeScreenshot(capture))
        val image=BitmapFactory.decodeFile(capture.path)
        assertNotNull("Screenshot could not be decoded",image)
        val colors=HashSet<Int>()
        for(y in image!!.height/5 until image.height*2/3 step 7)
            for(x in image.width/5 until image.width*4/5 step 7)colors.add(image.getPixel(x,y))
        assertTrue("3D surface unexpectedly uniform",colors.size>20)
        File(dir,"preview-check.txt").writeText(
            "model="+asset.name+"\\ndimensions="+asset.dimensions.label()+"\\ncolors="+colors.size
        )
        image.recycle()
        device.pressBack()
        assertTrue("Back navigation did not return to the library",
            device.wait(Until.hasObject(By.text("Загрузить GLB")),15_000))
        // Existing layout keeps archived asset references resolvable.
        assets.forEach {store.delete(it)}
    }
}
