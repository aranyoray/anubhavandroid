package com.anubhav.app.ui.customer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anubhav.app.R
import com.anubhav.app.data.model.CollectorPatient
import com.anubhav.app.data.model.CollectorPatientRequest
import com.anubhav.app.data.model.CustomerReport
import com.anubhav.app.data.repository.CustomerRepository
import com.anubhav.app.utils.CollectorLogSharer
import com.anubhav.app.utils.CustomerSessionManager
import com.anubhav.app.utils.ReportPdfSharer
import com.anubhav.app.utils.SessionManager
import com.anubhav.app.utils.localized
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CollectorDashboardFragment : Fragment() {
    private val repo = CustomerRepository()
    private lateinit var patientAdapter: CollectorPatientAdapter
    private lateinit var reportAdapter: CollectorReportAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_collector_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val progress = view.findViewById<ProgressBar>(R.id.progressBar)
        val patientsList = view.findViewById<RecyclerView>(R.id.rvCollectorPatients)
        val reportsList = view.findViewById<RecyclerView>(R.id.rvCollectorReports)
        val empty = view.findViewById<TextView>(R.id.tvCollectorEmpty)
        val syncState = view.findViewById<TextView>(R.id.tvCollectorSyncState)
        val reportStatus = view.findViewById<TextView>(R.id.tvCollectorReportStatusSummary)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveCollectorPatient)
        val btnRefresh = view.findViewById<MaterialButton>(R.id.btnRefreshCollectorReports)
        val btnShareLog = view.findViewById<MaterialButton>(R.id.btnShareCollectorLog)
        val name = view.findViewById<TextInputEditText>(R.id.etCollectorPatientName)
        val phone = view.findViewById<TextInputEditText>(R.id.etCollectorPhone)
        val age = view.findViewById<TextInputEditText>(R.id.etCollectorAge)
        val sex = view.findViewById<TextInputEditText>(R.id.etCollectorSex)
        val referredBy = view.findViewById<TextInputEditText>(R.id.etCollectorReferredBy)
        val followupStatus = view.findViewById<TextInputEditText>(R.id.etCollectorFollowupStatus)
        val notes = view.findViewById<TextInputEditText>(R.id.etCollectorNotes)
        val patientSearch = view.findViewById<TextInputEditText>(R.id.etCollectorPatientSearch)
        val reportSearch = view.findViewById<TextInputEditText>(R.id.etCollectorReportSearch)

        val collectorKey = SessionManager.getCollectorKey(requireContext())
            ?: CustomerSessionManager.getCollectorKey(requireContext())

        patientAdapter = CollectorPatientAdapter(
            onCall = { openDialer(it.phone) },
            onWhatsApp = { openWhatsApp(it.phone, it.patientName) },
        )
        reportAdapter = CollectorReportAdapter(
            onShare = {
                if (!ReportPdfSharer.shareToWhatsApp(requireContext(), it)) {
                    Toast.makeText(
                        requireContext(),
                        localized(R.string.whatsapp_not_available),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            onPayDue = { openPendingPayments() },
            onSupport = { openReportSupport(it) },
            onVisibleChanged = { reports -> bindCollectorReportSummary(reportStatus, reports) },
        )
        patientsList.layoutManager = LinearLayoutManager(requireContext())
        patientsList.adapter = patientAdapter
        reportsList.layoutManager = LinearLayoutManager(requireContext())
        reportsList.adapter = reportAdapter
        patientSearch.addSearchWatcher { patientAdapter.filter(it) }
        reportSearch.addSearchWatcher { reportAdapter.filter(it) }

        if (collectorKey == null || collectorKey <= 0) {
            progress.visibility = View.GONE
            empty.visibility = View.VISIBLE
            empty.text = localized(R.string.collector_no_access)
            syncState.text = localized(R.string.collector_key_required)
            setActionsEnabled(false)
            return
        }

        btnSave.setOnClickListener {
            val cleanPhone = phone.text?.toString()?.filter { it.isDigit() }.orEmpty().takeLast(10)
            val cleanName = name.text?.toString()?.trim().orEmpty()
            if (cleanName.isBlank() || cleanPhone.length != 10) {
                Toast.makeText(requireContext(), localized(R.string.collector_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val request = CollectorPatientRequest(
                collectorUserKey = collectorKey,
                patientName = cleanName,
                phone = cleanPhone,
                ageYear = age.text?.toString()?.toIntOrNull(),
                sex = sex.text?.toString()?.trim()?.ifBlank { null },
                referredBy = referredBy.text?.toString()?.trim()?.ifBlank { null },
                notes = notes.text?.toString()?.trim()?.ifBlank { null },
                followupStatus = followupStatus.text?.toString()?.trim()?.ifBlank { null },
            )
            savePatient(request, progress, empty) {
                listOf(name, phone, age, sex, referredBy, followupStatus, notes).forEach { it.text?.clear() }
                loadCollectorData(collectorKey, progress, empty, syncState, forceRefresh = false)
            }
        }

        btnRefresh.setOnClickListener {
            loadCollectorData(collectorKey, progress, empty, syncState, forceRefresh = true)
        }
        btnShareLog.setOnClickListener {
            val patients = patientAdapter.visiblePatients()
            if (patients.isEmpty()) {
                Toast.makeText(requireContext(), localized(R.string.collector_log_empty), Toast.LENGTH_SHORT).show()
            } else {
                CollectorLogSharer.share(requireContext(), patients)
            }
        }
        loadCollectorData(collectorKey, progress, empty, syncState, forceRefresh = false)
    }

    private fun savePatient(
        request: CollectorPatientRequest,
        progress: ProgressBar,
        empty: TextView,
        onDone: () -> Unit,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            setActionsEnabled(false)
            repo.createCollectorPatientCached(requireContext(), request).fold(
                onSuccess = { patient ->
                    progress.visibility = View.GONE
                    setActionsEnabled(true)
                    val message = if ((patient.id ?: 0) < 0) {
                        localized(R.string.collector_patient_saved_offline)
                    } else {
                        localized(R.string.collector_patient_saved)
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    onDone()
                },
                onFailure = {
                    progress.visibility = View.GONE
                    setActionsEnabled(true)
                    empty.visibility = View.VISIBLE
                    empty.text = it.message ?: localized(R.string.network_error)
                },
            )
        }
    }

    private fun loadCollectorData(
        collectorKey: Int,
        progress: ProgressBar,
        empty: TextView,
        syncState: TextView,
        forceRefresh: Boolean,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            setActionsEnabled(false)
            syncState.text = localized(R.string.loading)
            val syncResult = if (forceRefresh) {
                repo.syncQueuedCollectorPatients(requireContext(), collectorKey)
            } else {
                Result.success(0)
            }
            val patientResult = repo.getCollectorPatientsCached(requireContext(), collectorKey, forceRefresh)
            val reportResult = repo.getCollectorReportsCached(requireContext(), collectorKey, forceRefresh)
            val patients = patientResult.getOrElse { emptyList() }
            val reports = reportResult.getOrElse { emptyList() }

            progress.visibility = View.GONE
            setActionsEnabled(true)
            patientAdapter.submit(patients)
            reportAdapter.submit(reports)
            preSaveReadyReports(reports)
            empty.visibility = if (patients.isEmpty() && reports.isEmpty()) View.VISIBLE else View.GONE
            empty.text = when {
                patientResult.isFailure && reportResult.isFailure -> localized(R.string.network_error)
                patientResult.isFailure -> localized(R.string.collector_patients_load_failed)
                reportResult.isFailure -> localized(R.string.collector_reports_load_failed)
                else -> localized(R.string.collector_empty)
            }
            view?.findViewById<TextView>(R.id.tvCollectorPatientCount)?.text =
                localized(R.string.collector_patients_count, patients.size)
            view?.findViewById<TextView>(R.id.tvCollectorReportCount)?.text =
                localized(R.string.collector_reports_count, reports.size)
            val queuedCount = repo.getQueuedCollectorPatientCount(requireContext(), collectorKey)
            syncState.text = when {
                syncResult.isFailure -> localized(R.string.network_error)
                queuedCount == 0 -> localized(R.string.collector_all_synced)
                else -> localized(R.string.collector_waiting_sync, queuedCount)
            }
        }
    }

    private fun bindCollectorReportSummary(summary: TextView, reports: List<CustomerReport>) {
        val ready = reports.count { it.isShareable }
        val due = reports.count { it.isBillDueBlocked }
        val pending = (reports.size - ready - due).coerceAtLeast(0)
        summary.text = localized(R.string.report_summary, ready, pending, due)
        summary.visibility = if (reports.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun preSaveReadyReports(reports: List<CustomerReport>) {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            reports.filter { it.isShareable }.forEach {
                runCatching { ReportPdfSharer.ensureSaved(appContext, it) }
            }
        }
    }

    private fun openDialer(phone: String) {
        runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }
            .onFailure {
                Toast.makeText(requireContext(), localized(R.string.something_went_wrong), Toast.LENGTH_SHORT).show()
            }
    }

    private fun openWhatsApp(phone: String, patientName: String) {
        val message = localized(R.string.collector_whatsapp_message, patientName)
        val cleanPhone = phone.filter { it.isDigit() }.takeLast(10)
        val uri = Uri.parse("https://wa.me/91$cleanPhone?text=${Uri.encode(message)}")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure {
                Toast.makeText(requireContext(), localized(R.string.whatsapp_not_available), Toast.LENGTH_SHORT).show()
            }
    }

    private fun openPendingPayments() {
        runCatching { findNavController().navigate(R.id.nav_pending_payments) }
            .onFailure {
                Toast.makeText(requireContext(), localized(R.string.pending_payments_title), Toast.LENGTH_SHORT).show()
            }
    }

    private fun openReportSupport(report: CustomerReport) {
        val message = localized(
            R.string.report_support_message,
            report.billNo ?: report.billKey.toString(),
            report.testName ?: report.testCode ?: localized(R.string.report_fallback),
        )
        val uri = Uri.parse("https://wa.me/919230755876?text=${Uri.encode(message)}")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure {
                Toast.makeText(requireContext(), localized(R.string.whatsapp_not_available), Toast.LENGTH_SHORT).show()
            }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        listOf(
            R.id.btnSaveCollectorPatient,
            R.id.btnRefreshCollectorReports,
            R.id.btnShareCollectorLog,
        ).forEach { id ->
            view?.findViewById<MaterialButton>(id)?.isEnabled = enabled
        }
    }
}

private class CollectorPatientAdapter(
    private val onCall: (CollectorPatient) -> Unit,
    private val onWhatsApp: (CollectorPatient) -> Unit,
) : RecyclerView.Adapter<CollectorPatientAdapter.VH>() {
    private var allItems: List<CollectorPatient> = emptyList()
    private var items: List<CollectorPatient> = emptyList()
    private var query: String = ""

    fun submit(newItems: List<CollectorPatient>) {
        allItems = newItems
        applyFilter()
    }

    fun filter(newQuery: String) {
        query = newQuery
        applyFilter()
    }

    fun visiblePatients(): List<CollectorPatient> = items

    private fun applyFilter() {
        val needle = query.trim().lowercase()
        items = if (needle.isBlank()) {
            allItems
        } else {
            allItems.filter {
                listOf(it.patientName, it.phone, it.sex, it.referredBy, it.followupStatus, it.notes, it.createdAt)
                    .joinToString(" ")
                    .lowercase()
                    .contains(needle)
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
            background = ContextCompat.getDrawable(context, R.drawable.list_item_background)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, 10) }
        }
        val text = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 14f
            setLineSpacing(2f, 1f)
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isBaselineAligned = false
        }
        val call = actionButton(context, R.string.call_patient, R.drawable.ic_phone)
        val whatsapp = actionButton(context, R.string.whatsapp_patient, R.drawable.ic_whatsapp)
        actions.addView(call, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 10, 0, 0)
        })
        actions.addView(whatsapp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 8, 0, 0)
        })
        row.addView(text)
        row.addView(actions)
        return VH(row, text, call, whatsapp)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.text.text = buildString {
            append(item.patientName).append(" | ").append(item.phone)
            item.ageYear?.let { append('\n').append(holder.text.context.localized(R.string.collector_age_value, it)) }
            item.sex?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
            item.referredBy?.takeIf { it.isNotBlank() }?.let {
                append('\n').append(holder.text.context.localized(R.string.collector_referred_value, it))
            }
            item.followupStatus?.takeIf { it.isNotBlank() }?.let {
                append('\n').append(holder.text.context.localized(R.string.collector_status_value, it))
            }
            item.notes?.takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
            if ((item.id ?: 0) < 0) append('\n').append(holder.text.context.localized(R.string.saved_on_phone))
        }
        holder.call.setOnClickListener { onCall(item) }
        holder.whatsapp.setOnClickListener { onWhatsApp(item) }
    }

    override fun getItemCount(): Int = items.size

    class VH(
        view: View,
        val text: TextView,
        val call: MaterialButton,
        val whatsapp: MaterialButton,
    ) : RecyclerView.ViewHolder(view)
}

