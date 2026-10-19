package com.maksos.interiorar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.ar.core.*
import io.github.sceneview.*
import io.github.sceneview.ar.*
import io.github.sceneview.ar.scene.SceneUnderstanding
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One room Anchor, arbitrary independently editable instances in room-local metres. */
@Composable
fun ArExperience(project:KitchenProject,store:ProjectStore,onError:(String)->Unit) {
    val context=LocalContext.current
    val persistence=remember {RoomLayoutStore(context)}
    var catalog by remember {mutableStateOf<List<KitchenProject>>(emptyList())}
    var layout by remember(project.id) {mutableStateOf<RoomLayout?>(null)}
    val latest by rememberUpdatedState(layout)
    LaunchedEffect(project.id) {
        try {
            val result=withContext(Dispatchers.IO) {
                val seeds=store.placementAssets(project)
                Pair(store.catalogIncludingArchived(),persistence.load(project.id,seeds))
            }
            catalog=result.first;layout=result.second
        } catch(e:Exception) {onError(e.message ?: "Не удалось открыть расстановку")}
    }
    LaunchedEffect(layout) {
        val snapshot=layout ?: return@LaunchedEffect
        delay(180)
        try {withContext(Dispatchers.IO) {persistence.save(project.id,snapshot)}}
        catch(e:Exception) {onError(e.message ?: "Не удалось сохранить расстановку")}
    }
    DisposableEffect(project.id) {onDispose {
        latest?.let {try {persistence.save(project.id,it)}catch(e:Exception){android.util.Log.e("InteriorStorage","Final layout save failed",e)}}
    }}
    val current=layout
    if(current==null) {Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()};return}
    GenericArScene(current,catalog,onLayout={layout=it},onError=onError)
}

