package com.nextsoundz.showcase.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nextsoundz.showcase.network.ApiResult
import com.nextsoundz.showcase.network.SoundKitApi
import com.nextsoundz.showcase.network.apiCall
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * DEMONSTRATION SAMPLE — rewritten for this public repository.
 *
 * ---
 *
 * Cursor-based incremental catalog sync, as a `CoroutineWorker`.
 *
 * **What this replaced.** The original job re-downloaded the entire catalog on a weekly
 * timer. As the catalog grew that became a large, wasteful transfer that mostly re-wrote
 * identical rows, and a user who opened the app between runs saw stale content for up to a
 * week. The replacement asks "what changed since cursor X" and typically transfers nothing.
 *
 * **Worker design notes:**
 *
 *  - `CoroutineWorker` so `doWork` is a suspend function — no manual threading, and
 *    WorkManager's cancellation maps onto coroutine cancellation.
 *  - **Pages in a loop, committing each page** (see [SoundKitDao.applyChanges]). Interrupted
 *    halfway, the next run resumes from the last committed cursor rather than restarting.
 *  - **`Result.retry()` for transport failures, `Result.failure()` for semantic ones.**
 *    Retrying a 4xx forever burns battery to no purpose; retrying a timeout is correct.
 *  - **Exponential backoff** so a backend outage does not turn every installed device into
 *    a load generator against a server that is already struggling.
 *  - **A page cap per run.** A first sync on a large catalog is bounded rather than
 *    unbounded; the remainder arrives on the next run. Unbounded loops in background workers
 *    are how you end up in the Play Console's battery reports.
 */
@HiltWorker
class CatalogSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val api: SoundKitApi,
    private val dao: SoundKitDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        var cursor = dao.syncState()?.cursor
        var pages = 0

        while (pages < MAX_PAGES_PER_RUN) {
            when (val result = apiCall { api.getChangesSince(cursor) }) {

                is ApiResult.Success -> {
                    val changes = result.data

                    dao.applyChanges(
                        updated = changes.updated.map { it.toEntity() },
                        deletedIds = changes.deletedIds,
                        nextCursor = changes.nextCursor,
                        syncedAt = clock(),
                    )

                    if (!changes.hasMore || changes.nextCursor == null) return Result.success()

                    // Guard against a backend that returns the same cursor forever, which
                    // would otherwise spin this loop until the page cap on every single run.
                    if (changes.nextCursor == cursor) return Result.success()

                    cursor = changes.nextCursor
                    pages++
                }

                // Transport problems are worth another attempt later.
                is ApiResult.NetworkError -> return Result.retry()

                // 5xx is transient; 4xx means retrying will not help.
                is ApiResult.ApiError ->
                    return if (result.isServerFault) Result.retry() else Result.failure()

                is ApiResult.UnexpectedResponse -> return Result.failure()
            }
        }

        // Hit the page cap with work remaining: succeed and let the next run continue from
        // the committed cursor.
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "catalog-sync"
        private const val MAX_PAGES_PER_RUN = 20

        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<CatalogSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        // Catalog freshness is never worth draining a nearly-flat battery.
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            workManager.enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                // KEEP, not UPDATE: re-scheduling on every app start with UPDATE resets the
                // period, so on a frequently-opened app the job can starve and never run.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

private fun com.nextsoundz.showcase.network.SoundKitDto.toEntity() = SoundKitEntity(
    id = id,
    name = name,
    creatorName = creatorName,
    coverUrl = coverUrl,
    sampleCount = sampleCount,
    bpm = bpm,
    isPremium = isPremium,
    updatedAt = updatedAt?.let(::parseIso8601) ?: 0L,
)

private fun parseIso8601(value: String): Long = runCatching {
    java.time.Instant.parse(value).toEpochMilli()
}.getOrDefault(0L)
