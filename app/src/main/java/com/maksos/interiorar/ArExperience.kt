package com.maksos.interiorar

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.filament.LightManager
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
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
    val frameRef = remember { arrayOfNulls<Frame>(1) }
    val sessionRef = remember { arrayOfNulls<Session>(1) }
    var first by remember { mutableStateOf<Point3?>(null) }
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var status by remember { mutableStateOf("Наведите камеру на пол и отметьте начало гарнитура") }
    var x by remember(project.id) { mutableFloatStateOf(project.offsetX) }
    var z by remember(project.id) { mutableFloatStateOf(project.offsetZ) }
    var yaw by remember(project.id) { mutableFloatStateOf(project.rotationDegrees) }
    var liveLighting by remember { mutableStateOf(true) }
    var depthEnabled by remember { mutableStateOf(true) }
    var lightStrength by remember { mutableFloatStateOf(50000f) }
    var lastSample by remember { mutableLongStateOf(0L) }
    DisposableEffect(anchor) {
        val currentAnchor = anchor
        onDispose { currentAnchor?.detach() }
    }

    Box(Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            planeRenderer = anchor == null,
            sessionConfiguration = { session, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                config.depthMode = if (depthEnabled && session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                    Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
            },
            onSessionUpdated = { session, frame ->
                sessionRef[0] = session
                frameRef[0] = frame
                if (liveLighting && frame.timestamp - lastSample > 250_000_000L) {
                    lastSample = frame.timestamp
                    val estimate = frame.lightEstimate
                    if (estimate.state == com.google.ar.core.LightEstimate.State.VALID) {
                        val rgb = estimate.environmentalHdrMainLightIntensity
                        val brightness = ((rgb[0] + rgb[1] + rgb[2]) / 3f).coerceIn(.1f, 2f)
                        lightStrength = lightStrength * .85f + brightness * 50000f * .15f
                    }
                }
            },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { event, _ ->
                    if (anchor == null) {
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
                                first = point
                                status = "Укажите вторую точку вдоль стены, не ближе 50 см"
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
                                        anchor = session.createAnchor(pose)
                                        status = "Гарнитур закреплён. Можно ходить вокруг и корректировать положение."
                                        onPlacementSaved(x, z, yaw)
                                    } catch (e: Exception) { onError(e.message ?: "Не удалось создать AR Anchor") }
                                }
                            }
                        }
                    }
                }
            )
        ) {
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
            if (liveLighting) LightNode(
                type = LightManager.Type.SUN,
                apply = { intensity(lightStrength); castShadows(true) }
            )
        }

        Surface(Modifier.align(Alignment.TopCenter).padding(12.dp), tonalElevation = 5.dp) {
            Text(status, Modifier.padding(12.dp), style=MaterialTheme.typography.bodyMedium)
        }

        Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (anchor != null) {
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
                                first = null
                                status = "Наведите камеру на пол и отметьте начало гарнитура"
                            }
                        }
                    }
                }
            }
            Surface(tonalElevation=5.dp,shape=MaterialTheme.shapes.medium) {
                Row(Modifier.padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("Свет",style=MaterialTheme.typography.bodySmall)
                    Switch(checked=liveLighting,onCheckedChange={liveLighting=it})
                    Spacer(Modifier.width(8.dp))
                    Text("Глубина",style=MaterialTheme.typography.bodySmall)
                    Switch(checked=depthEnabled,onCheckedChange={depthEnabled=it})
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
