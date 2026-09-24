package com.anubhav.app.ui.customer

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anubhav.app.MainActivity
import com.anubhav.app.R
import com.anubhav.app.data.model.CustomerBill
import com.anubhav.app.data.model.CustomerPaymentRequest
import com.anubhav.app.data.repository.CustomerRepository
import com.anubhav.app.utils.CustomerSessionManager
import com.anubhav.app.utils.PaymentManager
import com.anubhav.app.utils.localized
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.razorpay.PaymentResultListener
import kotlinx.coroutines.launch

class PendingPaymentsFragment : Fragment(), PaymentResultListener {
    private val repo = CustomerRepository()
    private var pendingBill: CustomerBill? = null
    private var phone: String = ""
    private lateinit var rv: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var tvSummary: TextView
    private lateinit var btnRefresh: MaterialButton
    private lateinit var adapter: PendingAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_pending_payments, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? MainActivity)?.setPaymentListener(this)
        rv = view.findViewById(R.id.rvItems)
        progress = view.findViewById(R.id.progressBar)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        tvSummary = view.findViewById(R.id.tvPendingSummary)
        btnRefresh = view.findViewById(R.id.btnRefresh)
        val search = view.findViewById<TextInputEditText>(R.id.etPendingSearch)
        adapter = PendingAdapter(requireContext()) { bill ->
            pendingBill = bill
            PaymentManager(requireActivity(), this@PendingPaymentsFragment).startPayment(
                amount = bill.pendingAmount,
                name = bill.patientName.orEmpty(),
                email = CustomerSessionManager.getEmail(requireContext()).orEmpty(),
                phone = phone,
                description = localized(R.string.payment_description_pending, bill.billNo ?: "-"),
            )
        }
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        search.addSearchWatcher { adapter.filter(it) }

        view.findViewById<TextView>(R.id.tvPendingTitle)?.text = localized(R.string.pending_payments_title)
        btnRefresh.text = localized(R.string.refresh_from_aktiv)
        tvEmpty.text = localized(R.string.no_pending)

        phone = CustomerSessionManager.getPhone(requireContext()).orEmpty()
        if (phone.isBlank()) {
            progress.visibility = View.GONE
            tvSummary.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = localized(R.string.phone_required)
            btnRefresh.visibility = View.GONE
            return
        }

        btnRefresh.setOnClickListener { loadPending(forceRefresh = true) }
        loadPending(forceRefresh = false)
    }

    private fun loadPending(forceRefresh: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            btnRefresh.isEnabled = false
            repo.getPendingPaymentsCached(requireContext(), phone, forceRefresh).fold(
                onSuccess = { bills ->
                    progress.visibility = View.GONE
                    btnRefresh.isEnabled = true
                    tvEmpty.visibility = if (bills.isEmpty()) View.VISIBLE else View.GONE
                    tvEmpty.text = localized(R.string.no_pending)
                    tvSummary.text = localized(
                        R.string.pending_summary,
                        bills.size,
                        bills.sumOf { it.pendingAmount },
                    )
                    tvSummary.visibility = if (bills.isEmpty()) View.GONE else View.VISIBLE
                    adapter.submit(bills)
                },
                onFailure = {
                    progress.visibility = View.GONE
                    btnRefresh.isEnabled = true
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = localized(R.string.network_error)
                    Toast.makeText(requireContext(), it.message ?: localized(R.string.network_error), Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?) {
        val paymentId = razorpayPaymentId?.takeIf { it.isNotBlank() }
        if (paymentId == null) {
            Toast.makeText(requireContext(), localized(R.string.payment_failed), Toast.LENGTH_LONG).show()
            return
        }
        val bill = pendingBill
        if (bill == null) {
            // The fragment was recreated during checkout (rotation / low memory), so the
            // in-flight bill was lost. Never drop a paid transaction silently — surface the
            // payment id so the user can reconcile it with support.
            Toast.makeText(
                requireContext(),
                localized(R.string.payment_recorded_contact_support, paymentId),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repo.payPendingCached(
                requireContext(),
                CustomerPaymentRequest(
                    billKey = bill.billKey,
                    phone = phone,
                    amountPaid = bill.pendingAmount,
                    paymentId = paymentId,
                ),
            ).fold(
                onSuccess = {
                    Toast.makeText(requireContext(), localized(R.string.payment_success), Toast.LENGTH_LONG).show()
                    pendingBill = null
                    loadPending(forceRefresh = true)
                },
                onFailure = {
                    Toast.makeText(
                        requireContext(),
                        it.message ?: localized(R.string.payment_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    override fun onPaymentError(code: Int, description: String?) {
        Toast.makeText(
            requireContext(),
            description ?: localized(R.string.payment_failed),
            Toast.LENGTH_LONG,
        ).show()
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.setPaymentListener(null)
        super.onDestroyView()
    }
}

private class PendingAdapter(
    private val context: android.content.Context,
    private val onPay: (CustomerBill) -> Unit,
) : RecyclerView.Adapter<PendingAdapter.VH>() {
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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        // setMargins takes pixels, not dp — on a 3x-density phone the old literals were
        // a third of the gap they looked like in a preview.
        val density = parent.resources.displayMetrics.density
        val h = (16 * density).toInt()
        val v = (8 * density).toInt()
        val btn = MaterialButton(parent.context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(h, v, h, v) }
            maxLines = 2
        }
        return VH(btn)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val bill = items[position]
        holder.button.text = context.localized(R.string.pay_bill, bill.billNo ?: "-", bill.pendingAmount)
        holder.button.setOnClickListener { onPay(bill) }
    }

    override fun getItemCount(): Int = items.size

    class VH(val button: MaterialButton) : RecyclerView.ViewHolder(button)
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
