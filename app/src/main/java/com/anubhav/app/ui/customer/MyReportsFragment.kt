package com.anubhav.app.ui.customer

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.anubhav.app.R
import com.anubhav.app.data.model.CustomerVisit
import com.anubhav.app.data.model.ReportGroup
import com.anubhav.app.data.model.ReportParam
import com.anubhav.app.data.model.ReportValuesResponse
import com.anubhav.app.data.repository.CustomerRepository
import com.anubhav.app.utils.CustomerSessionManager
import com.anubhav.app.utils.PatientTokens
import com.anubhav.app.utils.ReportFetcher
import com.anubhav.app.utils.localized
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import com.anubhav.app.utils.apiResult

/**
 * My Reports — when logged in, lists EVERY AKTIV visit under the patient's phone
 * (names may differ: relatives share a number) from the static all-history DB.
 *
 * Tapping a visit expands its values in place: each parameter gets a red→green range
 * bar showing where the result sits against its reference range, plus an inline trend
 * chart when earlier visits measured the same thing. The PDF is the fallback, not the
 * primary view, and is fetched (collated into one) only when asked for.
 */
class MyReportsFragment : Fragment() {
    private val repo = CustomerRepository()
    private lateinit var root: LinearLayout
    private lateinit var listContainer: LinearLayout
    private lateinit var statusView: TextView
    /** The phone whose visits are on screen (the patient's own, or one verified via "Fetch another"). */
    private var shownPhone: String = ""
    private var historyJob: Job? = null

