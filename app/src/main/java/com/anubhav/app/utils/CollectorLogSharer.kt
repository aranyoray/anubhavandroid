package com.anubhav.app.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.anubhav.app.data.model.CollectorPatient
import java.io.File

object CollectorLogSharer {
    fun share(context: Context, patients: List<CollectorPatient>) {
        val file = createCsv(context, patients)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Collector patient log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share patient log"))
    }

    private fun createCsv(context: Context, patients: List<CollectorPatient>): File {
        val dir = File(context.cacheDir, "shared_reports").apply { mkdirs() }
        val file = File(dir, "collector_patient_log.csv")
        file.writeText(
            buildString {
                appendLine("Patient,Phone,Age,Sex,Referred By,Follow-up Status,Notes,Sync Status,Created At")
                patients.forEach { patient ->
                    appendCsv(patient.patientName)
                    append(',')
                    appendCsv(patient.phone)
                    append(',')
                    appendCsv(patient.ageYear?.toString().orEmpty())
                    append(',')
                    appendCsv(patient.sex.orEmpty())
                    append(',')
                    appendCsv(patient.referredBy.orEmpty())
                    append(',')
                    appendCsv(patient.followupStatus.orEmpty())
                    append(',')
                    appendCsv(patient.notes.orEmpty())
                    append(',')
                    appendCsv(if ((patient.id ?: 0) < 0) "Saved on phone" else "Synced")
                    append(',')
                    appendCsv(patient.createdAt.orEmpty())
                    appendLine()
                }
            },
        )
        return file
    }

    private fun StringBuilder.appendCsv(value: String) {
        append('"')
        append(value.replace("\"", "\"\""))
        append('"')
    }
}
