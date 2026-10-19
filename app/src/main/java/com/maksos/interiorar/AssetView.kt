package com.maksos.interiorar

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import io.github.sceneview.*
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.model
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import android.net.Uri

sealed interface AssetLoad {
    data object Loading:AssetLoad
    data class Ready(val model:ModelInstance):AssetLoad
    data class Failed(val reason:String):AssetLoad
}

/** Each call keyed by a scene instance id owns a separate glTF allocation. */
@Composable
fun loadAsset(loader:ModelLoader,asset:KitchenProject):AssetLoad {
    val loaded by produceState<AssetLoad>(AssetLoad.Loading,loader,asset.modelUri) {
        value=try {
            val buffer=withContext(Dispatchers.IO) {
                val uri=Uri.parse(asset.modelUri)
                require(uri.scheme=="file") {"Модель должна быть импортирована в библиотеку"}
                val file=File(uri.path ?: error("Не найден путь модели"))
                GlbInspector.inspect(file)
                ByteBuffer.wrap(file.readBytes())
            }
            AssetLoad.Ready(loader.createModelInstance(buffer) ?: error("Рендерер не поддерживает этот GLB"))
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {AssetLoad.Failed(e.message ?: "Ошибка GLB")}
    }
    val model=(loaded as? AssetLoad.Ready)?.model
    DisposableEffect(loader,model) {onDispose {model?.let {loader.destroyModel(it.model)}}}
    return loaded
}

@Composable
fun AssetPreview(asset:KitchenProject) {
    key(asset.id) {
        val engine=rememberEngine();val loader=rememberModelLoader(engine)
        val loaded=loadAsset(loader,asset)
        Box(Modifier.fillMaxSize()) {
            SceneView(modifier=Modifier.fillMaxSize(),engine=engine,modelLoader=loader,
                autoCenterContent=true,autoFitContent=true,
                // autoFitContent controls the camera only when no orbit manipulator owns it.
                // Static framing first; restoring orbit with explicit target is a separate step.
                cameraManipulator=null) {
                (loaded as? AssetLoad.Ready)?.model?.let {ModelNode(modelInstance=it,autoAnimate=false)}
            }
            when(loaded) {
                AssetLoad.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).testTag("model-loading"))
                is AssetLoad.Failed -> Surface(Modifier.align(Alignment.Center)) {Text((loaded as AssetLoad.Failed).reason)}
                is AssetLoad.Ready -> Text("Модель загружена",modifier=Modifier.align(Alignment.TopStart).testTag("model-ready"),style=MaterialTheme.typography.labelSmall)
            }
        }
    }
}
