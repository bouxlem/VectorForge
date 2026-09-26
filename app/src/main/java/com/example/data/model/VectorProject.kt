package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vector_projects")
data class VectorProject(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val qualityMode: String,
    val svgContent: String,
    val width: Int,
    val height: Int,
    val pathCount: Int,
    val nodeCount: Int,
    val fileSizeBytes: Long,
    val ssimScore: Double,
    val edgeIoU: Double,
    val category: String,
    val timestamp: Long = System.currentTimeMillis()
)
