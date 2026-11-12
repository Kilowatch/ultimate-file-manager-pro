package za.kilowatch.ultimatefilemanager.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextInputLayoutInflateStallTest {

    @Test
    fun identifiesSdmcG1TextInputLayoutInflateStall() {
        // Reproduces exact stack trace from bug report:
        // Device: SDMC G1, SDK 34, app 2.2.1-GOOGLE (code 305)
        // Thread: main [state=RUNNABLE]
        // at bz1.a(...)
        // at as.a(...)
        // at dg1.h(...)
        // at dg1.b(...)
        // at dg1.j(...)
        // at dg1.i(...)
        // at dg1.<init>(...)
        // at com.google.android.material.textfield.TextInputLayout.<init>(...)
        val topFrame = StackTraceElement(
            "bz1",
            "a",
            "r8-map-id-207354fb24f02c642f25454f24127a05c2e558b5872d7afc81b9f66723d4c56f",
            1
        )
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("as", "a", "r8-map-id-207354fb24f02c642f25454f24127a05c2e558b5872d7afc81b9f66723d4c56f", 1),
            StackTraceElement("dg1", "h", "r8-map-id-207354fb24f02c642f25454f24127a05c2e558b5872d7afc81b9f66723d4c56f", 7),
            StackTraceElement("dg1", "b", "r8-map-id-207354fb24f02c642f25454f24127a05c2e558b5872d7afc81b9f66723d4c56f", 15),
            StackTraceElement("dg1", "j", "r8-map-id-207354fb24f02c642f25454f24127a05c2e558b5872d7afc81b9f66723d4c56f", 407),
            StackTraceElement("dg1", "i", "r8-map-id-207354fb24f02c642f25454f24127a05c2e558b5872d7afc81b9f66723d4c56f", 46),
            StackTraceElement("dg1", "<init>", "r8-map-id-207354fb24f02c642f25454f24127a05c2e558b5872d7afc81b9f66723d4c56f", 89),
            StackTraceElement("com.google.android.material.textfield.TextInputLayout", "<init>", "r8-map-id-207354fb24f02c642f25454f24127a05c2e558b5872d7afc81b9f66723d4c56f", 74),
            StackTraceElement("com.google.android.material.textfield.TextInputLayout", "<init>", "r8-map-id-207354fb24f02c642f25454f24127a05c2e558b5872d7afc81b9f66723d4c56f", 921),
            StackTraceElement("java.lang.reflect.Constructor", "newInstance0", "Native Method", -2),
            StackTraceElement("java.lang.reflect.Constructor", "newInstance", "Constructor.java", 343),
            StackTraceElement("android.view.LayoutInflater", "createView", "LayoutInflater.java", 866),
            StackTraceElement("android.view.LayoutInflater", "createViewFromTag", "LayoutInflater.java", 1018),
            StackTraceElement("android.view.LayoutInflater", "createViewFromTag", "LayoutInflater.java", 973),
            StackTraceElement("android.view.LayoutInflater", "rInflate", "LayoutInflater.java", 1135),
            StackTraceElement("android.view.LayoutInflater", "rInflateChildren", "LayoutInflater.java", 1096),
            StackTraceElement("android.view.LayoutInflater", "rInflate", "LayoutInflater.java", 1138),
            StackTraceElement("android.view.LayoutInflater", "rInflateChildren", "LayoutInflater.java", 1096),
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 694),
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 538),
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 485),
            StackTraceElement("iw", "i", "r8-map-id-207354fb24f02c642f25454f24127a05c2e558b5872d7afc81b9f66723d4c56f", 24),
            StackTraceElement("hv", "setContentView", "r8-map-id-207354fb24f02c642f25454f24127a05c2e558b5872d7afc81b9f66723d4c56f", 8),
            StackTraceElement("za.kilowatch.ultimatefilemanager.ui.SafPickerActivity", "onCreate", "r8-map-id-207354fb24f02c642f25454f24127a05c2e558b5872d7afc81b9f66723d4c56f", 25),
            StackTraceElement("android.app.Activity", "performCreate", "Activity.java", 8595),
            StackTraceElement("android.app.Activity", "performCreate", "Activity.java", 8573),
            StackTraceElement("android.app.Instrumentation", "callActivityOnCreate", "Instrumentation.java", 1456),
            StackTraceElement("android.app.ActivityThread", "performLaunchActivity", "ActivityThread.java", 3764),
            StackTraceElement("android.app.ActivityThread", "handleLaunchActivity", "ActivityThread.java", 3922),
            StackTraceElement("android.app.servertransaction.LaunchActivityItem", "execute", "LaunchActivityItem.java", 103),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "executeCallbacks", "TransactionExecutor.java", 139),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "execute", "TransactionExecutor.java", 96),
            StackTraceElement("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 2443),
            StackTraceElement("dw1", "handleMessage", "r8-map-id-207354fb24f02c642f25454f24127a05c2e558b5872d7afc81b9f66723d4c56f", 37),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 102),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 205),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 294),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 8177)
        )

        assertTrue(CrashReportManager.isTextInputLayoutInflateStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesUnobfuscatedTextInputLayoutStall() {
        val topFrame = StackTraceElement(
            "com.google.android.material.textfield.IndicatorViewController",
            "<init>",
            "IndicatorViewController.java",
            104
        )
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("com.google.android.material.textfield.TextInputLayout", "<init>", "TextInputLayout.java", 350),
            StackTraceElement("android.view.LayoutInflater", "createViewFromTag", "LayoutInflater.java", 772),
            StackTraceElement("android.view.LayoutInflater", "rInflate", "LayoutInflater.java", 863),
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 515),
            StackTraceElement("za.kilowatch.ultimatefilemanager.ui.SafPickerActivity", "onCreate", "SafPickerActivity.kt", 132),
            StackTraceElement("android.app.Activity", "performCreate", "Activity.java", 7148),
            StackTraceElement("android.app.Instrumentation", "callActivityOnCreate", "Instrumentation.java", 1271),
            StackTraceElement("android.app.ActivityThread", "performLaunchActivity", "ActivityThread.java", 2893),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 6691)
        )

        assertTrue(CrashReportManager.isTextInputLayoutInflateStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsAppBusinessLogicExecution() {
        val topFrame = StackTraceElement(
            "com.google.android.material.textfield.TextInputLayout",
            "<init>",
            "TextInputLayout.java",
            350
        )
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 515),
            StackTraceElement("za.kilowatch.ultimatefilemanager.smb.SmbShareClient", "listFiles", "SmbShareClient.kt", 45),
            StackTraceElement("za.kilowatch.ultimatefilemanager.ui.SafPickerActivity", "onCreate", "SafPickerActivity.kt", 132),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 6691)
        )

        assertFalse(CrashReportManager.isTextInputLayoutInflateStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWhenBlockingPrimitivePresent() {
        val topFrame = StackTraceElement("java.lang.Object", "wait", "Object.java", 442)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("com.google.android.material.textfield.TextInputLayout", "<init>", "TextInputLayout.java", 350),
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 515),
            StackTraceElement("za.kilowatch.ultimatefilemanager.ui.SafPickerActivity", "onCreate", "SafPickerActivity.kt", 132),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 6691)
        )

        assertFalse(CrashReportManager.isTextInputLayoutInflateStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWithoutLayoutInflater() {
        val topFrame = StackTraceElement(
            "com.google.android.material.textfield.TextInputLayout",
            "<init>",
            "TextInputLayout.java",
            350
        )
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("za.kilowatch.ultimatefilemanager.ui.SafPickerActivity", "onCreate", "SafPickerActivity.kt", 132),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 6691)
        )

        assertFalse(CrashReportManager.isTextInputLayoutInflateStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWithoutTextInputLayout() {
        val topFrame = StackTraceElement("android.widget.EditText", "<init>", "EditText.java", 112)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 515),
            StackTraceElement("za.kilowatch.ultimatefilemanager.ui.SafPickerActivity", "onCreate", "SafPickerActivity.kt", 132),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 6691)
        )

        assertFalse(CrashReportManager.isTextInputLayoutInflateStall(topFrame, stackTrace))
    }
}
