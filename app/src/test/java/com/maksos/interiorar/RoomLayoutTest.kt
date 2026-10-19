package com.maksos.interiorar

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.PI

class RoomMathTest {
    @Test fun roomBasisRoundTripsAfter90DegreeTurn() {
        val origin=Point3(2f,0f,3f);val point=Point3(3f,.9f,5f);val yaw=(PI/2).toFloat()
        val local=RoomMath.relative(origin,yaw,point);val returned=RoomMath.world(origin,yaw,local)
        assertEquals(point.x,returned.x,1e-5f);assertEquals(point.y,returned.y,1e-5f);assertEquals(point.z,returned.z,1e-5f)
    }
    @Test fun duplicateOfSameGLBIsAnIndependentInstance() {
        val initial=RoomItem(assetId="chair",x=2f,placed=true);val layout=RoomLayout(listOf(initial)).duplicate(initial.id)
        assertEquals(2,layout.items.size);assertNotEquals(layout.items[0].id,layout.items[1].id)
        assertEquals(layout.items[0].assetId,layout.items[1].assetId);assertFalse(layout.items[1].placed)
        assertEquals(2f,layout.replace(layout.items[1].copy(x=8f)).items[0].x,0f)
    }
    @Test fun invalidScaleIsRejected() {
        assertThrows(Exception::class.java){RoomItem(assetId="a",scale=Float.NaN).checked()}
        assertThrows(Exception::class.java){RoomItem(assetId="a",scale=0f).checked()}
    }
    @Test fun cameraWindowIsNotUsedAsAFullBrightnessMultiplier() {
        assertEquals(1f,CameraBrightness.gain(IntArray(30){255}),1e-5f)
        assertTrue(CameraBrightness.gain(IntArray(30){80}) in .90f..1.02f)
    }
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class LayoutPersistenceTest {
    @Test fun roomLocalLayoutAndLightSettingsSurviveReload() {
        val context=RuntimeEnvironment.getApplication<android.app.Application>();val store=RoomLayoutStore(context)
        val room=RoomLayout(listOf(RoomItem(assetId="one",x=1f,y=.9f,z=2f,yaw=45f,placed=true)),LightSettings(true,-.7f))
        store.save("test-room",room)
        assertEquals(room,RoomLayoutStore(context).load("test-room",emptyList()))
    }
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ArchivedAssetTest {
    @Test fun hidingALibraryItemDoesNotBreakAnExistingRoom() {
        val context=RuntimeEnvironment.getApplication<android.app.Application>();val store=ProjectStore(context)
        val asset=store.createSample();val room=RoomLayout(listOf(RoomItem(assetId=asset.id,placed=true)))
        val layouts=RoomLayoutStore(context);layouts.save("archive-test",room);store.delete(asset)
        assertFalse(store.all().any {it.id==asset.id})
        assertTrue(store.catalogIncludingArchived().any {it.id==asset.id && it.archived})
        assertEquals(asset.id,layouts.load("archive-test",emptyList()).items.first().assetId)
        store.createSample();assertTrue(ProjectStore(context).catalogIncludingArchived().any {it.id==asset.id})
    }
}
