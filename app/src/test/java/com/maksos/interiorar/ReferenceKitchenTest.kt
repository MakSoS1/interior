package com.maksos.interiorar

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReferenceKitchenTest {
    @Test fun importedReferenceIsMeasuredInMetersAndContainsIsland() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = ProjectStore(context)
        val kitchen = store.createReferenceSample()
        assertEquals(3.89f, kitchen.dimensions.width, 0.025f)
        assertEquals(2.62f, kitchen.dimensions.height, 0.025f)
        assertEquals(3.227f, kitchen.dimensions.depth, 0.025f)
        assertTrue(kitchen.modelUri.contains("reference_kitchen.glb"))
        assertEquals(1, store.all().count { it.id == kitchen.id })
        store.delete(kitchen)
    }
    @Test fun splitWallAndIslandAreRealSizedAssets() {
        val context: Context = RuntimeEnvironment.getApplication()
        fun dimensionsOf(name: String): ModelDimensions {
            val file = java.io.File.createTempFile("group-", ".glb")
            try {
                context.assets.open(name).use { source ->
                    file.outputStream().use { source.copyTo(it) }
                }
                return GlbInspector.inspect(file)
            } finally { file.delete() }
        }
        val wall = dimensionsOf("reference_wall.glb")
        val island = dimensionsOf("reference_island.glb")
        assertTrue(wall.width > 3.6f && wall.width < 4.5f)
        assertTrue(island.width > 2.55f && island.width < 2.9f)
        assertTrue(island.depth > 1.05f && island.depth < 2.0f)
        assertTrue(island.height > 2.5f && island.height < 2.7f)
    }

    @Test fun individualModuleOffsetsSurviveProjectReopen() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store=ProjectStore(context)
        val project=store.createReferenceSample()
        val updated=project.copy(
            islandAdjust=ModuleAdjustment(.13f,-.07f,11f),
            fridgeAdjust=ModuleAdjustment(-.04f,.09f,-22f)
        )
        store.update(updated)
        val reloaded=ProjectStore(context).all().first { it.id==project.id }
        assertEquals(.13f,reloaded.islandAdjust.xMeters,1e-5f)
        assertEquals(-.07f,reloaded.islandAdjust.zMeters,1e-5f)
        assertEquals(11f,reloaded.islandAdjust.yawDegrees,1e-5f)
        assertEquals(-.04f,reloaded.fridgeAdjust.xMeters,1e-5f)
        assertEquals(.09f,reloaded.fridgeAdjust.zMeters,1e-5f)
        assertEquals(-22f,reloaded.fridgeAdjust.yawDegrees,1e-5f)
        store.delete(reloaded)
    }

}
