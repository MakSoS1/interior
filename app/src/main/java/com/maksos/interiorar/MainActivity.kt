package com.maksos.interiorar

import android.Manifest
import android.os.Bundle
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme=darkColorScheme()) { InteriorApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InteriorApp() {
    val context=LocalContext.current;val activity=context as ComponentActivity
    val catalog=remember{SceneCatalog(context.applicationContext)};val scope=rememberCoroutineScope()
    var documents by remember{mutableStateOf<List<ArDocument>>(emptyList())}
    var selected by remember{mutableStateOf<ArDocument?>(null)}
    var groupId by remember{mutableStateOf<String?>(null)}
    var route by remember{mutableStateOf("library")}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    var arAvailable by remember{mutableStateOf(false)}
    var appendId by remember{mutableStateOf<String?>(null)}
    var deleteProject by remember{mutableStateOf<ArDocument?>(null)}
    var removeGroup by remember{mutableStateOf<ArGroup?>(null)}
    var editGroup by remember{mutableStateOf<ArGroup?>(null)}
    val writes=remember{Channel<()->Unit>(Channel.UNLIMITED)}
    LaunchedEffect(Unit) {
        try {documents=withContext(Dispatchers.IO){catalog.list()}} catch(e:Exception){error="Не удалось прочитать проекты: "+e.message}
        for(operation in writes) try {withContext(Dispatchers.IO){operation()}} catch(e:Exception){error="Ошибка сохранения: "+e.message}
    }
    LaunchedEffect(route) {
        repeat(15) {
            val available=ArCoreApk.getInstance().checkAvailability(context)
            arAvailable=available.isSupported
            if(!available.isTransient)return@LaunchedEffect
            delay(200)
        }
    }
    fun open(doc:ArDocument) {selected=doc;groupId=doc.groups.first().id;documents=documents.filterNot{it.id==doc.id}+doc;route="project"}
    fun changed(doc:ArDocument) {selected=doc;documents=documents.map {if(it.id==doc.id)doc else it};writes.trySend{catalog.put(doc)}}
    fun task(block:suspend()->ArDocument) {
        busy=true
        scope.launch {try {open(withContext(Dispatchers.IO){block()})}catch(e:Exception){error=e.message?:"Ошибка"}finally{busy=false}}
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {val id=appendId;appendId=null;task {if(id==null)catalog.import(uri) else catalog.append(id,uri)}}
    }
    var exportDoc by remember{mutableStateOf<ArDocument?>(null)}
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val doc=exportDoc
        if(uri!=null && doc!=null) {
            busy=true;scope.launch {
                try {withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use { ScenePackAdapter.export(doc,it) } ?: throw IllegalStateException("Файл не открыт")};error="Пакет сохранён: модели, группы и настройки света"}
                catch(e:Exception){error=e.message?:"Ошибка экспорта"}finally{busy=false}
            }
        }
    }
    val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted)route="ar" else error="Для AR нужна камера; импорт и 3D доступны без неё"
    }
    fun launchAr() {
        if(!arAvailable){error="Устройство не подтвердило ARCore. Доступны импорт и 3D-просмотр.";return}
        try {
            if(ArCoreApk.getInstance().requestInstall(activity,true)!=ArCoreApk.InstallStatus.INSTALLED){error="Завершите установку Google Play Services for AR";return}
            if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)route="ar"
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }catch(e:Exception){error=e.message?:"ARCore недоступен"}
    }
    fun goBack(){route=if(route=="ar")"project" else "library"}
    BackHandler(route!="library"){goBack()}
    Scaffold(topBar={TopAppBar(title={Text(if(route=="library")"Interior AR" else if(route=="ar")"AR · расстановка" else selected?.title?:"Проект")},
        navigationIcon={if(route!="library")TextButton(onClick={goBack()}){Text("← Назад")}})},
        snackbarHost={error?.let { msg ->Snackbar(action={TextButton(onClick={error=null}){Text("Закрыть")}},modifier=Modifier.padding(8.dp)){Text(msg)}}}) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when(route) {
                "library" ->Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Любые модели и группы — в вашей комнате",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text("GLB — один объект или готовая группа. Пакет .interior.zip — несколько независимых групп.",style=MaterialTheme.typography.bodySmall)
                    Button(onClick={appendId=null;picker.launch(arrayOf("*/*"))},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Импорт GLB / пакета")}
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={task{catalog.reference()}},enabled=!busy,modifier=Modifier.weight(1f)){Text("Моя кухня")}
                        OutlinedButton(onClick={task{catalog.demoPack()}},enabled=!busy,modifier=Modifier.weight(1f)){Text("Пример адаптера")}
                    }
                    Text(if(arAvailable)"ARCore доступен · размеры в метрах" else "ARCore не подтверждён · 3D доступен",style=MaterialTheme.typography.labelSmall)
                    HorizontalDivider()
                    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        items(documents,key={it.id}) { doc ->
                            Card(onClick={open(doc)},modifier=Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)){Text(doc.title);Text("Групп: ${doc.groups.size}",style=MaterialTheme.typography.labelSmall)}
                                    TextButton(onClick={deleteProject=doc}){Text("Удалить")}
                                }
                            }
                        }
                    }
                }
                "project" ->selected?.let { doc ->
                    val group=doc.groups.find {it.id==groupId}?:doc.groups.first()
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            doc.groups.forEach {g ->FilterChip(selected=group.id==g.id,onClick={groupId=g.id},label={Text(g.label)})}
                        }
                        Box(Modifier.weight(1f)) { key(group.id){AssetPreview(group)} }
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("Размер: "+group.physicalDimensions().label(),style=MaterialTheme.typography.bodyMedium)
                            Text("${doc.groups.size} групп · каждая размещается независимо",style=MaterialTheme.typography.labelSmall)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick={appendId=doc.id;picker.launch(arrayOf("*/*"))},enabled=!busy){Text("Добавить")}
                                TextButton(onClick={editGroup=group}){Text("Размеры")}
                                TextButton(onClick={val copy=group.copy(id=UUID.randomUUID().toString(),label=(group.label+" · копия").take(160));changed(doc.copy(groups=doc.groups+copy));groupId=copy.id},enabled=doc.groups.size<32){Text("Копия")}
                                TextButton(onClick={removeGroup=group},enabled=doc.groups.size>1){Text("Убрать")}
                                TextButton(onClick={exportDoc=doc;exporter.launch("interior-project.interior.zip")}){Text("Экспорт")}
                            }
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text("Естественный свет",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                                Switch(checked=doc.lighting.natural,onCheckedChange={changed(doc.copy(lighting=doc.lighting.copy(natural=it)))})
                            }
                            Button(onClick={launchAr()},modifier=Modifier.fillMaxWidth(),enabled=!busy){Text("Расставить в AR")}
                        }
                    }
                }
                "ar" ->selected?.let { doc ->UniversalArView(doc,
                    onTransform={id,t ->val now=selected?:doc;changed(now.copy(groups=now.groups.map {if(it.id==id)it.copy(transform=t) else it}))},
                    onLighting={v ->val now=selected?:doc;changed(now.copy(lighting=v))},onError={error=it})}
            }
            if(busy)CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
    editGroup?.let { group ->GroupSettingsDialog(group,onDismiss={editGroup=null},onSave={updated->selected?.let {doc->changed(doc.copy(groups=doc.groups.map{if(it.id==updated.id)updated else it}))}}) }
    deleteProject?.let { doc ->AlertDialog(onDismissRequest={deleteProject=null},title={Text("Удалить проект?")},
        text={Text(doc.title)},confirmButton={TextButton(onClick={documents=documents.filterNot{it.id==doc.id};writes.trySend{catalog.delete(doc.id)};deleteProject=null}){Text("Удалить")}},
        dismissButton={TextButton(onClick={deleteProject=null}){Text("Отмена")}})}
    removeGroup?.let { group ->AlertDialog(onDismissRequest={removeGroup=null},title={Text("Убрать группу из проекта?")},text={Text(group.label)},
        confirmButton={TextButton(onClick={selected?.let {doc ->changed(doc.copy(groups=doc.groups.filterNot{it.id==group.id}));groupId=doc.groups.first{it.id!=group.id}.id};removeGroup=null}){Text("Убрать")}},
        dismissButton={TextButton(onClick={removeGroup=null}){Text("Отмена")}})}
}

@Composable
private fun AssetPreview(group:ArGroup) {
    val engine=rememberEngine();val loader=rememberModelLoader(engine)
    val model=rememberModelInstance(loader,Uri.fromFile(File(group.file)).toString())
    var timedOut by remember{mutableStateOf(false)}
    LaunchedEffect(model){if(model==null){delay(20000);timedOut=true}else timedOut=false}
    Box(Modifier.fillMaxSize()) {
        SceneView(modifier=Modifier.fillMaxSize(),engine=engine,modelLoader=loader,autoCenterContent=true,autoFitContent=true,
            cameraManipulator=rememberCameraManipulator(orbitRadius=(maxOf(group.dimensions.width,group.dimensions.height,group.dimensions.depth)*1.8f).coerceIn(2f,30f))) {
            model?.let {ModelNode(modelInstance=it,autoAnimate=false)}
        }
        Surface(Modifier.align(Alignment.TopStart).padding(6.dp),tonalElevation=3.dp) {
            Text(if(model!=null)"3D-модель загружена" else if(timedOut)"Не удалось загрузить геометрию" else "Загрузка 3D…",Modifier.padding(6.dp),style=MaterialTheme.typography.labelSmall)
        }
        if(model==null&&!timedOut)CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}
