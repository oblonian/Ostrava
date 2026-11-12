package com.ostrava.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {

    @Insert
    suspend fun insertPoint(point: DraftPointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDraft(draft: RecordingDraftEntity)

    @Query("SELECT * FROM recording_draft WHERE id = 1")
    suspend fun getDraft(): RecordingDraftEntity?

    @Query("SELECT * FROM recording_draft WHERE id = 1")
    fun observeDraft(): Flow<RecordingDraftEntity?>

    @Query("SELECT * FROM draft_points ORDER BY id ASC")
    suspend fun getPoints(): List<DraftPointEntity>

    @Query("DELETE FROM draft_points")
    suspend fun deletePoints()

    @Query("DELETE FROM recording_draft")
    suspend fun deleteDraft()

    @Transaction
    suspend fun clear() {
        deleteDraft()
        deletePoints()
    }

    /** Starts a fresh draft, dropping anything left over from an earlier recording. */
    @Transaction
    suspend fun begin(draft: RecordingDraftEntity) {
        deletePoints()
        upsertDraft(draft)
    }
}
