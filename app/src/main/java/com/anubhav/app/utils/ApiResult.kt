package com.anubhav.app.utils

import java.io.IOException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/** A cancelled screen must stop its work instead of showing an error or saving a session. */
suspend inline fun <T> apiResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

/** Only connectivity/server outages qualify for offline fallback, never a rejected login. */
fun canUseOfflineCache(error: Throwable): Boolean =
    error is IOException || (error is HttpException && error.code() in 500..599)
