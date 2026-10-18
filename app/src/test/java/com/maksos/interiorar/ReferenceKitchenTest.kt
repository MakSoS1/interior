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
}
