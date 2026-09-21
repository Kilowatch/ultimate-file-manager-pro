package za.kilowatch.ultimatefilemanager.support

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressIndicatorInflateStallTest {

    @Test
    fun identifiesDroidlogicLinearProgressIndicatorInflateStall() {
        val topFrame = StackTraceElement(
            "com.google.android.material.progressindicator.LinearProgressIndicator",
            "<init>",
            "LinearProgressIndicator.java",
            53
        )
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("com.google.android.material.progressindicator.LinearProgressIndicator", "<init>", "LinearProgressIndicator.java", 80),
            StackTraceElement("java.lang.reflect.Constructor", "newInstance0", "Native Method", -2),
            StackTraceElement("java.lang.reflect.Constructor", "newInstance", "Constructor.java", 343),
            StackTraceElement("android.view.LayoutInflater", "createView", "LayoutInflater.java", 647),
            StackTraceElement("android.view.LayoutInflater", "createViewFromTag", "LayoutInflater.java", 790),
            StackTraceElement("android.view.LayoutInflater", "rInflate", "LayoutInflater.java", 863),
            StackTraceElement("android.view.LayoutInflater", "rInflateChildren", "LayoutInflater.java", 824),
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 515),
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 423),
            StackTraceElement("ev5", "j", "r8", 9),
            StackTraceElement("p19", "k", "r8", 57),
            StackTraceElement("vs7", "b", "r8", 8),
            StackTraceElement("mt7", "l", "r8", 906),
            StackTraceElement("mt7", "d", "r8", 6),
            StackTraceElement("vb5", "b", "r8", 58),
            StackTraceElement("androidx.recyclerview.widget.GridLayoutManager", "c1", "GridLayoutManager.java", 99),
            StackTraceElement("androidx.recyclerview.widget.LinearLayoutManager", "P0", "LinearLayoutManager.java", 49),
            StackTraceElement("androidx.recyclerview.widget.LinearLayoutManager", "h0", "LinearLayoutManager.java", 837),
            StackTraceElement("androidx.recyclerview.widget.GridLayoutManager", "h0", "GridLayoutManager.java", 45),
            StackTraceElement("androidx.recyclerview.widget.RecyclerView", "t", "RecyclerView.java", 74),
            StackTraceElement("androidx.recyclerview.widget.RecyclerView", "onMeasure", "RecyclerView.java", 64),
            StackTraceElement("android.view.View", "measure", "View.java", 23169),
            StackTraceElement("android.widget.LinearLayout", "measureHorizontal", "LinearLayout.java", 1168),
            StackTraceElement("android.widget.LinearLayout", "onMeasure", "LinearLayout.java", 706),
            StackTraceElement("android.view.View", "measure", "View.java", 23169),
            StackTraceElement("android.view.ViewGroup", "measureChildWithMargins", "ViewGroup.java", 6749),
            StackTraceElement("android.widget.FrameLayout", "onMeasure", "FrameLayout.java", 185),
            StackTraceElement("com.android.internal.policy.DecorView", "onMeasure", "DecorView.java", 716),
            StackTraceElement("android.view.ViewRootImpl", "performMeasure", "ViewRootImpl.java", 2732),
            StackTraceElement("android.view.ViewRootImpl", "measureHierarchy", "ViewRootImpl.java", 1576),
            StackTraceElement("android.view.ViewRootImpl", "performTraversals", "ViewRootImpl.java", 1864),
            StackTraceElement("android.view.ViewRootImpl", "doTraversal", "ViewRootImpl.java", 1464),
            StackTraceElement("android.view.Choreographer", "doCallbacks", "Choreographer.java", 761),
            StackTraceElement("android.view.Choreographer", "doFrame", "Choreographer.java", 696),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 99),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 193),
            StackTraceElement("android.app.ActivityThread", "main", "ActivityThread.java", 6669)
        )

        assertTrue(CrashReportManager.isProgressIndicatorInflateStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesConstructorNewInstanceTopFrame() {
        val topFrame = StackTraceElement("java.lang.reflect.Constructor", "newInstance0", "Native Method", -2)
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("java.lang.reflect.Constructor", "newInstance", "Constructor.java", 343),
            StackTraceElement("android.view.LayoutInflater", "createView", "LayoutInflater.java", 647),
            StackTraceElement("com.google.android.material.progressindicator.LinearProgressIndicator", "<init>", "LinearProgressIndicator.java", 53),
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 515),
            StackTraceElement("androidx.recyclerview.widget.LinearLayoutManager", "onLayoutChildren", "LinearLayoutManager.java", 837),
            StackTraceElement("androidx.recyclerview.widget.RecyclerView", "onMeasure", "RecyclerView.java", 64),
            StackTraceElement("android.view.ViewRootImpl", "performMeasure", "ViewRootImpl.java", 2732),
            StackTraceElement("android.view.Choreographer", "doFrame", "Choreographer.java", 696)
        )

        assertTrue(CrashReportManager.isProgressIndicatorInflateStall(topFrame, stackTrace))
    }

    @Test
    fun identifiesCircularProgressIndicatorInflateStall() {
        val topFrame = StackTraceElement(
            "com.google.android.material.progressindicator.CircularProgressIndicator",
            "<init>",
            "CircularProgressIndicator.java",
            40
        )
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.view.LayoutInflater", "createView", "LayoutInflater.java", 647),
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 515),
            StackTraceElement("androidx.recyclerview.widget.GridLayoutManager", "onLayoutChildren", "GridLayoutManager.java", 99),
            StackTraceElement("androidx.recyclerview.widget.RecyclerView", "onLayout", "RecyclerView.java", 100),
            StackTraceElement("android.view.ViewRootImpl", "performTraversals", "ViewRootImpl.java", 1864)
        )

        assertTrue(CrashReportManager.isProgressIndicatorInflateStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsAppBusinessLogicExecution() {
        val topFrame = StackTraceElement(
            "za.kilowatch.ultimatefilemanager.storage.StorageAdapter",
            "calculateStorageSizes",
            "StorageAdapter.kt",
            500
        )
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("com.google.android.material.progressindicator.LinearProgressIndicator", "<init>", "LinearProgressIndicator.java", 53),
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 515),
            StackTraceElement("androidx.recyclerview.widget.RecyclerView", "onMeasure", "RecyclerView.java", 64)
        )

        assertFalse(CrashReportManager.isProgressIndicatorInflateStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsWhenBlockingPrimitiveHeld() {
        val topFrame = StackTraceElement(
            "com.google.android.material.progressindicator.LinearProgressIndicator",
            "<init>",
            "LinearProgressIndicator.java",
            53
        )
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 515),
            StackTraceElement("androidx.recyclerview.widget.RecyclerView", "onMeasure", "RecyclerView.java", 64),
            StackTraceElement("java.lang.Object", "wait", "Object.java", 442)
        )

        assertFalse(CrashReportManager.isProgressIndicatorInflateStall(topFrame, stackTrace))
    }

    @Test
    fun rejectsUnrelatedViewInflation() {
        val topFrame = StackTraceElement(
            "android.widget.TextView",
            "<init>",
            "TextView.java",
            100
        )
        val stackTrace = arrayOf(
            topFrame,
            StackTraceElement("android.view.LayoutInflater", "inflate", "LayoutInflater.java", 515),
            StackTraceElement("androidx.recyclerview.widget.RecyclerView", "onMeasure", "RecyclerView.java", 64)
        )

        assertFalse(CrashReportManager.isProgressIndicatorInflateStall(topFrame, stackTrace))
    }
}
