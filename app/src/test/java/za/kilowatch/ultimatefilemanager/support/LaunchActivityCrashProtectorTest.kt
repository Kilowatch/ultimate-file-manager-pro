package za.kilowatch.ultimatefilemanager.support

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LaunchActivityCrashProtectorTest {

    @Test
    fun isLaunchActivityProfilerInfoCrash_identifiesExactFrameworkException() {
        val npe = NullPointerException("Attempt to read from field 'android.app.ProfilerInfo android.app.ActivityThread\$ActivityClientRecord.profilerInfo' on a null object reference")
        npe.stackTrace = arrayOf(
            StackTraceElement("android.app.ActivityThread", "handleLaunchActivity", "ActivityThread.java", 3764),
            StackTraceElement("android.app.servertransaction.LaunchActivityItem", "execute", "LaunchActivityItem.java", 103),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "executeCallbacks", "TransactionExecutor.java", 135),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "execute", "TransactionExecutor.java", 95),
            StackTraceElement("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 2210)
        )

        assertTrue(CrashReportManager.isLaunchActivityProfilerInfoCrash(npe))
    }

    @Test
    fun isLaunchActivityProfilerInfoCrash_rejectsUnrelatedNpe() {
        val npe = NullPointerException("Attempt to invoke virtual method 'int java.lang.String.length()' on a null object reference")
        npe.stackTrace = arrayOf(
            StackTraceElement("za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity", "onCreate", "StorageBrowserActivity.kt", 120),
            StackTraceElement("android.app.Activity", "performCreate", "Activity.java", 8000)
        )

        assertFalse(CrashReportManager.isLaunchActivityProfilerInfoCrash(npe))
    }

    @Test
    fun isLaunchActivityProfilerInfoCrash_rejectsOtherExceptions() {
        val ex = IllegalStateException("Attempt to read from field 'android.app.ProfilerInfo android.app.ActivityThread\$ActivityClientRecord.profilerInfo' on a null object reference")
        assertFalse(CrashReportManager.isLaunchActivityProfilerInfoCrash(ex))
    }

    @Test
    fun install_runsSafelyInRobolectricEnvironment() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        CrashReportManager.install(app)
        // Passes without throwing any reflection or class loader exceptions
    }
}
