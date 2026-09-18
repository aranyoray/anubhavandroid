package com.anubhav.app.data.repository

import com.anubhav.app.data.model.AdminReport
import com.anubhav.app.data.remote.AktivApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AdminRepository(
    private val api: com.anubhav.app.data.remote.AdminApi = AktivApiClient.adminApi,
) {
    /** True when the password is accepted by the server. */
    suspend fun check(password: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching { api.check(password).ok }
    }

    suspend fun report(
        password: String,
        report: String,
        start: String,
        end: String,
        billDetails: Boolean,
        testDetails: Boolean,
    ): Result<AdminReport> = withContext(Dispatchers.IO) {
        runCatching { api.report(password, report, start, end, billDetails, testDetails) }
    }
}
