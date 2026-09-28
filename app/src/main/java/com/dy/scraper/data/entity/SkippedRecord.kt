package com.dy.scraper.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "skipped_records",
    indices = [Index(value = ["short_url"], unique = true)]
)
data class SkippedRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val shortUrl: String,
    val albumName: String = "",
    val albumCode: String = "",
    val remark: String = "",
    val skipReason: String = "",
    val createTime: String = ""
)