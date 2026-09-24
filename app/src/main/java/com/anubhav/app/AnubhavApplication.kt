package com.anubhav.app

import android.app.Application
import com.anubhav.app.utils.CustomerSessionManager
import com.anubhav.app.utils.PatientTokens

class AnubhavApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The HTTP client attaches patient tokens without a Context of its own.
        PatientTokens.init(this)
        PatientTokens.sessionPhone = CustomerSessionManager.getPhone(this)
    }
}
