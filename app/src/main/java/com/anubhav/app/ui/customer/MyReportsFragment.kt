package com.anubhav.app.ui.customer

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.anubhav.app.R
import com.anubhav.app.data.model.CustomerVisit
import com.anubhav.app.data.repository.CustomerRepository
import com.anubhav.app.utils.CustomerSessionManager
import com.anubhav.app.utils.PatientTokens
import com.anubhav.app.utils.ReportFetcher
import com.anubhav.app.utils.localized
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import com.anubhav.app.utils.apiResult

/**
 * My Reports — when logged in, lists EVERY AKTIV visit under the patient's phone
 * (names may differ: relatives share a number) from the static all-history DB.
 * PDFs are fetched (collated into one) + cached only when "View Report" is tapped.
 * A "Fetch another report" option below covers reports under a different number/bill.
 */
class MyReportsFragment : Fragment() {
    private val repo = CustomerRepository()
    private lateinit var root: LinearLayout
    private lateinit var listContainer: LinearLayout
    private lateinit var statusView: TextView
    /** The phone whose visits are on screen (the patient's own, or one verified via "Fetch another"). */
    private var shownPhone: String = ""
    private var historyJob: Job? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val scroll = ScrollView(requireContext()).apply { setBackgroundColor(0xFFF9FAFB.toInt()) }
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(root, ViewGroup.LayoutParams(MATCH, WRAP))
        return scroll
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        super.onViewCreated(view, s)
        root.addView(TextView(requireContext()).apply {
            text = localized(R.string.menu_my_reports); textSize = 20f; setTextColor(0xFF0D9488.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        statusView = TextView(requireContext()).apply {
            setTextColor(0xFF6B7280.toInt()); textSize = 13f; setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(statusView)
        root.addView(Button(requireContext()).apply {
            text = localized(R.string.refresh_from_aktiv); isAllCaps = false
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

    /**
     * Sessions from Google/email sign-in, or from builds before patient tokens existed,
     * know a phone but hold no proof for it; the server refuses those. Ask once, with the
     * phone filled in - phone + the bill number's last digits is enough.
     */
    private fun showVerifyPrompt(phone: String) {
        listContainer.removeAllViews()
        statusView.text = localized(R.string.reports_verify_again)
        listContainer.addView(Button(requireContext()).apply {
            text = localized(R.string.reports_verify_button); isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
            setOnClickListener { showFetchOtherDialog(prefillPhone = phone) }
        })
    }

    private fun loadHistory(phone: String) {
        historyJob?.cancel()
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
                        showVerifyPrompt(phone)
                    } else {
                        statusView.text = localized(R.string.reports_unavailable)
                        addFetchOtherButton()
                    }
                },
            )
        }
    }

    /** One visit row: PATIENT NAME (left) — Date (right); below: ALC + tests; right: View Report. */
    private fun visitCard(v: CustomerVisit): View {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
        }
        // top row: name (left) + date (right)
        val topRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(TextView(requireContext()).apply {
            text = (v.patientName ?: "").trim().ifBlank { localized(R.string.reports_patient_fallback) }
            setTextColor(0xFF111111.toInt()); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        topRow.addView(TextView(requireContext()).apply {
            text = v.billDate ?: ""; setTextColor(0xFF6B7280.toInt()); textSize = 13f
        })
        card.addView(topRow)

        // bottom row: ALC + tests (small black) on left, View Report on right
        val botRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val info = TextView(requireContext()).apply {
            val alc = v.billNo ?: ""
            val tests = v.tests ?: ""
            text = if (tests.isBlank()) alc else "$alc\n$tests"
            setTextColor(Color.BLACK); textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        botRow.addView(info)

        // Values expand/collapse inline under the card — no separate page.
        val valuesBox = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        val viewBtn = Button(requireContext()).apply {
            text = localized(R.string.reports_view_report); isAllCaps = false
            isEnabled = v.hasViewLink
            alpha = if (v.hasViewLink) 1f else 0.5f
        }
        viewBtn.setOnClickListener { toggleValues(v, viewBtn, valuesBox) }
        botRow.addView(viewBtn)
        card.addView(botRow)
        card.addView(valuesBox)
        if (!v.hasViewLink) {
            card.addView(TextView(requireContext()).apply {
                text = localized(R.string.reports_awaiting_authorisation)
                setTextColor(0xFFB45309.toInt()); textSize = 11f; setPadding(0, dp(4), 0, 0)
            })
        }
        return card
    }

    private fun toggleValues(v: CustomerVisit, btn: Button, box: LinearLayout) {
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
        box.addView(TextView(requireContext()).apply {
            text = localized(R.string.loading); setTextColor(0xFF667085.toInt()); textSize = 13f
        })
        viewLifecycleOwner.lifecycleScope.launch {
            repo.getReportValues(v.billKey, shownPhone)
                .onSuccess { data -> box.tag = "loaded"; renderValues(box, data, v) }
                .onFailure { err ->
                    box.removeAllViews()
                    box.addView(TextView(requireContext()).apply {
                        text = localized(R.string.report_values_unavailable)
                        setTextColor(0xFF667085.toInt()); textSize = 13f
                    })
                    box.addView(openPdfButton(v))
                    if (CustomerRepository.needsVerification(err)) {
                        PatientTokens.forget(shownPhone); showVerifyPrompt(shownPhone)
                    }
                }
        }
    }

    private fun renderValues(box: LinearLayout, data: com.anubhav.app.data.model.ReportValuesResponse, v: CustomerVisit) {
        box.removeAllViews()
        box.addView(TextView(requireContext()).apply {
            text = if (data.abnormalCount > 0) localized(R.string.report_values_abnormal, data.abnormalCount.toString())
            else localized(R.string.report_values_all_normal)
            setTextColor(if (data.abnormalCount > 0) 0xFFC2410C.toInt() else 0xFF2E7D32.toInt())
            textSize = 13f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(6))
        })
        if (data.reports.isEmpty()) {
            box.addView(TextView(requireContext()).apply {
                text = localized(R.string.report_values_empty); setTextColor(0xFF667085.toInt()); textSize = 13f
            })
        }
        data.reports.forEachIndexed { i, g -> box.addView(groupAccordion(g, i == 0)) }
        box.addView(openPdfButton(v))
    }

    private fun groupAccordion(group: com.anubhav.app.data.model.ReportGroup, expanded: Boolean): View {
        val wrap = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F7F8.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }
        }
        val body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; visibility = if (expanded) View.VISIBLE else View.GONE
        }
        val chevron = TextView(requireContext()).apply {
            text = if (expanded) "–" else "+"; textSize = 18f; setTextColor(0xFF0B6FB4.toInt())
        }
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(requireContext()).apply {
                text = group.testName?.takeIf { it.isNotBlank() } ?: group.category.orEmpty()
                textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1D2939.toInt())
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

    private fun paramRow(p: com.anubhav.app.data.model.ReportParam): View {
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        val top = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(requireContext()).apply {
            text = p.name; textSize = 14f; setTextColor(0xFF1D2939.toInt())
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        val flagColor = when (p.flag?.uppercase()) {
            "HIGH" -> 0xFFC2410C.toInt(); "LOW" -> 0xFF0B6FB4.toInt(); else -> 0xFF2E7D32.toInt()
        }
        top.addView(TextView(requireContext()).apply {
            text = listOfNotNull(p.result?.takeIf { it.isNotBlank() }, p.unit?.takeIf { it.isNotBlank() }).joinToString(" ")
            textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(flagColor)
        })
        row.addView(top)
        p.range?.takeIf { it.isNotBlank() }?.let {
            row.addView(TextView(requireContext()).apply {
                text = localized(R.string.report_values_ref, it); textSize = 12f
                setTextColor(0xFF667085.toInt()); setPadding(0, dp(2), 0, 0)
            })
        }
        if (p.value != null) {
            row.addView(TextView(requireContext()).apply {
                text = localized(R.string.report_values_view_trend); textSize = 13f
                setTextColor(0xFF0B6FB4.toInt()); setPadding(0, dp(4), 0, 0)
                setOnClickListener { showTrend(p.name, p.low?.toFloat(), p.high?.toFloat()) }
            })
        }
        return row
    }

    private fun showTrend(paramName: String, low: Float?, high: Float?) {
        Toast.makeText(requireContext(), localized(R.string.report_values_trend_loading), Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val visits = repo.getHistory(shownPhone).getOrNull()?.visits
                ?.filter { it.hasViewLink }?.sortedByDescending { it.billDate }?.take(6).orEmpty()
            val points = mutableListOf<TrendChartView.Point>()
            for (vi in visits) {
                val data = repo.getReportValues(vi.billKey, shownPhone).getOrNull() ?: continue
                val m = data.reports.flatMap { it.parameters }
                    .firstOrNull { it.name.equals(paramName, ignoreCase = true) && it.value != null } ?: continue
                points.add(TrendChartView.Point((data.billDate ?: vi.billDate).orEmpty().take(5), m.value!!.toFloat()))
            }
            points.reverse()
            if (points.size < 2) {
                Toast.makeText(requireContext(), localized(R.string.report_values_trend_none), Toast.LENGTH_LONG).show()
                return@launch
            }
            val chart = TrendChartView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP); setData(points, low, high)
            }
            val wrap = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(18))
                addView(TextView(requireContext()).apply {
                    text = paramName; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(8))
                })
                addView(chart)
            }
            com.google.android.material.bottomsheet.BottomSheetDialog(requireContext()).apply { setContentView(wrap) }.show()
        }
    }

    private fun openPdfButton(v: CustomerVisit) = Button(requireContext()).apply {
        text = localized(R.string.reports_open_pdf); isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        setOnClickListener { openPdf(v, this) }
    }

    private fun openPdf(v: CustomerVisit, btn: Button) {
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
                        PatientTokens.forget(shownPhone); showVerifyPrompt(shownPhone)
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
        listContainer.addView(Button(requireContext()).apply {
            text = localized(lookupTitle); isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) }
            setOnClickListener { showFetchOtherDialog() }
        })
    }

    /** Name + phone + bill verification, for the patient's own reports or a relative's. */
    private fun showFetchOtherDialog(prefillPhone: String = "") {
        val ctx = requireContext()
        val form = ReportLookupForm(ctx, layoutInflater, prefillPhone)
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
                    repo.verify(input.name, input.phone10, input.bill).fold(
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
    }
}
