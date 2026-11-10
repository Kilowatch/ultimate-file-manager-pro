package za.kilowatch.ultimatefilemanager.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatterFileSizeStallTest {

    @Test
    fun identifiesGoogleTvStreamerFormatterFileSizeStall() {
        val topFrame = StackTraceElement(
            "android.icu.text.PluralRules\$RuleList",
            "selectRule",
            "PluralRules.java",
            2011
        )
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.icu.text.PluralRules\$RuleList", "select", "PluralRules.java", 2023),
            StackTraceElement("android.icu.text.PluralRules", "select", "PluralRules.java", 2310),
            StackTraceElement("android.icu.impl.number.DecimalQuantity_AbstractBCD", "getStandardPlural", "DecimalQuantity_AbstractBCD.java", 256),
            StackTraceElement("android.icu.impl.number.RoundingUtils", "getPluralSafe", "RoundingUtils.java", 239),
            StackTraceElement("android.icu.impl.number.LongNameHandler", "processQuantity", "LongNameHandler.java", 1338),
            StackTraceElement("android.icu.impl.number.MutablePatternModifier", "processQuantity", "MutablePatternModifier.java", 282),
            StackTraceElement("android.icu.number.NumberFormatterImpl", "preProcessUnsafe", "NumberFormatterImpl.java", 121),
            StackTraceElement("android.icu.number.NumberFormatterImpl", "formatStatic", "NumberFormatterImpl.java", 67),
            StackTraceElement("android.icu.number.LocalizedNumberFormatter", "formatImpl", "LocalizedNumberFormatter.java", 149),
            StackTraceElement("android.icu.text.MeasureFormat", "formatMeasure", "MeasureFormat.java", 752),
            StackTraceElement("android.icu.text.MeasureFormat", "format", "MeasureFormat.java", 310),
            StackTraceElement("java.text.Format", "format", "Format.java", 159),
            StackTraceElement("android.text.format.Formatter", "formatMeasureShort", "Formatter.java", 154),
            StackTraceElement("android.text.format.Formatter", "formatRoundedBytesResult", "Formatter.java", 170),
            StackTraceElement("android.text.format.Formatter", "formatFileSize", "Formatter.java", 112),
            StackTraceElement("android.text.format.Formatter", "formatFileSize", "Formatter.java", 103),
            StackTraceElement("kd3", "b", "r8-map-id-207354fb24f02c642f25454f24127a05c2e558b5872d7afc81b9f66723d4c56f", 40),
            StackTraceElement("dz9", "invoke", "r8-map-id-207354fb24f02c642f25454f24127a05c2e558b5872d7afc81b9f66723d4c56f", 13),
            StackTraceElement("cd", "run", "r8-map-id-207354fb24f02c642f25454f24127a05c2e558b5872d7afc81b9f66723d4c56f", 56),
            StackTraceElement("android.os.Handler", "handleCallback", "Handler.java", 958),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 99),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 205),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 294),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 8177)
        )

        assertTrue(CrashReportManager.isFormatterFileSizeStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesUnobfuscatedFormatterFileSizeStall() {
        val topFrame = StackTraceElement(
            "android.text.format.Formatter",
            "formatFileSize",
            "Formatter.java",
            112
        )
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("za.kilowatch.ultimatefilemanager.network.NetworkFileAdapter", "onBindViewHolder", "NetworkFileAdapter.kt", 120),
            StackTraceElement("androidx.recyclerview.widget.RecyclerView\$Adapter", "onBindViewHolder", "RecyclerView.java", 7352),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 99),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 205),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 8177)
        )

        assertTrue(CrashReportManager.isFormatterFileSizeStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsAppBusinessLogicExecution() {
        val topFrame = StackTraceElement(
            "android.text.format.Formatter",
            "formatFileSize",
            "Formatter.java",
            112
        )
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("za.kilowatch.ultimatefilemanager.smb.SmbShareClient", "listFiles", "SmbShareClient.kt", 45),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 99),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 205),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 8177)
        )

        assertFalse(CrashReportManager.isFormatterFileSizeStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWhenBlockingPrimitivePresent() {
        val topFrame = StackTraceElement("java.lang.Object", "wait", "Object.java", 442)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.text.format.Formatter", "formatFileSize", "Formatter.java", 112),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 99),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 205),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 8177)
        )

        assertFalse(CrashReportManager.isFormatterFileSizeStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWithoutFormatterFrame() {
        val topFrame = StackTraceElement(
            "android.icu.text.PluralRules\$RuleList",
            "selectRule",
            "PluralRules.java",
            2011
        )
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 99),
            StackTraceElement("android.os.Looper", "loopOnce", "Looper.java", 205),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 8177)
        )

        assertFalse(CrashReportManager.isFormatterFileSizeStall(topFrame, stackTrace))
    }
}
