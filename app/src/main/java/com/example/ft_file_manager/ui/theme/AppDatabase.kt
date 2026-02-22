package com.example.ft_file_manager

import android.content.Context
import androidx.room.*

// 1. Ο Πίνακας
@Entity(tableName = "folder_cache")
data class FolderCacheEntity(
    @PrimaryKey val path: String,
    val size: Long,
    val fileCount: Int,
    val folderCount: Int,
    val lastModified: Long
)

// 2. Οι Εντολές
@Dao
interface FolderDao {
    @Query("SELECT * FROM folder_cache WHERE path = :path LIMIT 1")
    fun getFolder(path: String): FolderCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertFolder(folder: FolderCacheEntity)
}

// 3. Η Βάση
@Database(entities = [FolderCacheEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java, "folder_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}