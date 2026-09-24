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

data class AdminCheckResponse(
    val ok: Boolean = false,
)
