package com.anubhav.app.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Patient tokens, one per verified phone number.
 *
 * The server hands one back from the 2-of-3 check (`/api/customer/verify`) and only
 * answers report, bill and payment calls for the phone that token was issued for.
 * A patient can verify several numbers ("Fetch another report" for a relative), so
 * this keeps a token per phone. Stored in app-private prefs; backups are disabled.
 */
object PatientTokens {
    private const val PREFS = "anubhav_patient_tokens"
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun key(phone: String) = phone.filter { it.isDigit() }.takeLast(10)

    fun save(phone: String, token: String?) {
        if (token.isNullOrBlank()) return
        prefs?.edit()?.putString(key(phone), token)?.apply()
    }

    fun get(phone: String?): String? = phone?.let { prefs?.getString(key(it), null) }

    fun has(phone: String?): Boolean = !get(phone).isNullOrBlank()

    fun forget(phone: String) {
        prefs?.edit()?.remove(key(phone))?.apply()
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }

    /** The phone this session is signed in with, for requests that carry it in a body. */
    @Volatile var sessionPhone: String? = null
}
