package com.anubhav.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Admin booking-details report — the response of `GET /api/admin/report`.
 *
 * Every report (tests / income / cc / due) shares this envelope: a strip of headline
 * figures plus a list of tables the server has already formatted (Indian grouping, ₹),
 * so the Admin screen renders any of them with one generic view.
 */
data class AdminSummaryItem(
    val label: String = "",
    val value: String = "",
)

data class AdminSection(
    val title: String = "",
    val columns: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val note: String? = null,
)

data class AdminReport(
    val report: String = "",
    val title: String = "",
    val start: String = "",
    val end: String = "",
    @SerializedName("bill_details") val billDetails: Boolean = false,
    @SerializedName("test_details") val testDetails: Boolean = false,
    @SerializedName("range_note") val rangeNote: String? = null,
    val summary: List<AdminSummaryItem> = emptyList(),
    val sections: List<AdminSection> = emptyList(),
)

/** A bill in the staff search list (`GET /api/staff/bills`). */
data class StaffBill(
    @SerializedName("bill_key") val billKey: Int,
    @SerializedName("bill_no") val billNo: String = "",
    @SerializedName("bill_date") val billDate: String = "",
    @SerializedName("patient_name") val patientName: String = "",
    val phone: String = "",
    @SerializedName("net_amount") val netAmount: Double = 0.0,
    @SerializedName("received_amount") val receivedAmount: Double = 0.0,
)

data class StaffBillTest(
    @SerializedName("test_name") val testName: String = "",
    val ready: Boolean = false,
)

/** One bill with its particulars and tests (`GET /api/staff/bills/{key}`). */
data class StaffBillDetail(
    @SerializedName("bill_key") val billKey: Int,
    @SerializedName("bill_no") val billNo: String = "",
    @SerializedName("bill_date") val billDate: String = "",
    @SerializedName("patient_name") val patientName: String = "",
    val phone: String = "",
    val sex: String = "",
    @SerializedName("age_year") val ageYear: Int? = null,
    val remarks: String = "",
    @SerializedName("net_amount") val netAmount: Double = 0.0,
    @SerializedName("received_amount") val receivedAmount: Double = 0.0,
    val tests: List<StaffBillTest> = emptyList(),
)

/** Patient particulars a BILLCHANGE user may correct; null fields are left alone. */
data class StaffBillEdit(
    @SerializedName("patient_name") val patientName: String? = null,
    val phone: String? = null,
    val sex: String? = null,
    @SerializedName("age_year") val ageYear: Int? = null,
    val remarks: String? = null,
)
