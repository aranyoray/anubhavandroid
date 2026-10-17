package com.anubhav.app.ui.customer

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentManager
import com.anubhav.app.R
import com.anubhav.app.utils.localized
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** What the patient typed to find their reports. */
data class ReportLookupInput(val name: String, val billDate: String, val bill: String) {
    /**
     * The first problem as a string resource, or null when the details can be sent. The
     * server matches the name fuzzily, the bill date by its month and the bill number by
     * its last 3 or 4 digits.
     */
    @StringRes
    fun problem(): Int? = when {
        name.isBlank() -> R.string.verify_name_required
        billDate.isBlank() -> R.string.verify_date_required
        bill.length !in 3..4 || !bill.all(Char::isDigit) -> R.string.verify_bill_digits_required
        else -> null
    }
}

/** The name + bill date + bill number form shared by login and My Reports. */
class ReportLookupForm(
    context: Context,
    inflater: LayoutInflater,
    private val fragmentManager: FragmentManager,
) {
    private val ctx = context
    val view: View = inflater.inflate(R.layout.dialog_report_lookup, null)
    private val layoutName = view.findViewById<TextInputLayout>(R.id.layoutLookupName)
    private val layoutDate = view.findViewById<TextInputLayout>(R.id.layoutLookupDate)
    private val layoutBill = view.findViewById<TextInputLayout>(R.id.layoutLookupBill)
    private val etName = view.findViewById<TextInputEditText>(R.id.etLookupName)
    private val etDate = view.findViewById<TextInputEditText>(R.id.etLookupDate)
    private val etBill = view.findViewById<TextInputEditText>(R.id.etLookupBillDigits)

    /** The picked day as ISO yyyy-MM-dd, which is what the API expects. */
    private var billDateIso: String = ""

    init {
        view.findViewById<TextView>(R.id.tvLookupHelp).text = ctx.localized(R.string.verify_dialog_message_short)
        layoutName.hint = ctx.localized(R.string.verify_patient_name_hint)
        layoutDate.hint = ctx.localized(R.string.verify_bill_date_hint)
        layoutBill.hint = ctx.localized(R.string.verify_bill_no_label)
        layoutBill.helperText = ctx.localized(R.string.verify_bill_digits_hint)
        etDate.setOnClickListener { showDatePicker() }
        layoutDate.setStartIconOnClickListener { showDatePicker() }
    }

    private fun showDatePicker() {
        val today = MaterialDatePicker.todayInUtcMilliseconds()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(ctx.localized(R.string.verify_bill_date_hint))
            .setSelection(billDateIso.takeIf { it.isNotBlank() }?.let(::utcMillisOf) ?: today)
            .setCalendarConstraints(
                // A bill can only have been issued already.
                CalendarConstraints.Builder()
                    .setValidator(DateValidatorPointBackward.before(today))
                    .setOpenAt(today)
                    .build(),
            )
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            billDateIso = iso.format(millis)
            etDate.setText(display.format(millis))
            layoutDate.error = null
        }
        picker.show(fragmentManager, "reportLookupDate")
    }

    /** The typed details, or null after marking the first field that needs fixing. */
    fun validInput(): ReportLookupInput? {
        val input = ReportLookupInput(
            name = etName.text.toString().trim(),
            billDate = billDateIso,
            bill = etBill.text.toString().trim(),
        )
        layoutName.error = null
        layoutDate.error = null
        layoutBill.error = null
        val problem = input.problem() ?: return input
        val message = ctx.localized(problem)
        when (problem) {
            R.string.verify_name_required -> layoutName.error = message
            R.string.verify_date_required -> layoutDate.error = message
            else -> layoutBill.error = message
        }
        return null
    }

    private companion object {
        /** The picker reports UTC midnight, so both formatters must read it as UTC. */
        private fun utc(pattern: String) = SimpleDateFormat(pattern, Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }

        val iso = utc("yyyy-MM-dd")
        val display = utc("d MMM yyyy")

        fun utcMillisOf(isoDate: String): Long? = runCatching { iso.parse(isoDate)?.time }.getOrNull()
    }
}
