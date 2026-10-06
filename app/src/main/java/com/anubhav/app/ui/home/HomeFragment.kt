package com.anubhav.app.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.anubhav.app.R
import com.anubhav.app.data.repository.CatalogRepository
import com.anubhav.app.databinding.FragmentHomeBinding
import com.anubhav.app.ui.metrics.TestDetailFragment
import com.anubhav.app.utils.CustomerSessionManager
import com.anubhav.app.utils.localized
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    /**
     * The three cards under "Popular Tests". [catalogName] is the exact catalog
     * entry — the card's own label is a friendlier short form — and the price is
     * read from the catalog cache so it cannot drift from what the clinic charges.
     */
    private data class PopularTest(
        val cardId: Int,
        val priceId: Int,
        val catalogName: String,
    )

    private val popularTests = listOf(
        PopularTest(R.id.cardTest1, R.id.tvTest1Price, "CBC (Complete Blood Count)"),
        PopularTest(R.id.cardTest2, R.id.tvTest2Price, "LIPID PROFILE"),
        PopularTest(R.id.cardTest3, R.id.tvTest3Price, "T3 T4 TSH"),
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        setupButtonClickListeners()
        setupPopularTests()
        setupContactActions()
        applyLanguage()
        return binding.root
    }

    /**
     * Set every translated label by id.
     *
     * This used to walk the whole view tree once per string, matching on the
     * English text baked into the layout, which meant a label silently stopped
     * translating the moment its wording changed — and two of them already had.
     */
    private fun applyLanguage() {
        val ctx = requireContext()
        fun set(id: Int, res: Int) {
            binding.root.findViewById<TextView>(id)?.text = ctx.localized(res)
        }
        set(R.id.tvWelcomeTitle, R.string.welcome_title)
        set(R.id.tvWelcomeSubtitle, R.string.welcome_tagline)
        set(R.id.tvPopularTests, R.string.popular_tests)
        set(R.id.btnViewAll, R.string.view_all)
        set(R.id.tvWhyChooseUs, R.string.why_choose_us)
        set(R.id.tvFeature1, R.string.feature_home_collection)
        set(R.id.tvFeature2, R.string.feature_same_day)
        set(R.id.tvFeature3, R.string.feature_secure_payment)
        set(R.id.tvFeature4, R.string.feature_notifications)
        set(R.id.tvContactTitle, R.string.contact_us_title)
        set(R.id.tvCallLabel, R.string.call_label)
        set(R.id.tvWhatsappLabel, R.string.whatsapp_label)
        set(R.id.tvEmailLabel, R.string.email_label)

        binding.root.findViewById<MaterialButton>(R.id.btnBookTest)?.text =
            ctx.localized(R.string.book_test_title)
        binding.root.findViewById<MaterialButton>(R.id.btnViewReports)?.text =
            ctx.localized(R.string.view_reports_title)
        binding.root.findViewById<MaterialButton>(R.id.btnMyBookings)?.text =
            ctx.localized(R.string.my_bookings_title)
        binding.root.findViewById<MaterialButton>(R.id.btnHomeCollection)?.text =
            ctx.localized(R.string.home_collection_title)
    }

    private fun setupButtonClickListeners() {
        binding.root.findViewById<MaterialButton>(R.id.btnBookTest)?.setOnClickListener {
            findNavController().navigate(R.id.nav_book_test)
        }
        binding.root.findViewById<MaterialButton>(R.id.btnViewReports)?.setOnClickListener {
            findNavController().navigate(R.id.nav_my_reports)
        }
        binding.root.findViewById<MaterialButton>(R.id.btnMyBookings)?.setOnClickListener {
            findNavController().navigate(R.id.nav_my_bookings)
        }
        binding.root.findViewById<MaterialButton>(R.id.btnHomeCollection)?.setOnClickListener {
            val ctx = requireContext()
            val message = ctx.localized(
                R.string.home_collection_whatsapp_message,
                CustomerSessionManager.getName(ctx).orEmpty(),
                CustomerSessionManager.getPhone(ctx).orEmpty(),
            )
            launchOrToast(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/919230755876?text=${Uri.encode(message)}")))
        }
        // "View All" carried a ripple and a touch target but no listener, so it read
        // as a button and did nothing.
        binding.root.findViewById<View>(R.id.btnViewAll)?.setOnClickListener {
            findNavController().navigate(R.id.nav_metrics)
        }
    }

    /**
     * Wire the popular-test cards up and price them from the catalog cache. The
     * prices were previously literals in the layout, and CBC had drifted to ₹300
     * against a real price of ₹350.
     */
    private fun setupPopularTests() {
        popularTests.forEach { test ->
            binding.root.findViewById<MaterialCardView>(test.cardId)?.setOnClickListener {
                findNavController().navigate(
                    R.id.nav_test_detail,
                    bundleOf(TestDetailFragment.ARG_TEST_NAME to test.catalogName),
                )
            }
        }

        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            popularTests.forEach { test ->
                val priceView = binding.root.findViewById<TextView>(test.priceId) ?: return@forEach
                val item = CatalogRepository.findByName(ctx, test.catalogName)
                if (item == null) {
                    // Better to show no price than a wrong one.
                    priceView.visibility = View.GONE
                } else {
                    priceView.visibility = View.VISIBLE
                    priceView.text = formatPrice(item.price)
                }
            }
        }
    }

    private fun formatPrice(price: Double): String {
        val rupees = if (price % 1.0 == 0.0) price.toLong().toString() else String.format("%.2f", price)
        return "₹$rupees"
    }

    private fun setupContactActions() {
        binding.root.findViewById<View>(R.id.tvCallLabel)?.setOnClickListener {
            launchOrToast(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+919230755875")))
        }
        binding.root.findViewById<View>(R.id.tvWhatsappLabel)?.setOnClickListener {
            launchOrToast(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/919230755876")))
        }
        binding.root.findViewById<View>(R.id.tvEmailLabel)?.setOnClickListener {
            launchOrToast(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:contact.anubhavlife@gmail.com")),
            )
        }
    }

    private fun launchOrToast(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(requireContext(), localized(R.string.whatsapp_not_available), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) applyLanguage()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
