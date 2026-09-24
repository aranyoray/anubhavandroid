package com.anubhav.app.ui.admin

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.anubhav.app.R
import com.anubhav.app.data.model.AdminReport
import com.anubhav.app.data.model.AdminSection
import com.anubhav.app.data.model.AktivBookingRequest
import com.anubhav.app.data.model.AktivTest
import com.anubhav.app.data.model.StaffBillDetail
import com.anubhav.app.data.model.StaffBillEdit
import com.anubhav.app.data.model.StaffPermissions
import com.anubhav.app.data.repository.AdminRepository
import com.anubhav.app.data.repository.AktivRepository
import com.anubhav.app.utils.ReportFetcher
import com.anubhav.app.utils.StaffSession
import com.anubhav.app.utils.localized
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * Admin screen for AKTIV staff, reached from "Admin? Click here" on the login screen after
 * signing in with an AKTIV user id + password. What it offers follows that user's AKTIV
 * roles: anyone can look bills up and see test counts; money reports need account rights;
 * New booking needs booking rights; Edit and Cancel need BILLCHANGE / cancel rights. The
 * server checks the same rights on every call - hiding a button is only a courtesy.
 *
 * Reports: a date range + Bill/Test detail toggles feed Tests, Income, CC (collection
 * centres) and Due, each rendered as a headline strip plus tables that the server
 * (`api/admin_reports.py`) has already formatted.
 */
class AdminActivity : AppCompatActivity() {

    private val repo = AdminRepository()
    private val catalog = AktivRepository()
    private lateinit var perms: StaffPermissions

    private var from: LocalDate = LocalDate.now()
    private var to: LocalDate = LocalDate.now()

    private lateinit var tvFrom: TextView
    private lateinit var tvTo: TextView
    private lateinit var cbBill: MaterialCheckBox
    private lateinit var cbTest: MaterialCheckBox
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var results: LinearLayout

    private val displayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val isoFmt: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val staff = StaffSession.current
        if (staff == null || !StaffSession.isSignedIn) {
            finish()
            return
        }
        perms = staff.permissions
        setContentView(R.layout.activity_admin)

        val roles = perms.roles.joinToString(", ").ifBlank { staff.role }
        findViewById<TextView>(R.id.tvStaffWho).text =
            localized(R.string.admin_signed_in_as, staff.username.ifBlank { staff.userid }, roles)
        findViewById<TextView>(R.id.btnAdminSignOut).apply {
            text = localized(R.string.admin_sign_out)
            setOnClickListener {
                StaffSession.signOut()
                finish()
            }
        }
        findViewById<MaterialButton>(R.id.btnNewBooking).apply {
            text = localized(R.string.admin_new_booking)
            visibility = if (perms.canBook) View.VISIBLE else View.GONE
            setOnClickListener { showNewBooking() }
        }
        findViewById<MaterialButton>(R.id.btnFindBill).apply {
            text = localized(R.string.admin_find_bill)
            setOnClickListener { showFindBill() }
        }
        // Money figures follow AKTIV's account-view rights; test counts are for everyone.
        val money = if (perms.canViewSales) View.VISIBLE else View.GONE
        listOf(R.id.btnIncome, R.id.btnCC, R.id.btnDue).forEach { findViewById<View>(it).visibility = money }

        tvFrom = findViewById(R.id.tvFromDate)
        tvTo = findViewById(R.id.tvToDate)
        cbBill = findViewById(R.id.cbBillDetails)
        cbTest = findViewById(R.id.cbTestDetails)
        progress = findViewById(R.id.adminProgress)
        status = findViewById(R.id.tvAdminStatus)
        results = findViewById(R.id.adminResults)

        findViewById<View>(R.id.btnAdminBack).setOnClickListener { finish() }
        findViewById<View>(R.id.fieldFrom).setOnClickListener { pickDate(isFrom = true) }
        findViewById<View>(R.id.fieldTo).setOnClickListener { pickDate(isFrom = false) }

