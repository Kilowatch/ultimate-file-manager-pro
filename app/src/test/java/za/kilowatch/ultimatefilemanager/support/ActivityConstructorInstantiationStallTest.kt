package za.kilowatch.ultimatefilemanager.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityConstructorInstantiationStallTest {

    @Test
    fun identifiesSkyworthSwtvActivityConstructorInstantiationStall() {
        val topFrame = StackTraceElement("ji5", "a", "r8-map-id-99dcccc9f1f32f2dc09c545cedd1313b419024cbb68d5d2d6f42e721400fefcc", 1)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("ok1", "<init>", "r8-map-id-99dcccc9f1f32f2dc09c545cedd1313b419024cbb68d5d2d6f42e721400fefcc", 139),
            StackTraceElement("e14", "<init>", "r8-map-id-99dcccc9f1f32f2dc09c545cedd1313b419024cbb68d5d2d6f42e721400fefcc", 1),
            StackTraceElement("dv", "<init>", "r8-map-id-99dcccc9f1f32f2dc09c545cedd1313b419024cbb68d5d2d6f42e721400fefcc", 1),
            StackTraceElement("za.kilowatch.ultimatefilemanager.ui.SafPickerActivity", "<init>", "r8-map-id-99dcccc9f1f32f2dc09c545cedd1313b419024cbb68d5d2d6f42e721400fefcc", 1),
            StackTraceElement("java.lang.Class", "newInstance", "Native Method", -2),
            StackTraceElement("android.app.AppComponentFactory", "instantiateActivity", "AppComponentFactory.java", 95),
            StackTraceElement("android.app.Instrumentation", "newActivity", "Instrumentation.java", 1253),
            StackTraceElement("android.app.ActivityThread", "performLaunchActivity", "ActivityThread.java", 3353),
            StackTraceElement("android.app.ActivityThread", "handleLaunchActivity", "ActivityThread.java", 3601),
            StackTraceElement("android.app.servertransaction.LaunchActivityItem", "execute", "LaunchActivityItem.java", 85),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "executeCallbacks", "TransactionExecutor.java", 135),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "execute", "TransactionExecutor.java", 95),
            StackTraceElement("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 2066),
            StackTraceElement("ww1", "handleMessage", "r8-map-id-99dcccc9f1f32f2dc09c545cedd1313b419024cbb68d5d2d6f42e721400fefcc", 37),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 102),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 223),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 7656),
            StackTraceElement("java.lang.reflect.Method", "invoke", "Native Method", -2),
            StackTraceElement("com.android.internal.os.RuntimeInit\$MethodAndArgsCaller", "run", "RuntimeInit.java", 592),
            StackTraceElement("com.android.internal.os.ZygoteInit", "main", "ZygoteInit.java", 947)
        )

        assertTrue(CrashReportManager.isActivityConstructorInstantiationStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesUnobfuscatedDebugActivityConstructorInstantiationStall() {
        val topFrame = StackTraceElement("androidx.activity.contextaware.ContextAwareHelper", "addOnContextAvailableListener", "ContextAwareHelper.kt", 45)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("androidx.activity.ComponentActivity", "initContextAware", "ComponentActivity.kt", 139),
            StackTraceElement("androidx.activity.ComponentActivity", "<init>", "ComponentActivity.kt", 120),
            StackTraceElement("androidx.fragment.app.FragmentActivity", "<init>", "FragmentActivity.java", 98),
            StackTraceElement("androidx.appcompat.app.AppCompatActivity", "<init>", "AppCompatActivity.java", 112),
            StackTraceElement("za.kilowatch.ultimatefilemanager.ui.SafPickerActivity", "<init>", "SafPickerActivity.kt", 89),
            StackTraceElement("java.lang.Class", "newInstance", "Class.java", 260),
            StackTraceElement("android.app.AppComponentFactory", "instantiateActivity", "AppComponentFactory.java", 95),
            StackTraceElement("android.app.Instrumentation", "newActivity", "Instrumentation.java", 1253),
            StackTraceElement("android.app.ActivityThread", "performLaunchActivity", "ActivityThread.java", 3353),
            StackTraceElement("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 2066),
            StackTraceElement("za.kilowatch.ultimatefilemanager.support.CrashReportManager\$installActivityThreadCrashHook\$1", "handleMessage", "CrashReportManager.kt", 813),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 102),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 205),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 294),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 8177)
        )

        assertTrue(CrashReportManager.isActivityConstructorInstantiationStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesActivityConstructorTopFrameStall() {
        val topFrame = StackTraceElement("za.kilowatch.ultimatefilemanager.ui.SafPickerActivity", "<init>", "SafPickerActivity.kt", 89)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("java.lang.Class", "newInstance", "Class.java", 260),
            StackTraceElement("android.app.AppComponentFactory", "instantiateActivity", "AppComponentFactory.java", 95),
            StackTraceElement("android.app.Instrumentation", "newActivity", "Instrumentation.java", 1253),
            StackTraceElement("android.app.ActivityThread", "performLaunchActivity", "ActivityThread.java", 3353),
            StackTraceElement("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 2066),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 102),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 294),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 8177)
        )

        assertTrue(CrashReportManager.isActivityConstructorInstantiationStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesComponentActivityGetLifecycleTopFrameStall() {
        val topFrame = StackTraceElement("androidx.activity.ComponentActivity", "getLifecycle", "ComponentActivity.kt", 200)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("androidx.fragment.app.FragmentActivity", "<init>", "FragmentActivity.java", 98),
            StackTraceElement("androidx.appcompat.app.AppCompatActivity", "<init>", "AppCompatActivity.java", 112),
            StackTraceElement("za.kilowatch.ultimatefilemanager.ui.SafPickerActivity", "<init>", "SafPickerActivity.kt", 89),
            StackTraceElement("java.lang.Class", "newInstance", "Class.java", 260),
            StackTraceElement("android.app.AppComponentFactory", "instantiateActivity", "AppComponentFactory.java", 95),
            StackTraceElement("android.app.Instrumentation", "newActivity", "Instrumentation.java", 1253),
            StackTraceElement("android.app.ActivityThread", "performLaunchActivity", "ActivityThread.java", 3353),
            StackTraceElement("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 2066),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 102),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 294),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 8177)
        )

        assertTrue(CrashReportManager.isActivityConstructorInstantiationStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsAppBusinessLogicExecutionInConstructor() {
        val topFrame = StackTraceElement("za.kilowatch.ultimatefilemanager.ui.SafPickerActivity", "loadUserData", "SafPickerActivity.kt", 150)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("za.kilowatch.ultimatefilemanager.ui.SafPickerActivity", "<init>", "SafPickerActivity.kt", 90),
            StackTraceElement("java.lang.Class", "newInstance", "Class.java", 260),
            StackTraceElement("android.app.Instrumentation", "newActivity", "Instrumentation.java", 1253),
            StackTraceElement("android.app.ActivityThread", "performLaunchActivity", "ActivityThread.java", 3353),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 8177)
        )

        assertFalse(CrashReportManager.isActivityConstructorInstantiationStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsExternalBlockingPrimitive() {
        val topFrame = StackTraceElement("android.os.BinderProxy", "transactNative", "BinderProxy.java", -2)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.os.BinderProxy", "transact", "BinderProxy.java", 571),
            StackTraceElement("za.kilowatch.ultimatefilemanager.ui.SafPickerActivity", "<init>", "SafPickerActivity.kt", 89),
            StackTraceElement("java.lang.Class", "newInstance", "Class.java", 260),
            StackTraceElement("android.app.Instrumentation", "newActivity", "Instrumentation.java", 1253),
            StackTraceElement("android.app.ActivityThread", "performLaunchActivity", "ActivityThread.java", 3353),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 8177)
        )

        assertFalse(CrashReportManager.isActivityConstructorInstantiationStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWithoutActivityThreadMainOrLooper() {
        val topFrame = StackTraceElement("ok1", "<init>", "r8", 139)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("za.kilowatch.ultimatefilemanager.ui.SafPickerActivity", "<init>", "SafPickerActivity.kt", 89),
            StackTraceElement("java.lang.Class", "newInstance", "Class.java", 260),
            StackTraceElement("android.app.Instrumentation", "newActivity", "Instrumentation.java", 1253),
            StackTraceElement("android.app.ActivityThread", "performLaunchActivity", "ActivityThread.java", 3353)
        )

        assertFalse(CrashReportManager.isActivityConstructorInstantiationStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWithoutNewInstance() {
        val topFrame = StackTraceElement("ok1", "<init>", "r8", 139)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("za.kilowatch.ultimatefilemanager.ui.SafPickerActivity", "<init>", "SafPickerActivity.kt", 89),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 8177)
        )

        assertFalse(CrashReportManager.isActivityConstructorInstantiationStall(topFrame, stackTrace))
    }
}
