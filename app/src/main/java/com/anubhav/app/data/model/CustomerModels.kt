package com.anubhav.app.data.model

import com.google.gson.annotations.SerializedName

data class CustomerProfile(
    val found: Boolean,
    @SerializedName("patient_name") val patientName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    @SerializedName("registration_no") val registrationNo: String? = null,
    @SerializedName("regt_key") val regtKey: Int? = null,
)

/** Guest/patient login: 2 of 3 must match — name, (bill no OR bill date), phone. */
data class CustomerVerifyRequest(
    val name: String = "",
    val phone: String = "",
    @SerializedName("bill_no") val billNo: String = "",
    @SerializedName("bill_date") val billDate: String? = null,
)

data class VerifiedBill(
    @SerializedName("bill_key") val billKey: Int,
    @SerializedName("bill_no") val billNo: String?,
    @SerializedName("bill_date") val billDate: String?,
    @SerializedName("patient_name") val patientName: String?,
)

data class CustomerVerifyResponse(
    val matched: Boolean = false,
    @SerializedName("patient_name") val patientName: String = "",
    /** canonical phone recovered from the matched bill — key the portal off this. */
    val phone: String = "",
    val bills: List<VerifiedBill> = emptyList(),
    /** Server-signed proof of this match; report calls for [phone] need it. */
    val token: String? = null,
)

/** One AKTIV visit (bill) from the static all-history DB — the My Reports row. */
data class CustomerVisit(
    @SerializedName("bill_key") val billKey: Int,
    @SerializedName("patient_name") val patientName: String? = null,
    @SerializedName("bill_date") val billDate: String? = null,
    @SerializedName("bill_no") val billNo: String? = null,   // the ALC number
    val tests: String? = null,                                // comma-separated test names
    val ready: Boolean = false,
    @SerializedName("view_link") val viewLink: String? = null, // collated single-PDF link
) {
    val hasViewLink: Boolean get() = ready && !viewLink.isNullOrBlank()
}

data class CustomerHistoryResponse(
    val phone: String = "",
    val count: Int = 0,
    val visits: List<CustomerVisit> = emptyList(),
)

data class CustomerBill(
    @SerializedName("bill_key") val billKey: Int,
    @SerializedName("bill_no") val billNo: String?,
    @SerializedName("billdate") val billDate: String?,
    @SerializedName("patientname") val patientName: String?,
    @SerializedName("netamount") val netAmount: Double,
    @SerializedName("receivedamount") val receivedAmount: Double,
    @SerializedName("pending_amount") val pendingAmount: Double,
    val remarks: String? = null,
    @SerializedName("apntdate") val apntDate: String? = null,
    @SerializedName("apnt_no") val apntNo: String? = null,
    @SerializedName("reschedule_phone") val reschedulePhone: String? = null,
    @SerializedName("reschedule_note") val rescheduleNote: String? = null,
    // how many reports exist for this bill and how many are authorised/ready
    @SerializedName("report_count") val reportCount: Int = 0,
    @SerializedName("ready_count") val readyCount: Int = 0,
)

data class CustomerReport(
    @SerializedName("bill_key") val billKey: Int,
    @SerializedName("bill_no") val billNo: String?,
    @SerializedName("billdate") val billDate: String?,
    @SerializedName("patientname") val patientName: String? = null,
    @SerializedName("testname") val testName: String?,
    @SerializedName("testcode") val testCode: String?,
    @SerializedName("reportingdate") val reportingDate: String?,
    val status: String?,
    @SerializedName("pending_amount") val pendingAmount: Double = 0.0,
    @SerializedName("status_message") val statusMessage: String? = null,
    @SerializedName("can_share") val canShare: Boolean? = null,
    // Set by the API once a report is authorised (CONFIRM_REPORT=1).
    @SerializedName("ready") val ready: Boolean? = null,
    // Direct link to render/open the report PDF (all categories). Null until ready.
    @SerializedName("view_link") val viewLink: String? = null,
) {
    val isReady: Boolean
        get() = ready ?: (status.equals("READY", ignoreCase = true) && !reportingDate.isNullOrBlank())

    val isBillDueBlocked: Boolean
        get() = pendingAmount > 0.01

    val isShareable: Boolean
        get() = canShare ?: (isReady && !isBillDueBlocked)

    val hasViewLink: Boolean
        get() = !viewLink.isNullOrBlank()
}

