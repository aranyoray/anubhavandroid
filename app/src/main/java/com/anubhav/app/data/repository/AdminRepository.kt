package com.anubhav.app.data.repository

import android.content.Context
import com.anubhav.app.data.model.AdminReport
import com.anubhav.app.data.model.AktivBookingRequest
import com.anubhav.app.data.model.AktivBookingResponse
import com.anubhav.app.data.model.AktivLoginRequest
import com.anubhav.app.data.model.AktivLoginResponse
import com.anubhav.app.data.model.StaffBill
import com.anubhav.app.data.model.StaffBillDetail
import com.anubhav.app.data.model.StaffBillEdit
import com.anubhav.app.data.remote.AktivApiClient
import com.anubhav.app.utils.StaffSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

class AdminRepository(
    private val api: com.anubhav.app.data.remote.AdminApi = AktivApiClient.adminApi,
) {
    private suspend fun <T> io(block: suspend () -> T): Result<T> =
        withContext(Dispatchers.IO) { runCatching { block() } }

    private val token: String get() = StaffSession.current?.token.orEmpty()

    /** AKTIV user id + password; on success the session holds the user's token and rights. */
    suspend fun signIn(userid: String, password: String): Result<AktivLoginResponse> = io {
        AktivApiClient.api.login(AktivLoginRequest(userid.trim(), password)).also { StaffSession.current = it }
    }

    suspend fun report(
        report: String,
        start: String,
        end: String,
        billDetails: Boolean,
        testDetails: Boolean,
    ): Result<AdminReport> = io { api.report(token, report, start, end, billDetails, testDetails) }

    suspend fun searchBills(query: String): Result<List<StaffBill>> = io { api.searchBills(token, query) }

    suspend fun bill(billKey: Int): Result<StaffBillDetail> = io { api.bill(token, billKey) }

    suspend fun editBill(billKey: Int, edit: StaffBillEdit): Result<StaffBillDetail> =
        io { api.editBill(token, billKey, edit) }

    suspend fun createBooking(request: AktivBookingRequest): Result<AktivBookingResponse> =
        io { api.createBooking(token, request) }

    suspend fun cancelBooking(billKey: Int): Result<Unit> = io { api.cancelBooking(token, billKey); Unit }

    /** The bill's authorised reports as one PDF, into the app cache (not the patient report cache). */
    suspend fun reportPdf(context: Context, billKey: Int): Result<File> = io {
        val file = File(File(context.cacheDir, "staff_reports").apply { mkdirs() }, "bill_$billKey.pdf")
        val req = Request.Builder()
            .url(AktivApiClient.url("api/staff/bills/$billKey/pdf"))
            .header("X-Staff-Token", token)
            .build()
        AktivApiClient.httpClient.newCall(req).execute().use { resp ->
            val body = resp.body ?: error("empty response")
            if (!resp.isSuccessful) throw ServerError(resp.code, detailOf(body.string()))
            file.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        file
    }

    class ServerError(val code: Int, message: String?) : Exception(message)

    companion object {
        private fun detailOf(json: String?): String? =
            runCatching { org.json.JSONObject(json.orEmpty()).optString("detail").ifBlank { null } }.getOrNull()

        /** HTTP status of a failed call, or null for network trouble. */
        fun statusOf(t: Throwable): Int? = when (t) {
            is retrofit2.HttpException -> t.code()
            is ServerError -> t.code
            else -> null
        }

        /** The server's own explanation ("Your AKTIV role does not allow this"), when it sent one. */
        fun messageOf(t: Throwable): String? = when (t) {
            is retrofit2.HttpException -> detailOf(t.response()?.errorBody()?.string())
            is ServerError -> t.message
            else -> null
        }
    }
}
