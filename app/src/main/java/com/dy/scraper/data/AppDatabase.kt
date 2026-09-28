package com.dy.scraper.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.dy.scraper.data.entity.AppConfigEntity
import com.dy.scraper.data.entity.ScrapeRecord
import com.dy.scraper.data.entity.SkippedRecord
import com.dy.scraper.data.entity.UrlMapping

@Database(
    entities = [
        ScrapeRecord::class,
        SkippedRecord::class,
        UrlMapping::class,
        AppConfigEntity::class,
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun scrapeDao(): ScrapeDao
    abstract fun configDao(): ConfigDao

    companion object {
        private const val DB_NAME = "douyin_scraper.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}