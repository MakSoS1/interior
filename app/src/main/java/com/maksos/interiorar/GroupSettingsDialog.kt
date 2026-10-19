package com.maksos.interiorar

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Explicit unit conversion, never silent fitting of a model to the camera view. */
@Composable
fun GroupSettingsDialog(group:ArGroup,onDismiss:()->Unit,onSave:(ArGroup)->Unit) {
    var label by remember(group.id){mutableStateOf(group.label)}
    var units by remember(group.id){mutableFloatStateOf(group.metresPerUnit)}
    var width by remember(group.id){mutableStateOf("%.3f".format(group.physicalDimensions().width))}
    var elevation by remember(group.id){mutableStateOf("%.3f".format(group.transform.y))}
    var issue by remember{mutableStateOf<String?>(null)}
    fun number(text:String)=text.trim().replace(',','.').toFloatOrNull()
    AlertDialog(onDismissRequest=onDismiss,title={Text("Размеры и опорная высота")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value=label,onValueChange={label=it.take(160)},label={Text("Название группы")},singleLine=true)
            Text("Единицы исходной модели",style=MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("м" to 1f,"см" to .01f,"мм" to .001f).forEach { (name,multiplier) ->
                    FilterChip(selected=units==multiplier,onClick={units=multiplier;width="%.3f".format(group.dimensions.width*multiplier*group.transform.scale)},label={Text(name)})
                }
            }
            OutlinedTextField(value=width,onValueChange={width=it},label={Text("Физическая ширина, м")},singleLine=true)
            Text("Изменение ширины пропорционально меняет всю группу. Внутренние расстояния сохраняются.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(value=elevation,onValueChange={elevation=it},label={Text("Поднять основание над полом, м")},singleLine=true)
            Text("Для напольной мебели — 0. Для настенной полки — измеренная высота её нижней грани.",style=MaterialTheme.typography.bodySmall)
            issue?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }},
        confirmButton={TextButton(onClick={
            val w=number(width);val y=number(elevation)
            if(label.isBlank() || w==null || !w.isFinite() || w<=0f || y==null || !y.isFinite() || y !in 0f..20f || group.dimensions.width<=0f) {
                issue="Введите корректное название, положительную ширину и высоту 0–20 м"
            } else {
                val scale=w/(group.dimensions.width*units)
                try{onSave(group.copy(label=label,metresPerUnit=units,transform=group.transform.copy(y=y,scale=scale)));onDismiss()}
                catch(e:IllegalArgumentException){issue=e.message?:"Размер вне допустимого диапазона"}
            }
        }){Text("Сохранить")}},
        dismissButton={TextButton(onClick=onDismiss){Text("Отмена")}})
}
