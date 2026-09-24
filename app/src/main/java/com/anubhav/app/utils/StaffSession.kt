package com.anubhav.app.utils

import com.anubhav.app.data.model.AktivLoginResponse

/**
 * The AKTIV staff member signed in to the Admin screen. Memory only: the token lasts
 * 12 hours on the server, and a shared or lost phone should not stay signed in as
 * staff, so closing the app (or process death) means signing in again.
 */
object StaffSession {
    @Volatile var current: AktivLoginResponse? = null

    val isSignedIn: Boolean get() = !current?.token.isNullOrBlank()

    fun signOut() {
        current = null
    }
}
