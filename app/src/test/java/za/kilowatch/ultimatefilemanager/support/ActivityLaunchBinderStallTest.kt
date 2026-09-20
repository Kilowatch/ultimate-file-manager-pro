package za.kilowatch.ultimatefilemanager.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityLaunchBinderStallTest {

    @Test
    fun identifiesSdk28ActivityManagerStartActivityStall() {
        val topFrame = StackTraceElement("android.os.BinderProxy", "transactNative", "Native Method", -2)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.os.BinderProxy", "transact", "Binder.java", 1127),
            StackTraceElement("android.app.IActivityManager\$Stub\$Proxy", "startActivity", "IActivityManager.java", 3754),
            StackTraceElement("android.app.Instrumentation", "execStartActivity", "Instrumentation.java", 1676),
            StackTraceElement("android.app.Activity", "startActivityForResult", "Activity.java", 4586),
            StackTraceElement("android.app.Activity", "startActivity", "Activity.java", 4905),
            StackTraceElement("b0", "onClick", "r8", 1054),
            StackTraceElement("android.view.View", "performClick", "View.java", 6597),
            StackTraceElement("com.google.android.material.button.MaterialButton", "performClick", "r8", 20)
        )

        assertTrue(CrashReportManager.isActivityLaunchBinderStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesSdk29PlusActivityTaskManagerStartActivityStall() {
        val topFrame = StackTraceElement("android.os.BinderProxy", "transactNative", "Native Method", -2)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.os.BinderProxy", "transact", "Binder.java", 1127),
            StackTraceElement("android.app.IActivityTaskManager\$Stub\$Proxy", "startActivity", "IActivityTaskManager.java", 2341),
            StackTraceElement("android.app.Instrumentation", "execStartActivity", "Instrumentation.java", 1700),
            StackTraceElement("android.app.Activity", "startActivityForResult", "Activity.java", 4600)
        )

        assertTrue(CrashReportManager.isActivityLaunchBinderStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesLegacyActivityManagerProxyStartActivityStall() {
        val topFrame = StackTraceElement("android.os.BinderProxy", "transact", "Binder.java", 600)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.app.ActivityManagerProxy", "startActivity", "ActivityManagerNative.java", 1500),
            StackTraceElement("android.app.Instrumentation", "execStartActivity", "Instrumentation.java", 1500)
        )

        assertTrue(CrashReportManager.isActivityLaunchBinderStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsNonBinderStallInAppBusinessLogic() {
        val topFrame = StackTraceElement("za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity", "onClick", "StorageBrowserActivity.kt", 120)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.view.View", "performClick", "View.java", 6597)
        )

        assertFalse(CrashReportManager.isActivityLaunchBinderStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsUnrelatedBinderStall() {
        val topFrame = StackTraceElement("android.os.BinderProxy", "transactNative", "Native Method", -2)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.media.IAudioService\$Stub\$Proxy", "getStreamVolume", "IAudioService.java", 500)
        )

        assertFalse(CrashReportManager.isActivityLaunchBinderStall(topFrame, stackTrace))
    }
}
