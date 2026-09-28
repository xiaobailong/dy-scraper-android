package com.dy.scraper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dy.scraper.data.entity.AppConfigEntity

@Dao
interface ConfigDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setConfig(entity: AppConfigEntity)

    @Query("SELECT value FROM config WHERE `key` = :key LIMIT 1")
    suspend fun getConfig(key: String): String?

    @Query("SELECT * FROM config")
    suspend fun getAllConfig(): List<AppConfigEntity>
}