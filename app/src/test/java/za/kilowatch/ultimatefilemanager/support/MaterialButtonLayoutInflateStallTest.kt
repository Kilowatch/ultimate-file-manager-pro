package za.kilowatch.ultimatefilemanager.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialButtonLayoutInflateStallTest {

    @Test
    fun identifiesHivendorTvBoxMaterialButtonInflateStall() {
        val topFrame = StackTraceElement(
            "com.google.android.material.button.MaterialButton",
            "u",
            "r8-map-id-207354fb24f02c642f25454f24127a05c2e558b5872d7afc81b9f66723d4c56f",
            1
        )
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("com.google.android.material.button.MaterialButton", "onTextChanged", "r8", 12),
            StackTraceElement("android.widget.TextView", "setText", "TextView.java", 5751),
            StackTraceElement("android.widget.TextView", "setText", "TextView.java", 5588),
            StackTraceElement("com.google.android.material.button.MaterialButton", "setText", "r8", 5),
            StackTraceElement("android.widget.TextView", "<init>", "TextView.java", 1527),
            StackTraceElement("android.widget.Button", "<init>", "Button.java", 166),
            StackTraceElement("android.widget.Button", "<init>", "Button.java", 141),
            StackTraceElement("jv", "<init>", "r8", 4),
            StackTraceElement("com.google.android.material.button.MaterialButton", "<init>", "r8", 15),
            StackTraceElement("com.google.android.material.button.MaterialButton", "<init>", "r8", 466),
            StackTraceElement("com.google.android.material.theme.MaterialComponentsViewInflater", "b", "r8", 3),
            StackTraceElement("iw", "onCreateView", "r8", 325),
            StackTraceElement("android.view.LayoutInflater", "createViewFromTag", "LayoutInflater.java", 772),
            StackTraceElement("android.view.LayoutInflater", "createViewFromTag", "LayoutInflater.java", 730),
            StackTraceElement("android.view.LayoutInflater", "rInflate", "LayoutInflater.java", 863),
            StackTraceElement("android.view.LayoutInflater", "rInflateChildren", "LayoutInflater.java", 824),
            StackTraceElement("android.view.LayoutInflater", "rInflate", "LayoutInflater.java", 866),
            StackTraceElement("android.view.LayoutInflater", "rInflateChildren", "LayoutInflater.java", 824),
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 515),
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 423),
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 374),
            StackTraceElement("android.view.View", "inflate", "View.java", 24354),
            StackTraceElement("cx6", "<init>", "r8", 9),
            StackTraceElement("za.kilowatch.ultimatefilemanager.viewer.UFMPlayerActivity", "u", "r8", 1476),
            StackTraceElement("za.kilowatch.ultimatefilemanager.viewer.UFMPlayerActivity", "onCreate", "r8", 635),
            StackTraceElement("android.app.Activity", "performCreate", "Activity.java", 7148),
            StackTraceElement("android.app.Activity", "performCreate", "Activity.java", 7139),
            StackTraceElement("android.app.Instrumentation", "callActivityOnCreate", "Instrumentation.java", 1271),
            StackTraceElement("android.app.ActivityThread", "performLaunchActivity", "ActivityThread.java", 2893),
            StackTraceElement("android.app.ActivityThread", "handleLaunchActivity", "ActivityThread.java", 3048),
            StackTraceElement("android.app.servertransaction.LaunchActivityItem", "execute", "LaunchActivityItem.java", 78),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "executeCallbacks", "TransactionExecutor.java", 108),
            StackTraceElement("android.app.servertransaction.TransactionExecutor", "execute", "TransactionExecutor.java", 68),
            StackTraceElement("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 1808),
            StackTraceElement("dw1", "handleMessage", "r8", 37),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 102),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 193),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 6691)
        )

        assertTrue(CrashReportManager.isMaterialButtonLayoutInflateStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesUnobfuscatedMaterialButtonLayoutStall() {
        val topFrame = StackTraceElement(
            "com.google.android.material.button.MaterialButton",
            "updateIconPosition",
            "MaterialButton.java",
            120
        )
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("com.google.android.material.button.MaterialButton", "onTextChanged", "MaterialButton.java", 85),
            StackTraceElement("android.widget.TextView", "setText", "TextView.java", 5751),
            StackTraceElement("android.widget.TextView", "<init>", "TextView.java", 1527),
            StackTraceElement("android.widget.Button", "<init>", "Button.java", 166),
            StackTraceElement("com.google.android.material.button.MaterialButton", "<init>", "MaterialButton.java", 240),
            StackTraceElement("android.view.LayoutInflater", "createViewFromTag", "LayoutInflater.java", 772),
            StackTraceElement("android.view.LayoutInflater", "rInflate", "LayoutInflater.java", 863),
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 515),
            StackTraceElement("za.kilowatch.ultimatefilemanager.ui.browser.FileBrowserActivity", "onCreate", "FileBrowserActivity.kt", 112),
            StackTraceElement("android.app.Activity", "performCreate", "Activity.java", 7148),
            StackTraceElement("android.app.Instrumentation", "callActivityOnCreate", "Instrumentation.java", 1271),
            StackTraceElement("android.app.ActivityThread", "performLaunchActivity", "ActivityThread.java", 2893),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 6691)
        )

        assertTrue(CrashReportManager.isMaterialButtonLayoutInflateStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsAppBusinessLogicExecution() {
        val topFrame = StackTraceElement(
            "com.google.android.material.button.MaterialButton",
            "onTextChanged",
            "MaterialButton.java",
            85
        )
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("com.google.android.material.button.MaterialButton", "<init>", "MaterialButton.java", 240),
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 515),
            StackTraceElement("za.kilowatch.ultimatefilemanager.smb.SmbShareClient", "listFiles", "SmbShareClient.kt", 45),
            StackTraceElement("za.kilowatch.ultimatefilemanager.ui.browser.FileBrowserActivity", "onCreate", "FileBrowserActivity.kt", 112),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 6691)
        )

        assertFalse(CrashReportManager.isMaterialButtonLayoutInflateStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWhenBlockingPrimitivePresent() {
        val topFrame = StackTraceElement("java.lang.Object", "wait", "Object.java", 442)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("com.google.android.material.button.MaterialButton", "onTextChanged", "MaterialButton.java", 85),
            StackTraceElement("com.google.android.material.button.MaterialButton", "<init>", "MaterialButton.java", 240),
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 515),
            StackTraceElement("za.kilowatch.ultimatefilemanager.viewer.UFMPlayerActivity", "onCreate", "UFMPlayerActivity.kt", 635),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 6691)
        )

        assertFalse(CrashReportManager.isMaterialButtonLayoutInflateStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWithoutLayoutInflater() {
        val topFrame = StackTraceElement(
            "com.google.android.material.button.MaterialButton",
            "onTextChanged",
            "MaterialButton.java",
            85
        )
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("za.kilowatch.ultimatefilemanager.viewer.UFMPlayerActivity", "onCreate", "UFMPlayerActivity.kt", 635),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 6691)
        )

        assertFalse(CrashReportManager.isMaterialButtonLayoutInflateStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWithoutMaterialButton() {
        val topFrame = StackTraceElement("android.widget.TextView", "setText", "TextView.java", 5751)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 515),
            StackTraceElement("za.kilowatch.ultimatefilemanager.viewer.UFMPlayerActivity", "onCreate", "UFMPlayerActivity.kt", 635),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 6691)
        )

        assertFalse(CrashReportManager.isMaterialButtonLayoutInflateStall(topFrame, stackTrace))
    }
}
