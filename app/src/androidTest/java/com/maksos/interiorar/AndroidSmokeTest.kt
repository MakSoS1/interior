package com.maksos.interiorar

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** These tests run in the installed APK, not against a ZIP listing of its contents. */
class AndroidSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun screen(name:String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val dir=File(instrumentation.targetContext.getExternalFilesDir(null),"qa").apply{mkdirs()}
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        File(dir,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    private fun waitFor(text:String,timeout:Long=30000) {
        compose.waitUntil(timeout){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun mainScreenShowsGenericImportAndExample() {
        compose.onNodeWithText("Импорт GLB / пакета").assertIsDisplayed()
        compose.onNodeWithText("Пример адаптера").assertIsDisplayed()
        screen("01-library.png")
    }
    @Test fun importedPackageLoadsActualGlbAndCanDuplicateAGroup() {
        compose.onNodeWithText("Пример адаптера").performClick()
        waitFor("Расставить в AR")
        waitFor("3D-модель загружена")
        compose.onNodeWithText("Cabinet_60cm").assertIsDisplayed()
        compose.onNodeWithText("Two_chairs").assertIsDisplayed()
        screen("02-cabinet-loaded.png")
        compose.onNodeWithText("Two_chairs").performClick()
        waitFor("3D-модель загружена")
        compose.onNodeWithText("Копия").performClick()
        waitFor("Two_chairs · копия")
        compose.onNodeWithText("Two_chairs · копия").assertExists()
        screen("03-chairs-duplicated.png")
        compose.onNodeWithText("← Назад").performClick()
        compose.onNodeWithText("Импорт GLB / пакета").assertIsDisplayed()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(SceneCatalog(context).list().any{it.groups.size==3 && it.groups.last().label.contains("копия")})
    }
    @Test fun packagedFilesRoundTripOnRealAndroidFileSystem() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val doc=SceneCatalog(context).demoPack()
        val output=File(context.cacheDir,"instrumented-roundtrip.zip")
        output.outputStream().use{ScenePackAdapter.export(doc,it)}
        val again=ScenePackAdapter.importFile(output,"roundtrip",File(context.cacheDir,"instrumented-assets"))
        assertEquals(2,again.groups.size)
        assertEquals(doc.groups[0].physicalDimensions().height,again.groups[0].physicalDimensions().height,.0001f)
        assertEquals(AssetDigest.sha256(File(doc.groups[1].file)),AssetDigest.sha256(File(again.groups[1].file)))
        output.delete()
    }
}
