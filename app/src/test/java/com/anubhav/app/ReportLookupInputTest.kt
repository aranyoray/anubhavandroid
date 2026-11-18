package com.anubhav.app

import com.anubhav.app.ui.customer.ReportLookupInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The report lookup needs all three: the patient name, the 10-digit mobile number and the
 * bill's last 3-4 digits. The app sends no bill date, so the number is what narrows the
 * search — the server rejects a lookup without it.
 */
class ReportLookupInputTest {
    private fun problem(name: String = "Rina Das", phone: String = "9830012345", bill: String = "4171") =
        ReportLookupInput(name, phone, bill).problem()

    @Test fun allThreeFilledIsAccepted() {
        assertNull(problem())
        assertNull(problem(bill = "171"))
        assertNull(problem(phone = "98300 12345"))
    }

    @Test fun eachFieldIsRequired() {
        assertEquals(R.string.verify_name_required, problem(name = " "))
        assertEquals(R.string.verify_phone_required, problem(phone = ""))
        assertEquals(R.string.verify_bill_digits_required, problem(bill = ""))
    }

    @Test fun phoneNeedsTenDigits() {
        assertEquals(R.string.verify_phone_required, problem(phone = "983001234"))
        assertEquals(R.string.verify_phone_required, problem(phone = "98300123456"))
    }

    @Test fun billNeedsThreeOrFourDigits() {
        assertEquals(R.string.verify_bill_digits_required, problem(bill = "71"))
        assertEquals(R.string.verify_bill_digits_required, problem(bill = "41711"))
        assertEquals(R.string.verify_bill_digits_required, problem(bill = "41a"))
    }
}
