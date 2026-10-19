package com.maksos.interiorar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import android.graphics.Bitmap

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
        compose.waitUntil(20_000){compose.onAllNodesWithText(asset.name).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText(asset.name).performScrollTo().performClick()
        compose.waitUntil(30_000){compose.onAllNodesWithTag("model-ready").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Разместить в AR · 1:1").assertIsDisplayed()
        // Wait for actual native surface frames, then retain a screenshot for visual review.
        Thread.sleep(1800)
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        val image=bitmap!!
        val colors=HashSet<Int>()
        for(y in image.height/5 until image.height*2/3 step 7)
            for(x in image.width/5 until image.width*4/5 step 7)colors.add(image.getPixel(x,y))
        assertTrue("3D surface unexpectedly uniform",colors.size>20)
        val dir=File(context.getExternalFilesDir(null),"qa").apply {mkdirs()}
        File(dir,"blender-native-preview.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
        File(dir,"preview-check.txt").writeText("model="+asset.name+"\ndimensions="+asset.dimensions.label()+"\ncolors="+colors.size)
        image.recycle()
        compose.onNodeWithText("← Назад").performClick()
        compose.onNodeWithText("Загрузить GLB").assertIsDisplayed()
        // Existing layout keeps archived asset references resolvable.
        assets.forEach {store.delete(it)}
    }
}
