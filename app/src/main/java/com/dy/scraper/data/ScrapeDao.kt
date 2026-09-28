package com.dy.scraper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dy.scraper.data.entity.ScrapeRecord
import com.dy.scraper.data.entity.SkippedRecord
import com.dy.scraper.data.entity.UrlMapping

@Dao
interface ScrapeDao {

    // ── 已抓取记录 ──
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecord(record: ScrapeRecord): Long

    @Query("SELECT * FROM scrape_records WHERE short_url = :shortUrl LIMIT 1")
    suspend fun getRecordByShortUrl(shortUrl: String): ScrapeRecord?

    // ── 跳过记录 ──
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSkipped(record: SkippedRecord): Long

    @Query("SELECT * FROM skipped_records WHERE short_url = :shortUrl LIMIT 1")
    suspend fun getSkippedByShortUrl(shortUrl: String): SkippedRecord?

    // ── URL 映射 ──
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUrlMapping(mapping: UrlMapping): Long

    @Query("SELECT * FROM url_mapping WHERE final_url = :finalUrl LIMIT 1")
    suspend fun getByFinalUrl(finalUrl: String): UrlMapping?

    // ── 批量查询（启动时过滤已处理的 URL） ──
    @Query("SELECT short_url FROM scrape_records")
    suspend fun getAllProcessedUrls(): List<String>

    @Query("SELECT short_url FROM skipped_records")
    suspend fun getAllSkippedUrls(): List<String>
}