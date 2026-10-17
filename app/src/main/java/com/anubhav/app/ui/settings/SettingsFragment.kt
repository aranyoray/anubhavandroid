package com.anubhav.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.anubhav.app.BuildConfig
import com.anubhav.app.R
import com.anubhav.app.ui.login.LoginActivity
import com.anubhav.app.utils.ReportCache
import com.google.firebase.auth.FirebaseAuth
import com.anubhav.app.data.repository.AktivRepository
import com.anubhav.app.data.repository.CatalogRepository
import com.anubhav.app.data.repository.CustomerRepository
import com.anubhav.app.utils.CustomerSessionManager
import com.anubhav.app.utils.LanguageManager
import com.anubhav.app.utils.SessionManager
import com.anubhav.app.utils.localized
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {
    private lateinit var languageManager: LanguageManager
    private val aktivRepository = AktivRepository()
    private val customerRepository = CustomerRepository()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        languageManager = LanguageManager(requireContext())
        applyLocalizedLabels(view)
        bindAccountInfo(view)
        setupLanguageRadio(view)
        setupSupportButton(view)
        setupClinicPhoneLink(view)
        setupCollectorKey(view)
        setupDataSync(view)
        setupPrivacyPolicy(view)
        setupDeleteAccount(view)
        showAppVersion(view)
    }

    private fun applyLocalizedLabels(view: View) {
        val ctx = requireContext()
        view.findViewById<TextView>(R.id.tvSettingsLanguageTitle).text =
            ctx.localized(R.string.settings_language_title)
        view.findViewById<TextView>(R.id.tvSettingsLanguageHint).text =
            ctx.localized(R.string.settings_language_hint)
        view.findViewById<TextView>(R.id.tvSettingsAccountTitle).text =
            ctx.localized(R.string.settings_account_title)
        view.findViewById<MaterialButton>(R.id.btnContactSupport).text =
            ctx.localized(R.string.whatsapp_support)
        view.findViewById<MaterialButton>(R.id.btnSaveClinicPhone).text =
            ctx.localized(R.string.save_clinic_phone)
        view.findViewById<MaterialButton>(R.id.btnSaveCollectorKey).text =
            ctx.localized(R.string.save_collector_key)
        view.findViewById<MaterialButton>(R.id.btnSyncAppData).text =
            ctx.localized(R.string.sync_app_data)
        view.findViewById<RadioButton>(R.id.radioEnglish).text =
            ctx.localized(R.string.language_english_full)
        view.findViewById<RadioButton>(R.id.radioBengali).text =
            ctx.localized(R.string.language_bengali_full)
    }

    private fun bindAccountInfo(view: View) {
        val ctx = requireContext()
        val name = CustomerSessionManager.getName(ctx)
        val email = CustomerSessionManager.getEmail(ctx)
        val phone = CustomerSessionManager.getPhone(ctx)
        val warning = view.findViewById<TextView>(R.id.tvPhoneWarning)

        view.findViewById<TextView>(R.id.tvAccountName).text = name ?: ctx.localized(R.string.name)
        view.findViewById<TextView>(R.id.tvAccountEmail).text = email ?: "-"
        view.findViewById<TextView>(R.id.tvAccountPhone).text =
            if (phone.isNullOrBlank()) ctx.localized(R.string.phone_required_title) else phone
        view.findViewById<TextInputEditText>(R.id.etClinicPhone).setText(phone.orEmpty())
        view.findViewById<TextInputEditText>(R.id.etCollectorKey)
            .setText(CustomerSessionManager.getCollectorKey(ctx)?.toString().orEmpty())

        if (phone.isNullOrBlank()) {
            warning.visibility = View.VISIBLE
            warning.text = ctx.localized(R.string.complete_profile_banner)
        } else {
            warning.visibility = View.GONE
        }
    }

    private fun setupClinicPhoneLink(view: View) {
        val input = view.findViewById<TextInputEditText>(R.id.etClinicPhone)
        val button = view.findViewById<MaterialButton>(R.id.btnSaveClinicPhone)
        button.setOnClickListener {
            val phone = input.text?.toString()?.filter { it.isDigit() }.orEmpty().takeLast(10)
            if (phone.length != 10) {
                Toast.makeText(requireContext(), localized(R.string.phone_required), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            button.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                customerRepository.getProfile(phone = phone, email = null).fold(
                    onSuccess = { profile ->
                        if (!profile.found) {
                            button.isEnabled = true
                            Toast.makeText(
                                requireContext(),
                                localized(R.string.profile_not_found_message),
                                Toast.LENGTH_LONG,
                            ).show()
                            return@fold
                        }
                        CustomerSessionManager.save(
                            context = requireContext(),
                            phone = phone,
                            email = CustomerSessionManager.getEmail(requireContext()) ?: profile.email,
                            name = profile.patientName ?: CustomerSessionManager.getName(requireContext()),
                            firebaseUid = CustomerSessionManager.getFirebaseUid(requireContext()).orEmpty(),
                            role = CustomerSessionManager.getRole(requireContext()),
                        )
                        button.isEnabled = true
                        bindAccountInfo(view)
                        Toast.makeText(requireContext(), localized(R.string.phone_linked), Toast.LENGTH_SHORT).show()
                    },
                    onFailure = {
                        button.isEnabled = true
                        Toast.makeText(
                            requireContext(),
                            it.message ?: localized(R.string.network_error),
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            }
        }
    }

    private fun setupCollectorKey(view: View) {
        val input = view.findViewById<TextInputEditText>(R.id.etCollectorKey)
        val button = view.findViewById<MaterialButton>(R.id.btnSaveCollectorKey)
        button.setOnClickListener {
            val key = input.text?.toString()?.trim()?.toIntOrNull()
            if (key == null || key <= 0) {
                Toast.makeText(requireContext(), localized(R.string.collector_key_required), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            CustomerSessionManager.saveCollectorKey(requireContext(), key)
            Toast.makeText(requireContext(), localized(R.string.collector_key_saved), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupDataSync(view: View) {
        val button = view.findViewById<MaterialButton>(R.id.btnSyncAppData)
        button.setOnClickListener {
            val phone = CustomerSessionManager.getPhone(requireContext()).orEmpty()
            if (phone.isBlank()) {
                Toast.makeText(requireContext(), localized(R.string.phone_required), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            button.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val context = requireContext()
                val results = mutableListOf<Result<*>>()
                try {
                    results += customerRepository.getBillsCached(context, phone, forceRefresh = true)
                    results += customerRepository.getReportsCached(context, phone, forceRefresh = true)
                    results += customerRepository.getPendingPaymentsCached(context, phone, forceRefresh = true)
                    val collectorKey = SessionManager.getCollectorKey(context)
                        ?: CustomerSessionManager.getCollectorKey(context)
                    collectorKey?.takeIf { it > 0 }?.let {
                        results += customerRepository.syncQueuedCollectorPatients(context, it)
                        results += customerRepository.getCollectorPatientsCached(context, it, forceRefresh = true)
                        results += customerRepository.getCollectorReportsCached(context, it, forceRefresh = true)
                    }
                    results += aktivRepository.refreshTests(context)
                    // The test catalog is cached on the phone now, so a manual sync has
                    // to refresh it too or a price change never reaches this handset.
                    CatalogRepository.sync(context)
                    val message = if (results.none { it.isFailure }) {
                        localized(R.string.sync_app_data_done)
                    } else {
                        localized(R.string.network_error)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                } finally {
                    button.isEnabled = true
                }
            }
        }
    }

    private fun setupLanguageRadio(view: View) {
        val group = view.findViewById<RadioGroup>(R.id.radioLanguage)
        val selectedId = if (languageManager.isBengali()) R.id.radioBengali else R.id.radioEnglish
        group.check(selectedId)
        group.setOnCheckedChangeListener { _, checkedId ->
            val newLang = if (checkedId == R.id.radioBengali) {
                LanguageManager.LANGUAGE_BENGALI
            } else {
                LanguageManager.LANGUAGE_ENGLISH
            }
            if (languageManager.getCurrentLanguage() != newLang) {
                languageManager.setLanguage(newLang)
                Toast.makeText(requireContext(), localized(R.string.settings_saved), Toast.LENGTH_SHORT).show()
                requireActivity().recreate()
            }
        }
    }

    private fun setupSupportButton(view: View) {
        view.findViewById<MaterialButton>(R.id.btnContactSupport).setOnClickListener {
            val msg = requireContext().localized(R.string.whatsapp_booking_message)
            runCatching {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://wa.me/919230755876?text=${Uri.encode(msg)}"),
                    ),
                )
            }.onFailure {
                Toast.makeText(requireContext(), localized(R.string.whatsapp_not_available), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Play requires the policy to be reachable from inside the app, not just the listing. */
    private fun setupPrivacyPolicy(view: View) {
        view.findViewById<MaterialButton>(R.id.btnPrivacyPolicy).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_policy_url))))
            }.onFailure {
                Toast.makeText(requireContext(), localized(R.string.something_went_wrong), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Self-serve account deletion, which Play requires of any app that lets people sign in.
     *
     * What goes is everything the APP holds: the Firebase user (when the account came from
     * Google/email rather than a bill lookup), the session, the patient tokens and the cached
     * reports on this phone. What stays is the clinic's own record of their bills and results,
     * which a diagnostic lab is legally required to retain — the dialog says so rather than
     * implying a delete reaches further than it does.
     */
    private fun setupDeleteAccount(view: View) {
        view.findViewById<MaterialButton>(R.id.btnDeleteAccount).setOnClickListener {
            val ctx = requireContext()
            AlertDialog.Builder(ctx)
                .setTitle(ctx.localized(R.string.delete_account_title))
                .setMessage(ctx.localized(R.string.delete_account_message, getString(R.string.reschedule_phone)))
                .setNegativeButton(ctx.localized(R.string.cancel), null)
                .setPositiveButton(ctx.localized(R.string.delete_account_confirm)) { _, _ -> deleteAccount() }
                .show()
        }
    }

    private fun deleteAccount() {
        val ctx = requireContext()
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            finishDeletion() // a bill-lookup session: nothing exists server-side to delete
            return
        }
        user.delete().addOnCompleteListener { task ->
            if (!isAdded) return@addOnCompleteListener
            if (task.isSuccessful) {
                finishDeletion()
            } else {
                // Firebase refuses to delete a user whose sign-in has gone stale.
                Toast.makeText(ctx, localized(R.string.delete_account_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun finishDeletion() {
        val ctx = requireContext()
        CustomerSessionManager.clear(ctx)
        SessionManager.clear(ctx)
        ReportCache.clearAll(ctx)
        FirebaseAuth.getInstance().signOut()
        Toast.makeText(ctx, localized(R.string.delete_account_done), Toast.LENGTH_LONG).show()
        startActivity(
            Intent(ctx, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        requireActivity().finish()
    }

    private fun showAppVersion(view: View) {
        view.findViewById<TextView>(R.id.tvAppVersion).text =
            requireContext().localized(R.string.app_version, BuildConfig.VERSION_NAME)
    }
}
