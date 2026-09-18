package com.anubhav.app.ui.customer

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anubhav.app.R
import com.anubhav.app.data.model.CustomerBill
import com.anubhav.app.data.repository.CustomerRepository
import com.anubhav.app.utils.CustomerSessionManager
import com.anubhav.app.utils.localized
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class MyBookingsFragment : Fragment() {
    private val repo = CustomerRepository()
    private lateinit var adapter: BookingAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_customer_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.rvItems)
        val progress = view.findViewById<View>(R.id.progressBar)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmpty)
        val btnAction = view.findViewById<MaterialButton>(R.id.btnEmptyAction)
        val searchLayout = view.findViewById<TextInputLayout>(R.id.layoutListSearch)
        val search = view.findViewById<TextInputEditText>(R.id.etListSearch)
        val summary = view.findViewById<TextView>(R.id.tvListSummary)

        view.findViewById<TextView>(R.id.tvTitle).text = localized(R.string.menu_my_bookings)
        searchLayout.visibility = View.VISIBLE
        searchLayout.hint = localized(R.string.search_bookings)
        btnAction.text = localized(R.string.book_a_test)
        btnAction.visibility = View.VISIBLE
        summary.visibility = View.GONE

        adapter = BookingAdapter(
            onVisibleChanged = { bills -> bindSummary(summary, bills) },
            onPayDue = { openPendingPayments() },
        )
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        search.addSearchWatcher { adapter.filter(it) }
        btnAction.setOnClickListener { findNavController().navigate(R.id.nav_book_test) }

        loadBookings(progress, tvEmpty)
    }

    private fun loadBookings(progress: View, tvEmpty: TextView) {
        val phone = CustomerSessionManager.getPhone(requireContext()).orEmpty()
        if (phone.isBlank()) {
            progress.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = localized(R.string.phone_required)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            repo.getBillsCached(requireContext(), phone, forceRefresh = false).fold(
                onSuccess = { bills ->
                    progress.visibility = View.GONE
                    tvEmpty.visibility = if (bills.isEmpty()) View.VISIBLE else View.GONE
                    tvEmpty.text = localized(R.string.no_bookings)
                    adapter.submit(bills)
                },
                onFailure = {
                    progress.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = localized(R.string.network_error)
                    Toast.makeText(requireContext(), it.message ?: localized(R.string.network_error), Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    private fun bindSummary(summary: TextView, bills: List<CustomerBill>) {
        if (bills.isEmpty()) {
            summary.visibility = View.GONE
            return
        }
        summary.visibility = View.VISIBLE
        summary.text = localized(
            R.string.bill_register_summary,
            bills.size,
            bills.sumOf { it.receivedAmount },
            bills.sumOf { it.pendingAmount },
        )
    }

    private fun openPendingPayments() {
        runCatching { findNavController().navigate(R.id.nav_pending_payments) }
            .onFailure {
                Toast.makeText(requireContext(), localized(R.string.pending_payments_title), Toast.LENGTH_SHORT).show()
            }
    }
}

private class BookingAdapter(
    private val onVisibleChanged: (List<CustomerBill>) -> Unit,
    private val onPayDue: (CustomerBill) -> Unit,
) : RecyclerView.Adapter<BookingAdapter.VH>() {
    private var allItems: List<CustomerBill> = emptyList()
    private var items: List<CustomerBill> = emptyList()
    private var query: String = ""

    fun submit(newItems: List<CustomerBill>) {
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
                listOf(it.billNo, it.billDate, it.patientName, it.remarks, it.apntNo, it.apntDate)
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
        val title = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val meta = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 13f
            setLineSpacing(2f, 1f)
        }
        val status = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 12f
            setPadding(14, 8, 14, 8)
        }
        val pay = MaterialButton(context).apply {
            text = context.localized(R.string.pay_pending_bill)
            setIconResource(R.drawable.ic_payments)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(title)
        row.addView(meta)
        row.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 10, 0, 0) })
        row.addView(pay, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 10, 0, 0) })
        return VH(row, title, meta, status, pay)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val bill = items[position]
        val context = holder.itemView.context
        holder.title.text = buildString {
            append(bill.billNo ?: context.localized(R.string.bill_key_value, bill.billKey))
            append(" | ")
            append(bill.patientName ?: "-")
        }
        holder.meta.text = buildString {
            append(bill.billDate ?: "-")
            bill.apntDate?.takeIf { it.isNotBlank() }?.let {
                append('\n').append(context.localized(R.string.appointment_value, it, bill.apntNo ?: "-"))
            }
            bill.remarks?.takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
        }
        holder.status.text = context.localized(
            R.string.booking_amounts_value,
            bill.netAmount,
            bill.pendingAmount,
        )
        holder.status.background = ContextCompat.getDrawable(
            context,
            if (bill.pendingAmount > 0.01) R.drawable.chip_warning_bg else R.drawable.chip_success_soft_bg,
        )
        holder.pay.visibility = if (bill.pendingAmount > 0.01) View.VISIBLE else View.GONE
        holder.pay.setOnClickListener { onPayDue(bill) }
    }

    override fun getItemCount(): Int = items.size

    class VH(
        view: View,
        val title: TextView,
        val meta: TextView,
        val status: TextView,
        val pay: MaterialButton,
    ) : RecyclerView.ViewHolder(view)
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
