package com.dy.scraper.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scrape_records",
    indices = [Index(value = ["shortUrl"], unique = true)]
)
data class ScrapeRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val shortUrl: String,
    val finalUrl: String = "",
    val albumName: String = "",
    val albumCode: String = "",
    val remark: String = "",
    val createTime: String = ""
)