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
@Config(sdk=[35])
class SceneCatalogTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private fun reset() {
        File(context.filesDir,"ar_documents_v3.json").delete()
        File(context.filesDir,"ar_documents_v3.json.bak").delete()
        context.getSharedPreferences("projects_v1",Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun legacyThreeModuleAdjustmentsSurviveMigration() {
        reset()
        val old=ProjectStore(context)
        val p=old.createReferenceSample().copy(offsetX=.12f,offsetZ=-.08f,rotationDegrees=15f,
            islandAdjust=ModuleAdjustment(.2f,-.3f,30f),fridgeAdjust=ModuleAdjustment(.01f,.04f,-10f))
        old.update(p)
        val migrated=SceneCatalog(context).list().first { it.id==p.id }
        assertEquals(3,migrated.groups.size)
        val wall=migrated.groups.first { it.id=="wall" }
        val island=migrated.groups.first { it.id=="island" }
        val fridge=migrated.groups.first { it.id=="fridge" }
        assertEquals(.12f,wall.transform.x,1e-5f);assertEquals(15f,wall.transform.yaw,1e-5f)
        assertEquals(.2f,island.transform.x,1e-5f);assertEquals(-.3f,island.transform.z,1e-5f)
        assertEquals(-10f,fridge.transform.yaw,1e-5f)
        assertTrue(old.all().any { it.id==p.id })
    }
    @Test fun invalidImportDoesNotEraseEarlierProjects() {
        reset()
        val catalog=SceneCatalog(context);val good=catalog.sample()
        val file=File(context.cacheDir,"not-model.glb").apply{writeText("not a GLB")}
        assertThrows(IllegalArgumentException::class.java){catalog.importFile(file,"broken")}
        assertTrue(SceneCatalog(context).list().any{it.id==good.id})
    }
    @Test fun savedSettingsAndGroupCorrectionsRoundTrip() {
        reset()
        val catalog=SceneCatalog(context)
        val base=catalog.demoPack()
        val changed=base.copy(lighting=LightingSettings(true,-.6f,false),groups=base.groups.map{it.copy(transform=GroupTransform(.1f,1.2f,-.2f,45f,.5f))})
        catalog.put(changed)
        assertEquals(changed,SceneCatalog(context).list().first{it.id==changed.id})
    }
}
