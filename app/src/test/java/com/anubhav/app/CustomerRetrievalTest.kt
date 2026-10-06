package com.anubhav.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.anubhav.app.data.model.CustomerHistoryResponse
import com.anubhav.app.data.model.CustomerVerifyResponse
import com.anubhav.app.data.model.CustomerVisit
import com.anubhav.app.data.remote.CustomerApi
import com.anubhav.app.data.repository.CustomerRepository
import com.anubhav.app.utils.CustomerSessionManager
import com.anubhav.app.utils.PatientTokens
import com.anubhav.app.utils.ReportCache
import com.anubhav.app.utils.ReportFetcher
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomerRetrievalTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val phone = "9830012345"
    private val visit = CustomerVisit(1, patientName = "Test Patient", ready = false)

    @Before fun setup() {
        PatientTokens.init(context)
        PatientTokens.save(phone, "test-token")
        ReportCache.writeVisits(context, "history_$phone", listOf(visit))
    }

    @After fun cleanup() {
        PatientTokens.clear()
        File(context.filesDir, "report_cache").deleteRecursively()
        File(context.filesDir, "saved_reports").deleteRecursively()
    }

    private fun api(block: (String) -> Any): CustomerApi = Proxy.newProxyInstance(
        CustomerApi::class.java.classLoader, arrayOf(CustomerApi::class.java),
    ) { _, method, args ->
        try {
            block(method.name)
        } catch (error: Exception) {
            // Suspend interfaces have no Java throws clause. Resume the continuation
            // so IOException is delivered like Retrofit, without a proxy wrapper.
            @Suppress("UNCHECKED_CAST")
            val continuation = args.last() as Continuation<Any>
            continuation.resumeWith(Result.failure(error))
            COROUTINE_SUSPENDED
        }
    } as CustomerApi

    @Test fun rejectedLoginIsNotHiddenByOfflineHistory() = runTest {
        for (code in listOf(401, 403, 400)) {
            val repository = CustomerRepository(api { throw HttpException(Response.error<Any>(code, "{}".toResponseBody())) })
            val result = repository.getHistoryCached(context, phone, forceRefresh = true)
            assertTrue("HTTP $code must not return cached success", result.isFailure)
            assertEquals(code, (result.exceptionOrNull() as HttpException).code())
        }
    }

    @Test fun outageStillReturnsSavedHistory() = runTest {
        val repository = CustomerRepository(api { throw IOException("offline") })
        val result = repository.getHistoryCached(context, phone, forceRefresh = true).getOrThrow()
        assertEquals(listOf(visit), result.visits)
    }

    @Test fun cancellationNeverBecomesCachedSuccess() = runTest {
        val repository = CustomerRepository(api { throw CancellationException("screen closed") })
        try {
            repository.getHistoryCached(context, phone, forceRefresh = true)
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun sessionWithoutTokenCannotReadSavedPatientData() = runTest {
        PatientTokens.clear()
        val repository = CustomerRepository(api { error("network must not be called") })
        val result = repository.getHistoryCached(context, phone)
        assertTrue(CustomerRepository.needsVerification(result.exceptionOrNull()!!))
    }

    @Test fun verificationWithoutServerTokenDoesNotLogIn() = runTest {
        val repository = CustomerRepository(api { CustomerVerifyResponse(matched = true, phone = phone) })
        assertTrue(repository.verify("Test Patient", phone).isFailure)
    }

    @Test fun refreshingFindsNewlyAuthorisedReportsDespiteFreshCache() = runTest {
        val updated = visit.copy(ready = true, viewLink = "https://example.test/report")
        val repository = CustomerRepository(api { CustomerHistoryResponse(phone, 1, listOf(updated)) })
        val result = repository.getHistoryCached(context, phone, forceRefresh = true).getOrThrow()
        assertEquals(listOf(updated), result.visits)
        assertEquals(listOf(updated), ReportCache.readVisits(context, "history_$phone"))
    }

    @Test fun pdfCacheChangesWhenAnotherReportIsAuthorised() {
        val old = ReportFetcher.cachedFile(context, 1, phone, "one-report")
        old.writeText("%PDF-1.4\nfixture")
        assertTrue(ReportFetcher.isCached(context, 1, phone, "one-report"))
        assertFalse(ReportFetcher.isCached(context, 1, phone, "two-reports"))
        assertFalse(ReportFetcher.isCached(context, 1, "9830012346", "one-report"))
    }

    @Test fun htmlSavedByAnOlderBuildIsNotTreatedAsPdf() {
        ReportFetcher.cachedFile(context, 1, phone, "link").writeText("<html>Login required</html>")
        assertFalse(ReportFetcher.isCached(context, 1, phone, "link"))
    }
}