        findViewById<MaterialButton>(R.id.btnTests).setOnClickListener { load("tests") }
        findViewById<MaterialButton>(R.id.btnIncome).setOnClickListener { load("income") }
        findViewById<MaterialButton>(R.id.btnCC).setOnClickListener { load("cc") }
        findViewById<MaterialButton>(R.id.btnDue).setOnClickListener { load("due") }

        renderDates()
    }

    private fun renderDates() {
        tvFrom.text = from.format(displayFmt)
        tvTo.text = to.format(displayFmt)
    }

    private fun pickDate(isFrom: Boolean) {
        val current = if (isFrom) from else to
        DatePickerDialog(
            this,
            { _, y, m, d ->
                val picked = LocalDate.of(y, m + 1, d)
                if (isFrom) {
                    from = picked
                    if (to.isBefore(from)) to = from
                } else {
                    to = picked
                    if (from.isAfter(to)) from = to
                }
                renderDates()
            },
            current.year, current.monthValue - 1, current.dayOfMonth,
        ).show()
    }

    private fun load(report: String) {
        progress.visibility = View.VISIBLE
        results.removeAllViews()
        status.text = localized(R.string.loading)
        lifecycleScope.launch {
            repo.report(
                report = report,
                start = from.format(isoFmt),
                end = to.format(isoFmt),
                billDetails = cbBill.isChecked,
                testDetails = cbTest.isChecked,
            ).fold(
                onSuccess = { data ->
                    progress.visibility = View.GONE
                    render(data)
                },
                onFailure = {
                    progress.visibility = View.GONE
                    status.text = failure(it)
                },
            )
        }
    }

    /**
     * Toast the reason a staff call failed and return it. An expired or revoked sign-in
     * (401) ends the session; a refusal (403) says the AKTIV role does not allow it.
     */
    private fun failure(err: Throwable): String {
        val message = when (AdminRepository.statusOf(err)) {
            401 -> localized(R.string.admin_session_expired)
            403 -> AdminRepository.messageOf(err) ?: localized(R.string.admin_not_allowed)
            null -> localized(R.string.network_error)
            else -> AdminRepository.messageOf(err) ?: localized(R.string.something_went_wrong)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        if (AdminRepository.statusOf(err) == 401) {
            StaffSession.signOut()
            finish()
        }
        return message
    }

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
    }

    private fun field(hintRes: Int, type: Int, value: String = ""): EditText = EditText(this).apply {
        hint = localized(hintRes)
        inputType = type
        setText(value)
    }

    private fun sexPicker(current: String): RadioGroup = RadioGroup(this).apply {
        orientation = RadioGroup.HORIZONTAL
        listOf("MALE", "FEMALE", "OTHER").forEach { sex ->
            addView(RadioButton(this@AdminActivity).apply {
                id = View.generateViewId()
                text = sex
                tag = sex
                isChecked = sex == current.ifBlank { "MALE" }
            })
        }
    }

    private fun RadioGroup.selectedTag(): String? =
        findViewById<RadioButton>(checkedRadioButtonId)?.tag as? String

    private fun rupees(v: Double): String = String.format(java.util.Locale.US, "%,.0f", v)

    // ---------------------------------------------------------------- find / view a bill

    private fun showFindBill() {
        val box = column()
        val query = field(R.string.admin_find_bill_hint, InputType.TYPE_CLASS_TEXT)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(query)
        box.addView(ScrollView(this).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(320))
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle(localized(R.string.admin_find_bill))
            .setView(box)
            .setPositiveButton(localized(R.string.admin_search), null)
            .setNegativeButton(localized(R.string.admin_close), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val q = query.text.toString().trim()
                if (q.length < 2) return@setOnClickListener
                list.removeAllViews()
                lifecycleScope.launch {
                    repo.searchBills(q).fold(
                        onSuccess = { bills ->
                            if (bills.isEmpty()) {
                                list.addView(TextView(this@AdminActivity).apply {
                                    text = localized(R.string.admin_no_bills)
                                    setPadding(0, dp(12), 0, 0)
                                })
                            }
                            bills.forEach { b ->
                                list.addView(TextView(this@AdminActivity).apply {
                                    text = "${b.billNo}  ·  ${b.billDate}\n${b.patientName}  ·  ${b.phone}"
                                    textSize = 14f
                                    setPadding(0, dp(10), 0, dp(10))
                                    setBackgroundResource(android.R.drawable.list_selector_background)
                                    setOnClickListener { showBill(b.billKey) }
                                })
                            }
                        },
                        onFailure = { failure(it) },
                    )
                }
            }
        }
        dialog.show()
    }

    private fun showBill(billKey: Int) {
        lifecycleScope.launch {
            repo.bill(billKey).fold(
                onSuccess = { renderBill(it) },
                onFailure = { failure(it) },
            )
        }
    }

    private fun renderBill(bill: StaffBillDetail) {
        val box = column()
        box.addView(TextView(this).apply {
            text = localized(
                R.string.admin_bill_summary, bill.billNo, bill.billDate,
                rupees(bill.netAmount), rupees(bill.receivedAmount),
            )
            textSize = 14f
        })
        box.addView(TextView(this).apply {
            val age = bill.ageYear?.let { "$it y" } ?: "-"
            text = localized(R.string.admin_patient_line, bill.patientName, bill.phone, "${bill.sex} $age".trim())
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(6))
        })
        bill.tests.forEach { t ->
            box.addView(TextView(this).apply {
                val state = localized(if (t.ready) R.string.admin_test_ready else R.string.admin_test_pending)
                text = "• ${t.testName} ($state)"
                textSize = 13f
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton(localized(R.string.admin_close), null)
            .create()
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        fun action(label: Int, onClick: () -> Unit) = actions.addView(MaterialButton(this).apply {
            text = localized(label)
            isAllCaps = false
            setOnClickListener { onClick() }
        })
        if (bill.tests.any { it.ready }) action(R.string.admin_view_report) { openReport(bill.billKey) }
        if (perms.canEditBooking) action(R.string.admin_edit) { dialog.dismiss(); showEdit(bill) }
        if (perms.canCancelBooking) action(R.string.admin_cancel_bill) { dialog.dismiss(); confirmCancel(bill) }
        box.addView(actions)
        dialog.show()
    }

    private fun openReport(billKey: Int) {
        lifecycleScope.launch {
            repo.reportPdf(this@AdminActivity, billKey).fold(
                onSuccess = { file ->
                    runCatching { ReportFetcher.open(this@AdminActivity, file) }.onFailure {
                        Toast.makeText(this@AdminActivity, localized(R.string.reports_no_pdf_viewer), Toast.LENGTH_LONG).show()
                    }
                },
                onFailure = { failure(it) },
            )
        }
    }

    private fun showEdit(bill: StaffBillDetail) {
        val box = column()
        val name = field(R.string.verify_patient_name_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, bill.patientName)
        val phone = field(R.string.verify_phone_hint, InputType.TYPE_CLASS_PHONE, bill.phone)
        val sex = sexPicker(bill.sex)
        val age = field(R.string.admin_age_hint, InputType.TYPE_CLASS_NUMBER, bill.ageYear?.toString().orEmpty())
        val remarks = field(R.string.admin_remarks_hint, InputType.TYPE_CLASS_TEXT, bill.remarks)
        listOf(name, phone, sex, age, remarks).forEach { box.addView(it) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("${localized(R.string.admin_edit)} · ${bill.billNo}")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(localized(R.string.admin_save), null)
            .setNegativeButton(localized(R.string.cancel), null)
            .create()
        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                // Send only what changed, so an edit never rewrites fields nobody touched.
                fun changed(new: String, old: String) = new.trim().takeIf { it != old.trim() }
                val edit = StaffBillEdit(
                    patientName = changed(name.text.toString(), bill.patientName),
                    phone = changed(phone.text.toString(), bill.phone),
                    sex = sex.selectedTag()?.takeIf { it != bill.sex },
                    ageYear = age.text.toString().toIntOrNull()?.takeIf { it != bill.ageYear },
                    remarks = changed(remarks.text.toString(), bill.remarks),
                )
                if (edit == StaffBillEdit()) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                save.isEnabled = false
                lifecycleScope.launch {
                    repo.editBill(bill.billKey, edit).fold(
                        onSuccess = {
                            dialog.dismiss()
                            Toast.makeText(this@AdminActivity, localized(R.string.admin_saved), Toast.LENGTH_SHORT).show()
                            renderBill(it)
                        },
                        onFailure = {
                            save.isEnabled = true
                            failure(it)
                        },
                    )
                }
            }
        }
        dialog.show()
    }

    private fun confirmCancel(bill: StaffBillDetail) {
        AlertDialog.Builder(this)
            .setMessage(localized(R.string.admin_cancel_confirm, bill.billNo))
            .setPositiveButton(localized(R.string.admin_cancel_bill)) { _, _ ->
                lifecycleScope.launch {
                    repo.cancelBooking(bill.billKey).fold(
                        onSuccess = {
                            Toast.makeText(
                                this@AdminActivity,
                                localized(R.string.admin_cancelled, bill.billNo),
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                        onFailure = { failure(it) },
                    )
                }
            }
            .setNegativeButton(localized(R.string.cancel), null)
            .show()
    }

    // ---------------------------------------------------------------- new booking

    private fun showNewBooking() {
        val box = column()
        val name = field(R.string.verify_patient_name_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        val phone = field(R.string.verify_phone_hint, InputType.TYPE_CLASS_PHONE)
        val sex = sexPicker("MALE")
        val age = field(R.string.admin_age_hint, InputType.TYPE_CLASS_NUMBER)
        val search = field(R.string.admin_test_search_hint, InputType.TYPE_CLASS_TEXT)
        val picked = linkedMapOf<Int, AktivTest>()
        val summary = TextView(this).apply { setPadding(0, dp(6), 0, dp(6)); textSize = 13f }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val paid = field(R.string.admin_amount_paid_hint, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val mode = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf("CASH", "UPI", "CARD").forEach { m ->
                addView(RadioButton(this@AdminActivity).apply {
                    id = View.generateViewId(); text = m; tag = m; isChecked = m == "CASH"
                })
            }
        }
        fun refreshSummary() {
            summary.text = localized(R.string.admin_selected_tests, picked.size, rupees(picked.values.sumOf { it.rate }))
        }
        fun showResults(tests: List<AktivTest>) {
            results.removeAllViews()
            // Keep already-picked tests on screen so they can be un-ticked after a new search.
            (picked.values + tests.filterNot { picked.containsKey(it.testKey) }).take(40).forEach { t ->
                results.addView(CheckBox(this).apply {
                    text = "${t.testName}  ₹${rupees(t.rate)}"
                    isChecked = picked.containsKey(t.testKey)
                    setOnCheckedChangeListener { _, on ->
                        if (on) picked[t.testKey] = t else picked.remove(t.testKey)
                        refreshSummary()
                    }
                })
            }
        }
        refreshSummary()
        search.setOnEditorActionListener { v, _, _ ->
            val q = v.text.toString().trim()
            lifecycleScope.launch {
                catalog.searchTests(q).fold(onSuccess = { showResults(it) }, onFailure = { failure(it) })
            }
            true
        }
        search.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        listOf(name, phone, sex, age, search, results, summary, paid, mode).forEach { box.addView(it) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(localized(R.string.admin_new_booking))
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(localized(R.string.admin_create), null)
            .setNegativeButton(localized(R.string.cancel), null)
            .create()
        dialog.setOnShowListener {
            val create = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            create.setOnClickListener {
                val digits = phone.text.toString().filter { it.isDigit() }.takeLast(10)
                if (name.text.isBlank() || digits.length < 10 || picked.isEmpty()) {
                    Toast.makeText(this, localized(R.string.admin_booking_fill), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                create.isEnabled = false
                val request = AktivBookingRequest(
                    patientName = name.text.toString().trim(),
                    phone = digits,
                    sex = sex.selectedTag() ?: "MALE",
                    ageYear = age.text.toString().toIntOrNull(),
                    testKeys = picked.keys.toList(),
                    amountPaid = paid.text.toString().toDoubleOrNull(),
                    receiptMode = mode.selectedTag() ?: "CASH",
                )
                lifecycleScope.launch {
                    repo.createBooking(request).fold(
                        onSuccess = { r ->
                            dialog.dismiss()
                            Toast.makeText(
                                this@AdminActivity,
                                localized(R.string.admin_booking_done, r.billNo, rupees(r.netAmount)),
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                        onFailure = {
                            create.isEnabled = true
                            failure(it)
                        },
                    )
                }
            }
        }
        dialog.show()
    }

    private fun render(data: AdminReport) {
        results.removeAllViews()
        val range = "${data.title}  ·  ${data.start} → ${data.end}"
        status.text = data.rangeNote?.let { "$range\n$it" } ?: range

        if (data.summary.isNotEmpty()) {
            results.addView(summaryCard(data))
        }
        data.sections.forEach { results.addView(sectionView(it)) }
    }

    private fun summaryCard(data: AdminReport): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.list_item_background)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }
        data.summary.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(this).apply {
                text = item.label
                setTextColor(ContextCompat.getColor(this@AdminActivity, R.color.text_secondary))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            row.addView(TextView(this).apply {
                text = item.value
                setTextColor(ContextCompat.getColor(this@AdminActivity, R.color.brand_dark_blue))
                textSize = 16f
                gravity = Gravity.END
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(row)
        }
        return card
    }

    private fun sectionView(section: AdminSection): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) }
        }
        wrap.addView(TextView(this).apply {
            text = section.title
            setTextColor(ContextCompat.getColor(this@AdminActivity, R.color.brand_dark_blue))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(2), 0, 0, dp(8))
        })

        if (section.rows.isEmpty()) {
            wrap.addView(TextView(this).apply {
                text = localized(R.string.admin_no_rows)
                setTextColor(ContextCompat.getColor(this@AdminActivity, R.color.text_secondary))
                textSize = 13f
            })
            return wrap
        }

        val table = TableLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@AdminActivity, R.color.card_background))
        }
        table.addView(tableRow(section.columns, header = true))
        // Build a bounded number of rows: these tables are laid out synchronously on the main
        // thread inside a non-recycling ScrollView, so drawing a whole detail section (up to
        // hundreds of bills x six columns) at once would freeze a low-end phone. The server
        // already caps the data; this caps what is materialised into Views.
        val shown = section.rows.take(MAX_RENDER_ROWS)
        shown.forEach { table.addView(tableRow(it, header = false)) }

        val scroller = android.widget.HorizontalScrollView(this).apply {
            isFillViewport = true
            addView(table)
        }
        wrap.addView(scroller)

        if (section.rows.size > shown.size) {
            wrap.addView(TextView(this).apply {
                text = localized(R.string.admin_rows_truncated, shown.size, section.rows.size)
                setTextColor(ContextCompat.getColor(this@AdminActivity, R.color.text_secondary))
                textSize = 12f
                setPadding(dp(2), dp(6), 0, 0)
            })
        }

        section.note?.let { note ->
            wrap.addView(TextView(this).apply {
                text = note
                setTextColor(ContextCompat.getColor(this@AdminActivity, R.color.text_secondary))
                textSize = 12f
                setPadding(dp(2), dp(6), 0, 0)
            })
        }
        return wrap
    }

    private fun tableRow(cells: List<String>, header: Boolean): TableRow {
        val row = TableRow(this)
        if (header) {
            row.setBackgroundColor(ContextCompat.getColor(this, R.color.nav_selected_background))
        }
        cells.forEachIndexed { i, text ->
            row.addView(TextView(this).apply {
                this.text = text
                textSize = 13f
                maxWidth = dp(240)
                maxLines = 3
                setPadding(dp(10), dp(9), dp(10), dp(9))
                gravity = if (i == 0) Gravity.START else Gravity.END
                setTextColor(
                    ContextCompat.getColor(
                        this@AdminActivity,
                        if (header) R.color.brand_dark_blue else R.color.text_primary,
                    ),
                )
                if (header) setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
        return row
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        // Cap the rows materialised into Views per section so a wide-range detail report
        // cannot freeze a low-end phone; the count line still reports the true total.
        private const val MAX_RENDER_ROWS = 150
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
