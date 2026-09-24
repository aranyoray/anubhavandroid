package com.anubhav.app.ui.login

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.anubhav.app.MainActivity
import com.anubhav.app.R
import com.anubhav.app.data.repository.AdminRepository
import com.anubhav.app.data.repository.CustomerRepository
import com.anubhav.app.ui.admin.AdminActivity
import com.anubhav.app.utils.CustomerSessionManager
import com.anubhav.app.utils.LanguageManager
import com.anubhav.app.utils.PatientTokens
import com.anubhav.app.utils.localized
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FacebookAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class LoginActivity : AppCompatActivity() {

    private val auth = FirebaseAuth.getInstance()
    private val customerRepo = CustomerRepository()
    private val adminRepo = AdminRepository()
    private lateinit var languageManager: LanguageManager
    private lateinit var googleSignInClient: GoogleSignInClient
    private val facebookCallbackManager = CallbackManager.Factory.create()

    private var isSignUpMode = false
    private var pendingLanguage: String? = null

    private lateinit var layoutLanguage: LinearLayout
    private lateinit var layoutLogin: View
    private lateinit var tvLoginError: TextView
    private lateinit var progressLogin: ProgressBar
    private lateinit var btnEmailAuth: MaterialButton
    private lateinit var btnPhoneAuth: MaterialButton
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var etClinicPhone: TextInputEditText

    private val googleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        setLoading(false)
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken.isNullOrBlank()) {
                showInlineError(localized(R.string.login_failed))
                return@registerForActivityResult
            }
            signInWithGoogleToken(idToken)
        } catch (e: ApiException) {
            // 12501 = user cancelled the chooser; 10 = DEVELOPER_ERROR, i.e. this build's
            // SHA-1/package is not registered in the Firebase project, so no code change makes
            // Google work here - point the user at a login that does.
            when (e.statusCode) {
                12501 -> Unit
                10 -> showInlineError(localized(R.string.google_signin_misconfigured))
                else -> showInlineError(e.localizedMessage ?: localized(R.string.login_failed))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (CustomerSessionManager.isLoggedIn(this)) {
            openMain()
            return
        }

        languageManager = LanguageManager(this)
        setContentView(R.layout.activity_login)
        bindViews()
        setupGoogleSignIn()
        setupFacebookLogin()
        setupLanguageSelection()
        setupLoginUi()

        if (languageManager.hasSelectedLanguage()) {
            showLoginScreen()
        } else {
            showLanguageScreen()
        }
    }

    private fun bindViews() {
        layoutLanguage = findViewById(R.id.layoutLanguage)
        layoutLogin = findViewById(R.id.layoutLogin)
        tvLoginError = findViewById(R.id.tvLoginError)
        progressLogin = findViewById(R.id.progressLogin)
        btnEmailAuth = findViewById(R.id.btnEmailAuth)
        btnPhoneAuth = findViewById(R.id.btnPhoneAuth)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        etClinicPhone = findViewById(R.id.etClinicPhone)
    }

    private fun setupGoogleSignIn() {
        val webClientId = getString(R.string.default_web_client_id)
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun setupFacebookLogin() {
        LoginManager.getInstance().registerCallback(
            facebookCallbackManager,
            object : FacebookCallback<LoginResult> {
                override fun onSuccess(result: LoginResult) {
                    signInWithFacebookToken(result.accessToken.token)
                }

                override fun onCancel() {
                    setLoading(false)
                }

                override fun onError(error: FacebookException) {
                    setLoading(false)
                    showInlineError(error.localizedMessage ?: localized(R.string.login_failed))
                }
            },
        )
    }

    private fun setupLanguageSelection() {
        val cardEnglish = findViewById<MaterialCardView>(R.id.cardEnglish)
        val cardBengali = findViewById<MaterialCardView>(R.id.cardBengali)
        val ivEnglishCheck = findViewById<ImageView>(R.id.ivEnglishCheck)
        val ivBengaliCheck = findViewById<ImageView>(R.id.ivBengaliCheck)
        val btnContinue = findViewById<MaterialButton>(R.id.btnContinueLanguage)

        fun updateLanguageCards() {
            val englishSelected = pendingLanguage == LanguageManager.LANGUAGE_ENGLISH
            val bengaliSelected = pendingLanguage == LanguageManager.LANGUAGE_BENGALI
            cardEnglish.strokeWidth = if (englishSelected) 3 else 0
            cardBengali.strokeWidth = if (bengaliSelected) 3 else 0
            ivEnglishCheck.visibility = if (englishSelected) View.VISIBLE else View.GONE
            ivBengaliCheck.visibility = if (bengaliSelected) View.VISIBLE else View.GONE
            btnContinue.isEnabled = pendingLanguage != null
        }

        cardEnglish.setOnClickListener {
            pendingLanguage = LanguageManager.LANGUAGE_ENGLISH
            updateLanguageCards()
        }
        cardBengali.setOnClickListener {
            pendingLanguage = LanguageManager.LANGUAGE_BENGALI
            updateLanguageCards()
        }
        btnContinue.setOnClickListener {
            pendingLanguage?.let { languageManager.setLanguage(it) }
            showLoginScreen()
        }

        updateLanguageCards()
        refreshLanguageScreenTexts()
    }

    private fun setupLoginUi() {
        findViewById<MaterialButton>(R.id.btnGoogle).setOnClickListener {
            setLoading(true)
            hideInlineError()
            googleLauncher.launch(googleSignInClient.signInIntent)
        }

        val btnFacebook = findViewById<MaterialButton>(R.id.btnFacebook)
        // A button that only ever shows "not configured" is worse than no button. Hide it until
        // the real Facebook app id / client token replace the REPLACE_WITH_ placeholders.
        btnFacebook.visibility = if (isFacebookConfigured()) View.VISIBLE else View.GONE
        btnFacebook.setOnClickListener {
            setLoading(true)
            hideInlineError()
            if (!isFacebookConfigured()) {
                setLoading(false)
                showInlineError(localized(R.string.facebook_login_not_configured))
                return@setOnClickListener
            }
            LoginManager.getInstance().logInWithReadPermissions(
                this,
                facebookCallbackManager,
                listOf("email", "public_profile"),
            )
        }

        btnEmailAuth.setOnClickListener { handleEmailAuth() }
        btnPhoneAuth.setOnClickListener { handlePhoneAuth() }

        findViewById<TextView>(R.id.tvForgotPassword).setOnClickListener {
            val email = etEmail.text?.toString()?.trim().orEmpty()
            if (!email.contains("@")) {
                showInlineError(localized(R.string.invalid_email))
                return@setOnClickListener
            }
            setLoading(true)
            auth.sendPasswordResetEmail(email)
                .addOnCompleteListener { task ->
                    setLoading(false)
                    if (task.isSuccessful) {
                        Toast.makeText(this, localized(R.string.password_reset_sent), Toast.LENGTH_LONG).show()
                    } else {
                        showInlineError(task.exception?.localizedMessage ?: localized(R.string.something_went_wrong))
                    }
                }
        }

        findViewById<TextView>(R.id.tvToggleAuthMode).setOnClickListener {
            isSignUpMode = !isSignUpMode
            refreshLoginTexts()
            hideInlineError()
        }

        findViewById<TextView>(R.id.tvChangeLanguage).setOnClickListener {
            showLanguageScreen()
        }

        findViewById<TextView>(R.id.tvAdminLogin).setOnClickListener {
            showAdminDialog()
        }
    }

    /**
     * Staff sign-in with the same AKTIV user id + password as the desktop. The server
     * checks them against AKTIV and returns the user's rights, so the Admin screen offers
     * exactly what that user may do in AKTIV (some only book, some also edit or cancel).
     */
    private fun showAdminDialog() {
        hideInlineError()
        val etUser = EditText(this).apply {
            hint = localized(R.string.admin_userid_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val etPass = EditText(this).apply {
            hint = localized(R.string.admin_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad + pad / 4, pad / 2, pad + pad / 4, 0)
            addView(etUser)
            addView(etPass)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(localized(R.string.admin_signin_title))
            .setView(container)
            .setPositiveButton(localized(R.string.admin_unlock), null)
            .setNegativeButton(localized(R.string.cancel), null)
            .create()
        dialog.setOnShowListener {
            val signIn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            signIn.setOnClickListener {
                val user = etUser.text?.toString()?.trim().orEmpty()
                val pw = etPass.text?.toString().orEmpty()
                if (user.isEmpty() || pw.isEmpty()) return@setOnClickListener
                // The dialog stays open during the network check, so disable the button to
                // stop a double-tap from firing two sign-ins and stacking two admin screens.
                signIn.isEnabled = false
                setLoading(true)
                lifecycleScope.launch {
                    adminRepo.signIn(user, pw).fold(
                        onSuccess = {
                            setLoading(false)
                            dialog.dismiss()
                            startActivity(Intent(this@LoginActivity, AdminActivity::class.java))
                        },
                        onFailure = { err ->
                            setLoading(false)
                            signIn.isEnabled = true
                            val message = if (AdminRepository.statusOf(err) == 401) {
                                localized(R.string.admin_wrong_password)
                            } else {
                                localized(R.string.network_error)
                            }
                            Toast.makeText(this@LoginActivity, message, Toast.LENGTH_LONG).show()
                        },
                    )
                }
            }
        }
        dialog.show()
    }

    private fun handlePhoneAuth() {
        hideInlineError()
        val prefill = etClinicPhone.text?.toString()?.filter { it.isDigit() }.orEmpty().takeLast(10)
        showClinicVerifyDialog(prefill)
    }

    /**
     * AKTIV 2-of-3 verification (no OTP): patient enters Name, Bill No OR Bill Date, Phone.
     * Any two matching a bill logs them in — so a wrong phone still works via name + bill.
     * More secure than phone-only (a phone number alone shouldn't unlock someone's reports).
     */
    private fun showClinicVerifyDialog(prefillPhone: String) {
        val d = (resources.displayMetrics.density)
        fun px(v: Int) = (v * d).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
        }
        val etName = EditText(this).apply {
            hint = localized(R.string.verify_patient_name_hint)
            inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_CLASS_TEXT
        }
        val etPhone = EditText(this).apply {
            hint = localized(R.string.verify_phone_hint); inputType = InputType.TYPE_CLASS_PHONE; setText(prefillPhone)
        }
        val etBill = EditText(this).apply {
            // Text, not number: patients copy "2026/09/ALC/4171" off the receipt as often as "4171".
            hint = localized(R.string.verify_bill_no_hint); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        var billDateIso: String? = null
        val dateBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = localized(R.string.verify_pick_bill_date)
            setOnClickListener {
                val c = Calendar.getInstance()
                DatePickerDialog(this@LoginActivity, { _, y, m, day ->
                    billDateIso = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, day)
                    text = localized(
                        R.string.verify_bill_date_value,
                        String.format(Locale.US, "%02d/%02d/%04d", day, m + 1, y),
                    )
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
            }
        }
        container.addView(etName)
        container.addView(etPhone)
        container.addView(TextView(this).apply { text = localized(R.string.verify_bill_no_label); setPadding(0, px(8), 0, 0) })
        container.addView(etBill)
        container.addView(TextView(this).apply { text = localized(R.string.verify_or_divider); gravity = Gravity.CENTER; setPadding(0, px(6), 0, px(6)) })
        container.addView(dateBtn)

        val dialog = AlertDialog.Builder(this)
            .setTitle(localized(R.string.verify_dialog_title_login))
            .setMessage(localized(R.string.verify_dialog_message))
            .setView(container)
            .setPositiveButton(localized(R.string.verify_positive_view), null)
            .setNegativeButton(localized(R.string.cancel), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = etName.text.toString().trim()
                val phone = etPhone.text.toString().trim()
                val bill = etBill.text.toString().trim()
                val provided = listOf(name.isNotEmpty(), bill.isNotEmpty() || billDateIso != null, phone.isNotEmpty()).count { it }
                if (provided < 2) {
                    Toast.makeText(this, localized(R.string.verify_fill_two_fields), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                setLoading(true)
                lifecycleScope.launch {
                    customerRepo.verify(name, phone, bill, billDateIso).fold(
                        onSuccess = { r ->
                            setLoading(false)
                            if (r.matched && r.phone.isNotBlank()) {
                                PatientTokens.save(r.phone, r.token)
                                CustomerSessionManager.save(
                                    this@LoginActivity,
                                    phone = r.phone,
                                    email = null,
                                    name = r.patientName,
                                    firebaseUid = "clinic-verify-${r.phone}",
                                )
                                dialog.dismiss()
                                openMain()
                            } else {
                                Toast.makeText(
                                    this@LoginActivity,
                                    localized(R.string.verify_no_match),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        onFailure = { err ->
                            setLoading(false)
                            Toast.makeText(this@LoginActivity, err.localizedMessage ?: localized(R.string.network_error), Toast.LENGTH_LONG).show()
                        },
                    )
                }
            }
        }
        dialog.show()
    }

    private fun handleEmailAuth() {
        hideInlineError()
        val email = etEmail.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString().orEmpty()

        if (!email.contains("@")) {
            showInlineError(localized(R.string.invalid_email))
            return
        }
        if (password.length < 6) {
            showInlineError(localized(R.string.invalid_password))
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            try {
                val result = if (isSignUpMode) {
                    auth.createUserWithEmailAndPassword(email, password).await()
                } else {
                    auth.signInWithEmailAndPassword(email, password).await()
                }
                completeFirebaseLogin(result.user?.email, result.user?.displayName, result.user?.uid ?: "")
            } catch (e: Exception) {
                setLoading(false)
                showInlineError(e.localizedMessage ?: localized(if (isSignUpMode) R.string.sign_up_failed else R.string.login_failed))
            }
        }
    }

    private fun signInWithGoogleToken(idToken: String) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                val result = auth.signInWithCredential(credential).await()
                completeFirebaseLogin(result.user?.email, result.user?.displayName, result.user?.uid ?: "")
            } catch (e: Exception) {
                setLoading(false)
                showInlineError(e.localizedMessage ?: localized(R.string.login_failed))
            }
        }
    }

    private fun signInWithFacebookToken(token: String) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val credential = FacebookAuthProvider.getCredential(token)
                val result = auth.signInWithCredential(credential).await()
                completeFirebaseLogin(result.user?.email, result.user?.displayName, result.user?.uid ?: "")
            } catch (e: Exception) {
                setLoading(false)
                showInlineError(e.localizedMessage ?: localized(R.string.login_failed))
            }
        }
    }

    private suspend fun completeFirebaseLogin(email: String?, displayName: String?, firebaseUid: String) {
        val sessionUid = firebaseUid.ifBlank { auth.currentUser?.uid.orEmpty() }
        if (sessionUid.isBlank()) {
            setLoading(false)
            auth.signOut()
            showInlineError(localized(R.string.login_failed))
            return
        }

        try {
            val profile = customerRepo.getProfile(phone = null, email = email).getOrNull()
            if (profile == null && email.isNullOrBlank()) {
                setLoading(false)
                showProfileNotFoundDialog()
                auth.signOut()
                return
            }
            CustomerSessionManager.save(
                this,
                phone = profile?.phone,
                email = email ?: profile?.email,
                name = profile?.patientName ?: displayName,
                firebaseUid = sessionUid,
            )
            setLoading(false)
            openMain()
        } catch (e: Exception) {
            setLoading(false)
            // Allow login even if AKTIV lookup fails — user can still browse
            CustomerSessionManager.save(
                this,
                phone = null,
                email = email,
                name = displayName,
                firebaseUid = sessionUid,
            )
            openMain()
        }
    }

    private fun showProfileNotFoundDialog() {
        AlertDialog.Builder(this)
            .setTitle(localized(R.string.profile_not_found_title))
            .setMessage(localized(R.string.profile_not_found_message))
            .setPositiveButton(localized(R.string.contact_clinic)) { _, _ ->
                openExternalIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+919230755875")))
            }
            .setNeutralButton(localized(R.string.whatsapp_support)) { _, _ ->
                val msg = localized(R.string.whatsapp_booking_message)
                openExternalIntent(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/919230755876?text=${Uri.encode(msg)}")),
                )
            }
            .setNegativeButton(localized(R.string.cancel), null)
            .show()
    }

    private fun showLanguageScreen() {
        layoutLanguage.visibility = View.VISIBLE
        layoutLogin.visibility = View.GONE
        pendingLanguage = languageManager.getCurrentLanguage()
        refreshLanguageScreenTexts()
    }

    private fun showLoginScreen() {
        layoutLanguage.visibility = View.GONE
        layoutLogin.visibility = View.VISIBLE
        refreshLoginTexts()
    }

    private fun refreshLanguageScreenTexts() {
        findViewById<TextView>(R.id.tvChooseLanguageTitle).text = localized(R.string.choose_language_title)
        findViewById<TextView>(R.id.tvChooseLanguageSubtitle).text = localized(R.string.choose_language_subtitle)
        findViewById<MaterialButton>(R.id.btnContinueLanguage).text = localized(R.string.continue_btn)
    }

    private fun refreshLoginTexts() {
        findViewById<TextView>(R.id.tvLoginWelcome).text = localized(R.string.login_welcome)
        findViewById<TextView>(R.id.tvLoginSubtitle).text = localized(
            if (isSignUpMode) R.string.sign_up_subtitle else R.string.login_subtitle,
        )
        findViewById<TextView>(R.id.tvOrContinue).text = localized(R.string.or_continue_with)
        findViewById<MaterialButton>(R.id.btnGoogle).text = localized(R.string.login_with_google)
        findViewById<MaterialButton>(R.id.btnFacebook).text = localized(R.string.login_with_facebook)
        btnPhoneAuth.text = localized(R.string.continue_with_phone)
        btnEmailAuth.text = localized(if (isSignUpMode) R.string.sign_up_with_email else R.string.login_with_email)
        findViewById<TextView>(R.id.tvForgotPassword).text = localized(R.string.forgot_password)
        findViewById<TextView>(R.id.tvToggleAuthMode).text = localized(
            if (isSignUpMode) R.string.already_have_account else R.string.dont_have_account,
        )
        findViewById<TextView>(R.id.tvChangeLanguage).text =
            "${localized(R.string.action_settings)} · ${localized(R.string.settings_language_title)}"
        findViewById<TextView>(R.id.tvAdminLogin).text = localized(R.string.admin_login)
        findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layoutEmail).hint =
            localized(R.string.email)
        findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layoutPassword).hint =
            localized(R.string.password)
    }

    private fun setLoading(loading: Boolean) {
        progressLogin.visibility = if (loading) View.VISIBLE else View.GONE
        btnEmailAuth.isEnabled = !loading
        btnPhoneAuth.isEnabled = !loading
        findViewById<MaterialButton>(R.id.btnGoogle).isEnabled = !loading
        findViewById<MaterialButton>(R.id.btnFacebook).isEnabled = !loading
    }

    private fun showInlineError(message: String) {
        tvLoginError.text = message
        tvLoginError.visibility = View.VISIBLE
    }

    private fun isFacebookConfigured(): Boolean {
        val appId = getString(R.string.facebook_app_id)
        val clientToken = getString(R.string.facebook_client_token)
        return !appId.startsWith("REPLACE_WITH") && !clientToken.startsWith("REPLACE_WITH")
    }

    private fun openExternalIntent(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            showInlineError(localized(R.string.something_went_wrong))
        }
    }

    private fun hideInlineError() {
        tvLoginError.visibility = View.GONE
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        facebookCallbackManager.onActivityResult(requestCode, resultCode, data)
    }
}
