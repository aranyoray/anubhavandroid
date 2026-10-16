package com.anubhav.app

import com.anubhav.app.ui.customer.ReportLookupInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The report lookup needs all three: name, the full mobile, and the bill's last 3-4 digits. */
class ReportLookupInputTest {
    private fun problem(name: String = "Rina Das", phone: String = "9830012345", bill: String = "4171") =
        ReportLookupInput(name, phone, bill).problem()

    @Test fun allThreeFilledIsAccepted() {
        assertNull(problem())
        assertNull(problem(bill = "171"))
    }

    @Test fun eachFieldIsRequired() {
        assertEquals(R.string.verify_name_required, problem(name = " "))
        assertEquals(R.string.verify_phone_required, problem(phone = ""))
        assertEquals(R.string.verify_bill_digits_required, problem(bill = ""))
    }

    @Test fun phoneMustBeTheFullTenDigits() {
        assertEquals(R.string.verify_phone_required, problem(phone = "983001234"))
        assertNull(problem(phone = "+91 98300 12345"))
        assertEquals("9830012345", ReportLookupInput("x", "+91 98300-12345", "171").phone10)
    }

    @Test fun billNeedsThreeOrFourDigits() {
        assertEquals(R.string.verify_bill_digits_required, problem(bill = "71"))
        assertEquals(R.string.verify_bill_digits_required, problem(bill = "41711"))
        assertEquals(R.string.verify_bill_digits_required, problem(bill = "41a"))
    }
}
