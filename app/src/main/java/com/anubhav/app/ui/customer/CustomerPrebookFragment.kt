package com.anubhav.app.ui.customer

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anubhav.app.MainActivity
import com.anubhav.app.R
import com.anubhav.app.data.model.AktivTest
import com.anubhav.app.data.model.CustomerPrebookRequest
import com.anubhav.app.data.model.PrebookCalendar
import com.anubhav.app.data.model.PrebookDateInfo
import com.anubhav.app.data.repository.AktivRepository
import com.anubhav.app.data.repository.CustomerRepository
import com.anubhav.app.ui.booking.AktivTestAdapter
import com.anubhav.app.ui.location.MapPickerFragment
import com.anubhav.app.utils.CustomerSessionManager
import com.anubhav.app.utils.LocationHelper
import com.anubhav.app.utils.PaymentManager
import com.anubhav.app.utils.localized
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.razorpay.PaymentResultListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CustomerPrebookFragment : Fragment(), PaymentResultListener {
    private companion object {
        const val SEX_MALE = "MALE"
        const val SEX_FEMALE = "FEMALE"

        const val STATE_SEX = "state_sex"
        const val STATE_DATE = "state_date"
        const val STATE_SLOT = "state_slot"
        const val STATE_LATITUDE = "state_latitude"
        const val STATE_LONGITUDE = "state_longitude"
        const val STATE_ADVANCE = "state_advance"
        const val STATE_TESTS = "state_tests"
        const val STATE_AWAITING_PERMISSION = "state_awaiting_permission"

        const val MAP_PICKER_TAG = "map_picker"
    }

    private val aktivRepo = AktivRepository()
    private val customerRepo = CustomerRepository()
    private val selectedTests = linkedMapOf<Int, AktivTest>()
    private var searchJob: Job? = null
    private var pendingAdvance = 0.0
    private var selectedDate: String? = null
    private var selectedSlot: String? = null
    /** Canonical AKTIV code — never the localized label shown in the dropdown. */
    private var selectedSex = SEX_MALE
    private lateinit var testAdapter: AktivTestAdapter
    private var testAdapterItems: List<AktivTest> = emptyList()
    private var currentLatitude: Double? = null
    private var currentLongitude: Double? = null
    /**
     * Whether a grant coming back should autofill the address. Saved in instance state
     * rather than held as a lambda: the system permission dialog can outlive this
     * fragment (rotation), and the grant would otherwise arrive with nothing to run.
     */
    private var awaitingLocationPermission = false

    // Registered as a field so the launcher exists before the fragment is STARTED.
    // Both fine and coarse are requested: on Android 12+ the user can grant only the
    // approximate one, which returns granted=false for ACCESS_FINE_LOCATION alone.
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val ctx = context ?: return@registerForActivityResult
        val wanted = awaitingLocationPermission
        awaitingLocationPermission = false
        if (grants.values.any { it }) {
            if (wanted) fetchLocationIntoAddress()
        } else {
            Toast.makeText(ctx, localized(R.string.location_permission_needed), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_customer_prebook, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? MainActivity)?.setPaymentListener(this)
        val etName = view.findViewById<TextInputEditText>(R.id.etPatientName)
        val etAge = view.findViewById<TextInputEditText>(R.id.etAgeYear)
        val spinnerSex = view.findViewById<AutoCompleteTextView>(R.id.spinnerSex)
        val spinnerDate = view.findViewById<AutoCompleteTextView>(R.id.spinnerDate)
        val spinnerSlot = view.findViewById<AutoCompleteTextView>(R.id.spinnerSlot)
        val btnUseLocation = view.findViewById<MaterialButton>(R.id.btnUseLocation)
        val btnPickOnMap = view.findViewById<MaterialButton>(R.id.btnPickOnMap)
        val etSearch = view.findViewById<TextInputEditText>(R.id.etTestSearch)
        val searchLayout = view.findViewById<TextInputLayout>(R.id.layoutTestSearch)
        val rvTests = view.findViewById<RecyclerView>(R.id.rvTests)
        val tvSelected = view.findViewById<TextView>(R.id.tvSelectedTests)
        val selectedChips = view.findViewById<ChipGroup>(R.id.chipSelectedTests)
        val tvSearchStatus = view.findViewById<TextView>(R.id.tvTestSearchStatus)
        val tvTotal = view.findViewById<TextView>(R.id.tvTotalAmount)
        val tvAdvance = view.findViewById<TextView>(R.id.tvAdvanceAmount)
        val tvPolicy = view.findViewById<TextView>(R.id.tvPrebookPolicy)
        val btnPay = view.findViewById<MaterialButton>(R.id.btnPayAdvance)
        val progress = view.findViewById<ProgressBar>(R.id.progressBar)

        view.findViewById<TextView>(R.id.tvPrebookTitle).text = localized(R.string.prebook_time_slot)
        searchLayout.hint = localized(R.string.booking_test_search_hint)
        tvPolicy.text = localized(R.string.prebook_policy, getString(R.string.reschedule_phone))
        btnPay.text = localized(R.string.pay_advance)
        restoreState(savedInstanceState)

        // The dropdown shows localized labels, but AKTIV only understands MALE/FEMALE —
        // sending the Bengali label silently registered every patient as MALE.
        val sexLabels = listOf(localized(R.string.sex_male), localized(R.string.sex_female))
        spinnerSex.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, sexLabels),
        )
        spinnerSex.setText(sexLabels[if (selectedSex == SEX_FEMALE) 1 else 0], false)
        spinnerSex.setOnItemClickListener { parent, _, position, _ ->
            val label = parent.getItemAtPosition(position) as? String
            selectedSex = if (label == sexLabels[1]) SEX_FEMALE else SEX_MALE
        }

        // These fields are read-only pickers (inputType="none"); make a single tap
        // reliably open the dropdown instead of just focusing the field.
        spinnerSex.setOnClickListener { spinnerSex.showDropDown() }
        spinnerDate.setOnClickListener { spinnerDate.showDropDown() }
        spinnerSlot.setOnClickListener { spinnerSlot.showDropDown() }

        btnUseLocation.text = localized(R.string.use_my_location)
        btnUseLocation.setOnClickListener { requestAddressFromLocation() }

        btnPickOnMap.text = localized(R.string.pick_on_map)
        btnPickOnMap.setOnClickListener { openMapPicker() }
        view.findViewById<TextView>(R.id.tvPinnedLocation).setOnClickListener { openMapPicker() }

        setFragmentResultListener(MapPickerFragment.REQUEST_KEY) { _, bundle ->
            val latitude = bundle.getDouble(MapPickerFragment.RESULT_LATITUDE)
            val longitude = bundle.getDouble(MapPickerFragment.RESULT_LONGITUDE)
            if (!LocationHelper.isValidCoordinate(latitude, longitude)) return@setFragmentResultListener
            currentLatitude = latitude
            currentLongitude = longitude
            bundle.getString(MapPickerFragment.RESULT_ADDRESS)
                ?.takeIf { it.isNotBlank() }
                ?.let { this.view?.findViewById<TextInputEditText>(R.id.etAddress)?.setText(it) }
            renderPinnedLocation()
        }
        renderPinnedLocation()

        testAdapter = AktivTestAdapter { test ->
            if (selectedTests.containsKey(test.testKey)) {
                selectedTests.remove(test.testKey)
            } else {
                selectedTests[test.testKey] = test
                etSearch.text?.clear()
            }
            testAdapter.submit(testAdapterItems, selectedTests.values.toList())
            updateTotals(tvSelected, selectedChips, tvTotal, tvAdvance)
        }
        rvTests.layoutManager = LinearLayoutManager(requireContext())
        rvTests.adapter = testAdapter
        updateTotals(tvSelected, selectedChips, tvTotal, tvAdvance)
        loadCalendar(progress, spinnerDate, spinnerSlot, tvPolicy)
        searchTests("", tvSearchStatus, rvTests)

        etSearch.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchTests(s?.toString().orEmpty(), tvSearchStatus, rvTests)
                }

                override fun afterTextChanged(s: Editable?) = Unit
            },
        )

        btnPay.setOnClickListener {
            val phone = CustomerSessionManager.getPhone(requireContext())
            if (phone.isNullOrBlank()) {
                Toast.makeText(requireContext(), localized(R.string.phone_required), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val name = etName.text?.toString()?.trim().orEmpty()
            val age = etAge.text?.toString()?.trim().orEmpty()
            if (name.isBlank() || age.isBlank() || selectedTests.isEmpty() || selectedDate == null || selectedSlot == null) {
                Toast.makeText(requireContext(), localized(R.string.fill_all_fields), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val total = selectedTests.values.sumOf { it.rate }
            pendingAdvance = total * 0.5
            PaymentManager(requireActivity(), this).startPayment(
                amount = pendingAdvance,
                name = name,
                email = CustomerSessionManager.getEmail(requireContext()).orEmpty(),
                phone = phone,
                description = localized(R.string.payment_description_prebook),
                orderNote = "PREBOOK $selectedDate $selectedSlot",
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Razorpay checkout (and a plain rotation) can recreate this fragment. Without
        // this the slot, sex and pinned coordinates silently reset to their defaults.
        outState.putString(STATE_SEX, selectedSex)
        outState.putString(STATE_DATE, selectedDate)
        outState.putString(STATE_SLOT, selectedSlot)
        outState.putDouble(STATE_ADVANCE, pendingAdvance)
        currentLatitude?.let { outState.putDouble(STATE_LATITUDE, it) }
        currentLongitude?.let { outState.putDouble(STATE_LONGITUDE, it) }
        outState.putString(STATE_TESTS, Gson().toJson(selectedTests.values.toList()))
        outState.putBoolean(STATE_AWAITING_PERMISSION, awaitingLocationPermission)
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        val state = savedInstanceState ?: return
        selectedSex = state.getString(STATE_SEX) ?: SEX_MALE
        selectedDate = state.getString(STATE_DATE)
        selectedSlot = state.getString(STATE_SLOT)
        pendingAdvance = state.getDouble(STATE_ADVANCE, 0.0)
        awaitingLocationPermission = state.getBoolean(STATE_AWAITING_PERMISSION, false)
        if (state.containsKey(STATE_LATITUDE) && state.containsKey(STATE_LONGITUDE)) {
            currentLatitude = state.getDouble(STATE_LATITUDE)
            currentLongitude = state.getDouble(STATE_LONGITUDE)
        }
        state.getString(STATE_TESTS)?.let { json ->
            runCatching {
                Gson().fromJson<List<AktivTest>>(
                    json,
                    object : TypeToken<List<AktivTest>>() {}.type,
                )
            }.getOrNull()?.forEach { selectedTests[it.testKey] = it }
        }
    }

    private fun requestAddressFromLocation() {
        if (LocationHelper.hasLocationPermission(requireContext())) {
            fetchLocationIntoAddress()
        } else {
            awaitingLocationPermission = true
            locationPermissionLauncher.launch(LocationHelper.LOCATION_PERMISSIONS)
        }
    }

    /** Opens the Leaflet/OpenStreetMap picker on the current pin, if there is one. */
    private fun openMapPicker() {
        if (parentFragmentManager.isStateSaved) return
        if (parentFragmentManager.findFragmentByTag(MAP_PICKER_TAG) != null) return
        MapPickerFragment.newInstance(
            latitude = currentLatitude,
            longitude = currentLongitude,
            address = view?.findViewById<TextInputEditText>(R.id.etAddress)?.text?.toString(),
        ).show(parentFragmentManager, MAP_PICKER_TAG)
    }

    private fun renderPinnedLocation() {
        val pinned = view?.findViewById<TextView>(R.id.tvPinnedLocation) ?: return
        val latitude = currentLatitude
        val longitude = currentLongitude
        if (LocationHelper.isValidCoordinate(latitude, longitude)) {
            pinned.text = localized(
                R.string.location_pinned_value,
                LocationHelper.formatCoordinates(latitude!!, longitude!!),
            )
            pinned.visibility = View.VISIBLE
        } else {
            pinned.visibility = View.GONE
        }
    }

    /** Shows a "locating…" state, then fills etAddress and keeps lat/lng for the booking. */
    private fun fetchLocationIntoAddress() {
        val root = view ?: return
        root.findViewById<MaterialButton>(R.id.btnUseLocation).apply {
            isEnabled = false
            text = localized(R.string.locating)
        }
        LocationHelper.fetchCurrentAddress(requireContext()) { outcome ->
            // Re-resolve the views: the fragment's view can be rebuilt while the fix is
            // in flight, which would leave the captured references pointing at dead views.
            val current = view ?: return@fetchCurrentAddress
            if (!isAdded) return@fetchCurrentAddress
            current.findViewById<MaterialButton>(R.id.btnUseLocation).apply {
                isEnabled = true
                text = localized(R.string.use_my_location)
            }
            when (outcome) {
                is LocationHelper.Outcome.Success -> {
                    currentLatitude = outcome.latitude
                    currentLongitude = outcome.longitude
                    current.findViewById<TextInputEditText>(R.id.etAddress).setText(outcome.address)
                    renderPinnedLocation()
                }
                is LocationHelper.Outcome.NoAddress -> {
                    // Keep the coordinates for the booking even without a readable address.
                    currentLatitude = outcome.latitude
                    currentLongitude = outcome.longitude
                    renderPinnedLocation()
                    Toast.makeText(
                        requireContext(),
                        localized(R.string.location_unavailable),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                is LocationHelper.Outcome.Failure -> {
                    val messageRes = when (outcome.error) {
                        LocationHelper.Error.NO_PERMISSION -> R.string.location_permission_needed
                        LocationHelper.Error.LOCATION_DISABLED -> R.string.location_turn_on_gps
                        LocationHelper.Error.LOCATION_UNAVAILABLE -> R.string.location_unavailable
                    }
                    Toast.makeText(requireContext(), localized(messageRes), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadCalendar(
        progress: ProgressBar,
        spinnerDate: AutoCompleteTextView,
        spinnerSlot: AutoCompleteTextView,
        tvPolicy: TextView,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            customerRepo.getPrebookCalendar().fold(
                onSuccess = { calendar ->
                    tvPolicy.text = localized(R.string.prebook_policy, calendar.reschedulePhone)
                    bindCalendar(calendar, spinnerDate, spinnerSlot)
                    progress.visibility = View.GONE
                },
                onFailure = {
                    progress.visibility = View.GONE
                    Toast.makeText(requireContext(), localized(R.string.network_error), Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    private fun bindCalendar(
        calendar: PrebookCalendar,
        spinnerDate: AutoCompleteTextView,
        spinnerSlot: AutoCompleteTextView,
    ) {
        val dateLabels = calendar.dates.map { it.date }
        spinnerDate.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, dateLabels),
        )
        spinnerDate.setOnItemClickListener { parent, _, position, _ ->
            // Resolve by the clicked label rather than the index: the adapter filters as
            // soon as text is set, after which `position` no longer indexes calendar.dates.
            val label = parent.getItemAtPosition(position) as? String
            val date = calendar.dates.firstOrNull { it.date == label } ?: return@setOnItemClickListener
            selectedSlot = null
            spinnerSlot.setText("", false)
            bindSlotsFor(date, spinnerSlot)
        }

        // A restored selection (rotation, or coming back from Razorpay) needs its slot
        // list rebuilt, otherwise the date shows but the slot dropdown is empty.
        calendar.dates.firstOrNull { it.date == selectedDate }?.let { date ->
            spinnerDate.setText(date.date, false)
            bindSlotsFor(date, spinnerSlot, keepSelection = true)
        }
    }

    private fun bindSlotsFor(
        date: PrebookDateInfo,
        spinnerSlot: AutoCompleteTextView,
        keepSelection: Boolean = false,
    ) {
        selectedDate = date.date
        val available = date.slots.filter { it.available && it.remaining > 0 }
        if (available.isEmpty()) {
            spinnerSlot.setAdapter(
                ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, emptyList<String>()),
            )
            selectedSlot = null
            spinnerSlot.setText("", false)
            Toast.makeText(requireContext(), localized(R.string.prebook_no_slots), Toast.LENGTH_LONG).show()
            return
        }
        val labels = available.map { localized(R.string.slots_remaining, it.label, it.remaining) }
        spinnerSlot.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels),
        )
        spinnerSlot.setOnItemClickListener { parent, _, position, _ ->
            val label = parent.getItemAtPosition(position) as? String
            val index = labels.indexOf(label)
            selectedSlot = available.getOrNull(index)?.timeSlot
        }
        if (keepSelection) {
            val restored = available.indexOfFirst { it.timeSlot == selectedSlot }
            if (restored >= 0) {
                spinnerSlot.setText(labels[restored], false)
            } else {
                selectedSlot = null
                spinnerSlot.setText("", false)
            }
        }
    }

    private fun searchTests(query: String, status: TextView, results: RecyclerView) {
        searchJob?.cancel()
        val text = query.trim()
        testAdapterItems = emptyList()
        testAdapter.submit(emptyList(), selectedTests.values.toList())
        results.visibility = View.GONE
        if (text.length < 2) {
            status.text = localized(R.string.booking_test_search_prompt)
            status.visibility = View.VISIBLE
            return
        }
        status.text = localized(R.string.booking_test_searching)
        status.visibility = View.VISIBLE
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(220)
            val found = aktivRepo.searchTestsCached(requireContext(), text)
            if (!isActive) return@launch
            found.fold(
                onSuccess = { tests ->
                    testAdapterItems = tests
                    testAdapter.submit(tests, selectedTests.values.toList())
                    results.visibility = if (tests.isEmpty()) View.GONE else View.VISIBLE
                    status.text = localized(R.string.booking_test_search_empty)
                    status.visibility = if (tests.isEmpty()) View.VISIBLE else View.GONE
                },
                onFailure = {
                    status.text = localized(R.string.network_error)
                    status.visibility = View.VISIBLE
                },
            )
        }
    }

    private fun updateTotals(
        tvSelected: TextView,
        selectedChips: ChipGroup,
        tvTotal: TextView,
        tvAdvance: TextView,
    ) {
        val total = selectedTests.values.sumOf { it.rate }
        val hasSelection = selectedTests.isNotEmpty()
        tvSelected.visibility = if (hasSelection) View.VISIBLE else View.GONE
        selectedChips.visibility = if (hasSelection) View.VISIBLE else View.GONE
        tvSelected.text = localized(R.string.booking_selected_count, selectedTests.size)
        selectedChips.removeAllViews()
        selectedTests.values.forEach { test ->
            selectedChips.addView(Chip(requireContext()).apply {
                text = test.testName
                isCheckable = false
                isCloseIconVisible = true
                closeIconContentDescription = localized(R.string.booking_remove_test, test.testName)
                setOnCloseIconClickListener {
                    selectedTests.remove(test.testKey)
                    testAdapter.submit(testAdapterItems, selectedTests.values.toList())
                    updateTotals(tvSelected, selectedChips, tvTotal, tvAdvance)
                }
            })
        }
        tvTotal.text = localized(R.string.total_amount_value, total)
        tvAdvance.text = localized(R.string.advance_amount_value, total * 0.5)
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?) {
        val paymentId = razorpayPaymentId?.takeIf { it.isNotBlank() }
        if (paymentId == null) {
            Toast.makeText(requireContext(), localized(R.string.payment_failed), Toast.LENGTH_LONG).show()
            return
        }
        val root = view ?: return
        val phone = CustomerSessionManager.getPhone(requireContext()) ?: return
        // If the fragment/activity was recreated during Razorpay checkout, the transient
        // selection can be lost. Never submit a corrupt ₹0 booking with empty tests/slot —
        // surface the payment id so the paid user can reconcile with support.
        if (selectedTests.isEmpty() || selectedDate.isNullOrBlank() || selectedSlot.isNullOrBlank() || pendingAdvance <= 0.0) {
            Toast.makeText(
                requireContext(),
                localized(R.string.payment_recorded_contact_support, paymentId),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val etName = root.findViewById<TextInputEditText>(R.id.etPatientName)
        val etAge = root.findViewById<TextInputEditText>(R.id.etAgeYear)
        val etAddress = root.findViewById<TextInputEditText>(R.id.etAddress)
        val progress = root.findViewById<ProgressBar>(R.id.progressBar)
        val hasPin = LocationHelper.isValidCoordinate(currentLatitude, currentLongitude)
        viewLifecycleOwner.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            val request = CustomerPrebookRequest(
                patientName = etName.text?.toString()?.trim().orEmpty(),
                phone = phone,
                sex = selectedSex,
                ageYear = etAge.text?.toString()?.toIntOrNull(),
                testKeys = selectedTests.keys.toList(),
                slotDate = selectedDate.orEmpty(),
                timeSlot = selectedSlot.orEmpty(),
                paymentId = paymentId,
                amountPaid = pendingAdvance,
                email = CustomerSessionManager.getEmail(requireContext()),
                address = etAddress.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                // Never post a half-pair or a NaN — the server writes these straight into
                // the collector's remark line.
                latitude = currentLatitude.takeIf { hasPin },
                longitude = currentLongitude.takeIf { hasPin },
            )
            customerRepo.createPrebook(request).fold(
                onSuccess = { response ->
                    progress.visibility = View.GONE
                    Toast.makeText(
                        requireContext(),
                        localized(R.string.prebook_success, response.alcCode, response.reschedulePhone),
                        Toast.LENGTH_LONG,
                    ).show()
                    selectedTests.clear()
                    // Reflect the cleared selection in the UI (totals + list) instead of
                    // leaving the old amounts on screen after a successful booking.
                    testAdapter.submit(testAdapterItems, emptyList())
                    updateTotals(
                        root.findViewById(R.id.tvSelectedTests),
                        root.findViewById(R.id.chipSelectedTests),
                        root.findViewById(R.id.tvTotalAmount),
                        root.findViewById(R.id.tvAdvanceAmount),
                    )
                },
                onFailure = { error ->
                    progress.visibility = View.GONE
                    Toast.makeText(
                        requireContext(),
                        error.message ?: localized(R.string.something_went_wrong),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    override fun onPaymentError(code: Int, description: String?) {
        Toast.makeText(
            requireContext(),
            description ?: localized(R.string.payment_failed),
            Toast.LENGTH_LONG,
        ).show()
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.setPaymentListener(null)
        searchJob?.cancel()
        super.onDestroyView()
    }
}
