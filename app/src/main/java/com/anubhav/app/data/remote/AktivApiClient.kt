package com.anubhav.app.data.remote

import com.anubhav.app.BuildConfig
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object AktivApiClient {
    /**
     * Public API via the Cloudflare tunnel (works off-LAN). Overridden by the
     * BuildConfig.AKTIV_API_URL field; set AKTIV_API_URL=http://192.168.29.157:8080/
     * in local.properties for on-LAN development.
     */
    const val DEFAULT_BASE_URL = "https://api.anubhavlifecare.in/"

    private val gson = GsonBuilder().setLenient().create()

    private val httpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // The deployed customer API rejects unkeyed requests with 401 "Invalid or missing
            // API key". Attach the build-time key on every request; when it is blank (dev
            // builds, on-LAN API with no gate) no header is added and behaviour is unchanged.
            .addInterceptor { chain ->
                val key = BuildConfig.AKTIV_API_KEY
                val headerName = BuildConfig.AKTIV_API_KEY_HEADER
                val request = if (key.isNotBlank() && headerName.isNotBlank()) {
                    chain.request().newBuilder()
                        .header(headerName, key)
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .build()
    }

    val api: AktivApi by lazy {
        retrofit.create(AktivApi::class.java)
    }

    val customerApi: CustomerApi by lazy {
        retrofit.create(CustomerApi::class.java)
    }

    val catalogApi: CatalogApi by lazy {
        retrofit.create(CatalogApi::class.java)
    }

    // One Retrofit for every interface: three separate instances each built their
    // own converter and call adapters for no benefit.
    private val retrofit: Retrofit by lazy { buildRetrofit() }

    private fun buildRetrofit(): Retrofit {
        // Read BuildConfig directly. Reaching for it via Class.forName/getField
        // silently falls back to DEFAULT_BASE_URL once R8 shrinks or renames the
        // field, which would point release builds at the wrong host.
        val baseUrl = BuildConfig.AKTIV_API_URL.ifBlank { DEFAULT_BASE_URL }

        return Retrofit.Builder()
            .baseUrl(baseUrl.ensureTrailingSlash())
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    private fun String.ensureTrailingSlash(): String =
        if (endsWith("/")) this else "$this/"
}
