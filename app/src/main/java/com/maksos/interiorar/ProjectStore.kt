package com.maksos.interiorar

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class KitchenProject(
    val id: String,
    val name: String,
    val modelUri: String,
    val dimensions: ModelDimensions,
    val offsetX: Float = 0f,
    val offsetZ: Float = 0f,
    val rotationDegrees: Float = 0f
)

data class ModelDimensions(val width: Float, val height: Float, val depth: Float) {
    fun label() = "%.2f × %.2f × %.2f м".format(width, height, depth)
}

class ProjectStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("projects_v1", Context.MODE_PRIVATE)
    private val assets = File(context.filesDir, "model_assets").apply { mkdirs() }

    fun all(): List<KitchenProject> {
        val array = try { JSONArray(prefs.getString("projects", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until array.length()).mapNotNull { i ->
            try {
                val j = array.getJSONObject(i)
                KitchenProject(
                    j.getString("id"), j.getString("name"), j.getString("uri"),
                    ModelDimensions(j.getDouble("w").toFloat(), j.getDouble("h").toFloat(), j.getDouble("d").toFloat()),
                    j.optDouble("x", 0.0).toFloat(), j.optDouble("z", 0.0).toFloat(), j.optDouble("yaw", 0.0).toFloat()
                )
            } catch (_: Exception) { null }
        }
    }

    private fun save(projects: List<KitchenProject>) {
        val array = JSONArray()
        projects.forEach { p ->
            array.put(JSONObject().apply {
                put("id",p.id); put("name",p.name); put("uri",p.modelUri)
                put("w",p.dimensions.width.toDouble()); put("h",p.dimensions.height.toDouble())
                put("d",p.dimensions.depth.toDouble())
                put("x",p.offsetX.toDouble()); put("z",p.offsetZ.toDouble())
                put("yaw",p.rotationDegrees.toDouble())
            })
        }
        require(prefs.edit().putString("projects", array.toString()).commit()) { "Не удалось сохранить проект" }
    }

    fun import(uri: Uri): KitchenProject {
        val file = File.createTempFile("pending-", ".glb", assets)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        bytes += n
                        if (bytes > 200L * 1024 * 1024) throw IllegalArgumentException("Максимальный размер GLB — 200 МБ")
                        out.write(buffer, 0, n)
                    }
                    out.fd.sync()
                }
            } ?: throw IllegalArgumentException("Не удалось открыть файл")
            val bounds = GlbInspector.inspect(file)
            val sha = AssetDigest.sha256(file)
            val destination = File(assets, "$sha.glb")
            if (!destination.exists()) {
                if (!file.renameTo(destination)) throw IllegalStateException("Не удалось сохранить GLB")
            }
            val project = KitchenProject(UUID.randomUUID().toString(), readName(uri), Uri.fromFile(destination).toString(), bounds)
            save(all() + project)
            return project
        } finally {
            if (file.exists()) file.delete()
        }
    }

    private fun readName(uri: Uri): String {
        var title = "Моя кухня"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) title = it.getString(0) ?: title
        }
        return title.take(100)
    }

    fun createSample(): KitchenProject = createBundledSample(
        "demo_kitchen.glb", "Демонстрационная кухня"
    )

    /** Full reference-inspired scene. Dimensions are assumptions, not measured room dimensions. */
    fun createReferenceSample(): KitchenProject = createBundledSample(
        "reference_kitchen.glb", "Кухня по референсу · оценочные размеры"
    )

    private fun createBundledSample(assetName: String, title: String): KitchenProject {
        val file = File(assets, assetName)
        if (!file.exists()) {
            val temp = File.createTempFile("import-", ".glb", assets)
            try {
                context.assets.open(assetName).use { input ->
                    temp.outputStream().use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                // Fail loudly before installation, rather than persisting a broken sample.
                GlbInspector.inspect(temp)
                if (!temp.renameTo(file)) throw IllegalStateException("Не удалось сохранить модель")
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
        val project = KitchenProject(
            UUID.randomUUID().toString(), title,
            Uri.fromFile(file).toString(), GlbInspector.inspect(file)
        )
        save(all() + project)
        return project
    }

    fun update(project: KitchenProject) {
        save(all().map { if (it.id == project.id) project else it })
    }

    fun delete(project: KitchenProject) {
        save(all().filterNot { it.id == project.id })
        // Shared content-addressed models are deliberately retained until app data is cleared.
    }
}
