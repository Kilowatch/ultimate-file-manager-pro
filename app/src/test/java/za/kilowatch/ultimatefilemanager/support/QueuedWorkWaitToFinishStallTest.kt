package za.kilowatch.ultimatefilemanager.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedWorkWaitToFinishStallTest {

    @Test
    fun identifiesHisenseSmartTvQueuedWorkWaitToFinishStall() {
        val topFrame = StackTraceElement("android.app.QueuedWork", "processPendingWork", "QueuedWork.java", 252)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.app.QueuedWork", "waitToFinish", "QueuedWork.java", 178),
            StackTraceElement("android.app.ActivityThread", "handleStopActivity", "ActivityThread.java", 5203),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "performLifecycleSequence", "TransactionExecutor.java", 234),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "cycleToPath", "TransactionExecutor.java", 201),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "executeLifecycleState", "TransactionExecutor.java", 173),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "execute", "TransactionExecutor.java", 97),
            StackTraceElement("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 2210),
            StackTraceElement("ww1", "handleMessage", "r8-map-id-99dcccc9f1f32f2dc09c545cedd1313b419024cbb68d5d2d6f42e721400fefcc", 37),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 102),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 201),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 288),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 7839),
            StackTraceElement("java.lang.reflect.Method", "invoke", null, -2),
            StackTraceElement("com.android.internal.os.RuntimeInit\$MethodAndArgsCaller", "run", "RuntimeInit.java", 548),
            StackTraceElement("com.android.internal.os.ZygoteInit", "main", "ZygoteInit.java", 1045)
        )

        assertTrue(CrashReportManager.isQueuedWorkWaitToFinishStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesUnobfuscatedDebugQueuedWorkWaitToFinishStall() {
        val topFrame = StackTraceElement("android.app.QueuedWork", "processPendingWork", "QueuedWork.java", 252)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.app.QueuedWork", "waitToFinish", "QueuedWork.java", 178),
            StackTraceElement("android.app.ActivityThread", "handleStopActivity", "ActivityThread.java", 5203),
            StackTraceElement("android.app.servertransaction.StopActivityItem", "execute", "StopActivityItem.java", 43),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "execute", "TransactionExecutor.java", 80),
            StackTraceElement("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 3046),
            StackTraceElement("za.kilowatch.ultimatefilemanager.support.CrashReportManager\$installActivityThreadCrashHook\$1", "handleMessage", "CrashReportManager.kt", 813),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 102),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 205),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 294),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 8177)
        )

        assertTrue(CrashReportManager.isQueuedWorkWaitToFinishStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesMainThreadDrainingQueuedWorkWriteToFile() {
        val topFrame = StackTraceElement("libcore.io.Linux", "rename", "Linux.java", 704)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.app.ActivityThread\$AndroidOs", "rename", "ActivityThread.java", 7764),
            StackTraceElement("java.io.File", "renameTo", "File.java", 1410),
            StackTraceElement("android.app.SharedPreferencesImpl", "writeToFile", "SharedPreferencesImpl.java", 778),
            StackTraceElement("android.app.SharedPreferencesImpl\$2", "run", "SharedPreferencesImpl.java", 672),
            StackTraceElement("android.app.QueuedWork", "processPendingWork", "QueuedWork.java", 265),
            StackTraceElement("android.app.QueuedWork", "waitToFinish", "QueuedWork.java", 178),
            StackTraceElement("android.app.ActivityThread", "handleStopActivity", "ActivityThread.java", 5203),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "execute", "TransactionExecutor.java", 97),
            StackTraceElement("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 2210),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 102),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 201),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 288),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 7839)
        )

        assertTrue(CrashReportManager.isQueuedWorkWaitToFinishStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsAppBusinessLogicExecution() {
        val topFrame = StackTraceElement("android.app.QueuedWork", "processPendingWork", "QueuedWork.java", 252)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.app.QueuedWork", "waitToFinish", "QueuedWork.java", 178),
            StackTraceElement("za.kilowatch.ultimatefilemanager.ui.browser.FileBrowserActivity", "onStop", "FileBrowserActivity.kt", 240),
            StackTraceElement("android.app.Activity", "performStop", "Activity.java", 9595),
            StackTraceElement("android.app.ActivityThread", "handleStopActivity", "ActivityThread.java", 6435),
            StackTraceElement("android.app.servertransaction.StopActivityItem", "execute", "StopActivityItem.java", 43),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 9569)
        )

        assertFalse(CrashReportManager.isQueuedWorkWaitToFinishStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsExternalBlockingPrimitive() {
        val topFrame = StackTraceElement("android.os.BinderProxy", "transactNative", "BinderProxy.java", -2)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.os.BinderProxy", "transact", "BinderProxy.java", 571),
            StackTraceElement("android.app.QueuedWork", "waitToFinish", "QueuedWork.java", 178),
            StackTraceElement("android.app.ActivityThread", "handleStopActivity", "ActivityThread.java", 6435),
            StackTraceElement("android.app.servertransaction.StopActivityItem", "execute", "StopActivityItem.java", 43),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 9569)
        )

        assertFalse(CrashReportManager.isQueuedWorkWaitToFinishStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWithoutActivityThreadMainOrLooper() {
        val topFrame = StackTraceElement("android.app.QueuedWork", "processPendingWork", "QueuedWork.java", 252)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.app.QueuedWork", "waitToFinish", "QueuedWork.java", 178),
            StackTraceElement("android.app.ActivityThread", "handleStopActivity", "ActivityThread.java", 6435),
            StackTraceElement("android.os.HandlerThread", "run", "HandlerThread.java", 67)
        )

        assertFalse(CrashReportManager.isQueuedWorkWaitToFinishStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWithoutQueuedWorkWaitToFinish() {
        val topFrame = StackTraceElement("android.app.ActivityThread", "handleStopActivity", "ActivityThread.java", 6435)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.app.servertransaction.StopActivityItem", "execute", "StopActivityItem.java", 43),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 9569)
        )

        assertFalse(CrashReportManager.isQueuedWorkWaitToFinishStall(topFrame, stackTrace))
    }
}
