package com.nextsoundz.showcase.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * DEMONSTRATION SAMPLE — rewritten for this public repository.
 * Production has 23 service interfaces. Real paths and hosts are not published here.
 *
 * ---
 *
 * A Retrofit service in the style used across `:core:networking`.
 *
 *  - **`suspend` functions, no `Call<T>`.** Cancellation comes free from structured
 *    concurrency; there is no callback layer to leak a reference through.
 *  - **The backend's envelope is modelled, not fought.** The API wraps every payload in a
 *    `{ success, data, message }` envelope, so a generic [ApiEnvelope] captures that once
 *    instead of each response type re-declaring it.
 *  - **Moshi with `@JsonClass(generateAdapter = true)`** — codegen, not reflection, so
 *    parsing is fast and survives R8 without keep rules for every DTO.
 *  - **DTOs are not domain models.** These types belong to the wire and change when the
 *    backend changes; mapping to domain happens in the repository so a backend rename
 *    cannot ripple into the UI.
 */
interface SoundKitApi {

    @GET("sound-kits")
    suspend fun getSoundKits(
        @Query("page") page: Int = 1,
        @Query("per_page") perPage: Int = DEFAULT_PAGE_SIZE,
    ): ApiEnvelope<List<SoundKitDto>>

    @GET("sound-kits/{id}")
    suspend fun getSoundKit(
        @Path("id") id: String,
    ): ApiEnvelope<SoundKitDto>

    /**
     * Incremental catalog sync. The client sends the cursor it last saw and receives only
     * what changed. See samples/05-room-offline-sync for the consuming side.
     */
    @GET("sound-kits/changes")
    suspend fun getChangesSince(
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int = DEFAULT_PAGE_SIZE,
    ): ApiEnvelope<CatalogChangesDto>

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}

/** The envelope every endpoint returns. */
@JsonClass(generateAdapter = true)
data class ApiEnvelope<T>(
    @Json(name = "success") val success: Boolean,
    @Json(name = "data") val data: T?,
    @Json(name = "message") val message: String? = null,
)

@JsonClass(generateAdapter = true)
data class SoundKitDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "creator_name") val creatorName: String?,
    @Json(name = "cover_url") val coverUrl: String?,
    @Json(name = "sample_count") val sampleCount: Int = 0,
    @Json(name = "bpm") val bpm: Int? = null,
    @Json(name = "is_premium") val isPremium: Boolean = false,
    @Json(name = "updated_at") val updatedAt: String? = null,
)

@JsonClass(generateAdapter = true)
data class CatalogChangesDto(
    @Json(name = "updated") val updated: List<SoundKitDto> = emptyList(),
    @Json(name = "deleted_ids") val deletedIds: List<String> = emptyList(),
    @Json(name = "next_cursor") val nextCursor: String? = null,
    @Json(name = "has_more") val hasMore: Boolean = false,
)
