package com.anubhav.app.data.repository

import android.content.Context
import com.anubhav.app.data.model.CollectorPatient
import com.anubhav.app.data.model.CollectorPatientRequest
import com.anubhav.app.data.model.CustomerBill
import com.anubhav.app.data.model.CustomerPaymentRequest
import com.anubhav.app.data.model.CustomerPaymentResponse
import com.anubhav.app.data.model.CustomerPrebookRequest
import com.anubhav.app.data.model.CustomerPrebookResponse
import com.anubhav.app.data.model.CustomerProfile
import com.anubhav.app.data.model.CustomerReport
import com.anubhav.app.data.model.CustomerVerifyRequest
import com.anubhav.app.data.model.CustomerVerifyResponse
import com.anubhav.app.data.model.PrebookCalendar
import com.anubhav.app.data.remote.AktivApiClient
import com.anubhav.app.utils.ReportCache
import com.anubhav.app.utils.PatientTokens
import com.anubhav.app.utils.apiResult
import com.anubhav.app.utils.canUseOfflineCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CustomerRepository(
    private val api: com.anubhav.app.data.remote.CustomerApi = AktivApiClient.customerApi,
) {
    class VerificationRequired : Exception("Verify your details in My Reports to continue")

    private fun requireVerifiedPhone(phone: String) {
        if (!PatientTokens.has(phone)) throw VerificationRequired()
    }

    companion object {
        fun needsVerification(error: Throwable): Boolean =
            error is VerificationRequired ||
                (error is retrofit2.HttpException && error.code() in listOf(401, 403))
    }

    /**
     * Cached calls touch the filesystem as well as the network. Retrofit moves its own
     * work off the main thread, but the JSON cache reads and writes around it do not,
     * so run the whole body on IO.
     */
    private suspend fun <T> io(block: suspend () -> T): Result<T> =
        withContext(Dispatchers.IO) { apiResult { block() } }
    /** Guest/patient 2-of-3 fuzzy login. Returns the matched patient + canonical phone. */
    suspend fun verify(
        name: String,
        phone: String,
        billNo: String = "",
        billDate: String? = null,
    ): Result<CustomerVerifyResponse> = apiResult {
        api.verify(CustomerVerifyRequest(name.trim(), phone.trim(), billNo.trim(), billDate)).also {
            check(!it.matched || !it.token.isNullOrBlank()) {
                "The server did not return a verified session. Please contact the clinic to update the API."
            }
        }
    }

    /** All visits (bills) under a phone since 2022, from the static all-history DB. */
    suspend fun getHistory(phone: String): Result<com.anubhav.app.data.model.CustomerHistoryResponse> =
        apiResult { api.getHistory(phone.trim()) }

    /**
     * Same history, but the retrieved PII is cached on the device (files dir, excluded
     * from cloud backup via allowBackup=false) so it stays local and works offline.
     */
    suspend fun getHistoryCached(
        context: Context,
        phone: String,
        forceRefresh: Boolean = false,
    ): Result<com.anubhav.app.data.model.CustomerHistoryResponse> = io {
        requireVerifiedPhone(phone)
        val cacheKey = "history_$phone"
        if (!forceRefresh) {
            ReportCache.readVisits(context, cacheKey)?.let {
                return@io com.anubhav.app.data.model.CustomerHistoryResponse(phone, it.size, it)
            }
        }
        apiResult { api.getHistory(phone.trim()) }
            .onSuccess { ReportCache.writeVisits(context, cacheKey, it.visits) }
            .getOrElse { error ->
                if (!canUseOfflineCache(error)) throw error
                val cached = ReportCache.readVisits(context, cacheKey, ReportCache.ANY_AGE) ?: throw error
                com.anubhav.app.data.model.CustomerHistoryResponse(phone, cached.size, cached)
            }
    }

    suspend fun getProfile(phone: String?, email: String?): Result<CustomerProfile> =
        apiResult { api.getProfile(phone = phone, email = email) }

    suspend fun getBills(phone: String): Result<List<CustomerBill>> =
        apiResult { api.getBills(phone) }

    suspend fun getBillsCached(
        context: Context,
        phone: String,
        forceRefresh: Boolean = false,
    ): Result<List<CustomerBill>> = io {
        requireVerifiedPhone(phone)
        val cacheKey = "bills_$phone"
        if (!forceRefresh) {
            ReportCache.readBills(context, cacheKey)?.let { return@io it }
        }
        apiResult { api.getBills(phone) }
            .onSuccess { ReportCache.writeBills(context, cacheKey, it) }
            .getOrElse { error ->
                if (!canUseOfflineCache(error)) throw error
                ReportCache.readBills(context, cacheKey, ReportCache.ANY_AGE) ?: throw error
            }
    }

    suspend fun getReports(phone: String): Result<List<CustomerReport>> =
        apiResult { api.getReports(phone) }

    suspend fun getReportsCached(
        context: Context,
        phone: String,
        forceRefresh: Boolean = false,
    ): Result<List<CustomerReport>> = io {
        requireVerifiedPhone(phone)
        val cacheKey = "customer_$phone"
        if (!forceRefresh) {
            ReportCache.read(context, cacheKey)?.let { return@io it }
        }
        apiResult { api.getReports(phone) }
            .onSuccess { ReportCache.write(context, cacheKey, it) }
            .getOrElse { error ->
                if (!canUseOfflineCache(error)) throw error
                ReportCache.read(context, cacheKey, ReportCache.ANY_AGE) ?: throw error
            }
    }

    suspend fun getPendingPayments(phone: String): Result<List<CustomerBill>> =
        apiResult { api.getPendingPayments(phone) }

    suspend fun getPendingPaymentsCached(
        context: Context,
        phone: String,
        forceRefresh: Boolean = false,
    ): Result<List<CustomerBill>> = io {
        requireVerifiedPhone(phone)
        val cacheKey = "pending_$phone"
        if (!forceRefresh) {
            ReportCache.readBills(context, cacheKey)?.let { return@io it }
        }
        apiResult { api.getPendingPayments(phone) }
            .onSuccess { ReportCache.writeBills(context, cacheKey, it) }
            .getOrElse { error ->
                if (!canUseOfflineCache(error)) throw error
                ReportCache.readBills(context, cacheKey, ReportCache.ANY_AGE) ?: throw error
            }
    }

    suspend fun payPending(request: CustomerPaymentRequest): Result<CustomerPaymentResponse> =
        apiResult { api.payPending(request) }

    suspend fun payPendingCached(
        context: Context,
        request: CustomerPaymentRequest,
    ): Result<CustomerPaymentResponse> =
        io {
            api.payPending(request).also {
                if (it.success) ReportCache.removePendingBill(context, request.phone, request.billKey)
            }
        }

    suspend fun getPrebookCalendar(): Result<PrebookCalendar> =
        apiResult { api.getPrebookCalendar() }

    suspend fun createPrebook(request: CustomerPrebookRequest): Result<CustomerPrebookResponse> =
        apiResult { api.createPrebook(request) }

    suspend fun getCollectorPatients(collectorUserKey: Int): Result<List<CollectorPatient>> =
        apiResult { api.getCollectorPatients(collectorUserKey) }

    suspend fun getCollectorPatientsCached(
        context: Context,
        collectorUserKey: Int,
        forceRefresh: Boolean = false,
    ): Result<List<CollectorPatient>> = io {
        val cacheKey = "collector_patients_$collectorUserKey"
        if (!forceRefresh) {
            ReportCache.readCollectorPatients(context, cacheKey)?.let { return@io it }
        }
        apiResult { api.getCollectorPatients(collectorUserKey) }
            .map { serverPatients ->
                val localUnsynced = ReportCache.readCollectorPatients(context, cacheKey, ReportCache.ANY_AGE)
                    .orEmpty()
                    .filter { (it.id ?: 0) < 0 }
                mergeCollectorPatients(serverPatients + localUnsynced).also {
                    ReportCache.writeCollectorPatients(context, cacheKey, it)
                }
            }
            .getOrElse { error ->
                if (!canUseOfflineCache(error)) throw error
                ReportCache.readCollectorPatients(context, cacheKey, ReportCache.ANY_AGE) ?: throw error
            }
    }

    suspend fun createCollectorPatient(request: CollectorPatientRequest): Result<CollectorPatient> =
        apiResult { api.createCollectorPatient(request) }

    suspend fun createCollectorPatientCached(
        context: Context,
        request: CollectorPatientRequest,
    ): Result<CollectorPatient> =
        io {
            apiResult { api.createCollectorPatient(request) }
                .onSuccess { appendCollectorPatient(context, request.collectorUserKey, it) }
                .getOrElse { error ->
                    if (!canUseOfflineCache(error)) throw error
                    val queueKey = "collector_patient_queue_${request.collectorUserKey}"
                    val queued = ReportCache.readQueuedCollectorPatients(context, queueKey)
                        .filterNot { it.logicalKey() == request.logicalKey() } + request
                    ReportCache.writeQueuedCollectorPatients(context, queueKey, queued)
                    val localId = -((System.currentTimeMillis() % Int.MAX_VALUE).toInt().coerceAtLeast(1))
                    CollectorPatient(
                        id = localId,
                        collectorUserKey = request.collectorUserKey,
                        patientName = request.patientName,
                        phone = request.phone,
                        ageYear = request.ageYear,
                        sex = request.sex,
                        referredBy = request.referredBy,
                        notes = request.notes,
                        followupStatus = request.followupStatus,
                        createdAt = "Saved on phone",
                    ).also { appendCollectorPatient(context, request.collectorUserKey, it) }
                }
        }

    suspend fun syncQueuedCollectorPatients(context: Context, collectorUserKey: Int): Result<Int> =
        io {
            val queueKey = "collector_patient_queue_$collectorUserKey"
            val queued = ReportCache.readQueuedCollectorPatients(context, queueKey)
            if (queued.isEmpty()) return@io 0

            val remaining = mutableListOf<CollectorPatientRequest>()
            var synced = 0
            queued.forEach { request ->
                apiResult { api.createCollectorPatient(request) }
                    .onSuccess {
                        synced += 1
                        appendCollectorPatient(context, collectorUserKey, it)
                    }
                    .onFailure { remaining += request }
            }
            ReportCache.writeQueuedCollectorPatients(context, queueKey, remaining)
            synced
        }

    suspend fun getQueuedCollectorPatientCount(context: Context, collectorUserKey: Int): Int =
        withContext(Dispatchers.IO) {
            ReportCache.readQueuedCollectorPatients(context, "collector_patient_queue_$collectorUserKey").size
        }

    suspend fun getCollectorReportsCached(
        context: Context,
        collectorUserKey: Int,
        forceRefresh: Boolean = false,
    ): Result<List<CustomerReport>> = io {
        val cacheKey = "collector_$collectorUserKey"
        if (!forceRefresh) {
            ReportCache.read(context, cacheKey)?.let { return@io it }
        }
        apiResult { api.getCollectorReports(collectorUserKey) }
            .onSuccess { ReportCache.write(context, cacheKey, it) }
            .getOrElse { error ->
                if (!canUseOfflineCache(error)) throw error
                ReportCache.read(context, cacheKey, ReportCache.ANY_AGE) ?: throw error
            }
    }

    private fun appendCollectorPatient(context: Context, collectorUserKey: Int, patient: CollectorPatient) {
        val cacheKey = "collector_patients_$collectorUserKey"
        val existing = ReportCache.readCollectorPatients(context, cacheKey, ReportCache.ANY_AGE).orEmpty()
        val merged = mergeCollectorPatients(listOf(patient) + existing)
        ReportCache.writeCollectorPatients(context, cacheKey, merged)
    }

    private fun mergeCollectorPatients(patients: List<CollectorPatient>): List<CollectorPatient> =
        patients
            .sortedWith(
                compareByDescending<CollectorPatient> { (it.id ?: 0) > 0 }
                    .thenByDescending { it.id ?: 0 },
            )
            .distinctBy { it.logicalKey() }

    private fun CollectorPatient.logicalKey(): String =
        "${phone.filter { it.isDigit() }.takeLast(10)}|${patientName.trim().lowercase()}"

    private fun CollectorPatientRequest.logicalKey(): String =
        "${phone.filter { it.isDigit() }.takeLast(10)}|${patientName.trim().lowercase()}"
}
