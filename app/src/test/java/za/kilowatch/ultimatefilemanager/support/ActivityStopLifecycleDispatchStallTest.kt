package za.kilowatch.ultimatefilemanager.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityStopLifecycleDispatchStallTest {

    @Test
    fun identifiesPixel8ObfuscatedStopLifecycleStall() {
        val topFrame = StackTraceElement("w65", "invoke", null, 1)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("v65", "cancel", null, 36),
            StackTraceElement("u65", "p", null, 24),
            StackTraceElement("vx3", "a", null, 21),
            StackTraceElement("wx3", "g", null, 201),
            StackTraceElement("wx3", "e", null, 130),
            StackTraceElement("wx3", "d", null, 13),
            StackTraceElement("androidx.lifecycle.a", "a", null, 16),
            StackTraceElement("androidx.lifecycle.ReportFragment\$LifecycleCallbacks", "onActivityPreStopped", null, 8),
            StackTraceElement("android.app.Activity", "dispatchActivityPreStopped", "Activity.java", 1733),
            StackTraceElement("android.app.Activity", "performStop", "Activity.java", 9595),
            StackTraceElement("android.app.ActivityThread", "callActivityOnStop", "ActivityThread.java", 6392),
            StackTraceElement("android.app.ActivityThread", "performStopActivityInner", "ActivityThread.java", 6372),
            StackTraceElement("android.app.ActivityThread", "handleStopActivity", "ActivityThread.java", 6435),
            StackTraceElement("android.app.servertransaction.StopActivityItem", "execute", "StopActivityItem.java", 43),
            StackTraceElement("android.app.servertransaction.ActivityTransactionItem", "execute", "ActivityTransactionItem.java", 64),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "executeLifecycleItem", "TransactionExecutor.java", 169),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "executeTransactionItems", "TransactionExecutor.java", 101),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "execute", "TransactionExecutor.java", 80),
            StackTraceElement("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 3046),
            StackTraceElement("i41", "handleMessage", null, 37),
            StackTraceElement("android.os.Handler", "dispatchMessageImpl", "Handler.java", 138),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 126),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 295),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 398),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 9569),
            StackTraceElement("java.lang.reflect.Method", "invoke", null, -2),
            StackTraceElement("com.android.internal.os.RuntimeInit\$MethodAndArgsCaller", "run", "RuntimeInit.java", 575),
            StackTraceElement("com.android.internal.os.ZygoteInit", "main", "ZygoteInit.java", 918)
        )

        assertTrue(CrashReportManager.isActivityStopLifecycleDispatchStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesUnobfuscatedDebugStopLifecycleStall() {
        val topFrame = StackTraceElement(
            "androidx.activity.OnBackPressedDispatcher\$addCallback\$1",
            "invoke",
            "OnBackPressedDispatcher.kt",
            1
        )
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("androidx.activity.OnBackPressedDispatcher\$OnBackPressedCancellable", "cancel", "OnBackPressedDispatcher.kt", 36),
            StackTraceElement("androidx.activity.OnBackPressedDispatcher\$LifecycleOnBackPressedCancellable", "onStateChanged", "OnBackPressedDispatcher.kt", 24),
            StackTraceElement("androidx.lifecycle.LifecycleRegistry\$ObserverWithState", "dispatchEvent", "LifecycleRegistry.jvm.kt", 21),
            StackTraceElement("androidx.lifecycle.LifecycleRegistry", "backwardPass", "LifecycleRegistry.jvm.kt", 201),
            StackTraceElement("androidx.lifecycle.LifecycleRegistry", "sync", "LifecycleRegistry.jvm.kt", 130),
            StackTraceElement("androidx.lifecycle.LifecycleRegistry", "handleLifecycleEvent", "LifecycleRegistry.jvm.kt", 13),
            StackTraceElement("androidx.lifecycle.ReportFragment\$Companion", "dispatch", "ReportFragment.android.kt", 16),
            StackTraceElement("androidx.lifecycle.ReportFragment\$LifecycleCallbacks", "onActivityPreStopped", "ReportFragment.android.kt", 8),
            StackTraceElement("android.app.Activity", "dispatchActivityPreStopped", "Activity.java", 1733),
            StackTraceElement("android.app.Activity", "performStop", "Activity.java", 9595),
            StackTraceElement("android.app.ActivityThread", "callActivityOnStop", "ActivityThread.java", 6392),
            StackTraceElement("android.app.ActivityThread", "performStopActivityInner", "ActivityThread.java", 6372),
            StackTraceElement("android.app.ActivityThread", "handleStopActivity", "ActivityThread.java", 6435),
            StackTraceElement("android.app.servertransaction.StopActivityItem", "execute", "StopActivityItem.java", 43),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "execute", "TransactionExecutor.java", 80),
            StackTraceElement("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 3046),
            StackTraceElement("za.kilowatch.ultimatefilemanager.support.CrashReportManager\$installActivityThreadCrashHook\$1", "handleMessage", "CrashReportManager.kt", 813),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 102),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 205),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 294),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 8177)
        )

        assertTrue(CrashReportManager.isActivityStopLifecycleDispatchStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsAppBusinessLogicExecution() {
        val topFrame = StackTraceElement("w65", "invoke", null, 1)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("v65", "cancel", null, 36),
            StackTraceElement("za.kilowatch.ultimatefilemanager.ui.browser.FileBrowserActivity", "onStop", "FileBrowserActivity.kt", 240),
            StackTraceElement("android.app.Activity", "performStop", "Activity.java", 9595),
            StackTraceElement("android.app.ActivityThread", "handleStopActivity", "ActivityThread.java", 6435),
            StackTraceElement("android.app.servertransaction.StopActivityItem", "execute", "StopActivityItem.java", 43),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 9569)
        )

        assertFalse(CrashReportManager.isActivityStopLifecycleDispatchStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWhenBlockingPrimitiveHeld() {
        val topFrame = StackTraceElement("java.lang.Object", "wait", "Object.java", 442)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("w65", "invoke", null, 1),
            StackTraceElement("v65", "cancel", null, 36),
            StackTraceElement("androidx.lifecycle.ReportFragment\$LifecycleCallbacks", "onActivityPreStopped", null, 8),
            StackTraceElement("android.app.Activity", "dispatchActivityPreStopped", "Activity.java", 1733),
            StackTraceElement("android.app.Activity", "performStop", "Activity.java", 9595),
            StackTraceElement("android.app.ActivityThread", "handleStopActivity", "ActivityThread.java", 6435),
            StackTraceElement("android.app.servertransaction.StopActivityItem", "execute", "StopActivityItem.java", 43),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 9569)
        )

        assertFalse(CrashReportManager.isActivityStopLifecycleDispatchStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWithoutActivityThreadMain() {
        val topFrame = StackTraceElement("w65", "invoke", null, 1)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("v65", "cancel", null, 36),
            StackTraceElement("androidx.lifecycle.ReportFragment\$LifecycleCallbacks", "onActivityPreStopped", null, 8),
            StackTraceElement("android.app.Activity", "dispatchActivityPreStopped", "Activity.java", 1733),
            StackTraceElement("android.app.Activity", "performStop", "Activity.java", 9595),
            StackTraceElement("android.app.ActivityThread", "handleStopActivity", "ActivityThread.java", 6435),
            StackTraceElement("android.os.HandlerThread", "run", "HandlerThread.java", 67)
        )

        assertFalse(CrashReportManager.isActivityStopLifecycleDispatchStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWithoutStopLifecycle() {
        val topFrame = StackTraceElement("w65", "invoke", null, 1)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("v65", "cancel", null, 36),
            StackTraceElement("androidx.lifecycle.ReportFragment\$LifecycleCallbacks", "onActivityPreStopped", null, 8),
            StackTraceElement("android.app.Activity", "dispatchActivityPreStopped", "Activity.java", 1733),
            StackTraceElement("android.app.Activity", "performStop", "Activity.java", 9595),
            StackTraceElement("android.app.ActivityThread", "handleResumeActivity", "ActivityThread.java", 4500),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 9569)
        )

        assertFalse(CrashReportManager.isActivityStopLifecycleDispatchStall(topFrame, stackTrace))
    }
}
