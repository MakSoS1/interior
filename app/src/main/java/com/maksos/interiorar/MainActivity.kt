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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MaterialTheme(colorScheme=darkColorScheme()){InteriorApp()}}}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InteriorApp(){
    val context=LocalContext.current;val store=remember{ProjectStore(context.applicationContext)};val scope=rememberCoroutineScope()
    var projects by remember{mutableStateOf(store.all())};var selected by remember{mutableStateOf<KitchenProject?>(null)}
    var mode by remember{mutableStateOf("projects")};var busy by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf<String?>(null)};var toDelete by remember{mutableStateOf<KitchenProject?>(null)}
    var arAvailable by remember{mutableStateOf(false)};var checking by remember{mutableStateOf(true)}
    fun refresh(){projects=store.all()}
    fun sample(factory:()->KitchenProject){
        busy=true;scope.launch{
            try{selected=withContext(Dispatchers.IO){factory()};refresh();mode="preview"}
            catch(e:Exception){message=e.message ?: "Не удалось открыть модель"}finally{busy=false}
        }
    }
    LaunchedEffect(mode){if(mode!="ar"){
        checking=true
        repeat(15){
            val a=try{ArCoreApk.getInstance().checkAvailability(context)}catch(_:Exception){ArCoreApk.Availability.UNKNOWN_ERROR}
            arAvailable=a.isSupported
            if(!a.isTransient){checking=false;return@LaunchedEffect};delay(200)
        };checking=false
    }}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri ->if(uri!=null){
        busy=true;scope.launch{
            try{
                val imported=withContext(Dispatchers.IO){store.importAll(uri)};refresh();selected=imported.first();mode="preview"
                if(imported.size>1)message="Импортировано "+imported.size+" групп. В AR добавляйте их кнопкой «Добавить объект»."
            }catch(e:Exception){message=e.message ?: "Ошибка импорта"}finally{busy=false}
        }
    }}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted ->
        if(granted)mode="ar" else message="Для AR нужен доступ к камере. Библиотека и 3D работают без него."
    }
    fun goAr(){
        if(!arAvailable){message="Google Play Services for AR не подтвердил совместимость телефона. 3D доступен.";return}
        try{
            val result=ArCoreApk.getInstance().requestInstall(context as android.app.Activity,true)
            if(result==ArCoreApk.InstallStatus.INSTALLED){
                val granted=ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
                if(granted)mode="ar" else camera.launch(Manifest.permission.CAMERA)
            }else message="После установки Google Play Services for AR повторите запуск"
        }catch(e:Exception){message=e.message ?: "ARCore недоступен"}
    }
    BackHandler(mode!="projects"){mode=if(mode=="ar")"preview" else "projects";refresh()}
    Scaffold(topBar={TopAppBar(title={Text(when(mode){"projects"->"Interior AR";"preview"->selected?.name ?: "3D";else->"Моя кухня · AR"})},
        navigationIcon={if(mode!="projects")TextButton(onClick={mode=if(mode=="ar")"preview" else "projects";refresh()}){Text("← Назад")}})}){padding ->
        Box(Modifier.fillMaxSize().padding(padding)){
            when(mode){
                "projects"->LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    item{Text("Любые предметы и группы",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)}
                    item{Text("GLB сохраняет материалы и реальные размеры. ZIP из адаптера Blender добавляет сразу несколько групп.")}
                    item{Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        Button(enabled=!busy,onClick={picker.launch(arrayOf("model/gltf-binary","application/zip","application/octet-stream","*/*"))}){Text("Загрузить GLB")}
                        OutlinedButton(enabled=!busy,onClick={sample{store.createSample()}}){Text("Тестовая кухня")}
                    }}
                    item{Text("Также принимаются пакеты .interior.zip",style=MaterialTheme.typography.bodySmall)}
                    item{OutlinedButton(enabled=!busy,onClick={sample{store.createReferenceSample()}},modifier=Modifier.fillMaxWidth()){Text("Кухня из твоего референса · остров + 3 стула")}}
                    item{Text("Размеры примера оценочные. Перед заказом мебели нужны замеры.",style=MaterialTheme.typography.bodySmall)}
                    item{Text(if(checking)"Проверка ARCore…" else if(arAvailable)"ARCore: поддержка обнаружена" else "ARCore недоступен — можно использовать 3D",style=MaterialTheme.typography.labelSmall)}
                    item{HorizontalDivider();Text("Библиотека: "+projects.size,style=MaterialTheme.typography.titleMedium)}
                    items(projects,key={it.id}){asset ->Card(onClick={
                        android.util.Log.i("InteriorNav","Opening selected asset ${asset.id} from gallery")
                        selected=asset;mode="preview"
                    },modifier=Modifier.fillMaxWidth()){
                        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1f)){Text(asset.name,fontWeight=FontWeight.SemiBold);Text(asset.dimensions.label(),style=MaterialTheme.typography.bodySmall)}
                            TextButton(onClick={toDelete=asset}){Text("Удалить")}
                        }
                    }}
                    item{Spacer(Modifier.height(16.dp))}
                }
                "preview"->selected?.let{asset ->Column(Modifier.fillMaxSize()){
                    Box(Modifier.weight(1f)){AssetPreview(asset)}
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                        Text("Ширина × высота × глубина: "+asset.dimensions.label())
                        Text("Группа сохраняет взаимное положение своих деталей. Для отдельных предметов используйте экспорт коллекций.",style=MaterialTheme.typography.bodySmall)
                        Button(onClick={goAr()},modifier=Modifier.fillMaxWidth()){Text("Разместить в AR · 1:1")}
                    }
                }}
                "ar"->selected?.let{ArExperience(it,store,onError={message=it})}
            }
            if(busy)CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
    if(message!=null)AlertDialog(onDismissRequest={message=null},title={Text("Interior AR")},text={Text(message ?: "")},confirmButton={TextButton(onClick={message=null}){Text("Понятно")}})
    toDelete?.let{asset ->AlertDialog(onDismissRequest={toDelete=null},title={Text("Удалить из библиотеки?")},
        text={Text("Файл не удаляется из приватного хранилища, чтобы не повредить сохранённые расстановки.")},
        confirmButton={TextButton(onClick={store.delete(asset);refresh();toDelete=null}){Text("Удалить")}},
        dismissButton={TextButton(onClick={toDelete=null}){Text("Отмена")}})}
}
