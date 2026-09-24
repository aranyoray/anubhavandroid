package com.anubhav.app.data.model

import com.google.gson.annotations.SerializedName

data class AktivLoginRequest(
    val userid: String,
    val password: String,
)

data class AktivLoginResponse(
    val success: Boolean,
    @SerializedName("user_key") val userKey: Int,
    val userid: String,
    val username: String,
    val role: String = "staff",
    @SerializedName("collector_key") val collectorKey: Int? = null,
    /** What this AKTIV user may do, from their AKTIV roles (server-enforced too). */
    val permissions: StaffPermissions = StaffPermissions(),
    /** Sent as X-Staff-Token on staff calls; valid 12 hours. */
    val token: String = "",
)

/** Mirrors api/roles.py: AKTIV roles translated into what the Admin screen may offer. */
data class StaffPermissions(
    val roles: List<String> = emptyList(),
    @SerializedName("is_admin") val isAdmin: Boolean = false,
    @SerializedName("can_view_sales") val canViewSales: Boolean = false,
    @SerializedName("can_book") val canBook: Boolean = false,
    @SerializedName("can_edit_booking") val canEditBooking: Boolean = false,
    @SerializedName("can_cancel_booking") val canCancelBooking: Boolean = false,
    @SerializedName("modification_days") val modificationDays: Int = 0,
    @SerializedName("accountview_days") val accountviewDays: Int = 0,
)

data class AktivTest(
    @SerializedName("test_key") val testKey: Int,
    @SerializedName("testcode") val testCode: String,
    @SerializedName("testname") val testName: String,
    val rate: Double,
    @SerializedName("category_key") val categoryKey: Int? = null,
    @SerializedName("category_name") val categoryName: String? = null,
)

data class AktivDoctor(
    @SerializedName("refrdoctor_key") val refrdoctorKey: Int,
    @SerializedName("doctorcode") val doctorCode: String?,
    @SerializedName("doctorname") val doctorName: String,
    val qualification: String? = null,
    @SerializedName("qualification2") val qualification2: String? = null,
    @SerializedName("qualification3") val qualification3: String? = null,
    val phone: String? = null,
) {
    val displayName: String
        get() = listOfNotNull(doctorName, qualification, qualification2, qualification3)
            .joinToString(" ")
}

data class AktivCollectionCentre(
    @SerializedName("collcentre_key") val collcentreKey: Int,
    @SerializedName("collcentrecode") val collcentreCode: String?,
    @SerializedName("collcentrename") val collcentreName: String,
    @SerializedName("coll_initial") val collInitial: String? = null,
)

data class AktivReceptionUser(
    @SerializedName("user_key") val userKey: Int,
    val userid: String? = null,
    val username: String? = null,
) {
    val displayName: String
        get() = userid ?: username ?: "User $userKey"
}

data class AktivBillNumber(
    @SerializedName("bill_no") val billNo: String,
    @SerializedName("bill_number") val billNumber: String,
)

data class AktivBookingRequest(
    @SerializedName("patient_name") val patientName: String,
    val phone: String,
    val sex: String = "MALE",
    @SerializedName("age_year") val ageYear: Int? = null,
    @SerializedName("age_month") val ageMonth: Int? = null,
    @SerializedName("age_day") val ageDay: Int? = null,
    @SerializedName("refrdoctor_key") val refrdoctorKey: Int? = null,
    @SerializedName("collcentre_key") val collcentreKey: Int = 1,
    @SerializedName("test_keys") val testKeys: List<Int>,
    @SerializedName("bill_date") val billDate: String? = null,
    @SerializedName("bill_number") val billNumber: String? = null,
    @SerializedName("amount_paid") val amountPaid: Double? = null,
    @SerializedName("receipt_mode") val receiptMode: String = "CASH",
    @SerializedName("cheque_no") val chequeNo: String? = null,
    val remarks: String? = null,
    @SerializedName("test_mode") val testMode: Boolean? = null,
    @SerializedName("sys_user_key") val sysUserKey: Int? = null,
)

data class AktivBookingResponse(
    val success: Boolean,
    @SerializedName("bill_key") val billKey: Int,
    @SerializedName("bill_no") val billNo: String,
    @SerializedName("bill_number") val billNumber: String,
    @SerializedName("registration_no") val registrationNo: String,
    @SerializedName("apnt_key") val apntKey: Int,
    @SerializedName("net_amount") val netAmount: Double,
    @SerializedName("apnt_date") val apntDate: String,
    @SerializedName("test_mode") val testMode: Boolean = false,
)
