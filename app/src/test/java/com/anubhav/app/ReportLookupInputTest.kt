package com.anubhav.app

import com.anubhav.app.ui.customer.ReportLookupInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The report lookup needs all three: the patient name, the bill date and the bill's last 3-4 digits. */
class ReportLookupInputTest {
    private fun problem(name: String = "Rina Das", billDate: String = "2026-09-24", bill: String = "4171") =
        ReportLookupInput(name, billDate, bill).problem()

    @Test fun allThreeFilledIsAccepted() {
        assertNull(problem())
        assertNull(problem(bill = "171"))
    }

    @Test fun eachFieldIsRequired() {
        assertEquals(R.string.verify_name_required, problem(name = " "))
        assertEquals(R.string.verify_date_required, problem(billDate = ""))
        assertEquals(R.string.verify_bill_digits_required, problem(bill = ""))
    }

    @Test fun billNeedsThreeOrFourDigits() {
        assertEquals(R.string.verify_bill_digits_required, problem(bill = "71"))
        assertEquals(R.string.verify_bill_digits_required, problem(bill = "41711"))
        assertEquals(R.string.verify_bill_digits_required, problem(bill = "41a"))
    }
}
