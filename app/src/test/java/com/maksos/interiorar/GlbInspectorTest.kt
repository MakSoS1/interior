package com.maksos.interiorar

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GlbInspectorTest {
    private fun sampleFile(): File {
        val context: Context = RuntimeEnvironment.getApplication()
        return File.createTempFile("test-kitchen", ".glb").apply {
            deleteOnExit()
            context.assets.open("demo_kitchen.glb").use { input -> outputStream().use { input.copyTo(it) } }
        }
    }
    @Test fun originalKitchenHasPhysicalDimensions() {
        val dimensions = GlbInspector.inspect(sampleFile())
        assertEquals(2.60f, dimensions.width, 0.001f)
        assertTrue(dimensions.height in 2.05f..2.20f)
        assertTrue(dimensions.depth in .63f..0.85f)
    }
    @Test fun malformedGlbIsRejected() {
        val file = File.createTempFile("invalid", ".glb")
        try {
            file.writeText("not a valid glb")
            assertThrows(IllegalArgumentException::class.java) { GlbInspector.inspect(file) }
        } finally { file.delete() }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProjectStoreTest {
    @Test fun demoAndPlacementArePreservedAfterReopen() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("projects_v1",Context.MODE_PRIVATE).edit().clear().commit()
        val store=ProjectStore(context)
        val demo=store.createSample()
        assertEquals(2.60f,demo.dimensions.width,0.001f)
        store.update(demo.copy(offsetX=.11f,offsetZ=-.04f,rotationDegrees=15f))
        val reopened=ProjectStore(context).all().first { it.id==demo.id }
        assertEquals(.11f,reopened.offsetX,0.0001f)
        assertEquals(-.04f,reopened.offsetZ,0.0001f)
        assertEquals(15f,reopened.rotationDegrees,0.0001f)
        store.delete(reopened)
        assertFalse(ProjectStore(context).all().any { it.id==demo.id })
    }
}
