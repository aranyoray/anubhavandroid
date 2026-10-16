package com.anubhav.app.ui.customer

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.StringRes
import com.anubhav.app.R
import com.anubhav.app.utils.localized
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** What the patient typed to find their reports. */
data class ReportLookupInput(val name: String, val phone: String, val bill: String) {
    /** The 10-digit mobile, ignoring spaces and a +91 / 0 prefix. */
    val phone10: String get() = phone.filter(Char::isDigit).takeLast(10)

    /**
     * The first problem as a string resource, or null when the details can be sent. All
     * three are required: the server matches the phone exactly, the bill number by its last
     * 3 or 4 digits, and the name loosely (typos are fine).
     */
    @StringRes
    fun problem(): Int? = when {
        name.isBlank() -> R.string.verify_name_required
        phone10.length != 10 -> R.string.verify_phone_required
        bill.length !in 3..4 || !bill.all(Char::isDigit) -> R.string.verify_bill_digits_required
        else -> null
    }
}

/** The name + phone + bill number form shared by login and My Reports. */
class ReportLookupForm(context: Context, inflater: LayoutInflater, prefillPhone: String = "") {
    private val ctx = context
    val view: View = inflater.inflate(R.layout.dialog_report_lookup, null)
    private val layoutName = view.findViewById<TextInputLayout>(R.id.layoutLookupName)
    private val layoutPhone = view.findViewById<TextInputLayout>(R.id.layoutLookupPhone)
    private val etName = view.findViewById<TextInputEditText>(R.id.etLookupName)
    private val etPhone = view.findViewById<TextInputEditText>(R.id.etLookupPhone)
    private val etBill = view.findViewById<EditText>(R.id.etLookupBillDigits)

    init {
        view.findViewById<TextView>(R.id.tvLookupHelp).text = ctx.localized(R.string.verify_dialog_message_short)
        layoutName.hint = ctx.localized(R.string.verify_patient_name_hint)
        layoutPhone.hint = ctx.localized(R.string.verify_phone_hint)
        view.findViewById<TextView>(R.id.tvLookupBillLabel).text = ctx.localized(R.string.verify_bill_no_label)
        view.findViewById<TextView>(R.id.tvLookupBillHint).text = ctx.localized(R.string.verify_bill_digits_hint)
        etPhone.setText(prefillPhone)
    }

    /** The typed details, or null after marking the first field that needs fixing. */
    fun validInput(): ReportLookupInput? {
        val input = ReportLookupInput(
            name = etName.text.toString().trim(),
            phone = etPhone.text.toString().trim(),
            bill = etBill.text.toString().trim(),
        )
        layoutName.error = null
        layoutPhone.error = null
        etBill.error = null
        val problem = input.problem() ?: return input
        val message = ctx.localized(problem)
        when (problem) {
            R.string.verify_name_required -> layoutName.error = message
            R.string.verify_phone_required -> layoutPhone.error = message
            else -> etBill.error = message
        }
        return null
    }
}
