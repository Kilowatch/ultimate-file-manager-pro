package za.kilowatch.ultimatefilemanager.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityDestroyBackCallbackStallTest {

    @Test
    fun identifiesMotorolaMotoGPlayActivityDestroyBackCallbackStall() {
        val topFrame = StackTraceElement("java.util.TreeMap", "getEntry", "TreeMap.java", 380)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("java.util.TreeMap", "get", "TreeMap.java", 292),
            StackTraceElement("android.window.WindowOnBackInvokedDispatcher", "removeCallbackInternal", "WindowOnBackInvokedDispatcher.java", 293),
            StackTraceElement("android.window.WindowOnBackInvokedDispatcher", "unregisterOnBackInvokedCallback", "WindowOnBackInvokedDispatcher.java", 286),
            StackTraceElement("android.window.ProxyOnBackInvokedDispatcher", "unregisterOnBackInvokedCallback", "ProxyOnBackInvokedDispatcher.java", 90),
            StackTraceElement("android.app.Activity", "onDestroy", "Activity.java", 3008),
            StackTraceElement("mm", "s", "r8-map-id-5ea87e21d57ae7d96c3d1f82c7d4ec8668ae568e4e153cb432be5b248d57b9b2", 1),
            StackTraceElement("mm", "onDestroy", "r8-map-id-5ea87e21d57ae7d96c3d1f82c7d4ec8668ae568e4e153cb432be5b248d57b9b2", 1),
            StackTraceElement("android.app.Activity", "performDestroy", "Activity.java", 9611),
            StackTraceElement("android.app.Instrumentation", "callActivityOnDestroy", "Instrumentation.java", 1556),
            StackTraceElement("android.app.ActivityThread", "performDestroyActivity", "ActivityThread.java", 6445),
            StackTraceElement("android.app.ActivityThread", "handleDestroyActivity", "ActivityThread.java", 6490),
            StackTraceElement("android.app.servertransaction.DestroyActivityItem", "execute", "DestroyActivityItem.java", 52),
            StackTraceElement("android.app.servertransaction.ActivityTransactionItem", "execute", "ActivityTransactionItem.java", 63),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "executeLifecycleItem", "TransactionExecutor.java", 169),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "executeTransactionItems", "TransactionExecutor.java", 101),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "execute", "TransactionExecutor.java", 80),
            StackTraceElement("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 2966),
            StackTraceElement("n31", "handleMessage", "r8-map-id-5ea87e21d57ae7d96c3d1f82c7d4ec8668ae568e4e153cb432be5b248d57b9b2", 37),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 111),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 304),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 430),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 9345),
            StackTraceElement("java.lang.reflect.Method", "invoke", null, -2),
            StackTraceElement("com.android.internal.os.RuntimeInit\$MethodAndArgsCaller", "run", "RuntimeInit.java", 593),
            StackTraceElement("com.android.internal.os.ZygoteInit", "main", "ZygoteInit.java", 953)
        )

        assertTrue(CrashReportManager.isActivityDestroyBackCallbackStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesUnobfuscatedActivityDestroyBackCallbackStall() {
        val topFrame = StackTraceElement("java.util.TreeMap", "getEntry", "TreeMap.java", 380)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("java.util.TreeMap", "get", "TreeMap.java", 292),
            StackTraceElement("android.window.WindowOnBackInvokedDispatcher", "removeCallbackInternal", "WindowOnBackInvokedDispatcher.java", 293),
            StackTraceElement("android.window.WindowOnBackInvokedDispatcher", "unregisterOnBackInvokedCallback", "WindowOnBackInvokedDispatcher.java", 286),
            StackTraceElement("android.window.ProxyOnBackInvokedDispatcher", "unregisterOnBackInvokedCallback", "ProxyOnBackInvokedDispatcher.java", 90),
            StackTraceElement("android.app.Activity", "onDestroy", "Activity.java", 3008),
            StackTraceElement("androidx.fragment.app.FragmentActivity", "onDestroy", "FragmentActivity.java", 285),
            StackTraceElement("androidx.appcompat.app.AppCompatActivity", "onDestroy", "AppCompatActivity.java", 270),
            StackTraceElement("za.kilowatch.ultimatefilemanager.ui.browser.FileBrowserActivity", "onDestroy", "FileBrowserActivity.kt", 320),
            StackTraceElement("android.app.Activity", "performDestroy", "Activity.java", 9611),
            StackTraceElement("android.app.Instrumentation", "callActivityOnDestroy", "Instrumentation.java", 1556),
            StackTraceElement("android.app.ActivityThread", "performDestroyActivity", "ActivityThread.java", 6445),
            StackTraceElement("android.app.ActivityThread", "handleDestroyActivity", "ActivityThread.java", 6490),
            StackTraceElement("android.app.servertransaction.DestroyActivityItem", "execute", "DestroyActivityItem.java", 52),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "execute", "TransactionExecutor.java", 80),
            StackTraceElement("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 2966),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 111),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 304),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 430),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 9345)
        )

        assertTrue(CrashReportManager.isActivityDestroyBackCallbackStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsAppBusinessLogicExecution() {
        val topFrame = StackTraceElement("za.kilowatch.ultimatefilemanager.ui.browser.FileBrowserActivity", "cleanUpDatabaseSync", "FileBrowserActivity.kt", 325)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("za.kilowatch.ultimatefilemanager.ui.browser.FileBrowserActivity", "onDestroy", "FileBrowserActivity.kt", 320),
            StackTraceElement("android.app.Activity", "performDestroy", "Activity.java", 9611),
            StackTraceElement("android.app.ActivityThread", "handleDestroyActivity", "ActivityThread.java", 6490),
            StackTraceElement("android.app.servertransaction.DestroyActivityItem", "execute", "DestroyActivityItem.java", 52),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 9345)
        )

        assertFalse(CrashReportManager.isActivityDestroyBackCallbackStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWhenBlockingPrimitiveHeld() {
        val topFrame = StackTraceElement("android.os.BinderProxy", "transactNative", "BinderProxy.java", -2)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.os.BinderProxy", "transact", "BinderProxy.java", 571),
            StackTraceElement("android.window.WindowOnBackInvokedDispatcher", "unregisterOnBackInvokedCallback", "WindowOnBackInvokedDispatcher.java", 286),
            StackTraceElement("android.app.Activity", "onDestroy", "Activity.java", 3008),
            StackTraceElement("android.app.Activity", "performDestroy", "Activity.java", 9611),
            StackTraceElement("android.app.ActivityThread", "handleDestroyActivity", "ActivityThread.java", 6490),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 9345)
        )

        assertFalse(CrashReportManager.isActivityDestroyBackCallbackStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWhenNotInDestroyLifecycle() {
        val topFrame = StackTraceElement("java.util.TreeMap", "getEntry", "TreeMap.java", 380)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.window.WindowOnBackInvokedDispatcher", "removeCallbackInternal", "WindowOnBackInvokedDispatcher.java", 293),
            StackTraceElement("android.window.WindowOnBackInvokedDispatcher", "unregisterOnBackInvokedCallback", "WindowOnBackInvokedDispatcher.java", 286),
            StackTraceElement("android.app.Activity", "onPause", "Activity.java", 2800),
            StackTraceElement("android.app.ActivityThread", "handlePauseActivity", "ActivityThread.java", 5100),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 9345)
        )

        assertFalse(CrashReportManager.isActivityDestroyBackCallbackStall(topFrame, stackTrace))
    }
}
