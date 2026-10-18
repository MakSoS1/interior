package com.maksos.interiorar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
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
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import kotlin.math.cos
import kotlin.math.sin

/**
 * Metric AR placement: an ARCore world-coordinate floor raycast under a fixed
 * centre reticle, three confirmed floor points (A/B/C), and one independent
 * ARCore Anchor per furniture assembly. All objects keep local GLB scale 1.0.
 *
 * An ordinary MP4 recording contains no ARCore camera poses; it is not used to
 * claim that the real room has been reconstructed or measured.
 */
@Composable
fun ArExperience(
    project: KitchenProject,
    onPlacementSaved: (PlacementTarget, Float, Float, Float) -> Unit,
    onError: (String) -> Unit
) {
    val reference = project.modelUri.contains("reference_kitchen")
    val engine = rememberEngine()
    val loader = rememberModelLoader(engine)
    val wallModel = rememberModelInstance(
        loader, if (reference) "reference_wall.glb" else project.modelUri
    )
    val islandModel = rememberModelInstance(loader, "reference_island.glb")
    val fridgeModel = rememberModelInstance(loader, "fridge_opposite_wall.glb")
    val anchors = remember(project.id) { mutableStateMapOf<PlacementTarget, Anchor>() }
    val adjustments = remember(project.id) {
        mutableStateMapOf(
            PlacementTarget.WALL to ModuleAdjustment(project.offsetX,project.offsetZ,project.rotationDegrees),
            PlacementTarget.ISLAND to project.islandAdjust,
            PlacementTarget.FRIDGE to project.fridgeAdjust
        )
    }

    var activeTarget by remember(project.id) { mutableStateOf<PlacementTarget?>(PlacementTarget.WALL) }
    var selectedTarget by remember(project.id) { mutableStateOf(PlacementTarget.WALL) }
    var machine by remember(project.id) { mutableStateOf(FloorCalibrationMachine(PlacementTarget.WALL)) }
    var floorPlane by remember { mutableStateOf<Plane?>(null) }
    var tracking by remember { mutableStateOf(false) }
    var canUseDepth by remember { mutableStateOf(false) }
    var useDepth by remember { mutableStateOf(true) }
    var shadows by remember { mutableStateOf(true) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var status by remember {
        mutableStateOf("Поставьте прицел на ПОЛ в левом заднем углу мебели и зафиксируйте A")
    }

    val currentFrame = remember { arrayOfNulls<Frame>(1) }
    val currentSession = remember { arrayOfNulls<Session>(1) }
    DisposableEffect(project.id) {
        onDispose { anchors.values.forEach { it.detach() }; anchors.clear() }
    }

    fun begin(target: PlacementTarget) {
        activeTarget = target
        selectedTarget = target
        machine = FloorCalibrationMachine(target)
        status = when (target) {
            PlacementTarget.WALL -> "Гарнитур: направьте прицел на пол у левого края стены → A"
            PlacementTarget.ISLAND -> "ОСТРОВ: отметьте на полу его ЗАДНИЙ ЛЕВЫЙ угол A; не верх столешницы"
            PlacementTarget.FRIDGE -> "Холодильник: точка A на полу у стены напротив панорамных окон"
        }
    }

    fun capturePoint() {
        val target = activeTarget ?: return
        val frame = currentFrame[0]
        val session = currentSession[0]
        if (frame == null || session == null || !tracking || viewport.width < 1 || viewport.height < 1) {
            status = "Подождите, пока ARCore восстановит отслеживание камеры и обнаружит пол"
            return
        }
        // Centre-targeted pixel ray, not an arbitrary touch on the UI overlay.
        val hit = frame.hitTest(viewport.width / 2f, viewport.height / 2f).firstOrNull {
            val plane = it.trackable as? Plane
            plane != null && plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                plane.trackingState == TrackingState.TRACKING &&
                plane.isPoseInPolygon(it.hitPose)
        }
        if (hit == null) {
            status = "Не найдена горизонтальная плоскость под прицелом. Подойдите к полу и поводите камерой"
            return
        }
        val t = hit.hitPose.translation
        val update = machine.add(Point3(t[0], t[1], t[2]))
        when (update) {
            is CalibrationUpdate.NeedNext -> {
                status = update.instruction + " (" + update.pointsCollected + "/3)"
                if (update.pointsCollected == 1 && target == PlacementTarget.WALL)
                    floorPlane = hit.trackable as? Plane
            }
            is CalibrationUpdate.Invalid -> status = update.reason
            is CalibrationUpdate.Ready -> {
                val cal = update.calibration
                val half = cal.yawRadians / 2f
                val pose = Pose.makeTranslation(cal.origin.x, cal.origin.y, cal.origin.z)
                    .compose(Pose.makeRotation(0f, sin(half), 0f, cos(half)))
                try {
                    val newAnchor = session.createAnchor(pose)
                    anchors.remove(target)?.detach()
                    anchors[target] = newAnchor
                    activeTarget = null
                    selectedTarget = target
                    status = when (target) {
                        PlacementTarget.WALL -> "Гарнитур привязан к полу. Теперь разместите остров отдельными точками."
                        PlacementTarget.ISLAND -> "Остров стоит на выбранном полу. Проверьте со всех сторон."
                        PlacementTarget.FRIDGE -> "Холодильник привязан к противоположной стене."
                    }
                } catch(e: Exception) {
                    status = "Не удалось создать Anchor: " + (e.message ?: "ошибка ARCore")
                }
            }
        }
    }

    fun move(dx: Float=0f, dz: Float=0f, dyaw: Float=0f) {
        val p = adjustments[selectedTarget] ?: ModuleAdjustment()
        val corrected = p.adjusted(dx,dz,dyaw)
        adjustments[selectedTarget] = corrected
        onPlacementSaved(selectedTarget, corrected.xMeters, corrected.zMeters, corrected.yawDegrees)
    }

    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = loader,
            planeRenderer = activeTarget != null,
            sceneUnderstanding = SceneUnderstanding(
                occlusion = useDepth && canUseDepth,
                lighting = true,
                physics = false,
                planeVisualization = activeTarget != null
            ),
            depthMode = if (useDepth && canUseDepth)
                Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED,
            onSessionCreated = { session ->
                canUseDepth = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            },
            sessionConfiguration = { _, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            },
            onSessionFailed = { e -> onError(e.message ?: "ARCore не запустился") },
            onSessionUpdated = { session, frame ->
                currentSession[0] = session
                currentFrame[0] = frame
                val isTracking = frame.camera.trackingState == TrackingState.TRACKING
                if (isTracking != tracking) tracking = isTracking
            }
        ) {
            floorPlane?.let { floor ->
                if (anchors.containsKey(PlacementTarget.WALL) && shadows)
                    ShadowReceiverPlane(plane=floor, shadowIntensity=.52f)
            }
            anchors[PlacementTarget.WALL]?.let { anchored ->
                AnchorNode(anchor=anchored) {
                    wallModel?.let { asset ->
                        val p = adjustments[PlacementTarget.WALL] ?: ModuleAdjustment()
                        ModelNode(modelInstance=asset,autoAnimate=false,
                            position=Position(x=p.xMeters,y=0f,z=p.zMeters),
                            rotation=Rotation(y=p.yawDegrees))
                    }
                }
            }
            if (reference) {
                anchors[PlacementTarget.ISLAND]?.let { anchored ->
                    AnchorNode(anchor=anchored) {
                        islandModel?.let { asset ->
                            val p = adjustments[PlacementTarget.ISLAND] ?: ModuleAdjustment()
                            ModelNode(modelInstance=asset,autoAnimate=false,
                                position=Position(x=p.xMeters,y=0f,z=p.zMeters),
                                rotation=Rotation(y=p.yawDegrees))
                        }
                    }
                }
                anchors[PlacementTarget.FRIDGE]?.let { anchored ->
                    AnchorNode(anchor=anchored) {
                        fridgeModel?.let { asset ->
                            val p = adjustments[PlacementTarget.FRIDGE] ?: ModuleAdjustment()
                            ModelNode(modelInstance=asset,autoAnimate=false,
                                position=Position(x=p.xMeters,y=0f,z=p.zMeters),
                                rotation=Rotation(y=p.yawDegrees))
                        }
                    }
                }
            }
        }
        if (activeTarget != null) {
            Box(Modifier.align(Alignment.Center),contentAlignment=Alignment.Center) {
                Text("+",style=MaterialTheme.typography.displayLarge,
                    color=if(tracking) Color(0xFF55DF9F) else Color(0xFFFF8866))
            }
        }
        Surface(Modifier.align(Alignment.TopCenter).padding(10.dp),
            shape=MaterialTheme.shapes.medium,tonalElevation=4.dp) {
            Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(if(tracking) "ARCore: отслеживание активно" else "ARCore: нет устойчивого трекинга",
                    style=MaterialTheme.typography.labelMedium)
                Text(status,style=MaterialTheme.typography.bodySmall)
                if (activeTarget != null) Text("A — угол на полу; B — направление вправо; C — сторона комнаты",
                    style=MaterialTheme.typography.labelSmall)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(9.dp),
            verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Surface(tonalElevation=5.dp,shape=MaterialTheme.shapes.medium) {
                Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    if(activeTarget!=null) {
                        Button(onClick={capturePoint()},enabled=tracking,modifier=Modifier.fillMaxWidth()) {
                            Text("Зафиксировать точку " + when(machine.count) {
                                0 -> "A"; 1 -> "B"; else -> "C"
                            } + " · прицел по центру")
                        }
                        OutlinedButton(onClick={begin(activeTarget!!)},modifier=Modifier.fillMaxWidth()) {
                            Text("Начать эту привязку заново")
                        }
                    } else {
                        Text("Выбери мебель для привязки или точной поправки",
                            style=MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                        CompactButton("Гарнитур",Modifier.weight(1f)) { begin(PlacementTarget.WALL) }
                        if(reference) {
                            CompactButton("Остров",Modifier.weight(1f)) { begin(PlacementTarget.ISLAND) }
                            CompactButton("Холодильник",Modifier.weight(1f)) { begin(PlacementTarget.FRIDGE) }
                        }
                    }
                    if (anchors.isNotEmpty()) {
                        Row(horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                            for(target in anchors.keys.sortedBy { it.ordinal }) {
                                CompactButton(when(target) {
                                    PlacementTarget.WALL -> "Шкафы"
                                    PlacementTarget.ISLAND -> "Остров"
                                    PlacementTarget.FRIDGE -> "Холод."
                                },Modifier.weight(1f)) { selectedTarget=target }
                            }
                        }
                        Text("Поправка: " + selectedTarget.name + " · 1 см / 5°",
                            style=MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                            CompactButton("←",Modifier.weight(1f)) { move(dx=-.01f) }
                            CompactButton("→",Modifier.weight(1f)) { move(dx=.01f) }
                            CompactButton("↑",Modifier.weight(1f)) { move(dz=-.01f) }
                            CompactButton("↓",Modifier.weight(1f)) { move(dz=.01f) }
                            CompactButton("↺",Modifier.weight(1f)) { move(dyaw=-5f) }
                            CompactButton("↻",Modifier.weight(1f)) { move(dyaw=5f) }
                        }
                    }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Свет: Live HDR",style=MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.weight(1f))
                        Text("Depth",style=MaterialTheme.typography.labelSmall)
                        Switch(checked=useDepth && canUseDepth,onCheckedChange={useDepth=it},enabled=canUseDepth)
                        Text("Тени",style=MaterialTheme.typography.labelSmall)
                        Switch(checked=shadows,onCheckedChange={shadows=it})
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(modifier=modifier,onClick=onClick,
        contentPadding=PaddingValues(horizontal=2.dp,vertical=2.dp)) {
        Text(label, style=MaterialTheme.typography.labelSmall)
    }
}