data class CollectorPatient(
    val id: Int? = null,
    @SerializedName("collector_user_key") val collectorUserKey: Int,
    @SerializedName("patient_name") val patientName: String,
    val phone: String,
    @SerializedName("age_year") val ageYear: Int? = null,
    val sex: String? = null,
    @SerializedName("referred_by") val referredBy: String? = null,
    val notes: String? = null,
    @SerializedName("followup_status") val followupStatus: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
)

data class CollectorPatientRequest(
    @SerializedName("collector_user_key") val collectorUserKey: Int,
    @SerializedName("patient_name") val patientName: String,
    val phone: String,
    @SerializedName("age_year") val ageYear: Int? = null,
    val sex: String? = null,
    @SerializedName("referred_by") val referredBy: String? = null,
    val notes: String? = null,
    @SerializedName("followup_status") val followupStatus: String? = null,
)

data class PrebookSlotInfo(
    @SerializedName("time_slot") val timeSlot: String,
    val label: String,
    val capacity: Int,
    val booked: Int,
    val remaining: Int,
    val available: Boolean,
)

data class PrebookDateInfo(
    val date: String,
    val slots: List<PrebookSlotInfo>,
)

data class PrebookCalendar(
    @SerializedName("prebook_days") val prebookDays: List<Int>,
    @SerializedName("advance_fraction") val advanceFraction: Double,
    @SerializedName("advance_non_refundable") val advanceNonRefundable: Boolean,
    @SerializedName("reschedule_phone") val reschedulePhone: String,
    val dates: List<PrebookDateInfo>,
)

data class CustomerPrebookRequest(
    @SerializedName("patient_name") val patientName: String,
    val phone: String,
    val sex: String = "MALE",
    @SerializedName("age_year") val ageYear: Int? = null,
    @SerializedName("test_keys") val testKeys: List<Int>,
    @SerializedName("slot_date") val slotDate: String,
    @SerializedName("time_slot") val timeSlot: String,
    @SerializedName("payment_id") val paymentId: String,
    @SerializedName("amount_paid") val amountPaid: Double,
    val email: String? = null,
    // Home-collection address, optionally geotagged via "Use my current location".
    @SerializedName("address") val address: String? = null,
    @SerializedName("latitude") val latitude: Double? = null,
    @SerializedName("longitude") val longitude: Double? = null,
)

data class CustomerPrebookResponse(
    val success: Boolean,
    @SerializedName("bill_key") val billKey: Int,
    @SerializedName("bill_no") val billNo: String,
    @SerializedName("alc_code") val alcCode: String,
    @SerializedName("slot_date") val slotDate: String,
    @SerializedName("time_slot") val timeSlot: String,
    @SerializedName("time_slot_label") val timeSlotLabel: String,
    @SerializedName("total_amount") val totalAmount: Double,
    @SerializedName("advance_paid") val advancePaid: Double,
    @SerializedName("balance_due") val balanceDue: Double,
    @SerializedName("reschedule_phone") val reschedulePhone: String,
    @SerializedName("reschedule_note") val rescheduleNote: String,
)

data class CustomerPaymentRequest(
    @SerializedName("bill_key") val billKey: Int,
    val phone: String,
    @SerializedName("amount_paid") val amountPaid: Double,
    @SerializedName("payment_id") val paymentId: String,
)

data class CustomerPaymentResponse(
    val success: Boolean,
    @SerializedName("bill_key") val billKey: Int,
    @SerializedName("amount_paid") val amountPaid: Double,
    @SerializedName("payment_id") val paymentId: String,
)
