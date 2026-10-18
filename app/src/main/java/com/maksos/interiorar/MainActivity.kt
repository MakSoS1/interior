package com.maksos.interiorar

import android.Manifest
import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import io.github.sceneview.SceneView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberModelInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { InteriorApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InteriorApp() {
    val context = LocalContext.current
    val store = remember(context) { ProjectStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf(store.all()) }
    var selected by remember { mutableStateOf<KitchenProject?>(null) }
    var mode by remember { mutableStateOf("projects") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var arAvailable by remember { mutableStateOf(false) }
    val refresh: () -> Unit = { projects = store.all() }
    LaunchedEffect(mode) {
        if (mode == "projects" || mode == "preview") {
            arAvailable = try { ArCoreApk.getInstance().checkAvailability(context).isSupported }
                catch (_: Exception) { false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val p = withContext(Dispatchers.IO) { store.import(uri) }
                    refresh(); selected = p; mode = "preview"
                } catch (e: Exception) { message = e.message ?: "Ошибка импорта" }
                finally { busy = false }
            }
        }
    }
    var camGranted by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    ) }
    val cameraRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        camGranted = it
        if (it) mode = "ar" else message = "Разрешите доступ к камере для AR. 3D доступен без неё."
    }
    BackHandler(mode != "projects") { mode = if (mode == "ar") "preview" else "projects" }
    Scaffold(topBar = {
        TopAppBar(title = { Text(when (mode) {
            "projects" -> "Interior AR"
            "preview" -> selected?.name ?: "3D-просмотр"
            else -> "AR · 1:1"
        }) }, navigationIcon = {
            if (mode != "projects") TextButton(onClick = {
                mode = if (mode == "ar") "preview" else "projects"
            }) { Text("← Назад") }
        })
    }, snackbarHost = {
        if (message != null) Snackbar(modifier = Modifier.padding(8.dp), action = {
            TextButton(onClick = { message = null }) { Text("Закрыть") }
        }) { Text(message ?: "") }
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when (mode) {
                "projects" -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Мебель в реальном пространстве", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Импортируйте Blender-проект в GLB, сохранив размеры в метрах.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            picker.launch(arrayOf("model/gltf-binary", "application/octet-stream", "*/*"))
                        }, enabled = !busy) { Text("Загрузить GLB") }
                        OutlinedButton(onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val p = withContext(Dispatchers.IO) { store.createSample() }
                                    refresh(); selected = p; mode = "preview"
                                } catch(e:Exception) { message = e.message ?: "Ошибка демо" }
                                finally { busy = false }
                            }
                        }, enabled = !busy) { Text("Тестовая кухня") }
                    }
                    Text(if (arAvailable) "ARCore обнаружен" else "ARCore недоступен / проверяется. 3D-просмотр работает.", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("Мои проекты: " + projects.size, style = MaterialTheme.typography.titleMedium)
                    if (projects.isEmpty()) Text("Добавьте первый проект.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(projects, key = { it.id }) { project ->
                            Card(onClick = { selected = project; mode = "preview" }, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(project.name, fontWeight = FontWeight.SemiBold)
                                        Text(project.dimensions.label(), style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { store.delete(project); refresh() }) { Text("Удалить") }
                                }
                            }
                        }
                    }
                }
                "preview" -> selected?.let { project ->
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) { ModelPreview(project.modelUri) }
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Ширина × высота × глубина: " + project.dimensions.label())
                            Text("Масштаб 1:1. Перетаскивайте пальцем для вращения.", style=MaterialTheme.typography.bodySmall)
                            Button(onClick = {
                                if (!arAvailable) message = "ARCore не подтверждён. Проверьте Google Play Services for AR."
                                else if (camGranted) mode="ar" else cameraRequest.launch(Manifest.permission.CAMERA)
                            }, modifier = Modifier.fillMaxWidth()) { Text("Разместить в AR · 1:1") }
                        }
                    }
                }
                "ar" -> selected?.let { project ->
                    ArExperience(project, onPlacementSaved = { x,z,yaw ->
                        val updated = project.copy(offsetX=x,offsetZ=z,rotationDegrees=yaw)
                        selected=updated; store.update(updated); refresh()
                    }, onError = { message=it })
                }
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun ModelPreview(location: String) {
    val engine = rememberEngine()
    val loader = rememberModelLoader(engine)
    val instance = rememberModelInstance(loader, location)
    Box(Modifier.fillMaxSize()) {
        SceneView(modifier=Modifier.fillMaxSize(), engine=engine, modelLoader=loader,
            cameraManipulator=rememberCameraManipulator()) {
            instance?.let { ModelNode(modelInstance=it, autoAnimate=false) }
        }
        if (instance == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}
