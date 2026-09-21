package za.kilowatch.ultimatefilemanager.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityFinishBinderStallTest {

    @Test
    fun identifiesSdk30ActivityTaskManagerFinishActivityStall() {
        val topFrame = StackTraceElement("android.os.BinderProxy", "transactNative", "Native Method", -2)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.os.BinderProxy", "transact", "BinderProxy.java", 540),
            StackTraceElement("android.app.IActivityTaskManager\$Stub\$Proxy", "finishActivity", "IActivityTaskManager.java", 4266),
            StackTraceElement("android.app.Activity", "finish", "Activity.java", 6376),
            StackTraceElement("android.app.Activity", "finish", "Activity.java", 6412),
            StackTraceElement("za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity", "B", "r8", 78),
            StackTraceElement("va3", "handleOnBackPressed", "r8", 85),
            StackTraceElement("wu6", "d", "r8", 44),
            StackTraceElement("yi1", "onBackPressed", "r8", 5),
            StackTraceElement("android.app.Activity", "onKeyUp", "Activity.java", 3784),
            StackTraceElement("android.view.KeyEvent", "dispatch", "KeyEvent.java", 2866),
            StackTraceElement("android.app.Activity", "dispatchKeyEvent", "Activity.java", 4090),
            StackTraceElement("yi1", "superDispatchKeyEvent", "r8", 4),
            StackTraceElement("fj7", "q", "r8", 12),
            StackTraceElement("yi1", "dispatchKeyEvent", "r8", 23),
            StackTraceElement("iv", "dispatchKeyEvent", "r8", 23),
            StackTraceElement("za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity", "dispatchKeyEvent", "r8", 124),
            StackTraceElement("gw", "dispatchKeyEvent", "r8", 20),
            StackTraceElement("com.android.internal.policy.DecorView", "dispatchKeyEvent", "DecorView.java", 390)
        )

        assertTrue(CrashReportManager.isActivityFinishBinderStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesSdk31PlusActivityClientControllerFinishActivityStall() {
        val topFrame = StackTraceElement("android.os.BinderProxy", "transactNative", "Native Method", -2)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.os.BinderProxy", "transact", "BinderProxy.java", 571),
            StackTraceElement("android.app.IActivityClientController\$Stub\$Proxy", "finishActivity", "IActivityClientController.java", 2050),
            StackTraceElement("android.app.ActivityClient", "finishActivity", "ActivityClient.java", 350),
            StackTraceElement("android.app.Activity", "finish", "Activity.java", 6990),
            StackTraceElement("za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity", "onBackPressed", "StorageBrowserActivity.kt", 3249)
        )

        assertTrue(CrashReportManager.isActivityFinishBinderStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesLegacyActivityManagerFinishActivityStall() {
        val topFrame = StackTraceElement("android.os.BinderProxy", "transact", "Binder.java", 600)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.app.ActivityManagerProxy", "finishActivity", "ActivityManagerNative.java", 1520),
            StackTraceElement("android.app.Activity", "finish", "Activity.java", 5100),
            StackTraceElement("za.kilowatch.ultimatefilemanager.storage.SearchActivity", "navigateBack", "SearchActivity.kt", 268)
        )

        assertTrue(CrashReportManager.isActivityFinishBinderStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesFinishActivityAffinityStall() {
        val topFrame = StackTraceElement("android.os.BinderProxy", "transactNative", "Native Method", -2)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.os.BinderProxy", "transact", "BinderProxy.java", 540),
            StackTraceElement("android.app.IActivityTaskManager\$Stub\$Proxy", "finishActivityAffinity", "IActivityTaskManager.java", 4290),
            StackTraceElement("android.app.Activity", "finishAffinity", "Activity.java", 6450),
            StackTraceElement("za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity", "exitApp", "FileBrowserActivity.kt", 1800)
        )

        assertTrue(CrashReportManager.isActivityFinishBinderStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsNonBinderStallInAppBusinessLogic() {
        val topFrame = StackTraceElement("za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity", "performCleanup", "FileBrowserActivity.kt", 120)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.app.Activity", "finish", "Activity.java", 6376)
        )

        assertFalse(CrashReportManager.isActivityFinishBinderStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsUnrelatedBinderStall() {
        val topFrame = StackTraceElement("android.os.BinderProxy", "transactNative", "Native Method", -2)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.media.IAudioService\$Stub\$Proxy", "getStreamVolume", "IAudioService.java", 500)
        )

        assertFalse(CrashReportManager.isActivityFinishBinderStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsBinderStallWithHeldMonitorLock() {
        val topFrame = StackTraceElement("android.os.BinderProxy", "transactNative", "Native Method", -2)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.app.IActivityTaskManager\$Stub\$Proxy", "finishActivity", "IActivityTaskManager.java", 4266),
            StackTraceElement("android.app.Activity", "finish", "Activity.java", 6376),
            StackTraceElement("java.lang.Object", "wait", "Object.java", 442)
        )

        assertFalse(CrashReportManager.isActivityFinishBinderStall(topFrame, stackTrace))
    }
}
