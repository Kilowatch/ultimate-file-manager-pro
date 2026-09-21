package za.kilowatch.ultimatefilemanager.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityTaskDescriptionBinderStallTest {

    @Test
    fun identifiesSdk31ActivityClientControllerSetTaskDescriptionStall() {
        val topFrame = StackTraceElement("android.os.BinderProxy", "transactNative", "Native Method", -2)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.os.BinderProxy", "transact", "BinderProxy.java", 571),
            StackTraceElement("android.app.IActivityClientController\$Stub\$Proxy", "setTaskDescription", "IActivityClientController.java", 2038),
            StackTraceElement("android.app.ActivityClient", "setTaskDescription", "ActivityClient.java", 346),
            StackTraceElement("android.app.Activity", "setTaskDescription", "Activity.java", 6971),
            StackTraceElement("android.app.Activity", "onApplyThemeResource", "Activity.java", 5220),
            StackTraceElement("android.view.ContextThemeWrapper", "initializeTheme", "ContextThemeWrapper.java", 216),
            StackTraceElement("android.view.ContextThemeWrapper", "setTheme", "ContextThemeWrapper.java", 147),
            StackTraceElement("android.app.Activity", "setTheme", "Activity.java", 5153),
            StackTraceElement("dv", "setTheme", "r8", 1),
            StackTraceElement("android.app.ActivityThread", "performLaunchActivity", "ActivityThread.java", 3596),
            StackTraceElement("android.app.ActivityThread", "handleLaunchActivity", "ActivityThread.java", 3792),
            StackTraceElement("android.app.servertransaction.LaunchActivityItem", "execute", "LaunchActivityItem.java", 103),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "executeCallbacks", "TransactionExecutor.java", 135),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "execute", "TransactionExecutor.java", 95),
            StackTraceElement("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 2210)
        )

        assertTrue(CrashReportManager.isActivityTaskDescriptionBinderStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesSdk29ActivityTaskManagerSetTaskDescriptionStall() {
        val topFrame = StackTraceElement("android.os.BinderProxy", "transactNative", "Native Method", -2)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.os.BinderProxy", "transact", "Binder.java", 1127),
            StackTraceElement("android.app.IActivityTaskManager\$Stub\$Proxy", "setTaskDescription", "IActivityTaskManager.java", 2341),
            StackTraceElement("android.app.Activity", "setTaskDescription", "Activity.java", 6500),
            StackTraceElement("android.app.Activity", "onApplyThemeResource", "Activity.java", 5100),
            StackTraceElement("android.view.ContextThemeWrapper", "initializeTheme", "ContextThemeWrapper.java", 210),
            StackTraceElement("android.app.ActivityThread", "performLaunchActivity", "ActivityThread.java", 3400)
        )

        assertTrue(CrashReportManager.isActivityTaskDescriptionBinderStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesLegacyActivityManagerSetTaskDescriptionStall() {
        val topFrame = StackTraceElement("android.os.BinderProxy", "transact", "Binder.java", 600)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.app.ActivityManagerProxy", "setTaskDescription", "ActivityManagerNative.java", 1500),
            StackTraceElement("android.app.Activity", "setTaskDescription", "Activity.java", 5000),
            StackTraceElement("android.app.ActivityThread", "performLaunchActivity", "ActivityThread.java", 2900)
        )

        assertTrue(CrashReportManager.isActivityTaskDescriptionBinderStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsNonBinderStallInAppBusinessLogic() {
        val topFrame = StackTraceElement("za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity", "setTaskDescriptionCustom", "StorageBrowserActivity.kt", 120)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.app.Activity", "setTaskDescription", "Activity.java", 6971)
        )

        assertFalse(CrashReportManager.isActivityTaskDescriptionBinderStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsUnrelatedBinderStall() {
        val topFrame = StackTraceElement("android.os.BinderProxy", "transactNative", "Native Method", -2)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.media.IAudioService\$Stub\$Proxy", "getStreamVolume", "IAudioService.java", 500)
        )

        assertFalse(CrashReportManager.isActivityTaskDescriptionBinderStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsBinderStallWithHeldMonitorLock() {
        val topFrame = StackTraceElement("android.os.BinderProxy", "transactNative", "Native Method", -2)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.app.IActivityClientController\$Stub\$Proxy", "setTaskDescription", "IActivityClientController.java", 2038),
            StackTraceElement("android.app.Activity", "onApplyThemeResource", "Activity.java", 5220),
            StackTraceElement("java.lang.Object", "wait", "Object.java", 442)
        )

        assertFalse(CrashReportManager.isActivityTaskDescriptionBinderStall(topFrame, stackTrace))
    }
}
