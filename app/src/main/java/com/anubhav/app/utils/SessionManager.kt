package com.anubhav.app.utils

import android.content.Context

object SessionManager {
    private const val PREFS_NAME = "aktiv_admin_session"
    private const val KEY_USER_KEY = "user_key"
    private const val KEY_USERID = "userid"
    private const val KEY_USERNAME = "username"
    private const val KEY_ROLE = "role"
    private const val KEY_COLLECTOR_KEY = "collector_key"

    fun save(
        context: Context,
        userKey: Int,
        userid: String,
        username: String,
        role: String = "staff",
        collectorKey: Int? = null,
    ) {
        val normalizedRole = role.lowercase()
        val scopedCollectorKey = collectorKey.takeIf {
            normalizedRole == "collector" || normalizedRole == "admin"
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_USER_KEY, userKey)
            .putString(KEY_USERID, userid)
            .putString(KEY_USERNAME, username)
            .putString(KEY_ROLE, role)
            .apply {
                if (scopedCollectorKey != null) putInt(KEY_COLLECTOR_KEY, scopedCollectorKey) else remove(KEY_COLLECTOR_KEY)
            }
            .apply()
    }

    fun isLoggedIn(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(KEY_USER_KEY)

    fun getUserKey(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_USER_KEY)) prefs.getInt(KEY_USER_KEY, -1) else null
    }

    fun getUserid(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_USERID, null)

    fun getUsername(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_USERNAME, null)

    fun getRole(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ROLE, "staff")
            .orEmpty()

    fun getCollectorKey(context: Context): Int? {
        if (!isCollector(context)) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_COLLECTOR_KEY)) {
            prefs.getInt(KEY_COLLECTOR_KEY, -1).takeIf { it > 0 }
        } else {
            getUserKey(context)
        }
    }

    fun isCollector(context: Context): Boolean {
        val role = getRole(context).lowercase()
        return role == "collector" || role == "admin"
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
