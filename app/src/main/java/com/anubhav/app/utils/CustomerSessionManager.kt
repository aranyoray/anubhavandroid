package com.anubhav.app.utils

import android.content.Context

object CustomerSessionManager {
    private const val PREFS = "anubhav_customer_session"
    private const val KEY_PHONE = "phone"
    private const val KEY_EMAIL = "email"
    private const val KEY_NAME = "name"
    private const val KEY_FIREBASE_UID = "firebase_uid"
    private const val KEY_ROLE = "role"
    private const val KEY_COLLECTOR_KEY = "collector_key"

    fun save(
        context: Context,
        phone: String?,
        email: String?,
        name: String?,
        firebaseUid: String,
        role: String = "customer",
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PHONE, phone)
            .putString(KEY_EMAIL, email)
            .putString(KEY_NAME, name)
            .putString(KEY_FIREBASE_UID, firebaseUid)
            .putString(KEY_ROLE, role)
            .apply()
        PatientTokens.sessionPhone = phone
    }

    fun isLoggedIn(context: Context): Boolean = !getFirebaseUid(context).isNullOrBlank()

    fun getPhone(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PHONE, null)

    fun getEmail(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EMAIL, null)

    fun getName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, null)

    fun getFirebaseUid(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FIREBASE_UID, null)

    fun getRole(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROLE, "customer")
            .orEmpty()

    fun getCollectorKey(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_COLLECTOR_KEY)) {
            prefs.getInt(KEY_COLLECTOR_KEY, -1).takeIf { it > 0 }
        } else {
            null
        }
    }

    fun saveCollectorKey(context: Context, collectorKey: Int?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (collectorKey != null && collectorKey > 0) {
                    putInt(KEY_COLLECTOR_KEY, collectorKey)
                    putString(KEY_ROLE, "collector")
                } else {
                    remove(KEY_COLLECTOR_KEY)
                }
            }
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        PatientTokens.clear()
        PatientTokens.sessionPhone = null
    }
}
