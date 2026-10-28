package com.example.pixelcolor.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PixelRepository(private val context: Context) {

    private val dao = AppDatabase.getDao(context)
    private val gson = Gson()

    suspend fun loadTemplates(): List<Template> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Template>()
        try {
            val files = context.assets.list("templates")?.filter { it.endsWith(".json") }.orEmpty()
            for (fileName in files) {
                context.assets.open("templates/$fileName").bufferedReader().use { reader ->
                    val parsed = gson.fromJson(reader.readText(), Template::class.java)
                    if (parsed != null && parsed.id.isNotBlank()) {
                        result.add(parsed)
                    }
                }
            }
        } catch (_: Exception) {}

        val existingIds = result.map { it.id }.toSet()
        for (rawJson in BUILTIN_TEMPLATES_JSON) {
            val fallback = gson.fromJson(rawJson, Template::class.java)
            if (fallback.id !in existingIds) result.add(fallback)
        }
        result
    }

    // ТЕПЕРЬ ВОЗВРАЩАЕТ Map<String, Int> (Координата -> Цвет)
    suspend fun getAllProgressMap(): Map<String, Map<String, Int>> = withContext(Dispatchers.IO) {
        val type = object : TypeToken<Map<String, Int>>() {}.type
        dao.getAllProgress().associate { entity ->
            val map: Map<String, Int> = try {
                gson.fromJson(entity.filledCellsJson, type) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
            entity.templateId to map
        }
    }

    // ТЕПЕРЬ СОХРАНЯЕТ Map<String, Int>
    suspend fun saveProgress(templateId: String, filledCells: Map<String, Int>) = withContext(Dispatchers.IO) {
        val json = gson.toJson(filledCells)
        dao.saveProgress(
            ProgressEntity(
                templateId = templateId,
                filledCellsJson = json,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun getArtworks(): List<ArtworkEntity> = withContext(Dispatchers.IO) {
        dao.getAllArtworks().filter { File(it.filePath).exists() }
    }

    suspend fun deleteArtwork(artwork: ArtworkEntity) = withContext(Dispatchers.IO) {
        File(artwork.filePath).delete()
        dao.deleteArtwork(artwork.id)
    }

    suspend fun exportAndSaveArtwork(template: Template, scaleMultiplier: Int): ArtworkEntity = withContext(Dispatchers.IO) {
        val baseCellSize = (256 / maxOf(template.rows, template.cols)).coerceAtLeast(8)
        val cellPx = baseCellSize * scaleMultiplier
        val width = template.cols * cellPx
        val height = template.rows * cellPx

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        canvas.drawColor(Color.WHITE)

        for (r in 0 until template.rows) {
            for (c in 0 until template.cols) {
                val colorNum = template.grid.getOrNull(r)?.getOrNull(c) ?: 0
                if (colorNum > 0) {
                    val hex = template.palette[colorNum] ?: "#000000"
                    paint.color = try { Color.parseColor(hex) } catch (_: Exception) { Color.BLACK }
                    canvas.drawRect(
                        (c * cellPx).toFloat(), (r * cellPx).toFloat(),
                        ((c + 1) * cellPx).toFloat(), ((r + 1) * cellPx).toFloat(), paint
                    )
                }
            }
        }

        val timestamp = System.currentTimeMillis()
        val fileName = "pixel_${template.id}_${scaleMultiplier}x_$timestamp.png"

        val internalDir = File(context.filesDir, "artworks").apply { mkdirs() }
        val internalFile = File(internalDir, fileName)
        FileOutputStream(internalFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        saveBitmapToGallery(bitmap, fileName)

        val entity = ArtworkEntity(
            templateId = template.id,
            templateName = "${template.name} (${scaleMultiplier}×)",
            filePath = internalFile.absolutePath,
            createdAt = timestamp
        )
        val newId = dao.insertArtwork(entity)
        entity.copy(id = newId)
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, fileName: String): Uri? {
        return try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PixelColor")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            imageUri?.let { uri ->
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
            }
            imageUri
        } catch (_: Exception) { null }
    }

    fun shareArtwork(artwork: ArtworkEntity) {
        try {
            val file = File(artwork.filePath)
            if (!file.exists()) return
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return
            val shareUri = saveBitmapToGallery(bitmap, "share_${file.name}") ?: return

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Поделиться").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (_: Exception) {}
    }

    fun vibrateWrongTap() {
        try {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50L)
            }
        } catch (_: Exception) {}
    }

    companion object {
        // 5 встроенных шаблонов 16x16 по ТЗ: Котик, Сердце, Гриб, Машинка, Зонт
        private val BUILTIN_TEMPLATES_JSON = listOf(
            """
            {
              "id": "cat",
              "name": "Котик",
              "rows": 16,
              "cols": 16,
              "palette": {
                "1": "#212121",
                "2": "#FF9800",
                "3": "#FFE0B2",
                "4": "#F48FB1",
                "5": "#4CAF50"
              },
              "grid": [
                [0,0,1,1,0,0,0,0,0,0,0,0,1,1,0,0],
                [0,1,2,2,1,0,0,0,0,0,0,1,2,2,1,0],
                [0,1,2,4,2,1,1,1,1,1,1,2,4,2,1,0],
                [0,1,2,2,2,2,2,2,2,2,2,2,2,2,1,0],
                [0,1,2,2,2,2,2,2,2,2,2,2,2,2,1,0],
                [1,2,2,1,5,2,2,2,2,2,2,5,1,2,2,1],
                [1,2,2,1,5,2,2,2,2,2,2,5,1,2,2,1],
                [1,2,2,2,2,2,2,4,4,2,2,2,2,2,2,1],
                [1,2,3,3,2,2,1,2,2,1,2,2,3,3,2,1],
                [0,1,2,3,3,1,2,2,2,2,1,3,3,2,1,0],
                [0,0,1,1,1,1,1,1,1,1,1,1,1,1,0,0],
                [0,0,0,1,2,2,3,3,3,3,2,2,1,0,0,0],
                [0,0,1,2,2,2,3,3,3,3,2,2,2,1,0,1],
                [0,0,1,2,2,2,3,3,3,3,2,2,2,1,1,2],
                [0,0,1,3,3,1,2,2,2,2,1,3,3,1,2,0],
                [0,0,0,1,1,0,1,1,1,1,0,1,1,0,0,0]
              ]
            }
            """.trimIndent(),
            """
            {
              "id": "heart",
              "name": "Сердце",
              "rows": 16,
              "cols": 16,
              "palette": {
                "1": "#880E4F",
                "2": "#E91E63",
                "3": "#F48FB1",
                "4": "#FFFFFF"
              },
              "grid": [
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,1,1,1,1,0,0,0,0,1,1,1,1,0,0],
                [0,1,2,2,2,2,1,0,0,1,2,2,2,2,1,0],
                [1,2,3,4,4,2,2,1,1,2,2,2,2,2,2,1],
                [1,2,4,4,2,2,2,2,2,2,2,2,2,2,2,1],
                [1,2,3,2,2,2,2,2,2,2,2,2,2,2,2,1],
                [1,2,2,2,2,2,2,2,2,2,2,2,2,2,2,1],
                [1,2,2,2,2,2,2,2,2,2,2,2,2,2,2,1],
                [0,1,2,2,2,2,2,2,2,2,2,2,2,2,1,0],
                [0,0,1,2,2,2,2,2,2,2,2,2,2,1,0,0],
                [0,0,0,1,2,2,2,2,2,2,2,2,1,0,0,0],
                [0,0,0,0,1,2,2,2,2,2,2,1,0,0,0,0],
                [0,0,0,0,0,1,2,2,2,2,1,0,0,0,0,0],
                [0,0,0,0,0,0,1,2,2,1,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,1,1,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]
              ]
            }
            """.trimIndent(),
            """
            {
              "id": "mushroom",
              "name": "Гриб",
              "rows": 16,
              "cols": 16,
              "palette": {
                "1": "#212121",
                "2": "#E53935",
                "3": "#FFFFFF",
                "4": "#FFE0B2"
              },
              "grid": [
                [0,0,0,0,0,1,1,1,1,1,1,0,0,0,0,0],
                [0,0,0,1,1,2,2,3,3,2,2,1,1,0,0,0],
                [0,0,1,2,2,2,3,3,3,3,2,2,2,1,0,0],
                [0,1,2,2,2,2,2,3,3,2,2,2,2,2,1,0],
                [0,1,3,3,2,2,2,2,2,2,2,2,3,3,1,0],
                [1,3,3,3,3,2,2,2,2,2,2,3,3,3,3,1],
                [1,3,3,3,3,2,2,3,3,2,2,3,3,3,3,1],
                [1,2,3,3,2,2,3,3,3,3,2,2,3,3,2,1],
                [1,2,2,2,2,2,3,3,3,3,2,2,2,2,2,1],
                [0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0],
                [0,0,0,0,1,4,4,4,4,4,4,1,0,0,0,0],
                [0,0,0,1,4,4,1,4,4,1,4,4,1,0,0,0],
                [0,0,0,1,4,4,1,4,4,1,4,4,1,0,0,0],
                [0,0,0,1,4,4,4,4,4,4,4,4,1,0,0,0],
                [0,0,0,0,1,4,4,4,4,4,4,1,0,0,0,0],
                [0,0,0,0,0,1,1,1,1,1,1,0,0,0,0,0]
              ]
            }
            """.trimIndent(),
            """
            {
              "id": "car",
              "name": "Машинка",
              "rows": 16,
              "cols": 16,
              "palette": {
                "1": "#263238",
                "2": "#1E88E5",
                "3": "#81D4FA",
                "4": "#FDD835",
                "5": "#B0BEC5"
              },
              "grid": [
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,1,1,1,1,1,1,1,0,0,0,0,0],
                [0,0,0,1,2,2,2,2,2,2,2,1,0,0,0,0],
                [0,0,1,2,3,3,3,1,3,3,3,2,1,0,0,0],
                [0,0,1,2,3,3,3,1,3,3,3,2,1,0,0,0],
                [0,1,2,2,2,2,2,2,2,2,2,2,2,1,1,0],
                [1,2,2,2,2,2,2,2,2,2,2,2,2,2,4,1],
                [1,4,2,2,2,2,2,2,2,2,2,2,2,2,4,1],
                [1,2,1,1,1,2,2,2,2,2,1,1,1,2,2,1],
                [1,1,1,5,1,1,1,1,1,1,1,5,1,1,1,1],
                [0,0,1,1,1,0,0,0,0,0,1,1,1,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]
              ]
            }
            """.trimIndent(),
            """
            {
              "id": "umbrella",
              "name": "Зонт",
              "rows": 16,
              "cols": 16,
              "palette": {
                "1": "#212121",
                "2": "#AB47BC",
                "3": "#29B6F6",
                "4": "#FFA726",
                "5": "#8D6E63"
              },
              "grid": [
                [0,0,0,0,0,0,0,1,1,0,0,0,0,0,0,0],
                [0,0,0,0,0,1,1,2,2,1,1,0,0,0,0,0],
                [0,0,0,1,1,3,3,2,2,4,4,1,1,0,0,0],
                [0,0,1,3,3,3,3,2,2,4,4,4,4,1,0,0],
                [0,1,2,2,3,3,2,2,2,2,4,4,2,2,1,0],
                [1,2,2,2,3,3,2,2,2,2,4,4,2,2,2,1],
                [1,2,2,3,3,3,2,2,2,2,4,4,4,2,2,1],
                [1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1],
                [0,0,0,0,0,0,0,5,5,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,5,5,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,5,5,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,5,5,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,5,5,0,0,0,0,0,0,0],
                [0,0,0,0,1,1,0,5,5,0,0,0,0,0,0,0],
                [0,0,0,0,1,5,5,5,5,0,0,0,0,0,0,0],
                [0,0,0,0,0,1,1,1,0,0,0,0,0,0,0,0]
              ]
            }
            """.trimIndent(),
            """
            {
              "id": "sword_epic",
              "name": "Меч",
              "rows": 32,
              "cols": 32,
              "palette": {
                "1": "#37474F",
                "2": "#90A4AE",
                "3": "#CFD8DC",
                "4": "#FFD54F",
                "5": "#FF6F00",
                "6": "#8D6E63"
              },
              "grid": [
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,3],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,3,2],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,3,2,2],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,3,2,2,1],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,3,2,2,1,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,3,2,2,1,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,3,2,2,1,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,3,2,2,1,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,3,2,2,1,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,3,2,2,1,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,3,2,2,1,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,3,2,2,1,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,3,2,2,1,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,3,2,2,1,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,3,2,2,1,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,3,2,2,1,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,3,2,2,1,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,1,3,2,2,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,1,3,2,2,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,1,3,2,2,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,1,3,2,2,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,1,3,2,2,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,1,3,2,2,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,1,4,4,4,1,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,1,4,5,5,4,4,4,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,1,4,4,5,5,5,4,4,4,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,1,1,1,4,4,5,4,4,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,1,1,6,1,1,4,4,4,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,1,1,6,6,1,1,1,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,1,1,6,6,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [1,1,1,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]
              ]
            }
            """.trimIndent()
        )
    }
}