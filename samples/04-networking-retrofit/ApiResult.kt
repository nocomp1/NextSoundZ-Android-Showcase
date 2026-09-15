package com.nextsoundz.showcase.network

import retrofit2.HttpException
import java.io.IOException

/**
 * DEMONSTRATION SAMPLE — rewritten for this public repository.
 *
 * ---
 *
 * The boundary type between the data layer and everything above it.
 *
 * Why a sealed type instead of throwing: an exception crossing into a ViewModel turns
 * every `viewModelScope.launch` into a potential crash site, and `try/catch` in a ViewModel
 * inevitably collapses distinct failures into one "something went wrong" toast.
 *
 * Distinguishing [NetworkError] from [ApiError] matters to the *user*, not just to us:
 * offline is retryable and the UI should offer a retry; a 403 on a premium kit should open
 * the paywall; a 404 should remove a stale item from the list. Same failure channel,
 * three completely different correct behaviours.
 */
sealed interface ApiResult<out T> {

    data class Success<T>(val data: T) : ApiResult<T>

    /** No usable connection, DNS failure, timeout. Retryable. */
    data class NetworkError(val cause: IOException) : ApiResult<Nothing>

    /** The server answered, but not with what we wanted. */
    data class ApiError(
        val code: Int,
        val message: String?,
    ) : ApiResult<Nothing> {
        val isUnauthorized get() = code == 401
        val isForbidden get() = code == 403
        val isNotFound get() = code == 404
        val isServerFault get() = code in 500..599
    }

    /** A 2xx whose body was missing, malformed, or failed the envelope's `success` flag. */
    data class UnexpectedResponse(val reason: String) : ApiResult<Nothing>
}

/**
 * Runs an API call and maps every failure mode into [ApiResult].
 *
 * `CancellationException` is deliberately **not** caught. Swallowing it breaks structured
 * concurrency: a cancelled screen's in-flight request would report a fake error and the
 * cancellation would never propagate. This is a subtle and very common mistake in
 * `runCatching`-based wrappers.
 */
suspend fun <T> apiCall(block: suspend () -> ApiEnvelope<T>): ApiResult<T> =
    try {
        val envelope = block()
        val body = envelope.data
        when {
            !envelope.success -> ApiResult.UnexpectedResponse(
                envelope.message ?: "Request reported failure"
            )
            body == null -> ApiResult.UnexpectedResponse("Envelope contained no data")
            else -> ApiResult.Success(body)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: HttpException) {
        ApiResult.ApiError(e.code(), e.message())
    } catch (e: IOException) {
        ApiResult.NetworkError(e)
    }

/** Maps the payload while leaving failures untouched — used for DTO→domain mapping. */
inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.NetworkError -> this
    is ApiResult.ApiError -> this
    is ApiResult.UnexpectedResponse -> this
}
