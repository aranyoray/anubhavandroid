package com.anubhav.app.ui.customer

import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

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
        shownPhone = phone
        statusView.text = localized(R.string.reports_loading)
        listContainer.removeAllViews()
        viewLifecycleOwner.lifecycleScope.launch {
            repo.getHistoryCached(requireContext(), phone).fold(
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
                    val code = (err as? retrofit2.HttpException)?.code()
                    if (code == 401 || code == 403) {
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

        val viewBtn = Button(requireContext()).apply {
            text = localized(R.string.reports_view_report); isAllCaps = false
            setOnClickListener { onViewReport(v, this) }
            isEnabled = v.hasViewLink
            alpha = if (v.hasViewLink) 1f else 0.5f
        }
        botRow.addView(viewBtn)
        card.addView(botRow)
        if (!v.hasViewLink) {
            card.addView(TextView(requireContext()).apply {
                text = localized(R.string.reports_awaiting_authorisation)
                setTextColor(0xFFB45309.toInt()); textSize = 11f; setPadding(0, dp(4), 0, 0)
            })
        }
        return card
    }

    private fun onViewReport(v: CustomerVisit, btn: Button) {
        val link = v.viewLink
        if (link.isNullOrBlank()) {
            Toast.makeText(requireContext(), localized(R.string.reports_not_ready_toast), Toast.LENGTH_SHORT).show()
            return
        }
        btn.isEnabled = false
        val original = btn.text
        btn.text = if (ReportFetcher.isCached(requireContext(), v.billKey)) {
            localized(R.string.reports_opening)
        } else {
            localized(R.string.reports_fetching)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { ReportFetcher.download(requireContext(), v.billKey, shownPhone, link) }
                .onSuccess { file ->
                    btn.isEnabled = true; btn.text = original
                    runCatching { ReportFetcher.open(requireContext(), file) }
                        .onFailure { Toast.makeText(requireContext(), localized(R.string.reports_no_pdf_viewer), Toast.LENGTH_LONG).show() }
                }
                .onFailure { err ->
                    btn.isEnabled = true; btn.text = original
                    if (err is ReportFetcher.NotAllowed) {
                        PatientTokens.forget(shownPhone)
                        showVerifyPrompt(shownPhone)
                    } else {
                        Toast.makeText(requireContext(), localized(R.string.reports_fetch_failed), Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    private fun addFetchOtherButton() {
        listContainer.addView(Button(requireContext()).apply {
            text = localized(R.string.verify_dialog_title_fetch); isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) }
            setOnClickListener { showFetchOtherDialog() }
        })
    }

    /** 2-of-3 verification for a report under a DIFFERENT number/bill (relative etc.). */
    private fun showFetchOtherDialog(prefillPhone: String = "") {
        val ctx = requireContext()
        val form = layoutInflater.inflate(R.layout.dialog_report_lookup, null)
        form.findViewById<TextView>(R.id.tvLookupHelp).text = localized(R.string.verify_dialog_message_short)
        form.findViewById<TextInputLayout>(R.id.layoutLookupName).hint = localized(R.string.verify_patient_name_hint)
        form.findViewById<TextInputLayout>(R.id.layoutLookupPhone).hint = localized(R.string.verify_phone_hint)
        form.findViewById<TextView>(R.id.tvLookupDateLabel).text = localized(R.string.verify_bill_date_label)
        form.findViewById<TextView>(R.id.tvLookupBillLabel).text = localized(R.string.verify_bill_no_label)
        form.findViewById<TextView>(R.id.tvLookupOr).text = localized(R.string.verify_or_short)
        form.findViewById<TextView>(R.id.tvLookupBillHint).text = localized(R.string.verify_bill_digits_hint)
        val etName = form.findViewById<TextInputEditText>(R.id.etLookupName)
        val etPhone = form.findViewById<TextInputEditText>(R.id.etLookupPhone).apply { setText(prefillPhone) }
        val etBill = form.findViewById<EditText>(R.id.etLookupBillDigits)
        val dateBtn = form.findViewById<MaterialButton>(R.id.btnLookupDate).apply {
            text = localized(R.string.verify_pick_date_short)
        }
        var billDateIso: String? = null
        dateBtn.setOnClickListener {
            val c = Calendar.getInstance()
            DatePickerDialog(ctx, { _, y, m, d ->
                billDateIso = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                dateBtn.text = String.format(Locale.US, "%02d/%02d/%04d", d, m + 1, y)
                etBill.text?.clear()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        etBill.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrEmpty() && billDateIso != null) {
                    billDateIso = null
                    dateBtn.text = localized(R.string.verify_pick_date_short)
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(localized(R.string.verify_dialog_title_fetch))
            .setView(form)
            .setPositiveButton(localized(R.string.verify_positive_find), null)
            .setNegativeButton(localized(R.string.cancel), null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = etName.text?.toString()?.trim().orEmpty()
            val phone = etPhone.text?.toString()?.trim().orEmpty()
            val bill = etBill.text?.toString()?.trim().orEmpty()
            if (bill.isNotEmpty() && bill.length < 3) {
                etBill.error = localized(R.string.verify_bill_digits_required)
                return@setOnClickListener
            }
            val provided = listOf(name.isNotEmpty(), bill.isNotEmpty() || billDateIso != null, phone.isNotEmpty()).count { it }
            if (provided < 2) {
                Toast.makeText(ctx, localized(R.string.verify_fill_two_fields), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            submit.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                repo.verify(name, phone, bill, billDateIso).fold(
                    onSuccess = { r ->
                        if (r.matched && r.phone.isNotBlank()) {
                            PatientTokens.save(r.phone, r.token)
                            dialog.dismiss()
                            Toast.makeText(ctx, localized(R.string.verify_showing_reports_for, r.patientName), Toast.LENGTH_SHORT).show()
                            loadHistory(r.phone)
                        } else {
                            submit.isEnabled = true
                            Toast.makeText(ctx, localized(R.string.verify_no_match), Toast.LENGTH_LONG).show()
                        }
                    },
                    onFailure = {
                        submit.isEnabled = true
                        Toast.makeText(ctx, localized(R.string.network_error), Toast.LENGTH_LONG).show()
                    },
                )
            }
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
