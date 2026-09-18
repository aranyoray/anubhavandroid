package com.anubhav.app.ui.admin

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.anubhav.app.R
import com.anubhav.app.data.model.AdminReport
import com.anubhav.app.data.model.AdminSection
import com.anubhav.app.data.repository.AdminRepository
import com.anubhav.app.utils.localized
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * Admin booking-details screen (reached from the login screen with the admin password).
 * A date range + Bill/Test detail toggles feed the four report buttons — Tests, Income,
 * CC (collection centres) and Due — each rendered as a headline strip plus tables that
 * the server (`api/admin_reports.py`) has already formatted.
 */
class AdminActivity : AppCompatActivity() {

    private val repo = AdminRepository()
    private lateinit var password: String

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
        password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        if (password.isBlank()) {
            finish()
            return
        }
        setContentView(R.layout.activity_admin)

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
                password = password,
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
                    status.text = it.message ?: localized(R.string.network_error)
                    Toast.makeText(
                        this@AdminActivity,
                        it.message ?: localized(R.string.network_error),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
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
        section.rows.forEach { table.addView(tableRow(it, header = false)) }

        val scroller = android.widget.HorizontalScrollView(this).apply {
            isFillViewport = true
            addView(table)
        }
        wrap.addView(scroller)

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
        const val EXTRA_PASSWORD = "admin_password"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
