package com.nextsoundz.showcase.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * DEMONSTRATION SAMPLE — rewritten for this public repository.
 *
 * ---
 *
 * The Hilt networking module. Two points worth calling out, both learned the hard way:
 *
 * **1. The base URL is injected, never hardcoded.** It is supplied as a build-config value
 * per build type. No production hostname appears in source — which is also why you will not
 * find one in this repository.
 *
 * **2. Body logging is debug-only, and the release path has no logging interceptor at all.**
 * `HttpLoggingInterceptor.Level.BODY` in a release build writes auth tokens and full
 * payloads to logcat, readable by anything with log access on the device. Gating it on
 * `isDebug` at *construction* means the interceptor is never even added in release, rather
 * than added and set to NONE.
 *
 * **On TLS:** production uses the platform's default certificate validation. A custom
 * `HostnameVerifier` that returns `true` — a pattern that shows up in a lot of Android
 * tutorials and in more than one shipping app — disables hostname checking entirely and
 * makes the app trivially interceptable by any proxy. Never ship one. If a staging server
 * has a self-signed certificate, pin that certificate in the debug build only.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttp(
        authInterceptor: AuthInterceptor,
        config: NetworkConfig,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .apply {
            if (config.isDebug) {
                addInterceptor(
                    HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
                )
            }
        }
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(
        client: OkHttpClient,
        moshi: Moshi,
        config: NetworkConfig,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(config.baseUrl)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    @Provides
    @Singleton
    fun provideSoundKitApi(retrofit: Retrofit): SoundKitApi =
        retrofit.create(SoundKitApi::class.java)

    private const val CONNECT_TIMEOUT_SECONDS = 30L

    /**
     * Read timeouts are generous because some endpoints proxy long-running server-side audio
     * jobs. Those are polled with a bounded number of attempts rather than held open
     * indefinitely — an unbounded poll once left a progress dialog stranded on screen with
     * no way out, which is a worse failure than an honest timeout.
     */
    private const val READ_TIMEOUT_SECONDS = 60L
}

/** Supplied from BuildConfig. No endpoint literals live in source. */
data class NetworkConfig(
    val baseUrl: String,
    val isDebug: Boolean,
)

/** Attaches the bearer token, and never logs it. */
class AuthInterceptor @javax.inject.Inject constructor(
    private val tokenProvider: TokenProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val token = tokenProvider.currentToken()
            ?: return chain.proceed(chain.request())

        val authorized = chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        return chain.proceed(authorized)
    }

    fun interface TokenProvider {
        fun currentToken(): String?
    }
}