private class CollectorReportAdapter(
    private val onShare: (CustomerReport) -> Unit,
    private val onPayDue: (CustomerReport) -> Unit,
    private val onSupport: (CustomerReport) -> Unit,
    private val onVisibleChanged: (List<CustomerReport>) -> Unit,
) : RecyclerView.Adapter<CollectorReportAdapter.VH>() {
    private var allItems: List<CustomerReport> = emptyList()
    private var items: List<CustomerReport> = emptyList()
    private var query: String = ""

    fun submit(newItems: List<CustomerReport>) {
        allItems = newItems
        applyFilter()
    }

    fun filter(newQuery: String) {
        query = newQuery
        applyFilter()
    }

    private fun applyFilter() {
        val needle = query.trim().lowercase()
        items = if (needle.isBlank()) {
            allItems
        } else {
            allItems.filter {
                listOf(it.patientName, it.billNo, it.testName, it.testCode, it.reportingDate, it.status, it.statusMessage)
                    .joinToString(" ")
                    .lowercase()
                    .contains(needle)
            }
        }
        notifyDataSetChanged()
        onVisibleChanged(items)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
            background = ContextCompat.getDrawable(context, R.drawable.list_item_background)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, 10) }
        }
        val text = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 14f
            setLineSpacing(2f, 1f)
        }
        val status = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 12f
            setPadding(14, 8, 14, 8)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isBaselineAligned = false
        }
        val share = actionButton(context, R.string.share_pdf_whatsapp, R.drawable.ic_whatsapp)
        val secondary = actionButton(context, R.string.whatsapp_support, R.drawable.ic_whatsapp)
        actions.addView(share, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 10, 0, 0)
        })
        actions.addView(secondary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 8, 0, 0)
        })
        row.addView(text)
        row.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 10, 0, 0) })
        row.addView(actions)
        return VH(row, text, status, share, secondary)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val report = items[position]
        val context = holder.text.context
        holder.text.text = buildString {
            report.patientName?.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
            append(report.testName ?: report.testCode ?: context.localized(R.string.report_fallback))
            report.billNo?.let { append('\n').append(it) }
            report.reportingDate?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
        }
        holder.status.text = report.availabilityText(context)
        holder.status.background = ContextCompat.getDrawable(context, report.statusBackground())
        holder.share.isEnabled = report.isShareable
        holder.share.alpha = if (report.isShareable) 1f else 0.45f
        holder.share.setOnClickListener { if (report.isShareable) onShare(report) }

        if (report.isBillDueBlocked) {
            holder.secondary.text = context.localized(R.string.pay_pending_bill)
            holder.secondary.setIconResource(R.drawable.ic_payments)
            holder.secondary.setOnClickListener { onPayDue(report) }
        } else {
            holder.secondary.text = context.localized(R.string.whatsapp_support)
            holder.secondary.setIconResource(R.drawable.ic_whatsapp)
            holder.secondary.setOnClickListener { onSupport(report) }
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(
        view: View,
        val text: TextView,
        val status: TextView,
        val share: MaterialButton,
        val secondary: MaterialButton,
    ) : RecyclerView.ViewHolder(view)
}

private fun actionButton(context: Context, textRes: Int, iconRes: Int): MaterialButton =
    MaterialButton(context).apply {
        text = context.localized(textRes)
        setIconResource(iconRes)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        iconPadding = 6
        minHeight = 48
    }

private fun CustomerReport.availabilityText(context: Context): String =
    statusMessage ?: when {
        isBillDueBlocked -> context.localized(R.string.report_blocked_due)
        isShareable -> context.localized(R.string.report_ready_to_share)
        else -> context.localized(R.string.report_not_ready)
    }

private fun CustomerReport.statusBackground(): Int = when {
    isBillDueBlocked -> R.drawable.chip_error_bg
    isShareable -> R.drawable.chip_success_soft_bg
    else -> R.drawable.chip_warning_bg
}

private fun TextInputEditText.addSearchWatcher(onQueryChanged: (String) -> Unit) {
    addTextChangedListener(
        object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onQueryChanged(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        },
    )
}
