package za.kilowatch.ultimatefilemanager.util

import android.app.Dialog
import android.content.Context
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import za.kilowatch.ultimatefilemanager.R

@RunWith(RobolectricTestRunner::class)
class DialogInputHelperTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testDoneActionTriggeredOnImeActionDone() {
        val editText = EditText(context)
        var doneCalled = false

        DialogInputHelper.setupDoneAction(editText) {
            doneCalled = true
        }

        // Simulate IME_ACTION_DONE
        editText.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertTrue("Callback should be invoked on IME_ACTION_DONE", doneCalled)
    }

    @Test
    fun testDoneActionTriggeredOnImeActionGo() {
        val editText = EditText(context)
        var doneCalled = false

        DialogInputHelper.setupDoneAction(editText) {
            doneCalled = true
        }

        // Simulate IME_ACTION_GO
        editText.onEditorAction(EditorInfo.IME_ACTION_GO)
        assertTrue("Callback should be invoked on IME_ACTION_GO", doneCalled)
    }

    @Test
    fun testDoneActionTriggeredOnImeActionSend() {
        val editText = EditText(context)
        var doneCalled = false

        DialogInputHelper.setupDoneAction(editText) {
            doneCalled = true
        }

        // Simulate IME_ACTION_SEND
        editText.onEditorAction(EditorInfo.IME_ACTION_SEND)
        assertTrue("Callback should be invoked on IME_ACTION_SEND", doneCalled)
    }

    @Test
    fun testDoneActionTriggeredOnEnterKeyDown() {
        val editText = EditText(context)
        var doneCalled = false

        DialogInputHelper.setupDoneAction(editText) {
            doneCalled = true
        }

        val enterDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
        val handled = editText.dispatchKeyEvent(enterDown)
        assertTrue("Enter key down should be handled", handled)
        assertTrue("Callback should be invoked on Enter key down", doneCalled)
    }

    @Test
    fun testDoneActionIgnoredOnOtherKeys() {
        val editText = EditText(context)
        var doneCalled = false

        DialogInputHelper.setupDoneAction(editText) {
            doneCalled = true
        }

        val otherKey = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
        editText.dispatchKeyEvent(otherKey)
        assertFalse("Callback should NOT be invoked on key A", doneCalled)

        val enterUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
        editText.dispatchKeyEvent(enterUp)
        assertFalse("Callback should NOT be invoked on Enter KEY UP", doneCalled)
    }

    @Test
    fun testSetupDialogInputSetsSoftInputMode() {
        val dialog = Dialog(context)
        val editText = EditText(context)
        var doneCalled = false

        DialogInputHelper.setupDialogInput(dialog, editText) {
            doneCalled = true
        }

        val softInputMode = dialog.window?.attributes?.softInputMode
        assertNotNull(softInputMode)
        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
            softInputMode?.and(WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE)
        )

        editText.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertTrue("onDone callback should be wired by setupDialogInput", doneCalled)
    }
}
