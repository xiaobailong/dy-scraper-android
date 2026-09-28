package com.dy.scraper.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "url_mapping",
    indices = [Index(value = ["finalUrl"], unique = true)]
)
data class UrlMapping(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val finalUrl: String,
    val shortUrl: String = "",
    val createTime: String = ""
)