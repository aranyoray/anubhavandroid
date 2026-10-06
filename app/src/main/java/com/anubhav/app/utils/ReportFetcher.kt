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
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Fetches a report PDF ONLY when the patient taps "View Report".
 * The server URL renders the whole bill into ONE collated PDF (multiple reports → one file),
 * which we download via the Cloudflare tunnel, cache on the phone, and open.
 * A cached copy is reused (works offline / while the clinic server is off, midnight–7 AM).
 */
object ReportFetcher {
    fun cachedFile(context: Context, billKey: Int, phone: String, viewUrl: String?): File {
        val dir = File(context.filesDir, "saved_reports").apply { mkdirs() }
        // A bill may gain more authorised reports later. Its view link describes the
        // report set, so a previously downloaded subset must not shadow the new PDF.
        val identity = "${phone.filter(Char::isDigit).takeLast(10)}|${viewUrl.orEmpty()}"
        val version = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "bill_${billKey}_$version.pdf")
    }

    fun isCached(context: Context, billKey: Int, phone: String, viewUrl: String?): Boolean =
        isPdf(cachedFile(context, billKey, phone, viewUrl))

    private fun isPdf(file: File): Boolean = runCatching {
        file.inputStream().buffered().use { input ->
            val header = ByteArray(5)
            java.io.DataInputStream(input).readFully(header)
            header.contentEquals("%PDF-".toByteArray())
        }
    }.getOrDefault(false)

    /** Refused by the server for this patient (token missing/expired/other phone). */
    class NotAllowed(val code: Int) : Exception("HTTP $code")

    /**
     * Download+cache the bill's reports as one PDF if not already cached. Returns the local file.
     *
     * The API checks the patient token and returns a complete, validated PDF. The
     * report set in [viewUrl] versions the local copy. Refresh older copies online;
     * retain a valid copy during outages, but propagate authorization failures.
     */
    suspend fun download(context: Context, billKey: Int, phone: String, viewUrl: String?): File =
        withContext(Dispatchers.IO) {
            if (!PatientTokens.has(phone)) throw NotAllowed(401)
            val file = cachedFile(context, billKey, phone, viewUrl)
            val cached = isPdf(file)
            val age = System.currentTimeMillis() - file.lastModified()
            if (cached && age in 0..TimeUnit.MINUTES.toMillis(15)) return@withContext file
            val apiUrl = AktivApiClient.url("api/customer/report-pdf") +
                "?bill_key=$billKey&phone=${java.net.URLEncoder.encode(phone, "UTF-8")}"
            try {
                fetchTo(AktivApiClient.httpClient, apiUrl, file)
            } catch (e: NotAllowed) {
                throw e
            } catch (e: IOException) {
                // Saved reports remain available during a network/origin outage. A
                // server refusal above must still reach the re-verification UI.
                if (!cached) throw e
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
                if (resp.code >= 500) throw IOException("HTTP ${resp.code}")
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body ?: error("empty response")
                tmp.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
                // Guard against caching a 200-but-not-a-PDF payload (e.g. a Cloudflare
                // tunnel/origin HTML error page). Such a file would poison the cache
                // permanently because isCached()/the early return key only on size.
                if (!isPdf(tmp)) throw IOException("The server did not return a PDF")
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
