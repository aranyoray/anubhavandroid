package com.anubhav.app.data.remote

import com.anubhav.app.data.model.AdminReport
import com.anubhav.app.data.model.AktivBookingRequest
import com.anubhav.app.data.model.AktivBookingResponse
import com.anubhav.app.data.model.StaffBill
import com.anubhav.app.data.model.StaffBillDetail
import com.anubhav.app.data.model.StaffBillEdit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Staff endpoints behind the Admin screen. Staff sign in with their AKTIV user id and
 * password (`api/auth/login`); the token that returns travels as X-Staff-Token, and the
 * server re-checks the user's AKTIV roles on every call.
 */
interface AdminApi {
    @GET("api/admin/report")
    suspend fun report(
        @Header("X-Staff-Token") token: String,
        @Query("report") report: String,
        @Query("start") start: String,
        @Query("end") end: String,
        @Query("bill_details") billDetails: Boolean,
        @Query("test_details") testDetails: Boolean,
    ): AdminReport

    @GET("api/staff/bills")
    suspend fun searchBills(
        @Header("X-Staff-Token") token: String,
        @Query("q") query: String,
    ): List<StaffBill>

    @GET("api/staff/bills/{billKey}")
    suspend fun bill(
        @Header("X-Staff-Token") token: String,
        @Path("billKey") billKey: Int,
    ): StaffBillDetail

    @PATCH("api/staff/bills/{billKey}")
    suspend fun editBill(
        @Header("X-Staff-Token") token: String,
        @Path("billKey") billKey: Int,
        @Body edit: StaffBillEdit,
    ): StaffBillDetail

    @POST("api/bookings")
    suspend fun createBooking(
        @Header("X-Staff-Token") token: String,
        @Body request: AktivBookingRequest,
    ): AktivBookingResponse

    @POST("api/bookings/{billKey}/cancel")
    suspend fun cancelBooking(
        @Header("X-Staff-Token") token: String,
        @Path("billKey") billKey: Int,
        @Body body: Map<String, Int?> = emptyMap(),
    ): Map<String, Any>
}
