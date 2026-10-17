package com.anubhav.app.utils

import android.content.Context
import com.anubhav.app.data.model.CollectorPatient
import com.anubhav.app.data.model.CollectorPatientRequest
import com.anubhav.app.data.model.CustomerBill
import com.anubhav.app.data.model.CustomerReport
import com.anubhav.app.data.model.CustomerVisit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit

/**
 * On-device cache of retrieved patient data. It lives in `filesDir` and the app sets
 * `allowBackup=false`, so this PII never leaves the handset.
 *
 * Entries carry an age limit. A fresh entry is served instead of a request; a stale one
 * is skipped — but still kept, because [ANY_AGE] lets a caller fall back to it when the
 * server cannot be reached (the AKTIV box is offline 01:00–06:00).
 */
object ReportCache {
    private const val DIR = "report_cache"
    private val gson = Gson()
    private val billListType = object : TypeToken<List<CustomerBill>>() {}.type
    private val collectorPatientListType = object : TypeToken<List<CollectorPatient>>() {}.type
    private val collectorPatientRequestListType = object : TypeToken<List<CollectorPatientRequest>>() {}.type
    private val reportListType = object : TypeToken<List<CustomerReport>>() {}.type
    private val visitListType = object : TypeToken<List<CustomerVisit>>() {}.type

    /** Accept a cached entry no matter how old — for use only as a network-failure fallback. */
    const val ANY_AGE = Long.MAX_VALUE

    /** Past visits and issued reports do not change once written. */
    val HISTORY_MAX_AGE: Long = TimeUnit.HOURS.toMillis(24)

    /** Money owed does change — at the clinic counter, without the app hearing about it. */
    val BILLING_MAX_AGE: Long = TimeUnit.MINUTES.toMillis(15)

    /** Locally queued work; the entry is the source of truth until it syncs. */
    val QUEUE_MAX_AGE: Long = ANY_AGE

    fun read(context: Context, key: String, maxAgeMillis: Long = HISTORY_MAX_AGE): List<CustomerReport>? =
        readJson(context, key, reportListType, maxAgeMillis)

    fun write(context: Context, key: String, reports: List<CustomerReport>) {
        writeJson(context, key, reports)
    }

    /** The patient's retrieved visit/report history, kept on-device (never our cloud). */
    fun readVisits(context: Context, key: String, maxAgeMillis: Long = HISTORY_MAX_AGE): List<CustomerVisit>? =
        readJson(context, key, visitListType, maxAgeMillis)

    fun writeVisits(context: Context, key: String, visits: List<CustomerVisit>) {
        writeJson(context, key, visits)
    }

    fun readBills(context: Context, key: String, maxAgeMillis: Long = BILLING_MAX_AGE): List<CustomerBill>? =
        readJson(context, key, billListType, maxAgeMillis)

    fun writeBills(context: Context, key: String, bills: List<CustomerBill>) {
        writeJson(context, key, bills)
    }

    fun readCollectorPatients(
        context: Context,
        key: String,
        maxAgeMillis: Long = HISTORY_MAX_AGE,
    ): List<CollectorPatient>? = readJson(context, key, collectorPatientListType, maxAgeMillis)

    fun writeCollectorPatients(context: Context, key: String, patients: List<CollectorPatient>) {
        writeJson(context, key, patients)
    }

    fun readQueuedCollectorPatients(context: Context, key: String): List<CollectorPatientRequest> =
        readJson(context, key, collectorPatientRequestListType, QUEUE_MAX_AGE) ?: emptyList()

    fun writeQueuedCollectorPatients(
        context: Context,
        key: String,
        patients: List<CollectorPatientRequest>,
    ) {
        writeJson(context, key, patients)
    }

    /**
     * Settle a bill in the cache after a successful payment, so the pending list does
     * not show it again before the next refresh. Reads ignore the age limit here: the
     * point is to rewrite whatever is on disk, however old.
     */
    fun removePendingBill(context: Context, phone: String, billKey: Int) {
        val pendingKey = "pending_$phone"
        readBills(context, pendingKey, ANY_AGE)
            ?.filterNot { it.billKey == billKey }
            ?.let { writeBills(context, pendingKey, it) }

        val billsKey = "bills_$phone"
        readBills(context, billsKey, ANY_AGE)
            ?.map { bill ->
                if (bill.billKey == billKey) {
                    bill.copy(receivedAmount = bill.netAmount, pendingAmount = 0.0)
                } else {
                    bill
                }
            }
            ?.let { writeBills(context, billsKey, it) }
    }

    private fun <T> readJson(context: Context, key: String, type: Type, maxAgeMillis: Long): T? {
        val file = cacheFile(context, key)
        if (!file.exists()) return null
        if (maxAgeMillis != ANY_AGE) {
            val age = System.currentTimeMillis() - file.lastModified()
            // A negative age means the clock moved backwards; treat that as stale rather
            // than trusting an entry we cannot date.
            if (age < 0 || age > maxAgeMillis) return null
        }
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson<T>(file.readText(), type)
        }.onFailure {
            file.delete()
        }.getOrNull()
    }

    private fun writeJson(context: Context, key: String, value: Any) {
        val file = cacheFile(context, key)
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(value))
    }

    /** Drops every cached report and bill — used when an account is deleted. */
    fun clearAll(context: Context) {
        File(context.filesDir, DIR).deleteRecursively()
    }

    private fun cacheFile(context: Context, key: String): File {
        val safeKey = key.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
        return File(File(context.filesDir, DIR), "$safeKey.json")
    }
}
