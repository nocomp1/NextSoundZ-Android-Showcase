package com.nextsoundz.showcase.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * DEMONSTRATION SAMPLE — rewritten for this public repository. The production database has
 * 7 entities and is at schema version 4.
 *
 * ---
 *
 * Room as the single source of truth for the UI.
 *
 * The rule: **the UI never reads the network.** It observes Room. Sync writes to Room. This
 * means a screen renders identically and instantly whether the user is online, offline, or
 * on a connection that is technically up but useless — which, on mobile, is most of them.
 *
 * `exportSchema = true` is on so schema JSON is committed and migrations are testable
 * against real historical schemas rather than written hopefully.
 */
@Entity(tableName = "sound_kits")
data class SoundKitEntity(
    @PrimaryKey val id: String,
    val name: String,
    val creatorName: String?,
    val coverUrl: String?,
    val sampleCount: Int,
    val bpm: Int?,
    val isPremium: Boolean,
    /** Server-side ordering value; also what the sync cursor is derived from. */
    val updatedAt: Long,
)

/**
 * Local-only state, kept in a **separate table** from the server-owned catalog.
 *
 * This separation is deliberate and was a bug fix. When "is this kit downloaded" lived on
 * the catalog row, a catalog refresh overwrote it, and users watched their downloaded kits
 * spontaneously appear un-downloaded. Server-owned data and device-owned data have
 * different lifetimes and must not share a row.
 */
@Entity(tableName = "kit_local_state")
data class KitLocalStateEntity(
    @PrimaryKey val kitId: String,
    val isDownloaded: Boolean,
    val localPath: String?,
    val lastOpenedAt: Long?,
)

/** One row. Stores where the last incremental sync got to. */
@Entity(tableName = "catalog_sync_state")
data class CatalogSyncStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val cursor: String?,
    val lastSyncedAt: Long,
) {
    companion object { const val SINGLETON_ID = 1 }
}

@Dao
interface SoundKitDao {

    /** Observed by the UI. Emits again automatically whenever sync writes. */
    @Query("SELECT * FROM sound_kits ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SoundKitEntity>>

    @Query("SELECT * FROM sound_kits WHERE id = :id")
    suspend fun findById(id: String): SoundKitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(kits: List<SoundKitEntity>)

    @Query("DELETE FROM sound_kits WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT * FROM catalog_sync_state WHERE id = :id")
    suspend fun syncState(id: Int = CatalogSyncStateEntity.SINGLETON_ID): CatalogSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSyncState(state: CatalogSyncStateEntity)

    /**
     * Applies one page of changes **atomically**.
     *
     * `@Transaction` is load-bearing here. Without it, a process death between the upsert
     * and the cursor write leaves the cursor pointing *past* data that was never stored —
     * and because sync is incremental, that page is never requested again. The catalog is
     * then permanently missing rows, with nothing to indicate it.
     *
     * Advancing the cursor in the same transaction as the data it describes is the whole
     * correctness argument for incremental sync.
     */
    @Transaction
    suspend fun applyChanges(
        updated: List<SoundKitEntity>,
        deletedIds: List<String>,
        nextCursor: String?,
        syncedAt: Long,
    ) {
        if (updated.isNotEmpty()) upsertAll(updated)
        if (deletedIds.isNotEmpty()) deleteByIds(deletedIds)
        setSyncState(CatalogSyncStateEntity(cursor = nextCursor, lastSyncedAt = syncedAt))
    }
}

@Database(
    entities = [
        SoundKitEntity::class,
        KitLocalStateEntity::class,
        CatalogSyncStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ShowcaseDatabase : RoomDatabase() {
    abstract fun soundKitDao(): SoundKitDao
}
