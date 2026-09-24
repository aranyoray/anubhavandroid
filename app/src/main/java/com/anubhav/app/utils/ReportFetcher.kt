package com.anubhav.app.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.anubhav.app.data.remote.AktivApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Fetches a report PDF ONLY when the patient taps "View Report".
 * The server URL renders the whole bill into ONE collated PDF (multiple reports → one file),
 * which we download via the Cloudflare tunnel, cache on the phone, and open.
 * A cached copy is reused (works offline / while the clinic server is off, midnight–7 AM).
 */
object ReportFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    fun cachedFile(context: Context, billKey: Int): File {
        val dir = File(context.filesDir, "saved_reports").apply { mkdirs() }
        return File(dir, "bill_$billKey.pdf")
    }

    fun isCached(context: Context, billKey: Int): Boolean =
        cachedFile(context, billKey).let { it.exists() && it.length() > 0L }

    /** Refused by the server for this patient (token missing/expired/other phone). */
    class NotAllowed(val code: Int) : Exception("HTTP $code")

    /**
     * Download+cache the bill's reports as one PDF if not already cached. Returns the local file.
     *
     * First choice is the API's `report-pdf`, which checks the patient token, fetches each
     * authorised report separately and merges only the ones that rendered - so one broken
     * report no longer spoils the whole file. If that fails for any reason other than a
     * refusal, the print page's own collated link ([viewUrl]) is tried as before.
     */
    suspend fun download(context: Context, billKey: Int, phone: String, viewUrl: String?): File =
        withContext(Dispatchers.IO) {
            val file = cachedFile(context, billKey)
            if (file.exists() && file.length() > 0L) return@withContext file
            val apiUrl = AktivApiClient.url("api/customer/report-pdf") +
                "?bill_key=$billKey&phone=${java.net.URLEncoder.encode(phone, "UTF-8")}"
            try {
                fetchTo(AktivApiClient.httpClient, apiUrl, file)
            } catch (e: NotAllowed) {
                throw e
            } catch (e: Exception) {
                if (viewUrl.isNullOrBlank()) throw e
                fetchTo(client, viewUrl, file)
            }
            file
        }

    private fun fetchTo(http: OkHttpClient, url: String, file: File) {
        val req = Request.Builder().url(url).header("Accept", "application/pdf").build()
        // Unique per download: two taps on the same bill would otherwise write the
        // same .part file and corrupt each other.
        val tmp = File(file.parentFile, "${file.name}.${System.nanoTime()}.part")
        try {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) throw NotAllowed(resp.code)
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body ?: error("empty response")
                tmp.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
                // Guard against caching a 200-but-not-a-PDF payload (e.g. a Cloudflare
                // tunnel/origin HTML error page). Such a file would poison the cache
                // permanently because isCached()/the early return key only on size.
                val header = ByteArray(5)
                val read = tmp.inputStream().use { stream ->
                    // A single read() may return fewer bytes than asked for, which
                    // would reject a perfectly good PDF.
                    var total = 0
                    while (total < header.size) {
                        val n = stream.read(header, total, header.size - total)
                        if (n <= 0) break
                        total += n
                    }
                    total
                }
                if (read < header.size || !header.decodeToString().startsWith("%PDF-")) {
                    error("not a PDF")
                }
                // renameTo can fail across filesystems; fall back to a copy so the
                // returned File always exists.
                if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)
            }
        } finally {
            // Never leave a half-written .part behind, on success or failure.
            if (tmp.exists()) tmp.delete()
        }
    }

    fun open(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
