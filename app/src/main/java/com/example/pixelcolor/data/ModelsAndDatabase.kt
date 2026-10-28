package com.example.pixelcolor.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

// Модель шаблона из JSON
data class Template(
    val id: String,
    val name: String,
    val rows: Int,
    val cols: Int,
    val palette: Map<Int, String>,
    val grid: List<List<Int>>
) {
    // Количество клеток, которые нужно закрасить (все клетки > 0)
    val totalColorableCells: Int
        get() = grid.sumOf { row -> row.count { it > 0 } }
}

// Таблица Room: прогресс раскрашивания
@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val templateId: String,
    val filledCellsJson: String, // JSON со списком закрашенных координат ["0_2", "1_3"]
    val updatedAt: Long
)

// Таблица Room: сохранённые готовые работы
@Entity(tableName = "artworks")
data class ArtworkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: String,
    val templateName: String,
    val filePath: String,
    val createdAt: Long
)

@Dao
interface PixelColorDao {
    @Query("SELECT * FROM progress")
    suspend fun getAllProgress(): List<ProgressEntity>

    @Query("SELECT * FROM progress WHERE templateId = :templateId LIMIT 1")
    suspend fun getProgress(templateId: String): ProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: ProgressEntity)

    @Query("SELECT * FROM artworks ORDER BY createdAt DESC")
    suspend fun getAllArtworks(): List<ArtworkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtwork(artwork: ArtworkEntity): Long

    @Query("DELETE FROM artworks WHERE id = :id")
    suspend fun deleteArtwork(id: Long)
}

@Database(entities = [ProgressEntity::class, ArtworkEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): PixelColorDao

    companion object {
        @Volatile
        private var INSTANCE: PixelColorDao? = null

        fun getDao(context: Context): PixelColorDao {
            return INSTANCE ?: synchronized(this) {
                val instance = try {
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "pixel_color.db"
                    ).build().dao()
                } catch (_: Exception) {
                    // Встроенный SQLite-движок для работы таблиц progress и artworks без настройки KSP
                    SqlitePixelColorDao(context.applicationContext)
                }
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * Реализация DAO поверх SQLite с теми же таблицами `progress` и `artworks`,
 * чтобы БД гарантированно работала без ошибок компиляции плагинов KSP/KAPT.
 */
private class SqlitePixelColorDao(context: Context) :
    SQLiteOpenHelper(context, "pixel_color.db", null, 1), PixelColorDao {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS progress (
                templateId TEXT PRIMARY KEY NOT NULL,
                filledCellsJson TEXT NOT NULL,
                updatedAt INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS artworks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                templateId TEXT NOT NULL,
                templateName TEXT NOT NULL,
                filePath TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    override suspend fun getAllProgress(): List<ProgressEntity> {
        val list = mutableListOf<ProgressEntity>()
        readableDatabase.rawQuery("SELECT templateId, filledCellsJson, updatedAt FROM progress", null).use { c ->
            while (c.moveToNext()) {
                list.add(ProgressEntity(c.getString(0), c.getString(1), c.getLong(2)))
            }
        }
        return list
    }

    override suspend fun getProgress(templateId: String): ProgressEntity? {
        readableDatabase.rawQuery(
            "SELECT templateId, filledCellsJson, updatedAt FROM progress WHERE templateId = ?",
            arrayOf(templateId)
        ).use { c ->
            if (c.moveToFirst()) {
                return ProgressEntity(c.getString(0), c.getString(1), c.getLong(2))
            }
        }
        return null
    }

    override suspend fun saveProgress(progress: ProgressEntity) {
        val cv = ContentValues().apply {
            put("templateId", progress.templateId)
            put("filledCellsJson", progress.filledCellsJson)
            put("updatedAt", progress.updatedAt)
        }
        writableDatabase.insertWithOnConflict("progress", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override suspend fun getAllArtworks(): List<ArtworkEntity> {
        val list = mutableListOf<ArtworkEntity>()
        readableDatabase.rawQuery(
            "SELECT id, templateId, templateName, filePath, createdAt FROM artworks ORDER BY createdAt DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    ArtworkEntity(
                        id = c.getLong(0),
                        templateId = c.getString(1),
                        templateName = c.getString(2),
                        filePath = c.getString(3),
                        createdAt = c.getLong(4)
                    )
                )
            }
        }
        return list
    }

    override suspend fun insertArtwork(artwork: ArtworkEntity): Long {
        val cv = ContentValues().apply {
            put("templateId", artwork.templateId)
            put("templateName", artwork.templateName)
            put("filePath", artwork.filePath)
            put("createdAt", artwork.createdAt)
        }
        return writableDatabase.insert("artworks", null, cv)
    }

    override suspend fun deleteArtwork(id: Long) {
        writableDatabase.delete("artworks", "id = ?", arrayOf(id.toString()))
    }
}