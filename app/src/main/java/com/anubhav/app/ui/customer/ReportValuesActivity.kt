package com.anubhav.app.ui.customer

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.anubhav.app.R
import com.anubhav.app.data.model.ReportGroup
import com.anubhav.app.data.model.ReportParam
import com.anubhav.app.data.repository.CustomerRepository
import com.anubhav.app.utils.ReportFetcher
import com.anubhav.app.utils.localized
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch

/**
 * Report visualizer: structured values grouped into collapsible accordions, each
 * abnormal parameter flagged, with a lazily-loaded trend graph when the patient
 * has prior readings of the same parameter. The PDF is an explicit export option.
 */
class ReportValuesActivity : AppCompatActivity() {

    private val repo = CustomerRepository()
    private lateinit var container: LinearLayout

    private var billKey = 0
    private var phone = ""
    private var billNo: String? = null
    private var tests: String? = null
    private var viewLink: String? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        billKey = intent.getIntExtra(EXTRA_BILL_KEY, 0)
        phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        billNo = intent.getStringExtra(EXTRA_BILL_NO)
        tests = intent.getStringExtra(EXTRA_TESTS)
        viewLink = intent.getStringExtra(EXTRA_VIEW_LINK)

        val scroll = ScrollView(this).apply { setBackgroundColor(0xFFF4F7F8.toInt()) }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
        }
        scroll.addView(container)
        setContentView(scroll)

        buildHeader()
        val loading = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dp(32); gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        container.addView(loading)
        load(loading)
    }

    private fun buildHeader() {
        container.addView(TextView(this).apply {
            text = localized(R.string.report_values_title)
            textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF0B2F44.toInt())
        })
        container.addView(TextView(this).apply {
            text = listOfNotNull(billNo?.let { "ALC $it" }, tests?.takeIf { it.isNotBlank() })
                .joinToString(" · ")
            setTextColor(0xFF667085.toInt()); textSize = 13f
            setPadding(0, dp(2), 0, dp(10))
        })
        if (!viewLink.isNullOrBlank()) {
            container.addView(Button(this).apply {
                text = localized(R.string.report_export_pdf); isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                setOnClickListener { exportPdf(it as Button) }
            })
        }
    }

    private fun load(loading: View) {
        lifecycleScope.launch {
            repo.getReportValues(billKey, phone)
                .onSuccess { data ->
                    container.removeView(loading)
                    if (data.reports.isEmpty()) {
                        container.addView(infoText(localized(R.string.report_values_empty)))
                        return@onSuccess
                    }
                    container.addView(TextView(this@ReportValuesActivity).apply {
                        text = if (data.abnormalCount > 0) {
                            localized(R.string.report_values_abnormal, data.abnormalCount.toString())
                        } else {
                            localized(R.string.report_values_all_normal)
                        }
                        setTextColor(if (data.abnormalCount > 0) 0xFFC2410C.toInt() else 0xFF2E7D32.toInt())
                        textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding(0, 0, 0, dp(10))
                    })
                    data.reports.forEachIndexed { i, group -> container.addView(groupCard(group, i == 0)) }
                }
                .onFailure {
                    container.removeView(loading)
                    container.addView(infoText(localized(R.string.network_error)))
                }
        }
    }

    private fun infoText(msg: String) = TextView(this).apply {
        text = msg; setTextColor(0xFF667085.toInt()); textSize = 14f; setPadding(0, dp(24), 0, 0)
    }

    /** One collapsible accordion per test/report group. */
    private fun groupCard(group: ReportGroup, expanded: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        val chevron = TextView(this).apply { text = if (expanded) "–" else "+"; textSize = 20f; setTextColor(0xFF0B6FB4.toInt()) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@ReportValuesActivity).apply {
                text = group.testName?.takeIf { it.isNotBlank() } ?: group.category.orEmpty()
                textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFF1D2939.toInt())
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(chevron)
            setOnClickListener {
                val show = body.visibility != View.VISIBLE
                body.visibility = if (show) View.VISIBLE else View.GONE
                chevron.text = if (show) "–" else "+"
            }
        }
        card.addView(header)
        group.parameters.forEach { body.addView(paramRow(it)) }
        card.addView(body)
        return card
    }

    private fun paramRow(p: ReportParam): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = p.name; textSize = 14f; setTextColor(0xFF1D2939.toInt())
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        val flagColor = when (p.flag?.uppercase()) {
            "HIGH" -> 0xFFC2410C.toInt()
            "LOW" -> 0xFF0B6FB4.toInt()
            else -> 0xFF2E7D32.toInt()
        }
        top.addView(TextView(this).apply {
            text = listOfNotNull(p.result?.takeIf { it.isNotBlank() }, p.unit?.takeIf { it.isNotBlank() })
                .joinToString(" ")
            textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(flagColor)
        })
        row.addView(top)

        val meta = buildString {
            p.range?.takeIf { it.isNotBlank() }?.let { append(localized(R.string.report_values_ref, it)) }
            p.flag?.uppercase()?.takeIf { it == "HIGH" || it == "LOW" }?.let {
                if (isNotEmpty()) append("  ·  "); append(it)
            }
        }
        if (meta.isNotEmpty()) {
            row.addView(TextView(this).apply {
                text = meta; textSize = 12f; setTextColor(0xFF667085.toInt()); setPadding(0, dp(2), 0, 0)
            })
        }
        if (p.value != null) {
            row.addView(TextView(this).apply {
                text = localized(R.string.report_values_view_trend); isAllCaps = false
                textSize = 13f; setTextColor(0xFF0B6FB4.toInt()); setPadding(0, dp(4), 0, 0)
                setOnClickListener { showTrend(p.name, p.low?.toFloat(), p.high?.toFloat()) }
            })
        }
        return row
    }

    /** Lazily gather this parameter's values across the patient's recent ready bills. */
    private fun showTrend(paramName: String, low: Float?, high: Float?) {
        val progress = Toast.makeText(this, localized(R.string.report_values_trend_loading), Toast.LENGTH_SHORT)
        progress.show()
        lifecycleScope.launch {
            val visits = repo.getHistory(phone).getOrNull()?.visits
                ?.filter { it.hasViewLink }?.sortedByDescending { it.billDate }?.take(6).orEmpty()
            val points = mutableListOf<TrendChartView.Point>()
            for (v in visits) {
                val data = repo.getReportValues(v.billKey, phone).getOrNull() ?: continue
                val match = data.reports.flatMap { it.parameters }
                    .firstOrNull { it.name.equals(paramName, ignoreCase = true) && it.value != null } ?: continue
                val label = (data.billDate ?: v.billDate).orEmpty().take(5) // dd/MM
                points.add(TrendChartView.Point(label, match.value!!.toFloat()))
            }
            points.reverse() // oldest -> newest
            if (points.size < 2) {
                Toast.makeText(this@ReportValuesActivity, localized(R.string.report_values_trend_none), Toast.LENGTH_LONG).show()
                return@launch
            }
            val chart = TrendChartView(this@ReportValuesActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                setData(points, low, high)
            }
            val wrap = LinearLayout(this@ReportValuesActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(18))
                addView(TextView(this@ReportValuesActivity).apply {
                    text = paramName; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, 0, dp(8))
                })
                addView(chart)
            }
            BottomSheetDialog(this@ReportValuesActivity).apply { setContentView(wrap) }.show()
        }
    }

    private fun exportPdf(btn: Button) {
        val link = viewLink ?: return
        btn.isEnabled = false
        val original = btn.text
        btn.text = localized(R.string.reports_fetching)
        lifecycleScope.launch {
            runCatching { ReportFetcher.download(this@ReportValuesActivity, billKey, phone, link) }
                .onSuccess { file ->
                    btn.isEnabled = true; btn.text = original
                    val displayName = "Anubhav-Report-${(billNo ?: billKey.toString()).filter { it.isLetterOrDigit() }}"
                    val message = "Anubhav Life Care report ${billNo.orEmpty()} ${tests.orEmpty()}".trim()
                    val content = LinearLayout(this@ReportValuesActivity).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(20))
                    }
                    val sheet = BottomSheetDialog(this@ReportValuesActivity)
                    fun option(labelRes: Int, action: () -> Unit) {
                        content.addView(Button(this@ReportValuesActivity).apply {
                            text = localized(labelRes); isAllCaps = false; textSize = 15f
                            gravity = Gravity.START or Gravity.CENTER_VERTICAL
                            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
                            setOnClickListener { sheet.dismiss(); action() }
                        })
                    }
                    option(R.string.report_open) {
                        runCatching { ReportFetcher.open(this@ReportValuesActivity, file) }
                            .onFailure { Toast.makeText(this@ReportValuesActivity, localized(R.string.reports_no_pdf_viewer), Toast.LENGTH_LONG).show() }
                    }
                    option(R.string.report_share_whatsapp) {
                        if (!ReportFetcher.share(this@ReportValuesActivity, file, message)) {
                            Toast.makeText(this@ReportValuesActivity, localized(R.string.report_share_failed), Toast.LENGTH_LONG).show()
                        }
                    }
                    option(R.string.report_save_file) {
                        val ok = ReportFetcher.saveToDownloads(this@ReportValuesActivity, file, displayName)
                        Toast.makeText(this@ReportValuesActivity, localized(if (ok) R.string.report_saved_to_downloads else R.string.report_save_failed), Toast.LENGTH_LONG).show()
                    }
                    sheet.setContentView(content); sheet.show()
                }
                .onFailure {
                    btn.isEnabled = true; btn.text = original
                    Toast.makeText(this@ReportValuesActivity, localized(R.string.reports_fetch_failed), Toast.LENGTH_LONG).show()
                }
        }
    }

    companion object {
        const val EXTRA_BILL_KEY = "bill_key"
        const val EXTRA_PHONE = "phone"
        const val EXTRA_BILL_NO = "bill_no"
        const val EXTRA_TESTS = "tests"
        const val EXTRA_VIEW_LINK = "view_link"
    }
}