@Composable
private fun GenericArScene(layout:RoomLayout,catalog:List<KitchenProject>,onLayout:(RoomLayout)->Unit,onError:(String)->Unit) {
    val engine=rememberEngine();val loader=rememberModelLoader(engine)
    val scene=rememberScene(engine);val environment=rememberAREnvironment(engine)
    val environmentLoader=rememberEnvironmentLoader(engine);val camera=rememberARCameraNode(engine)
    val light=rememberMainLightNode(engine)
    val bridge=remember(engine){LiveLighting(engine,scene,camera,environmentLoader,environment,light)}
    DisposableEffect(bridge){onDispose{bridge.destroy()}}
    var room by remember {mutableStateOf<Anchor?>(null)}
    val pointAnchors=remember {mutableStateListOf<Anchor>()}
    var floor by remember {mutableStateOf<Plane?>(null)}
    var captureFor by remember {mutableStateOf<String?>("ROOM")}
    var selected by remember {mutableStateOf(layout.items.firstOrNull()?.id)}
    var tracking by remember {mutableStateOf(false)}
    var size by remember {mutableStateOf(IntSize.Zero)}
    var depth by remember {mutableStateOf(false)}
    var useDepth by remember {mutableStateOf(true)}
    var shadows by remember {mutableStateOf(true)}
    var showLibrary by remember {mutableStateOf(false)}
    var showSettings by remember {mutableStateOf(false)}
    var status by remember {mutableStateOf("Привяжите комнату: A — известный угол ПОЛА, B — вправо, C — в комнату")}
    var lightingStatus by remember {mutableStateOf("Ожидание света")}
    var diagClock by remember {mutableLongStateOf(0L)}
    val frameRef=remember {arrayOfNulls<Frame>(1)}
    val catalogById=remember(catalog){catalog.associateBy {it.id}}
    val selectedItem=layout.items.firstOrNull {it.id==selected}
    val roomRef by rememberUpdatedState(room)
    DisposableEffect(Unit){onDispose{pointAnchors.forEach {it.detach()};pointAnchors.clear();roomRef?.detach()}}

    fun begin(id:String) {
        pointAnchors.forEach {it.detach()};pointAnchors.clear();captureFor=id
        if(id!="ROOM")selected=id
        status=if(id=="ROOM") "A/B/C: отметьте ТОТ ЖЕ угол пола комнаты для восстановления сохранённой расстановки"
            else "A — задний левый угол ОСНОВАНИЯ предмета на полу; B — вправо; C — перед предметом"
    }
    fun capture() {
        val mode=captureFor ?: return;val frame=frameRef[0] ?: return
        if(!tracking || size.width==0){status="Нет устойчивого трекинга";return}
        val hit=frame.hitTest(size.width/2f,size.height/2f).firstOrNull {
            val p=it.trackable as? Plane
            p!=null && p.type==Plane.Type.HORIZONTAL_UPWARD_FACING && p.trackingState==TrackingState.TRACKING && p.isPoseInPolygon(it.hitPose)
        }
        if(hit==null){status="Под прицелом не найден пол. Наведите на видимый участок пола";return}
        val p=hit.trackable as Plane
        // Earlier observations are Anchors, so map refinements cannot leave stale world floats.
        val machine=FloorCalibrationMachine(PlacementTarget.FRIDGE)
        for(anchor in pointAnchors) {
            if(anchor.trackingState!=TrackingState.TRACKING){begin(mode);status="Точки потеряны: повторите A/B/C";return}
            val t=anchor.pose.translation
            if(machine.add(Point3(t[0],t[1],t[2])) is CalibrationUpdate.Invalid){begin(mode);status="Изменилась оценка пола: повторите A/B/C";return}
        }
        val t=hit.hitPose.translation
        when(val result=machine.add(Point3(t[0],t[1],t[2]))) {
            is CalibrationUpdate.Invalid -> status=result.reason
            is CalibrationUpdate.NeedNext -> {
                try {pointAnchors.add(hit.createAnchor());status=result.instruction}
                catch(e:Exception){status=e.message ?: "Не удалось запомнить точку"}
            }
            is CalibrationUpdate.Ready -> {
                val c=result.calibration;val h=c.yawRadians/2
                val pose=Pose.makeTranslation(c.origin.x,c.origin.y,c.origin.z).compose(Pose.makeRotation(0f,sin(h),0f,cos(h)))
                if(mode=="ROOM") {
                    try {
                        val fresh=p.createAnchor(pose)
                        room?.detach();room=fresh;floor=p
                        status="Комната привязана. Сохранённые объекты восстановлены относительно её угла"
                    }catch(e:Exception){status=e.message ?: "Не удалось создать Anchor";return}
                }else{
                    val reference=room ?: return
                    val translation=reference.pose.inverse().transformPoint(floatArrayOf(c.origin.x,c.origin.y,c.origin.z))
                    val right=reference.pose.getTransformedAxis(0,1f);val roomYaw=atan2(-right[2],right[0])
                    layout.items.firstOrNull {it.id==mode}?.let {item ->
                        onLayout(layout.replace(item.copy(x=translation[0],y=translation[1],z=translation[2],
                            yaw=Math.toDegrees((c.yawRadians-roomYaw).toDouble()).toFloat(),placed=true)))
                    }
                    status="Предмет зафиксирован. Можно осмотреть и поправить положение"
                }
                pointAnchors.forEach {it.detach()};pointAnchors.clear();captureFor=null
            }
        }
    }
    fun move(dx:Float=0f,dy:Float=0f,dz:Float=0f,dyaw:Float=0f) {
        selectedItem?.let {onLayout(layout.replace(it.copy(x=it.x+dx,y=it.y+dy,z=it.z+dz,yaw=it.yaw+dyaw)))}
    }
    Box(Modifier.fillMaxSize().onSizeChanged {size=it}) {
        ARSceneView(modifier=Modifier.fillMaxSize(),engine=engine,modelLoader=loader,
            scene=scene,environment=environment,environmentLoader=environmentLoader,cameraNode=camera,mainLightNode=light,
            planeRenderer=captureFor!=null,
            sceneUnderstanding=SceneUnderstanding(occlusion=depth&&useDepth,lighting=false,physics=false,planeVisualization=captureFor!=null),
            depthMode=if(depth&&useDepth) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED,
            onSessionCreated={depth=it.isDepthModeSupported(Config.DepthMode.AUTOMATIC)},
            sessionConfiguration={_,config ->config.planeFindingMode=Config.PlaneFindingMode.HORIZONTAL;config.lightEstimationMode=Config.LightEstimationMode.ENVIRONMENTAL_HDR},
            onSessionFailed={onError(it.message ?: "ARCore недоступен")},
            onSessionUpdated={session,frame ->
                frameRef[0]=frame
                val ok=frame.camera.trackingState==TrackingState.TRACKING;if(ok!=tracking)tracking=ok
                bridge.update(session,frame,layout.lighting)
                if(frame.timestamp-diagClock>500_000_000L){diagClock=frame.timestamp;lightingStatus=bridge.status+" · ×"+"%.2f".format(bridge.gainApplied)}
            }) {
            floor?.let {p ->if(shadows && room!=null && captureFor==null) ShadowReceiverPlane(plane=p,shadowIntensity=.32f)}
            room?.let {anchor ->
                AnchorNode(anchor=anchor) {
                    for(item in layout.items.filter {it.placed}) key(item.id) {
                        catalogById[item.assetId]?.let {asset ->
                            val loaded=loadAsset(loader,asset)
                            (loaded as? AssetLoad.Ready)?.model?.let {model ->
                                Node(position=Position(item.x,item.y,item.z),rotation=Rotation(y=item.yaw),scale=Scale(item.scale)) {
                                    ModelNode(modelInstance=model,autoAnimate=false,
                                        centerOrigin=if(asset.originMode=="floor") Position(-1f,-1f,-1f) else null)
                                }
                            }
                            if(loaded is AssetLoad.Failed) LaunchedEffect(loaded){onError(loaded.reason)}
                        }
                    }
                }
            }
        }
        if(captureFor!=null)Text("+",Modifier.align(Alignment.Center),color=if(tracking)Color(0xFF59D9A0) else Color(0xFFFF8877),style=MaterialTheme.typography.displayLarge)
        Surface(Modifier.align(Alignment.TopCenter).padding(10.dp),shape=MaterialTheme.shapes.medium,tonalElevation=4.dp) {
            Column(Modifier.padding(10.dp)){
                Text(if(tracking)status else "Трекинг приостановлен — медленно наведите на пол",style=MaterialTheme.typography.bodySmall)
                Text(lightingStatus,style=MaterialTheme.typography.labelSmall)
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).padding(9.dp),shape=MaterialTheme.shapes.medium,tonalElevation=5.dp) {
            Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                LazyRow(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    items(layout.items,key={it.id}){item ->FilterChip(selected=selected==item.id,onClick={selected=item.id},label={Text(catalogById[item.assetId]?.name?.take(22) ?: "Модель недоступна")})}
                }
                if(captureFor!=null) {
                    Button(onClick={capture()},enabled=tracking,modifier=Modifier.fillMaxWidth()){Text("Зафиксировать "+listOf("A","B","C")[pointAnchors.size.coerceIn(0,2)])}
                    TextButton(onClick={begin(captureFor!!)},modifier=Modifier.fillMaxWidth()){Text("Сбросить точки A/B/C")}
                }else if(selectedItem!=null && room!=null){
                    Button(onClick={begin(selectedItem.id)},modifier=Modifier.fillMaxWidth()){Text(if(selectedItem.placed)"Переставить выбранное" else "Поставить выбранное")}
                    if(selectedItem.placed)Row(horizontalArrangement=Arrangement.spacedBy(2.dp)){
                        SmallAction("←"){move(dx=-.01f)};SmallAction("→"){move(dx=.01f)}
                        SmallAction("Ближе"){move(dz=.01f)};SmallAction("Дальше"){move(dz=-.01f)}
                        SmallAction("↺"){move(dyaw=-5f)};SmallAction("↻"){move(dyaw=5f)}
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                    OutlinedButton(onClick={showLibrary=true},modifier=Modifier.weight(1f)){Text("Добавить объект")}
                    OutlinedButton(onClick={showSettings=true},modifier=Modifier.weight(1f)){Text("Свет / настройки")}
                }
            }
        }
    }
    if(showLibrary)AlertDialog(onDismissRequest={showLibrary=false},title={Text("Объекты и группы")},text={
        LazyColumn{items(catalog.filterNot {it.archived},key={it.id}){asset ->TextButton(onClick={
            try {val next=layout.add(asset.id);onLayout(next);selected=next.items.last().id;showLibrary=false}
            catch(e:Exception){onError(e.message ?: "Лимит объектов")}
        }){Text(asset.name)}}}
    },confirmButton={TextButton(onClick={showLibrary=false}){Text("Закрыть")}})
    if(showSettings)AlertDialog(onDismissRequest={showSettings=false},title={Text("Настройки сцены")},text={
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(verticalAlignment=Alignment.CenterVertically){Text("Естественный свет",Modifier.weight(1f));Switch(checked=layout.lighting.natural,onCheckedChange={onLayout(layout.copy(lighting=layout.lighting.copy(natural=it)))})}
            Text("Яркость мебели: "+"%+.2f EV".format(layout.lighting.exposureEv))
            Slider(value=layout.lighting.exposureEv,onValueChange={onLayout(layout.copy(lighting=layout.lighting.copy(exposureEv=it)))},valueRange=-2f..1f)
            Text("Коррекция влияет только на 3D, не на изображение камеры",style=MaterialTheme.typography.bodySmall)
            Row(verticalAlignment=Alignment.CenterVertically){Text(if(depth)"Перекрытие Depth" else "Depth не поддерживается",Modifier.weight(1f));Switch(useDepth&&depth,{useDepth=it},enabled=depth)}
            Row(verticalAlignment=Alignment.CenterVertically){Text("Контактные тени",Modifier.weight(1f));Switch(shadows,{shadows=it})}
            selectedItem?.let {item ->
                Text("Высота: "+"%.2f м".format(item.y))
                Row{TextButton(onClick={move(dy=.01f)}){Text("Выше 1 см")};TextButton(onClick={move(dy=-.01f)}){Text("Ниже 1 см")}}
                Text("Масштаб: "+"%.3f".format(item.scale)+" · 1.000 = реальные метры")
                Row{
                    TextButton(onClick={onLayout(layout.replace(item.copy(scale=(item.scale*.99f).coerceAtLeast(.01f))))}){Text("−1%")}
                    TextButton(onClick={onLayout(layout.replace(item.copy(scale=1f)))}){Text("1:1")}
                    TextButton(onClick={onLayout(layout.replace(item.copy(scale=(item.scale*1.01f).coerceAtMost(100f))))}){Text("+1%")}
                }
                TextButton(onClick={try{val next=layout.duplicate(item.id);onLayout(next);selected=next.items.last().id;showSettings=false}catch(e:Exception){onError(e.message ?: "Лимит")}}){Text("Создать ещё один экземпляр")}
                TextButton(onClick={val remaining=layout.items.filterNot {it.id==item.id};onLayout(layout.copy(items=remaining));selected=remaining.firstOrNull()?.id;showSettings=false}){Text("Убрать из комнаты")}
            }
            TextButton(onClick={begin("ROOM");showSettings=false}){Text("Повторно привязать всю комнату")}
            Text("После перезапуска отметьте те же A/B/C комнаты. Файл проекта не сохраняет ARCore-карту квартиры.",style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(onClick={showSettings=false}){Text("Готово")}})
}

@Composable
private fun RowScope.SmallAction(label:String,click:()->Unit) {
    OutlinedButton(modifier=Modifier.weight(1f),onClick=click,contentPadding=PaddingValues(1.dp)){Text(label,style=MaterialTheme.typography.labelSmall)}
}