    /**
     * Earlier readings per parameter name, loaded at most once per phone and shared by
     * every parameter row — otherwise each "trend" tap would refetch the whole history.
     */
    private var trendLoad: Deferred<Map<String, List<TrendChartView.Point>>>? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun color(id: Int) = ContextCompat.getColor(requireContext(), id)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val scroll = ScrollView(requireContext()).apply {
            setBackgroundColor(color(R.color.background_light))
            clipToPadding = false
        }
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(28))
        }
        scroll.addView(root, ViewGroup.LayoutParams(MATCH, WRAP))
        return scroll
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        super.onViewCreated(view, s)
        root.addView(TextView(requireContext()).apply {
            text = localized(R.string.menu_my_reports)
            textSize = 24f
            setTextColor(color(R.color.brand_dark_blue))
            setTypeface(typeface, Typeface.BOLD)
        })
        statusView = TextView(requireContext()).apply {
            setTextColor(color(R.color.text_secondary))
            textSize = 14f
            setPadding(0, dp(4), 0, dp(14))
        }
        root.addView(statusView)
        root.addView(outlinedButton(R.string.refresh_from_aktiv).apply {
            setOnClickListener { if (shownPhone.isNotBlank()) loadHistory(shownPhone) }
        })
        listContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        val phone = CustomerSessionManager.getPhone(requireContext()).orEmpty()
        if (phone.isBlank()) {
            statusView.text = localized(R.string.reports_login_prompt)
            addFetchOtherButton()
        } else {
            loadHistory(phone)
        }
    }

    // ------------------------------------------------------------------ shared widgets
    private fun filledButton(textRes: Int) = MaterialButton(requireContext()).apply {
        text = localized(textRes)
        isAllCaps = false
        cornerRadius = dp(14)
        minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
    }

    private fun outlinedButton(textRes: Int) = MaterialButton(
        requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        text = localized(textRes)
        isAllCaps = false
        cornerRadius = dp(14)
        minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
    }

    /**
     * Sessions from Google/email sign-in, or from builds before patient tokens existed,
     * know a phone but hold no proof for it; the server refuses those. Ask once for any
     * one of that patient's bills, which hands back a scoped token.
     */
    private fun showVerifyPrompt() {
        listContainer.removeAllViews()
        statusView.text = localized(R.string.reports_verify_again)
        listContainer.addView(filledButton(R.string.reports_verify_button).apply {
            setOnClickListener { showFetchOtherDialog() }
        })
    }

    private fun loadHistory(phone: String) {
        historyJob?.cancel()
        trendLoad = null // a different number has a different history
        shownPhone = phone
        statusView.text = localized(R.string.reports_loading)
        listContainer.removeAllViews()
        historyJob = viewLifecycleOwner.lifecycleScope.launch {
            repo.getHistoryCached(requireContext(), phone, forceRefresh = true).fold(
                onSuccess = { res ->
                    listContainer.removeAllViews()
                    if (res.visits.isEmpty()) {
                        statusView.text = localized(R.string.reports_none_for_number)
                    } else {
                        statusView.text = localized(R.string.reports_visit_count, res.visits.size)
                        res.visits.forEach { listContainer.addView(visitCard(it)) }
                    }
                    addFetchOtherButton()
                },
                onFailure = { err ->
                    if (CustomerRepository.needsVerification(err)) {
                        PatientTokens.forget(phone)
                        showVerifyPrompt()
                    } else {
                        statusView.text = localized(R.string.reports_unavailable)
                        addFetchOtherButton()
                    }
                },
            )
        }
    }

    /** One visit card: patient name + date, the ALC number and tests, then View Report. */
    private fun visitCard(v: CustomerVisit): View {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = ContextCompat.getDrawable(requireContext(), R.drawable.report_card_bg)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
        }
        val topRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(TextView(requireContext()).apply {
            text = (v.patientName ?: "").trim().ifBlank { localized(R.string.reports_patient_fallback) }
            setTextColor(color(R.color.text_primary))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        topRow.addView(TextView(requireContext()).apply {
            text = v.billDate ?: ""
            setTextColor(color(R.color.text_secondary))
            textSize = 13f
        })
        card.addView(topRow)

        card.addView(TextView(requireContext()).apply {
            val alc = v.billNo ?: ""
            val tests = v.tests ?: ""
            text = if (tests.isBlank()) alc else "$alc  ·  $tests"
            setTextColor(color(R.color.text_secondary))
            textSize = 13f
            setPadding(0, dp(4), 0, 0)
        })

        // Values expand/collapse inline under the card — no separate page.
        val valuesBox = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        val viewBtn = filledButton(R.string.reports_view_report).apply {
            isEnabled = v.hasViewLink
            alpha = if (v.hasViewLink) 1f else 0.5f
        }
        viewBtn.setOnClickListener { toggleValues(v, viewBtn, valuesBox) }
        card.addView(viewBtn)
        card.addView(valuesBox)
        if (!v.hasViewLink) {
            card.addView(TextView(requireContext()).apply {
                text = localized(R.string.reports_awaiting_authorisation)
                setTextColor(color(R.color.status_warning))
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
            })
        }
        return card
    }

    private fun toggleValues(v: CustomerVisit, btn: MaterialButton, box: LinearLayout) {
        if (!v.hasViewLink) {
            Toast.makeText(requireContext(), localized(R.string.reports_not_ready_toast), Toast.LENGTH_SHORT).show()
            return
        }
        if (box.visibility == View.VISIBLE) {
            box.visibility = View.GONE
            btn.text = localized(R.string.reports_view_report)
            return
        }
        box.visibility = View.VISIBLE
        btn.text = localized(R.string.reports_hide_report)
        if (box.tag == "loaded") return

        box.removeAllViews()
        box.addView(mutedText(R.string.loading))
        viewLifecycleOwner.lifecycleScope.launch {
            repo.getReportValues(v.billKey, shownPhone)
                .onSuccess { data -> box.tag = "loaded"; renderValues(box, data, v) }
                .onFailure { err ->
                    box.removeAllViews()
                    box.addView(mutedText(R.string.report_values_unavailable))
                    box.addView(openPdfButton(v))
                    if (CustomerRepository.needsVerification(err)) {
                        PatientTokens.forget(shownPhone); showVerifyPrompt()
                    }
                }
        }
    }

    private fun mutedText(res: Int) = TextView(requireContext()).apply {
        text = localized(res)
        setTextColor(color(R.color.text_secondary))
        textSize = 13f
    }

    private fun renderValues(box: LinearLayout, data: ReportValuesResponse, v: CustomerVisit) {
        box.removeAllViews()
        val abnormal = data.abnormalCount > 0
        box.addView(TextView(requireContext()).apply {
            text = if (abnormal) localized(R.string.report_values_abnormal, data.abnormalCount.toString())
            else localized(R.string.report_values_all_normal)
            setTextColor(color(if (abnormal) R.color.status_error else R.color.status_success))
            background = ContextCompat.getDrawable(
                requireContext(),
                if (abnormal) R.drawable.chip_warning_bg else R.drawable.chip_success_soft_bg,
            )
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (data.reports.isEmpty()) box.addView(mutedText(R.string.report_values_empty))
        data.reports.forEachIndexed { i, g -> box.addView(groupAccordion(g, i == 0)) }
        box.addView(openPdfButton(v))
    }

    private fun groupAccordion(group: ReportGroup, expanded: Boolean): View {
        val wrap = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(requireContext(), R.drawable.report_group_bg)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
        }
        val body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        val chevron = TextView(requireContext()).apply {
            text = if (expanded) "–" else "+"
            textSize = 20f
            setTextColor(color(R.color.brand_blue))
        }
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(requireContext()).apply {
                text = group.testName?.takeIf { it.isNotBlank() } ?: group.category.orEmpty()
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            addView(chevron)
            setOnClickListener {
                val show = body.visibility != View.VISIBLE
                body.visibility = if (show) View.VISIBLE else View.GONE
                chevron.text = if (show) "–" else "+"
            }
        }
        wrap.addView(header)
        group.parameters.forEach { body.addView(paramRow(it)) }
        wrap.addView(body)
        return wrap
    }

    /**
     * One parameter: name, flagged result, then the range bar when a reference range came
     * back. The trend chart is appended later, once the shared history load resolves and
     * only if this parameter was measured before.
     */
    private fun paramRow(p: ReportParam): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        val top = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(requireContext()).apply {
            text = p.name
            textSize = 14f
            setTextColor(color(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        val flagColorRes = when (p.flag?.uppercase()) {
            "HIGH" -> R.color.status_error
            "LOW" -> R.color.status_pending
            else -> R.color.status_success
        }
        top.addView(TextView(requireContext()).apply {
            text = listOfNotNull(
                p.result?.takeIf { it.isNotBlank() },
                p.unit?.takeIf { it.isNotBlank() },
            ).joinToString(" ")
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(flagColorRes))
        })
        // Spelt out as well as coloured, so the flag survives a colour-blind reader.
        when (p.flag?.uppercase()) {
            "HIGH" -> R.string.report_values_flag_high
            "LOW" -> R.string.report_values_flag_low
            else -> null
        }?.let { flagRes ->
            top.addView(TextView(requireContext()).apply {
                text = localized(flagRes).uppercase()
                textSize = 11f
                letterSpacing = 0.08f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(flagColorRes))
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) }
            })
        }
        row.addView(top)

        // The red→green bar replaces the old "Ref: 12-16" line whenever we have numbers
        // to place the marker against; the printed range stays as the caption below it.
        val low = p.low?.toFloat()
        val high = p.high?.toFloat()
        if (low != null && high != null && high > low) {
            row.addView(RangeBarView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }
                setRange(low, high, p.value?.toFloat())
            })
        }
        p.range?.takeIf { it.isNotBlank() }?.let {
            row.addView(TextView(requireContext()).apply {
                text = localized(R.string.report_values_ref, it)
                textSize = 12f
                setTextColor(color(R.color.text_secondary))
                setPadding(0, dp(2), 0, 0)
            })
        }
        if (p.value != null) attachTrend(row, p, low, high)
        return row
    }

    /**
     * Adds a collapsed trend toggle to [row] once the shared history load finishes, and
     * only when this parameter has at least two readings to join up. Nothing appears for
     * a first-ever test, which is the common case.
     */
    private fun attachTrend(row: LinearLayout, p: ReportParam, low: Float?, high: Float?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val points = trendSeries()[p.name.trim().lowercase()] ?: return@launch
            if (points.size < 2) return@launch
            val chart = TrendChartView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
                visibility = View.GONE
                setData(points, low, high)
            }
            val toggle = TextView(requireContext()).apply {
                text = localized(R.string.report_values_trend_show, points.size)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.brand_blue))
                setPadding(0, dp(8), 0, dp(4))
                setOnClickListener {
                    val show = chart.visibility != View.VISIBLE
                    chart.visibility = if (show) View.VISIBLE else View.GONE
                    text = if (show) localized(R.string.report_values_trend_hide)
                    else localized(R.string.report_values_trend_show, points.size)
                }
            }
            row.addView(toggle)
            row.addView(chart)
        }
    }

    /** The history load, started at most once per shown phone and awaited by every row. */
    private suspend fun trendSeries(): Map<String, List<TrendChartView.Point>> {
        trendLoad?.let { return it.await() }
        val phone = shownPhone
        return viewLifecycleOwner.lifecycleScope
            .async(start = CoroutineStart.LAZY) { buildTrendSeries(phone) }
            .also { trendLoad = it }
            .await()
    }

    /**
     * Earlier readings for every numeric parameter, keyed by lower-cased name, oldest
     * first so a chart reads left to right. Visits are ordered by bill key: bill dates
     * arrive as DD/MM/YYYY strings, which do not sort chronologically.
     */
    private suspend fun buildTrendSeries(phone: String): Map<String, List<TrendChartView.Point>> {
        val visits = repo.getHistory(phone).getOrNull()?.visits
            ?.filter { it.hasViewLink }
            ?.sortedBy { it.billKey }
            ?.takeLast(TREND_VISITS)
            .orEmpty()
        val series = mutableMapOf<String, MutableList<TrendChartView.Point>>()
        for (visit in visits) {
            val data = repo.getReportValues(visit.billKey, phone).getOrNull() ?: continue
            val label = (data.billDate ?: visit.billDate).orEmpty().take(5)
            data.reports.flatMap { it.parameters }.forEach { param ->
                val value = param.value ?: return@forEach
                series.getOrPut(param.name.trim().lowercase()) { mutableListOf() }
                    .add(TrendChartView.Point(label, value.toFloat()))
            }
        }
        return series
    }

    private fun openPdfButton(v: CustomerVisit) = outlinedButton(R.string.reports_open_pdf).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
        setOnClickListener { openPdf(v, this) }
    }

    private fun openPdf(v: CustomerVisit, btn: MaterialButton) {
        val link = v.viewLink ?: return
        btn.isEnabled = false
        val original = btn.text
        btn.text = localized(R.string.reports_fetching)
        viewLifecycleOwner.lifecycleScope.launch {
            apiResult { ReportFetcher.download(requireContext(), v.billKey, shownPhone, link) }
                .onSuccess { file ->
                    btn.isEnabled = true; btn.text = original
                    val name = "Anubhav-Report-${(v.billNo ?: v.billKey.toString()).filter { it.isLetterOrDigit() }}"
                    val msg = "Anubhav Life Care report ${v.billNo.orEmpty()} ${v.tests.orEmpty()}".trim()
                    ReportPdfDialog.show(parentFragmentManager, file.absolutePath, name, msg)
                }
                .onFailure { err ->
                    btn.isEnabled = true; btn.text = original
                    if (err is ReportFetcher.NotAllowed) {
                        PatientTokens.forget(shownPhone); showVerifyPrompt()
                    } else {
                        Toast.makeText(requireContext(), localized(R.string.reports_fetch_failed), Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    /** With no verified phone yet (guest, Google/email sign-in) the lookup finds their OWN reports. */
    private val lookupTitle: Int
        get() = if (shownPhone.isBlank()) R.string.verify_dialog_title_login else R.string.verify_dialog_title_fetch

    private fun addFetchOtherButton() {
        listContainer.addView(outlinedButton(lookupTitle).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(20) }
            setOnClickListener { showFetchOtherDialog() }
        })
    }

    /** Name + date + bill verification, for the patient's own reports or a relative's. */
    private fun showFetchOtherDialog() {
        val ctx = requireContext()
        val form = ReportLookupForm(ctx, layoutInflater, childFragmentManager)
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(localized(lookupTitle))
            .setView(form.view)
            .setPositiveButton(localized(R.string.verify_positive_find), null)
            .setNegativeButton(localized(R.string.cancel), null)
            .create()
        dialog.setOnShowListener {
            val verifyButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            verifyButton.setOnClickListener {
                val input = form.validInput() ?: return@setOnClickListener
                verifyButton.isEnabled = false
                dialog.setCancelable(false)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    repo.verify(input.name, input.billDate, input.bill).fold(
                        onSuccess = { r ->
                            verifyButton.isEnabled = true
                            dialog.setCancelable(true)
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                            if (r.matched && r.phone.isNotBlank()) {
                                PatientTokens.save(r.phone, r.token)
                                dialog.dismiss()
                                Toast.makeText(ctx, localized(R.string.verify_showing_reports_for, r.patientName), Toast.LENGTH_SHORT).show()
                                // show that person's full history, fresh (the old list may be another number's)
                                loadHistory(r.phone)
                            } else {
                                Toast.makeText(ctx, localized(R.string.verify_no_match), Toast.LENGTH_LONG).show()
                            }
                        },
                        onFailure = {
                            verifyButton.isEnabled = true
                            dialog.setCancelable(true)
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                            Toast.makeText(ctx, it.message ?: localized(R.string.network_error), Toast.LENGTH_LONG).show()
                        },
                    )
                }
            }
        }
        dialog.show()
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /** How many recent visits to chart; each one costs a report-values call. */
        const val TREND_VISITS = 6
    }
}
