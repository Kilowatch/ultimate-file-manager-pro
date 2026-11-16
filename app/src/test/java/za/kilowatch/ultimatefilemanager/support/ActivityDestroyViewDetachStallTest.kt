package za.kilowatch.ultimatefilemanager.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityDestroyViewDetachStallTest {

    @Test
    fun identifiesSamsungA346EActivityDestroyViewDetachStall() {
        val topFrame = StackTraceElement("android.os.MessageQueue", "removeMessagesLegacy", "MessageQueue.java", 1778)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.os.MessageQueue", "removeMessages", "MessageQueue.java", 1812),
            StackTraceElement("android.os.Handler", "removeMessages", "Handler.java", 870),
            StackTraceElement("android.view.ViewRootImpl", "cancelInvalidate", "ViewRootImpl.java", 13303),
            StackTraceElement("android.view.View", "cleanupDraw", "View.java", 24570),
            StackTraceElement("android.view.View", "onDetachedFromWindowInternal", "View.java", 24550),
            StackTraceElement("android.view.View", "dispatchDetachedFromWindow", "View.java", 24790),
            StackTraceElement("android.view.ViewGroup", "dispatchDetachedFromWindow", "ViewGroup.java", 4316),
            StackTraceElement("android.view.ViewGroup", "dispatchDetachedFromWindow", "ViewGroup.java", 4308),
            StackTraceElement("android.view.ViewRootImpl", "dispatchDetachedFromWindow", "ViewRootImpl.java", 7938),
            StackTraceElement("android.view.ViewRootImpl", "doDie", "ViewRootImpl.java", 12409),
            StackTraceElement("android.view.ViewRootImpl", "die", "ViewRootImpl.java", 12381),
            StackTraceElement("android.view.WindowManagerGlobal", "removeViewLocked", "WindowManagerGlobal.java", 721),
            StackTraceElement("android.view.WindowManagerGlobal", "removeView", "WindowManagerGlobal.java", 654),
            StackTraceElement("android.view.WindowManagerImpl", "removeViewImmediate", "WindowManagerImpl.java", 254),
            StackTraceElement("android.app.ActivityThread", "handleDestroyActivity", "ActivityThread.java", 7424),
            StackTraceElement("android.app.servertransaction.DestroyActivityItem", "execute", "DestroyActivityItem.java", 52),
            StackTraceElement("android.app.servertransaction.ActivityTransactionItem", "execute", "ActivityTransactionItem.java", 63),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "executeLifecycleItem", "TransactionExecutor.java", 169),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "executeTransactionItems", "TransactionExecutor.java", 101),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "execute", "TransactionExecutor.java", 80),
            StackTraceElement("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 3287),
            StackTraceElement("za.kilowatch.ultimatefilemanager.support.CrashReportManager", "installActivityThreadCrashHook\$lambda\$87", "CrashReportManager.kt", 1404),
            StackTraceElement("za.kilowatch.ultimatefilemanager.support.CrashReportManager\$\$ExternalSyntheticLambda3", "handleMessage", "D8\$\$SyntheticClass", 0),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 128),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 288),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 392),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 10364),
            StackTraceElement("java.lang.reflect.Method", "invoke", null, -2),
            StackTraceElement("com.android.internal.os.RuntimeInit\$MethodAndArgsCaller", "run", "RuntimeInit.java", 638),
            StackTraceElement("com.android.internal.os.ZygoteInit", "main", "ZygoteInit.java", 972)
        )

        assertTrue(CrashReportManager.isActivityDestroyViewDetachStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesUnobfuscatedDestroyViewDetachStall() {
        val topFrame = StackTraceElement("android.view.View", "cleanupDraw", "View.java", 24570)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.view.View", "onDetachedFromWindowInternal", "View.java", 24550),
            StackTraceElement("android.view.View", "dispatchDetachedFromWindow", "View.java", 24790),
            StackTraceElement("android.view.ViewGroup", "dispatchDetachedFromWindow", "ViewGroup.java", 4316),
            StackTraceElement("android.view.ViewRootImpl", "dispatchDetachedFromWindow", "ViewRootImpl.java", 7938),
            StackTraceElement("android.view.ViewRootImpl", "doDie", "ViewRootImpl.java", 12409),
            StackTraceElement("android.view.ViewRootImpl", "die", "ViewRootImpl.java", 12381),
            StackTraceElement("android.view.WindowManagerGlobal", "removeViewLocked", "WindowManagerGlobal.java", 721),
            StackTraceElement("android.view.WindowManagerGlobal", "removeView", "WindowManagerGlobal.java", 654),
            StackTraceElement("android.view.WindowManagerImpl", "removeViewImmediate", "WindowManagerImpl.java", 254),
            StackTraceElement("android.app.ActivityThread", "handleDestroyActivity", "ActivityThread.java", 7424),
            StackTraceElement("android.app.servertransaction.DestroyActivityItem", "execute", "DestroyActivityItem.java", 52),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "execute", "TransactionExecutor.java", 80),
            StackTraceElement("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 3287),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 128),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 288),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 392),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 10364)
        )

        assertTrue(CrashReportManager.isActivityDestroyViewDetachStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsAppBusinessLogicExecution() {
        val topFrame = StackTraceElement("za.kilowatch.ultimatefilemanager.ui.browser.FileBrowserActivity", "onDestroy", "FileBrowserActivity.kt", 320)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.app.Activity", "performDestroy", "Activity.java", 8500),
            StackTraceElement("android.app.ActivityThread", "handleDestroyActivity", "ActivityThread.java", 7424),
            StackTraceElement("android.app.servertransaction.DestroyActivityItem", "execute", "DestroyActivityItem.java", 52),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 10364)
        )

        assertFalse(CrashReportManager.isActivityDestroyViewDetachStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWhenBlockingPrimitiveHeld() {
        val topFrame = StackTraceElement("java.lang.Object", "wait", "Object.java", 442)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.view.View", "cleanupDraw", "View.java", 24570),
            StackTraceElement("android.view.View", "dispatchDetachedFromWindow", "View.java", 24790),
            StackTraceElement("android.view.ViewGroup", "dispatchDetachedFromWindow", "ViewGroup.java", 4316),
            StackTraceElement("android.view.ViewRootImpl", "dispatchDetachedFromWindow", "ViewRootImpl.java", 7938),
            StackTraceElement("android.view.ViewRootImpl", "die", "ViewRootImpl.java", 12381),
            StackTraceElement("android.view.WindowManagerGlobal", "removeViewLocked", "WindowManagerGlobal.java", 721),
            StackTraceElement("android.app.ActivityThread", "handleDestroyActivity", "ActivityThread.java", 7424),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 10364)
        )

        assertFalse(CrashReportManager.isActivityDestroyViewDetachStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWithoutActivityThreadMainOrLooper() {
        val topFrame = StackTraceElement("android.os.MessageQueue", "removeMessagesLegacy", "MessageQueue.java", 1778)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.os.Handler", "removeMessages", "Handler.java", 870),
            StackTraceElement("android.view.ViewRootImpl", "cancelInvalidate", "ViewRootImpl.java", 13303),
            StackTraceElement("android.view.View", "cleanupDraw", "View.java", 24570),
            StackTraceElement("android.view.View", "dispatchDetachedFromWindow", "View.java", 24790),
            StackTraceElement("android.view.ViewGroup", "dispatchDetachedFromWindow", "ViewGroup.java", 4316),
            StackTraceElement("android.view.ViewRootImpl", "die", "ViewRootImpl.java", 12381),
            StackTraceElement("android.view.WindowManagerGlobal", "removeViewLocked", "WindowManagerGlobal.java", 721),
            StackTraceElement("android.app.ActivityThread", "handleDestroyActivity", "ActivityThread.java", 7424),
            StackTraceElement("android.os.HandlerThread", "run", "HandlerThread.java", 67)
        )

        assertFalse(CrashReportManager.isActivityDestroyViewDetachStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWithoutDestroyOrDetachLifecycle() {
        val topFrame = StackTraceElement("android.os.MessageQueue", "removeMessagesLegacy", "MessageQueue.java", 1778)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.os.Handler", "removeMessages", "Handler.java", 870),
            StackTraceElement("android.view.ViewRootImpl", "cancelInvalidate", "ViewRootImpl.java", 13303),
            StackTraceElement("android.app.ActivityThread", "handleResumeActivity", "ActivityThread.java", 4500),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 10364)
        )

        assertFalse(CrashReportManager.isActivityDestroyViewDetachStall(topFrame, stackTrace))
    }
}
