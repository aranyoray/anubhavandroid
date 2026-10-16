package com.anubhav.app.ui.customer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.anubhav.app.R
import com.anubhav.app.utils.ReportFetcher
import com.anubhav.app.utils.localized
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** In-app, closeable report viewer: renders the PDF pages and exports via the share sheet. */
class ReportPdfDialog : DialogFragment() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val file get() = File(requireArguments().getString(ARG_PATH)!!)
    private val displayName get() = requireArguments().getString(ARG_NAME).orEmpty()
    private val message get() = requireArguments().getString(ARG_MSG).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFECEFF2.toInt())
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        // Top bar: close (left), title, export (right).
        val bar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        bar.addView(TextView(requireContext()).apply {
            text = "✕"; textSize = 20f; setTextColor(0xFF1D2939.toInt())
            setPadding(dp(8), dp(4), dp(12), dp(4))
            setOnClickListener { dismiss() }
        })
        bar.addView(TextView(requireContext()).apply {
            text = localized(R.string.reports_view_report)
            textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF0B2F44.toInt())
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        bar.addView(TextView(requireContext()).apply {
            text = localized(R.string.report_export_pdf)
            textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF0B6FB4.toInt()); setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { showExport() }
        })
        root.addView(bar)

        val pages = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val progress = ProgressBar(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dp(40); gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        pages.addView(progress)
        root.addView(ScrollView(requireContext()).apply {
            addView(pages); layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        })

        renderInto(pages, progress)
        return root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(MATCH_PARENT, MATCH_PARENT)
    }

    private fun renderInto(pages: LinearLayout, progress: View) {
        val widthPx = resources.displayMetrics.widthPixels - dp(20)
        lifecycleScope.launch {
            val bitmaps = withContext(Dispatchers.IO) {
                val out = mutableListOf<Bitmap>()
                runCatching {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            for (i in 0 until renderer.pageCount) {
                                renderer.openPage(i).use { page ->
                                    val scale = widthPx.toFloat() / page.width
                                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                                    val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                                    bmp.eraseColor(Color.WHITE)
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    out.add(bmp)
                                }
                            }
                        }
                    }
                }
                out
            }
            pages.removeView(progress)
            if (bitmaps.isEmpty()) {
                pages.addView(TextView(requireContext()).apply {
                    text = localized(R.string.reports_no_pdf_viewer)
                    setTextColor(0xFF667085.toInt()); setPadding(dp(8), dp(24), dp(8), 0)
                })
                return@launch
            }
            bitmaps.forEach { bmp ->
                pages.addView(ImageView(requireContext()).apply {
                    setImageBitmap(bmp); adjustViewBounds = true
                    setBackgroundColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) }
                })
            }
        }
    }

    private fun showExport() {
        val ctx = requireContext()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        val sheet = BottomSheetDialog(ctx)
        fun option(labelRes: Int, action: () -> Unit) {
            content.addView(TextView(ctx).apply {
                text = localized(labelRes); textSize = 16f; setTextColor(0xFF1D2939.toInt())
                setPadding(dp(4), dp(14), dp(4), dp(14))
                setOnClickListener { sheet.dismiss(); action() }
            })
        }
        option(R.string.report_share_sheet) {
            if (!ReportFetcher.shareChooser(ctx, file, message)) {
                Toast.makeText(ctx, localized(R.string.report_share_failed), Toast.LENGTH_LONG).show()
            }
        }
        option(R.string.report_save_file) {
            val ok = ReportFetcher.saveToDownloads(ctx, file, displayName)
            Toast.makeText(ctx, localized(if (ok) R.string.report_saved_to_downloads else R.string.report_save_failed), Toast.LENGTH_LONG).show()
        }
        sheet.setContentView(content)
        sheet.show()
    }

    companion object {
        private const val ARG_PATH = "path"
        private const val ARG_NAME = "name"
        private const val ARG_MSG = "msg"

        fun show(fm: FragmentManager, path: String, displayName: String, message: String) {
            ReportPdfDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_PATH, path); putString(ARG_NAME, displayName); putString(ARG_MSG, message)
                }
            }.show(fm, "report_pdf")
        }
    }
}
