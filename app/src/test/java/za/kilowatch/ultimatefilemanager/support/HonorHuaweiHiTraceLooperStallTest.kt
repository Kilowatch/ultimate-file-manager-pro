package za.kilowatch.ultimatefilemanager.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HonorHuaweiHiTraceLooperStallTest {

    @Test
    fun identifiesHonorCrtLx3HiTraceLooperStall() {
        val topFrame = StackTraceElement("hihonor.android.common.HwFrameworkFactoryImpl", "getHiTraceHandler", "HwFrameworkFactoryImpl.java", 1012)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.common.HwFrameworkFactory", "getHiTraceHandler", "HwFrameworkFactory.java", 2138),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 203),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 296),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 9000),
            StackTraceElement("java.lang.reflect.Method", "invoke", null, -2),
            StackTraceElement("com.android.internal.os.RuntimeInit\$MethodAndArgsCaller", "run", "RuntimeInit.java", 569),
            StackTraceElement("com.android.internal.os.ZygoteInit", "main", "ZygoteInit.java", 976)
        )

        assertTrue(CrashReportManager.isHonorHuaweiHiTraceLooperStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesHuaweiHwFrameworkFactoryHiTraceLooperStall() {
        val topFrame = StackTraceElement("huawei.android.common.HwFrameworkFactoryImpl", "getHiTraceHandler", "HwFrameworkFactoryImpl.java", 950)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.common.HwFrameworkFactory", "getHiTraceHandler", "HwFrameworkFactory.java", 2100),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 203),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 296),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 8500)
        )

        assertTrue(CrashReportManager.isHonorHuaweiHiTraceLooperStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesTopFrameInHwFrameworkFactoryDirect() {
        val topFrame = StackTraceElement("android.common.HwFrameworkFactory", "getHiTraceHandler", "HwFrameworkFactory.java", 2138)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 203),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 296),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 9000)
        )

        assertTrue(CrashReportManager.isHonorHuaweiHiTraceLooperStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsAppBusinessLogicExecution() {
        val topFrame = StackTraceElement("hihonor.android.common.HwFrameworkFactoryImpl", "getHiTraceHandler", "HwFrameworkFactoryImpl.java", 1012)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.common.HwFrameworkFactory", "getHiTraceHandler", "HwFrameworkFactory.java", 2138),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 203),
            StackTraceElement("za.kilowatch.ultimatefilemanager.indexing.FileIndexDao_Impl", "insert", "FileIndexDao_Impl.java", 120),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 9000)
        )

        assertFalse(CrashReportManager.isHonorHuaweiHiTraceLooperStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWhenBlockingPrimitiveHeld() {
        val topFrame = StackTraceElement("hihonor.android.common.HwFrameworkFactoryImpl", "getHiTraceHandler", "HwFrameworkFactoryImpl.java", 1012)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.common.HwFrameworkFactory", "getHiTraceHandler", "HwFrameworkFactory.java", 2138),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 203),
            StackTraceElement("java.lang.Object", "wait", "Object.java", 442)
        )

        assertFalse(CrashReportManager.isHonorHuaweiHiTraceLooperStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsUnrelatedLooperCallbackWithoutHonorOrHuawei() {
        val topFrame = StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 106)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 203),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 296),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 9000)
        )

        assertFalse(CrashReportManager.isHonorHuaweiHiTraceLooperStall(topFrame, stackTrace))
    }
}
