package com.maksos.interiorar

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.scene.SceneUnderstanding
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import kotlin.math.sin
import kotlin.math.cos

@Composable
fun ArExperience(project: KitchenProject, onPlacementSaved: (Float, Float, Float)->Unit, onError: (String)->Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val model = rememberModelInstance(modelLoader, project.modelUri)
    val fridgeModel = rememberModelInstance(modelLoader, "fridge_opposite_wall.glb")
    val frameRef = remember { arrayOfNulls<Frame>(1) }
    val sessionRef = remember { arrayOfNulls<Session>(1) }
    var first by remember { mutableStateOf<Point3?>(null) }
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var fridgeAnchor by remember { mutableStateOf<Anchor?>(null) }
    var placingFridge by remember { mutableStateOf(false) }
    var selectedFloor by remember { mutableStateOf<Plane?>(null) }
    var status by remember { mutableStateOf("Наведите камеру на пол и отметьте начало гарнитура") }
    var x by remember(project.id) { mutableFloatStateOf(project.offsetX) }
    var z by remember(project.id) { mutableFloatStateOf(project.offsetZ) }
    var yaw by remember(project.id) { mutableFloatStateOf(project.rotationDegrees) }
    var depthEnabled by remember { mutableStateOf(true) }
    var depthSupported by remember { mutableStateOf(false) }
    var floorShadows by remember { mutableStateOf(true) }
    DisposableEffect(anchor) {
        val currentAnchor = anchor
        onDispose { currentAnchor?.detach() }
    }
    DisposableEffect(fridgeAnchor) {
        val currentAnchor = fridgeAnchor
        onDispose { currentAnchor?.detach() }
    }

    Box(Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            planeRenderer = anchor == null || placingFridge,
            sceneUnderstanding = SceneUnderstanding(
                occlusion = depthEnabled && depthSupported,
                lighting = true,
                physics = false,
                planeVisualization = anchor == null || placingFridge
            ),
            depthMode = if (depthEnabled && depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED,
            onSessionCreated = { session ->
                depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            },
            sessionConfiguration = { _, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            },
            onSessionFailed = { error ->
                onError(error.message ?: "ARCore не запустился на этом устройстве")
            },
            onTrackingFailureChanged = { reason ->
                if (reason != null && reason != com.google.ar.core.TrackingFailureReason.NONE) {
                    status = when(reason) {
                        com.google.ar.core.TrackingFailureReason.INSUFFICIENT_LIGHT -> "Недостаточно света для отслеживания"
                        com.google.ar.core.TrackingFailureReason.EXCESSIVE_MOTION -> "Двигайте телефон медленнее"
                        com.google.ar.core.TrackingFailureReason.INSUFFICIENT_FEATURES -> "Наведите камеру на поверхность с деталями"
                        com.google.ar.core.TrackingFailureReason.CAMERA_UNAVAILABLE -> "Камера недоступна: закройте другие приложения"
                        else -> "AR-трекинг приостановлен"
                    }
                }
            },
            onSessionUpdated = { session, frame ->
                sessionRef[0] = session
                frameRef[0] = frame
            },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { event, _ ->
                    if (anchor == null || placingFridge) {
                        val hit = frameRef[0]?.hitTest(event)?.firstOrNull {
                            val p = it.trackable as? Plane
                            p != null && p.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                                p.trackingState == TrackingState.TRACKING && p.isPoseInPolygon(it.hitPose)
                        }
                        if (hit == null) {
                            status = "Пол пока не найден — перемещайте телефон плавно"
                        } else {
                            val t = hit.hitPose.translation
                            val point = Point3(t[0], t[1], t[2])
                            if (first == null) {
                                if (!placingFridge) selectedFloor = hit.trackable as? Plane
                                first = point
                                status = if (placingFridge)
                                    "Отметьте вторую точку вдоль стены холодильника (от 50 см)"
                                else "Укажите вторую точку вдоль стены, не ближе 50 см"
                            } else {
                                val direction = PlacementMath.wallFromPoints(first!!, point)
                                val session = sessionRef[0]
                                if (direction == null || session == null) {
                                    status = "Отметьте вторую точку дальше от первой"
                                } else {
                                    val half = direction.yawRadians / 2f
                                    val a = first!!
                                    val pose = Pose.makeTranslation(a.x, a.y, a.z).compose(
                                        Pose.makeRotation(0f, sin(half), 0f, cos(half))
                                    )
                                    try {
                                        if (placingFridge) {
                                            fridgeAnchor = session.createAnchor(pose)
                                            placingFridge = false
                                            first = null
                                            status = "Холодильник и кухонный остров теперь закреплены независимо. Обойдите оба."
                                        } else {
                                            anchor = session.createAnchor(pose)
                                            status = "Гарнитур закреплён. Можно ходить вокруг и корректировать положение."
                                            onPlacementSaved(x, z, yaw)
                                        }
                                    } catch (e: Exception) { onError(e.message ?: "Не удалось создать AR Anchor") }
                                }
                            }
                        }
                    }
                }
            )
        ) {
            // SceneView's native Filament shadow receiver darkens only the captured
            // floor under virtual cabinets; hide the plane grid after placement
            // to avoid two overlapping receivers on one physical surface.
            selectedFloor?.let { floor ->
                if (anchor != null && floorShadows) {
                    key(floor) { ShadowReceiverPlane(plane = floor, shadowIntensity = .54f) }
                }
            }
            anchor?.let { current ->
                AnchorNode(anchor = current) {
                    model?.let { instance ->
                        ModelNode(
                            modelInstance = instance,
                            autoAnimate = false,
                            position = Position(x = x, y = 0f, z = z),
                            rotation = Rotation(y = yaw)
                        )
                    }
                }
            }
            fridgeAnchor?.let { current ->
                AnchorNode(anchor = current) {
                    fridgeModel?.let { instance ->
                        ModelNode(modelInstance = instance, autoAnimate = false)
                    }
                }
            }
        }

        Surface(Modifier.align(Alignment.TopCenter).padding(12.dp), tonalElevation = 5.dp) {
            Text(status, Modifier.padding(12.dp), style=MaterialTheme.typography.bodyMedium)
        }

        Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (anchor != null) {
                if (project.modelUri.contains("reference_kitchen") && fridgeAnchor == null) {
                    Button(
                        enabled = !placingFridge && fridgeModel != null,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            placingFridge = true
                            first = null
                            status = "Наведите камеру на пол у НЕПРОЗРАЧНОЙ стены напротив панорамных окон"
                        }
                    ) { Text(if (placingFridge) "Размещаем холодильник…" else "Добавить холодильник у противоположной стены") }
                }
                if (fridgeAnchor != null) {
                    OutlinedButton(
                        onClick = {
                            fridgeAnchor = null
                            placingFridge = true
                            first = null
                            status = "Повторно отметьте две точки у стены холодильника"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Переместить холодильник отдельно") }
                }
                Surface(tonalElevation = 5.dp, shape=MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(8.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            SmallButton("← 1 см") { x -= .01f; onPlacementSaved(x,z,yaw) }
                            SmallButton("1 см →") { x += .01f; onPlacementSaved(x,z,yaw) }
                            SmallButton("↑ 1 см") { z += .01f; onPlacementSaved(x,z,yaw) }
                            SmallButton("↓ 1 см") { z -= .01f; onPlacementSaved(x,z,yaw) }
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            SmallButton("↺ 5°") { yaw=PlacementMath.normalizeDegrees(yaw-5f); onPlacementSaved(x,z,yaw) }
                            SmallButton("↻ 5°") { yaw=PlacementMath.normalizeDegrees(yaw+5f); onPlacementSaved(x,z,yaw) }
                            SmallButton("180°") { yaw=PlacementMath.normalizeDegrees(yaw+180f); onPlacementSaved(x,z,yaw) }
                            SmallButton("Переставить") {
                                anchor = null
                                fridgeAnchor = null
                                placingFridge = false
                                selectedFloor = null
                                first = null
                                status = "Наведите камеру на пол и отметьте начало гарнитура"
                            }
                        }
                    }
                }
            }
            Surface(tonalElevation=5.dp,shape=MaterialTheme.shapes.medium) {
                Row(Modifier.padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("Живой свет: HDR",style=MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    Text(if (depthSupported) "Depth Occlusion" else "Depth не поддерживается",style=MaterialTheme.typography.bodySmall)
                    Switch(checked=depthEnabled && depthSupported,onCheckedChange={depthEnabled=it},enabled=depthSupported)
                    Text("Тени",style=MaterialTheme.typography.bodySmall)
                    Switch(checked=floorShadows,onCheckedChange={floorShadows=it})
                }
            }
        }
    }
}

@Composable
private fun SmallButton(text:String,onClick:()->Unit) {
    OutlinedButton(onClick=onClick,contentPadding=PaddingValues(horizontal=8.dp,vertical=2.dp)) {
        Text(text,style=MaterialTheme.typography.labelSmall)
    }
}
