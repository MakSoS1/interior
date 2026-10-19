package com.maksos.interiorar

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.ar.core.*
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.rememberAREnvironment
import io.github.sceneview.ar.scene.SceneUnderstanding
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberScene
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import java.io.File
import kotlin.math.sin
import kotlin.math.cos

@Composable
fun UniversalArView(document:ArDocument,onTransform:(String,GroupTransform)->Unit,onLighting:(LightingSettings)->Unit,onError:(String)->Unit) {
    val engine=rememberEngine();val loader=rememberModelLoader(engine);val scene=rememberScene(engine)
    val environment=rememberAREnvironment(engine)
    val mainLight=rememberMainLightNode(engine) { intensity=10_000f }
    val lightController=remember(scene,mainLight) { RuntimeLightController(mainLight,scene,environment.indirectLight?.intensity ?: 10_000f) }
    val settings by rememberUpdatedState(document.lighting)
    val anchors=remember(document.id){mutableStateMapOf<String,Anchor>()}
    val calibrated=remember(document.id){ArrayList<Anchor>()}
    val planes=remember(document.id){mutableStateMapOf<String,Plane>()}
    var selected by remember(document.id){mutableStateOf(document.groups.first().id)}
    var placing by remember(document.id){mutableStateOf(true)}
    var count by remember{mutableIntStateOf(0)}
    var status by remember{mutableStateOf("Наведите прицел на пол: A — задний левый угол объекта")}
    var tracking by remember{mutableStateOf(false)}
    var depth by remember{mutableStateOf(false)}
    var useDepth by remember{mutableStateOf(true)}
    var shadows by remember{mutableStateOf(true)}
    var showLight by remember{mutableStateOf(false)}
    var lightStatus by remember{mutableStateOf("Ожидание оценки света")}
    var size by remember{mutableStateOf(IntSize.Zero)}
    val frameRef=remember{arrayOfNulls<Frame>(1)};val sessionRef=remember{arrayOfNulls<Session>(1)}
    var uiTimestamp by remember{mutableLongStateOf(0)}
    fun clearMarkers() { calibrated.forEach { it.detach() };calibrated.clear();count=0 }
    fun begin(id:String) { clearMarkers();selected=id;placing=true;status="A: задний левый угол на ПОЛУ. B: вправо вдоль края. C: перед объектом." }
    DisposableEffect(document.id) {
        onDispose { anchors.values.forEach { it.detach() };calibrated.forEach { it.detach() } }
    }
    fun capture() {
        val frame=frameRef[0] ?: return;val session=sessionRef[0] ?: return
        if(!tracking || frame.camera.trackingState!=TrackingState.TRACKING) {status="Дождитесь трекинга";return}
        val hit=frame.hitTest(size.width/2f,size.height/2f).firstOrNull {
            val p=it.trackable as? Plane
            p!=null && p.type==Plane.Type.HORIZONTAL_UPWARD_FACING && p.trackingState==TrackingState.TRACKING && p.isPoseInPolygon(it.hitPose)
        }
        if(hit==null) { status="Под прицелом ещё нет обнаруженного пола";return }
        if(calibrated.any { it.trackingState!=TrackingState.TRACKING }) {clearMarkers();status="Точки потеряны. Начните с A";return}
        val group=document.groups.first { it.id==selected }
        val machine=FloorCalibrationMachine(if(group.physicalDimensions().width<1f) PlacementTarget.FRIDGE else PlacementTarget.WALL)
        // Refresh previous point poses from temporary anchors instead of reusing stale world floats.
        for(marker in calibrated) {
            val p=marker.pose;val result=machine.add(Point3(p.tx(),p.ty(),p.tz()))
            if(result is CalibrationUpdate.Invalid) {clearMarkers();status=result.reason;return}
        }
        val p=hit.hitPose
        when(val update=machine.add(Point3(p.tx(),p.ty(),p.tz()))) {
            is CalibrationUpdate.Invalid -> status=update.reason
            is CalibrationUpdate.NeedNext -> try {
                calibrated.add(hit.createAnchor());count=calibrated.size;status=update.instruction
            } catch(e:Exception) {onError(e.message ?: "Не удалось закрепить точку")}
            is CalibrationUpdate.Ready -> try {
                val cal=update.calibration;val half=cal.yawRadians/2f
                val pose=Pose.makeTranslation(cal.origin.x,cal.origin.y,cal.origin.z).compose(Pose.makeRotation(0f,sin(half),0f,cos(half)))
                val newAnchor=(hit.trackable as Plane).createAnchor(pose)
                anchors.remove(selected)?.detach();anchors[selected]=newAnchor;planes[selected]=hit.trackable as Plane
                clearMarkers();placing=false;status="${group.label}: закреплено. Размеры ${group.physicalDimensions().label()}"
            } catch(e:Exception) {onError(e.message ?: "Ошибка Anchor")}
        }
    }
    fun adjust(dx:Float=0f,dy:Float=0f,dz:Float=0f,dyaw:Float=0f) {
        val g=document.groups.first { it.id==selected };val t=g.transform
        onTransform(g.id,t.copy(x=t.x+dx,y=t.y+dy,z=t.z+dz,yaw=PlacementMath.normalizeDegrees(t.yaw+dyaw)))
    }
    Box(Modifier.fillMaxSize().onSizeChanged { size=it }) {
        ARSceneView(modifier=Modifier.fillMaxSize(),engine=engine,modelLoader=loader,scene=scene,
            environment=environment,mainLightNode=mainLight,fillLightNode=null,
            planeRenderer=placing,
            sceneUnderstanding=SceneUnderstanding(occlusion=depth&&useDepth,lighting=true,physics=false,planeVisualization=placing),
            depthMode=if(depth&&useDepth) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED,
            onSessionCreated={depth=it.isDepthModeSupported(Config.DepthMode.AUTOMATIC)},
            sessionConfiguration={session,config ->
                config.planeFindingMode=Config.PlaneFindingMode.HORIZONTAL
                config.lightEstimationMode=Config.LightEstimationMode.ENVIRONMENTAL_HDR
            },
            onSessionFailed={onError(it.message ?: "ARCore недоступен")},
            onSessionUpdated={session,frame ->
                frameRef[0]=frame;sessionRef[0]=session
                val valid=frame.camera.trackingState==TrackingState.TRACKING
                if(tracking!=valid)tracking=valid
                lightController.update(session,frame,settings)
                if(frame.timestamp-uiTimestamp>500_000_000L) {uiTimestamp=frame.timestamp;lightStatus=lightController.status}
            }
        ) {
            if(shadows && !placing) planes.values.distinct().forEach { plane ->
                key(plane) { ShadowReceiverPlane(plane=plane,shadowIntensity=.38f) }
            }
            document.groups.forEach { group ->
                anchors[group.id]?.let { anchor ->
                    key(group.id) {
                        val instance=rememberModelInstance(loader,Uri.fromFile(File(group.file)).toString())
                        AnchorNode(anchor=anchor) {
                            instance?.let {
                                val root=GroupPlacement.root(group)
                                ModelNode(modelInstance=it,autoAnimate=false,
                                    position=Position(root.position.x,root.position.y,root.position.z),
                                    rotation=Rotation(y=root.yaw),scale=Scale(root.scale,root.scale,root.scale))
                            }
                        }
                    }
                }
            }
        }
        if(placing)Text("+",Modifier.align(Alignment.Center),style=MaterialTheme.typography.displayLarge,
            color=if(tracking)Color(0xFF6EE7B7) else Color(0xFFFFA07A))
        Surface(Modifier.align(Alignment.TopCenter).padding(10.dp),shape=MaterialTheme.shapes.medium,tonalElevation=4.dp) {
            Column(Modifier.padding(10.dp)) {
                Text(if(tracking)"ARCore · отслеживание" else "Нет устойчивого трекинга",style=MaterialTheme.typography.labelMedium)
                Text(status,style=MaterialTheme.typography.bodySmall)
                Text(lightStatus,style=MaterialTheme.typography.labelSmall)
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).padding(8.dp),shape=MaterialTheme.shapes.medium,tonalElevation=5.dp) {
            Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    document.groups.forEach { g -> FilterChip(selected=selected==g.id,onClick={clearMarkers();selected=g.id;placing=!anchors.containsKey(g.id)},label={Text(g.label)}) }
                }
                if(placing) {
                    Button(onClick={capture()},enabled=tracking && size.width>0,modifier=Modifier.fillMaxWidth()) {Text("Зафиксировать "+listOf("A","B","C")[count.coerceIn(0,2)])}
                    Text("Точки на полу; высота предмета берётся из модели",style=MaterialTheme.typography.labelSmall)
                } else {
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        Button(onClick={begin(selected)},modifier=Modifier.weight(1f)){Text("Переставить")}
                        OutlinedButton(onClick={anchors.remove(selected)?.detach();planes.remove(selected);begin(selected)},modifier=Modifier.weight(1f)){Text("Убрать")}
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                        Tiny("←"){adjust(dx=-.01f)};Tiny("→"){adjust(dx=.01f)};Tiny("↑"){adjust(dz=-.01f)};Tiny("↓"){adjust(dz=.01f)};Tiny("↺"){adjust(dyaw=-5f)};Tiny("↻"){adjust(dyaw=5f)}
                    }
                }
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick={showLight=!showLight}){Text("Освещение")}
                    Text("Depth",style=MaterialTheme.typography.labelSmall);Switch(checked=depth&&useDepth,onCheckedChange={useDepth=it},enabled=depth)
                    Text("Тени",style=MaterialTheme.typography.labelSmall);Switch(checked=shadows,onCheckedChange={shadows=it})
                }
                if(showLight) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Естественная яркость",Modifier.weight(1f));Switch(checked=settings.natural,onCheckedChange={onLighting(settings.copy(natural=it))})
                    }
                    Text("Светлее / темнее · ${"%.1f".format(settings.exposureStops)} EV",style=MaterialTheme.typography.labelSmall)
                    Slider(value=settings.exposureStops,onValueChange={onLighting(settings.copy(exposureStops=it))},valueRange=-2f..1f)
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Зафиксировать яркость",Modifier.weight(1f));Switch(checked=settings.lock,onCheckedChange={onLighting(settings.copy(lock=it))})
                    }
                }
            }
        }
    }
}
@Composable private fun RowScope.Tiny(text:String,action:()->Unit) {
    OutlinedButton(onClick=action,modifier=Modifier.weight(1f),contentPadding=PaddingValues(1.dp)){Text(text)}
}
