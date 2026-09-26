package com.example.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.VectorProject
import kotlinx.coroutines.flow.Flow

@Dao
interface VectorProjectDao {
    @Query("SELECT * FROM vector_projects ORDER BY timestamp DESC")
    fun getAllProjects(): Flow<List<VectorProject>>

    @Query("SELECT * FROM vector_projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: Long): VectorProject?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: VectorProject): Long

    @Update
    suspend fun updateProject(project: VectorProject)

    @Delete
    suspend fun deleteProject(project: VectorProject)

    @Query("DELETE FROM vector_projects WHERE id = :id")
    suspend fun deleteById(id: Long)
}
