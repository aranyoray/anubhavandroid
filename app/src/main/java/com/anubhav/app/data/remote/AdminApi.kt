package com.anubhav.app.data.remote

import com.anubhav.app.data.model.AdminCheckResponse
import com.anubhav.app.data.model.AdminReport
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Admin booking-details endpoints. The password is entered by the admin at login and sent
 * on every call; the server (`api/admin_reports.py`) is the authority on it.
 */
interface AdminApi {
    /** Cheap password gate the app calls before opening the Admin screen. */
    @GET("api/admin/check")
    suspend fun check(@Query("password") password: String): AdminCheckResponse

    @GET("api/admin/report")
    suspend fun report(
        @Query("password") password: String,
        @Query("report") report: String,
        @Query("start") start: String,
        @Query("end") end: String,
        @Query("bill_details") billDetails: Boolean,
        @Query("test_details") testDetails: Boolean,
    ): AdminReport
}
