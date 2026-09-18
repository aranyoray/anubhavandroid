package com.anubhav.app.data.remote

import com.anubhav.app.data.model.AdminCheckResponse
import com.anubhav.app.data.model.AdminReport
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * Admin booking-details endpoints. The password is entered by the admin at login and sent
 * as a request header (not in the URL, so it never reaches web-server access logs); the
 * server (`api/admin_reports.py`) is the authority on it.
 */
interface AdminApi {
    /** Cheap password gate the app calls before opening the Admin screen. */
    @GET("api/admin/check")
    suspend fun check(@Header("X-Admin-Password") password: String): AdminCheckResponse

    @GET("api/admin/report")
    suspend fun report(
        @Header("X-Admin-Password") password: String,
        @Query("report") report: String,
        @Query("start") start: String,
        @Query("end") end: String,
        @Query("bill_details") billDetails: Boolean,
        @Query("test_details") testDetails: Boolean,
    ): AdminReport
}
