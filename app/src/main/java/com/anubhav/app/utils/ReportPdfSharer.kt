package com.anubhav.app.utils

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.anubhav.app.data.model.CustomerReport
import java.io.File
import java.io.FileOutputStream

object ReportPdfSharer {
    fun ensureSaved(context: Context, report: CustomerReport): File {
        val file = reportFile(context, report)
        if (!file.exists() || file.length() == 0L) {
            createPdf(file, report)
        }
        return file
    }

    fun isSaved(context: Context, report: CustomerReport): Boolean {
        val file = reportFile(context, report)
        return file.exists() && file.length() > 0L
    }

    /**
     * @return false when nothing could be shared (no disk space to write the PDF, or no
     *   app on the device that accepts it) so the caller can say so instead of crashing.
     */
    fun shareToWhatsApp(context: Context, report: CustomerReport): Boolean {
        val file = runCatching { ensureSaved(context, report) }.getOrNull() ?: return false
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return false
        val message = "Anubhav Life Care report ${report.billNo.orEmpty()} ${report.testName.orEmpty()}".trim()
        val baseIntent = shareIntent(uri, message)
        val shared = listOf("com.whatsapp", "com.whatsapp.w4b").any { packageName ->
            runCatching {
                context.startActivity(Intent(baseIntent).setPackage(packageName))
            }.isSuccess
        }
        if (shared) return true
        // No WhatsApp: fall back to the system chooser, which can itself be missing on
        // stripped-down builds.
        return runCatching {
            context.startActivity(Intent.createChooser(baseIntent, "Share report"))
        }.isSuccess
    }

    private fun shareIntent(uri: Uri, message: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_TEXT, message)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun reportFile(context: Context, report: CustomerReport): File {
        val dir = File(context.filesDir, "saved_reports").apply { mkdirs() }
        val testCode = (report.testCode ?: report.testName ?: "test").replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, "report_${report.billKey}_$testCode.pdf")
    }

    private fun createPdf(file: File, report: CustomerReport) {
        val document = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas

            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 22f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 13f
            }
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 11f
            }

            var y = 64f
            canvas.drawText("Anubhav Life Care", 48f, y, title)
            y += 34f
            canvas.drawText("Patient report summary", 48f, y, body)
            y += 32f

            val rows = listOf(
                "Patient" to report.patientName.orEmpty(),
                "Bill" to (report.billNo ?: report.billKey.toString()),
                "Bill date" to report.billDate.orEmpty(),
                "Test" to (report.testName ?: report.testCode ?: "Report"),
                "Test code" to report.testCode.orEmpty(),
                "Reporting date" to report.reportingDate.orEmpty(),
                "Status" to (report.statusMessage ?: report.status ?: if (report.isShareable) "READY" else "PENDING"),
                "Pending amount" to if (report.pendingAmount > 0.01) "Rs ${"%.0f".format(report.pendingAmount)}" else "None",
            )
            rows.forEach { (name, value) ->
                canvas.drawText(name, 48f, y, label)
                canvas.drawText(value.ifBlank { "-" }, 180f, y, body)
                y += 24f
            }

            y += 24f
            val note = when {
                report.isBillDueBlocked -> "Report sharing is blocked because the bill has pending dues."
                report.isShareable -> "This report was saved on this phone for offline sharing."
                else -> "This report is not ready or authorized yet."
            }
            canvas.drawText(note, 48f, y, body)
            canvas.drawText("Generated by the Anubhav Life Care mobile app.", 48f, 790f, small)

            document.finishPage(page)
            FileOutputStream(file).use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }
}
