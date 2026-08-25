package za.kilowatch.ultimatefilemanager.storage

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.cancellation.CancellationException
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.network.*
import za.kilowatch.ultimatefilemanager.settings.ApkExtractPreferenceManager
import za.kilowatch.ultimatefilemanager.util.ApkMetadataExtractor
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import android.widget.TextView
import com.google.android.material.progressindicator.LinearProgressIndicator
import za.kilowatch.ultimatefilemanager.util.TransferConflictHelper
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.settings.SideBySideVideoPreferenceManager
import za.kilowatch.ultimatefilemanager.viewer.FileViewerRouter
import za.kilowatch.ultimatefilemanager.viewer.TwinWindowPlayerFragment
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import android.view.MotionEvent
import android.view.GestureDetector
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import kotlin.math.abs

/**
 * Twin Window (Dual-pane) Activity.
 * Orchestrates file operations between two panes, which can be Local or Network storage.
 */
class TwinWindowActivity : AppCompatActivity() {

    companion object {
        /** Optional: pre-seed pane 1 with a specific local path (e.g. from FileBrowserActivity) */
        const val EXTRA_TOP_LOCAL_PATH  = "twin_top_local_path"
        /** Optional: label for the local path pane (e.g. "Internal Storage") */
        const val EXTRA_TOP_LOCAL_LABEL = "twin_top_local_label"
        /** Optional: the current subfolder within the local mount (start here, but root stays correct) */
        const val EXTRA_TOP_LOCAL_INITIAL_PATH = "twin_top_local_initial_path"
        /** Optional: pre-seed pane 1 with a network share ID (e.g. from NetworkBrowserActivity) */
        const val EXTRA_TOP_SHARE_ID    = "twin_top_share_id"
        /** Optional: current subfolder path within the network share (so pane 1 opens at the right place) */
        const val EXTRA_TOP_SHARE_PATH  = "twin_top_share_path"
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private var pane1: Fragment? = null
    private var pane2: Fragment? = null

    // Tracks pane 1's original type for restoration after closing the player.
    // Pane 1 can be seeded as either local (default) or network (via intent extras).
    private var pane1IsNetwork: Boolean = false
    private var pane1ShareId: String? = null
    private var pane1SharePath: String = ""


    private var selectedPaneIndex: Int = 1 // 1 or 2

    private enum class SwipeState { SPLIT, TOP_MIN, BOTTOM_MIN }
    private var currentState = SwipeState.SPLIT
    private var currentTransferConnection: AutoCloseable? = null // raw TCP connection — close() kills SMB socket instantly

    private val storagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val isNetwork = data.getBooleanExtra("is_network", false)
            val isApps = data.getBooleanExtra("is_apps", false)
            
            if (isNetwork) {
                val shareId = data.getStringExtra("share_id") ?: return@registerForActivityResult
                // Remember pane 2 choice so it is restored on next launch
                if (selectedPaneIndex == 2) {
                    za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager
                        .savePane2Selection(this, "network", shareId = shareId)
                }
                // Track pane 1 type so restorePane(1) can restore correctly
                if (selectedPaneIndex == 1) {
                    pane1IsNetwork = true
                    pane1ShareId = shareId
                    pane1SharePath = intent.getStringExtra(EXTRA_TOP_SHARE_PATH) ?: ""
                }
                setupNetworkPane(selectedPaneIndex, shareId, requestInitialFocus = true)
            } else if (isApps) {
                if (selectedPaneIndex == 2) {
                    za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager
                        .savePane2Selection(this, "apps")
                }
                setupAppPane(selectedPaneIndex)
            } else {
                val path = data.getStringExtra("result_selected_local_path") 
                           ?: data.getStringExtra(FileBrowserActivity.EXTRA_MOUNT_PATH) 
                           ?: return@registerForActivityResult
                val label = data.getStringExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL) ?: "Storage"
                // Remember pane 2 choice so it is restored on next launch
                if (selectedPaneIndex == 2) {
                    za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager
                        .savePane2Selection(this, "local", path = path, label = label)
                }
                setupLocalPane(selectedPaneIndex, path, label, requestInitialFocus = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        val isTv = DeviceUtils.isTvDevice(this)
        val isVerticalSplit = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isVerticalSplit(this)

        val layoutId = if (isTv && isVerticalSplit) {
            R.layout.activity_twin_window_tv
        } else if (isTv && !isVerticalSplit) {
            R.layout.activity_twin_window_horizontal_tv
        } else if (isVerticalSplit) {
            R.layout.activity_twin_window_vertical
        } else {
            R.layout.activity_twin_window
        }
        setContentView(layoutId)

        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val isVerticalMode = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isVerticalSplit(this@TwinWindowActivity)
            val p1Id = if (isVerticalMode) R.id.paneLeft else R.id.paneTop
            val p2Id = if (isVerticalMode) R.id.paneRight else R.id.paneBottom
            
            val pane1 = findViewById<View>(p1Id)
            val pane2 = findViewById<View>(p2Id)
            val divider = findViewById<View>(R.id.divider)

            if (imeVisible && pane1 != null && pane2 != null && divider != null) {
                val focus = currentFocus
                if (focus != null) {
                    if (isViewInPane(focus, pane2)) {
                        pane1.visibility = View.GONE
                        divider.visibility = View.GONE
                        pane2.visibility = View.VISIBLE
                    } else if (isViewInPane(focus, pane1)) {
                        pane2.visibility = View.GONE
                        divider.visibility = View.GONE
                        pane1.visibility = View.VISIBLE
                    }
                }
            } else if (pane1 != null && pane2 != null && divider != null) {
                pane1.visibility = View.VISIBLE
                pane2.visibility = View.VISIBLE
                divider.visibility = View.VISIBLE
            }
            
            insets
        }
        // Initialize panes — pane 1 can be seeded by the caller, pane 2 restores last selection
        val internalPath = android.os.Environment.getExternalStorageDirectory().absolutePath
        val internalLabel = getString(R.string.storage_internal)

        val topLocalPath  = intent.getStringExtra(EXTRA_TOP_LOCAL_PATH)
        val topLocalLabel = intent.getStringExtra(EXTRA_TOP_LOCAL_LABEL) ?: internalLabel
        val topLocalInitialPath = intent.getStringExtra(EXTRA_TOP_LOCAL_INITIAL_PATH) ?: ""
        val topShareId    = intent.getStringExtra(EXTRA_TOP_SHARE_ID)
        val topSharePath  = intent.getStringExtra(EXTRA_TOP_SHARE_PATH) ?: ""

        when {
            topLocalPath != null -> {
                pane1IsNetwork = false
                val validPath = if (java.io.File(topLocalPath).exists()) topLocalPath else internalPath
                val validLabel = if (java.io.File(topLocalPath).exists()) topLocalLabel else internalLabel
                val validInit = if (topLocalInitialPath.isNotEmpty() && java.io.File(topLocalInitialPath).exists()) topLocalInitialPath else ""
                setupLocalPane(1, validPath, validLabel, validInit, requestInitialFocus = true)
            }
            topShareId   != null && isShareValid(topShareId) -> {
                pane1IsNetwork = true
                pane1ShareId = topShareId
                pane1SharePath = topSharePath
                setupNetworkPane(1, topShareId, topSharePath, requestInitialFocus = true)
            }
            else                 -> {
                pane1IsNetwork = false
                setupLocalPane(1, internalPath, internalLabel, requestInitialFocus = true)
            }
        }

        // Restore pane 2's last remembered storage selection.
        // Fallback to Internal Storage if the saved path no longer exists (SD card removed)
        // or if a network share ID was saved but is now unavailable — keeping the UX clean.
        val p2PrefsManager = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager
        val p2Type        = p2PrefsManager.getPane2Type(this)
        val p2Path        = p2PrefsManager.getPane2LocalPath(this)
        val p2Label       = p2PrefsManager.getPane2LocalLabel(this)
        val p2ShareId     = p2PrefsManager.getPane2ShareId(this)
        val p2InitialPath = p2PrefsManager.getPane2InitialPath(this) ?: ""
        val p2PathValid   = p2Path != null && java.io.File(p2Path).exists()
        val p2InitValid   = p2InitialPath.isNotEmpty() && java.io.File(p2InitialPath).exists()
        val p2ShareValid  = p2ShareId != null && isShareValid(p2ShareId)

        when {
            p2Type == "network" && p2ShareValid -> setupNetworkPane(2, p2ShareId!!, if (p2InitValid) p2InitialPath else "", requestInitialFocus = false)
            p2Type == "apps"                         -> setupAppPane(2)
            p2Type == "local" && p2PathValid          -> setupLocalPane(2, p2Path!!, p2Label ?: internalLabel, if (p2InitValid) p2InitialPath else "", requestInitialFocus = false)
            else                                     -> setupLocalPane(2, internalPath, internalLabel, "", requestInitialFocus = false)
        }

        if (!DeviceUtils.isTvDevice(this)) {
            supportFragmentManager.registerFragmentLifecycleCallbacks(object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: androidx.fragment.app.FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                    if (f is FileBrowserFragment || f is NetworkBrowserFragment || f is AppBrowserFragment) {
                        attachSwipeListener(f)
                    }
                }
            }, false)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val focusedFragment = getFocusedFragment()
                if (focusedFragment is TwinWindowPlayerFragment) {
                    focusedFragment.onClosePlayer?.invoke()
                    return
                }
                if (dispatchBackPressToFragment(focusedFragment)) {
                    return
                }

                // When pressing back from Twin Window and the focused pane is at root,
                // navigate back to the main Storage screen (Main Menu).
                val intent = Intent(this@TwinWindowActivity, StorageBrowserActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                finish()
            }
        })
    }

    override fun onStop() {
        super.onStop()
        // Snapshot pane 2's storage identity AND current subfolder so both are restored on next
        // launch. savePane2Selection() is normally called by storagePickerLauncher, but TV users
        // often keep the default Internal Storage and navigate sub-folders without ever touching
        // the Drive Picker — in that case p2Path is never saved and onCreate()'s else-fallback
        // ignores the saved p2InitialPath. Writing both values here fixes that gap.
        val prefs = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager
        val internalPath  = android.os.Environment.getExternalStorageDirectory().absolutePath
        val internalLabel = getString(R.string.storage_internal)
        when (val f = pane2) {
            is FileBrowserFragment -> {
                prefs.savePane2InitialPath(this, f.getCurrentDir().absolutePath)
                prefs.savePane2Selection(
                    this, "local",
                    path  = f.getRootPath().ifEmpty { internalPath },
                    label = f.getStorageLabel().ifEmpty { internalLabel }
                )
            }
            is NetworkBrowserFragment -> {
                val share = f.getShare()
                val rawPath = f.getCurrentPath().trimStart('/')
                val shareName = share.remotePath.trimStart('/')
                // For share-mode, remotePath is already in the repo — save path as-is (relative).
                // For server-mode, include share name prefix so first-navigation can extract it.
                val savedPath = if (share.isServerMode) {
                    val strippedPath = if (rawPath.startsWith("$shareName/")) {
                        rawPath.removePrefix("$shareName/")
                    } else if (rawPath == shareName) {
                        ""
                    } else {
                        rawPath
                    }
                    if (strippedPath.isEmpty()) shareName else "$shareName/$strippedPath"
                } else {
                    rawPath
                }
                android.util.Log.d("AfterVideo", "onStop savePane2: rawPath='$rawPath' savedPath='$savedPath' serverMode=${share.isServerMode} shareId=${share.id}")
                prefs.savePane2InitialPath(this, savedPath)
                prefs.savePane2Selection(this, "network", shareId = share.id)
            }
            else -> { /* AppBrowser — no sub-path to save */ }
        }
    }

    private fun isViewInPane(view: View, pane: View): Boolean {
        var current: android.view.ViewParent? = view.parent
        while (current != null) {
            if (current === pane) return true
            current = current.parent
        }
        return false
    }

    private fun isShareValid(shareId: String): Boolean {
        if (shareId.isEmpty()) return false
        val repoShare = za.kilowatch.ultimatefilemanager.network.NetworkShareRepository.getInstance(this).getById(shareId)
        if (repoShare != null) return true
        val onlineShare = za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository.getInstance(this).getById(shareId)
        if (onlineShare != null) return true
        val pairedDev = za.kilowatch.ultimatefilemanager.network.PairingManager.getInstance(this).getPairedDevice(shareId)
        if (pairedDev != null) return true
        return false
    }

    private fun setupLocalPane(index: Int, path: String, label: String, initialPath: String = "", requestInitialFocus: Boolean = false) {
        val internalPath = android.os.Environment.getExternalStorageDirectory().absolutePath
        val internalLabel = getString(R.string.storage_internal)

        val isSaf = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(path) || za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this, path)
        val validPath = if (path.isNotEmpty() && (java.io.File(path).exists() || isSaf)) path else internalPath
        val validLabel = if (path.isNotEmpty() && (java.io.File(path).exists() || isSaf)) label else internalLabel
        val isSafInit = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(initialPath) || za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this, initialPath)
        val validInit = if (initialPath.isNotEmpty() && (java.io.File(initialPath).exists() || isSafInit)) initialPath else ""

        // Both panes show their back button so the user can navigate/exit from either side.
        val fragment = FileBrowserFragment.newInstance(validPath, validLabel, isTwinWindow = true, hideBack = false, initialPath = validInit, requestInitialFocus = requestInitialFocus)
        fragment.onStoragePickerRequested = {
            selectedPaneIndex = index
            launchStoragePicker()
        }
        fragment.onActionRequested = { action -> onActionRequested(fragment, action) }
        // Apps-switch button only appears on the left (source) pane for local storage
        if (index == 1) {
            fragment.onSwitchToApps = { setupAppPane(1) }
        }
        fragment.onMediaFileSelected = { file ->
            handleMediaFileSelected(index, file.absolutePath, file.name, null, null)
        }
        fragment.onCloseTwinWindow = {
            val closePath = fragment.getCurrentDir().absolutePath
            android.util.Log.d("TPath", "Close twin window (local): index=$index mountPath='${fragment.getRootPath()}' currentDir='$closePath'")
            val intent = Intent(this, FileBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_MOUNT_PATH, fragment.getRootPath())
                putExtra(FileBrowserActivity.EXTRA_STORAGE_LABEL, fragment.getStorageLabel())
                putExtra(FileBrowserActivity.EXTRA_STORAGE_ID, fragment.getStorageId())
                putExtra(FileBrowserActivity.EXTRA_STORAGE_TYPE, fragment.getStorageType())
                putExtra(FileBrowserActivity.EXTRA_INITIAL_PATH, closePath)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            finish()
        }
        replacePane(index, fragment)
    }

    private fun setupNetworkPane(index: Int, shareId: String, initialPath: String = "", requestInitialFocus: Boolean = false) {
        val internalPath = android.os.Environment.getExternalStorageDirectory().absolutePath
        val internalLabel = getString(R.string.storage_internal)

        if (!isShareValid(shareId)) {
            setupLocalPane(index, internalPath, internalLabel, requestInitialFocus = requestInitialFocus)
            return
        }

        val fragment = NetworkBrowserFragment.newInstance(shareId, initialPath = initialPath, isTwinWindow = true, requestInitialFocus = requestInitialFocus)
        fragment.onInvalidShare = {
            setupLocalPane(index, internalPath, internalLabel, requestInitialFocus = requestInitialFocus)
        }
        fragment.onStoragePickerRequested = {
            selectedPaneIndex = index
            launchStoragePicker()
        }
        fragment.onActionRequested = { action -> onActionRequested(fragment, action) }
        fragment.onMediaFileSelected = { networkFile ->
            val liveShare = fragment.getShare()
            val remotePath = liveShare?.remotePath ?: ""
            handleMediaFileSelected(index, networkFile.path, networkFile.name, shareId, null, remotePath)
        }
        fragment.onCloseTwinWindow = {
            val liveShare = fragment.getShare()
            // In server-mode SMB, the fragment's share.remotePath holds the active SMB share name
            // (e.g. "/Share") and getCurrentPath() is relative to that share root (e.g. "/Camera").
            // NetworkBrowserActivity expects EXTRA_INITIAL_PATH to be the full path with the share
            // name as the first segment (e.g. "Share/Camera") so it can re-derive the share name
            // on first navigation. Without this reconstruction, the share name is dropped and the
            // subfolder name (e.g. "Camera") is mistakenly used as the SMB share name.
            // IMPORTANT: getCurrentPath() may already include the share name (e.g. "PrivateDL/MM")
            // when the twin window was initialised with a path containing the share name.
            // Strip any existing share name prefix before reconstructing, to avoid duplication.
            val rawPath = fragment.getCurrentPath().trimStart('/')
            val initialPath = if (liveShare.isServerMode) {
                val shareName = liveShare.remotePath.trimStart('/')
                val stripped = if (rawPath.startsWith("$shareName/")) {
                    rawPath.removePrefix("$shareName/")
                } else if (rawPath == shareName) {
                    ""
                } else {
                    rawPath
                }
                if (stripped.isEmpty()) shareName else "$shareName/$stripped"
            } else {
                rawPath
            }
            android.util.Log.d("TPath", "Close twin window (network): index=$index share.id=${liveShare.id} serverMode=${liveShare.isServerMode} rawPath='$rawPath' initialPath='$initialPath'")
            val intent = Intent(this, NetworkBrowserActivity::class.java).apply {
                putExtra(NetworkBrowserActivity.EXTRA_SHARE_ID, liveShare.id)
                putExtra(NetworkBrowserActivity.EXTRA_STORAGE_LABEL, liveShare.name)
                putExtra(NetworkBrowserActivity.EXTRA_INITIAL_PATH, initialPath)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            finish()
        }
        replacePane(index, fragment)
    }

    private fun setupAppPane(index: Int) {
        val fragment = AppBrowserFragment.newInstance(isTwinWindow = true)
        fragment.onStoragePickerRequested = {
            selectedPaneIndex = index
            launchStoragePicker()
        }
        fragment.onActionRequested = { action -> onActionRequested(fragment, action) }
        // Back button restores the local file browser for this pane
        fragment.onNavigateBack = {
            val internalPath = android.os.Environment.getExternalStorageDirectory().absolutePath
            val internalLabel = getString(R.string.storage_internal)
            setupLocalPane(index, internalPath, internalLabel, requestInitialFocus = true)
        }
        replacePane(index, fragment)
    }


    private fun replacePane(index: Int, fragment: Fragment) {
        val isVerticalSplit = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isVerticalSplit(this)
        val paneId = if (index == 1) {
            if (isVerticalSplit) R.id.paneLeft else R.id.paneTop
        } else {
            if (isVerticalSplit) R.id.paneRight else R.id.paneBottom
        }

        if (index == 1) pane1 = fragment else pane2 = fragment

        supportFragmentManager.beginTransaction()
            .replace(paneId, fragment)
            .commit()
    }

    private fun handleMediaFileSelected(index: Int, filePath: String, fileName: String, shareId: String?, provider: String?, remotePath: String = "") {
        if (!SideBySideVideoPreferenceManager.isEnabled(this)) {
            if (shareId != null) {
                // Network file — route to UFMPlayerActivity instead of local FileViewerRouter
                val share = za.kilowatch.ultimatefilemanager.network.NetworkShareRepository.getInstance(this).getById(shareId)
                if (share != null) {
                    val currentPane = getPaneFragment(index)
                    val playlist = if (currentPane is za.kilowatch.ultimatefilemanager.network.NetworkBrowserFragment) {
                        currentPane.getSortedFiles().filter {
                            val x = it.name.substringAfterLast('.', "").lowercase()
                            FileViewerRouter.isAudio(x) || FileViewerRouter.isVideo(x)
                        }.map { it.path }
                    } else null

                    val intent = Intent(this, za.kilowatch.ultimatefilemanager.viewer.UFMPlayerActivity::class.java).apply {
                        putExtra("shareId", shareId)
                        putExtra(za.kilowatch.ultimatefilemanager.network.NetworkBrowserActivity.EXTRA_REMOTE_PATH, remotePath)
                        putExtra("shareHost", share.host)
                        putExtra("shareName", share.name)
                        putExtra("initialPath", filePath)
                        if (!playlist.isNullOrEmpty()) {
                            putStringArrayListExtra("playlist", ArrayList(playlist))
                        }
                    }
                    startActivity(intent)
                    return
                }
            }
            val file = java.io.File(filePath)
            if (file.exists()) {
                FileViewerRouter.openFile(this, file)
            }
            return
        }

        val otherIndex = if (index == 1) 2 else 1
        val otherFragment = getPaneFragment(otherIndex)
        var otherIsPlayingUnmuted = false
        if (otherFragment is TwinWindowPlayerFragment && otherFragment.hasPlayer) {
            if (otherFragment.isPlaying) {
                otherIsPlayingUnmuted = true
            }
        }

        if (otherIsPlayingUnmuted) {
            showAudioConflictDialog { muteNew ->
                replacePaneWithPlayer(index, filePath, fileName, shareId, provider, muteNew, remotePath)
            }
        } else {
            replacePaneWithPlayer(index, filePath, fileName, shareId, provider, false, remotePath)
        }
    }

    private fun showAudioConflictDialog(onResult: (muteNew: Boolean) -> Unit) {
        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)
        val layoutRes = if (isTv) R.layout.dialog_support_message_tv else R.layout.dialog_support_message
        val dialogView = layoutInflater.inflate(layoutRes, null)
        val imgIcon = dialogView.findViewById<android.widget.ImageView>(R.id.imgDialogIcon)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtDialogTitle)
        val txtMessage = dialogView.findViewById<TextView>(R.id.txtDialogMessage)
        val btnPositive = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogPositive)
        val btnNegative = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogNegative)

        imgIcon?.setImageResource(R.drawable.ic_audio)
        txtTitle?.setText(R.string.audio_conflict_title)
        txtMessage?.setText(R.string.audio_conflict_message)
        btnPositive?.setText(R.string.ok)
        btnNegative?.visibility = View.VISIBLE
        btnNegative?.setText(R.string.player_mute)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnPositive?.setOnClickListener {
            dialog.dismiss()
            onResult(false)
        }

        btnNegative?.setOnClickListener {
            dialog.dismiss()
            onResult(true)
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    private fun replacePaneWithPlayer(index: Int, filePath: String, fileName: String, shareId: String?, provider: String?, startMuted: Boolean, remotePath: String = "") {
        // Save the current pane's state before replacing it with the player,
        // so restorePane can correctly restore the pane after the player closes.
        val exitingFragment = getPaneFragment(index)
        val prefs = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager
        val internalPath = android.os.Environment.getExternalStorageDirectory().absolutePath
        if (exitingFragment is za.kilowatch.ultimatefilemanager.network.NetworkBrowserFragment) {
            val netFrag = exitingFragment
            val liveShare = netFrag.getShare()
            // Normalise the current path: strip any existing share name prefix first,
            // then reconstruct with the canonical share name. This avoids duplicating
            // the share name when getCurrentPath() already contains it (e.g. from the
            // twin window initial path like "PrivateDL/MM").
            val rawPath = netFrag.getCurrentPath().trimStart('/')
            val shareName = liveShare.remotePath.trimStart('/')
            // For server-mode, the path needs the share name prefix so the first-navigation
            // logic can extract it on restore (repo has remotePath=""). For share-mode,
            // remotePath is already set in the repo, so save the path as-is (relative).
            val canonPath = if (liveShare.isServerMode) {
                val strippedPath = if (rawPath.startsWith("$shareName/")) {
                    rawPath.removePrefix("$shareName/")
                } else if (rawPath == shareName) {
                    ""
                } else {
                    rawPath
                }
                if (strippedPath.isEmpty()) shareName else "$shareName/$strippedPath"
            } else {
                rawPath
            }
            if (index == 2) {
                android.util.Log.d("AfterVideo", "replacePaneWithPlayer(2): saving path='$canonPath' shareId=${liveShare.id} rawPath='$rawPath' serverMode=${liveShare.isServerMode}")
                prefs.savePane2InitialPath(this, canonPath)
                prefs.savePane2Selection(this, "network", shareId = liveShare.id)
            } else {
                pane1IsNetwork = true
                pane1ShareId = liveShare.id
                pane1SharePath = canonPath
                android.util.Log.d("AfterVideo", "replacePaneWithPlayer(1): saving path='$canonPath' shareId=${liveShare.id}")
            }
        } else if (exitingFragment is za.kilowatch.ultimatefilemanager.storage.FileBrowserFragment) {
            val localFrag = exitingFragment
            val localPath = localFrag.getCurrentDir().absolutePath
            if (index == 2) {
                prefs.savePane2InitialPath(this, localPath)
                prefs.savePane2Selection(this, "local", path = localFrag.getRootPath().ifEmpty { internalPath }, label = localFrag.getStorageLabel().ifEmpty { "Storage" })
            } else {
                // For pane 1 local, save the current path so restorePane(1) can restore it
                // Instead of always going to internalPath root
                pane1IsNetwork = false
                // Store the path for local pane 1 restore
                // We'll use pane1SharePath as a generic "restore path" regardless of type
                pane1SharePath = localPath
                android.util.Log.d("AfterVideo", "replacePaneWithPlayer(1-local): saving path='$localPath'")
            }
            android.util.Log.d("AfterVideo", "replacePaneWithPlayer($index-local): saving path='$localPath'")
        }
        val ext = fileName.substringAfterLast(".").lowercase()
        val isVideo = FileViewerRouter.isVideo(ext) || !FileViewerRouter.isAudio(ext)
        val fragment = TwinWindowPlayerFragment.newInstance(
            filePath = filePath,
            fileName = fileName,
            isVideo = isVideo,
            paneIndex = index,
            startMuted = startMuted,
            shareId = shareId,
            remotePath = remotePath
        )
        fragment.onClosePlayer = {
            android.util.Log.d("AfterVideo", "onClosePlayer called: index=$index shareId=$shareId remotePath=$remotePath pane1IsNetwork=$pane1IsNetwork pane1ShareId=$pane1ShareId")
            collapsePanes()
            restorePane(index)
        }
        fragment.onToggleFullscreen = { paneIdx, fullscreen ->
            if (fullscreen) {
                expandPane(paneIdx)
            } else {
                collapsePanes()
            }
        }
        replacePane(index, fragment)
    }

    private fun expandPane(paneIndex: Int) {
        val targetPercent = if (paneIndex == 1) 1f else 0f
        animateGuideline(targetPercent)
    }

    private fun collapsePanes() {
        animateGuideline(0.5f)
    }

    private fun animateGuideline(targetPercent: Float) {
        val guideline = findViewById<Guideline>(R.id.splitGuideline) ?: return
        val params = guideline.layoutParams as ConstraintLayout.LayoutParams
        val start = params.guidePercent
        val animator = android.animation.ValueAnimator.ofFloat(start, targetPercent)
        animator.duration = 300
        animator.addUpdateListener { anim ->
            val p = guideline.layoutParams as ConstraintLayout.LayoutParams
            p.guidePercent = anim.animatedValue as Float
            guideline.layoutParams = p
        }
        animator.start()
    }

    private fun restorePane(index: Int) {
        val prefs = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager
        val internalPath = android.os.Environment.getExternalStorageDirectory().absolutePath
        val internalLabel = getString(R.string.storage_internal)

        val p2Type = prefs.getPane2Type(this)
        val p2Path = prefs.getPane2LocalPath(this)
        val p2Label = prefs.getPane2LocalLabel(this)
        val p2ShareId = prefs.getPane2ShareId(this)
        val p2InitialPath = prefs.getPane2InitialPath(this) ?: ""
        val p2PathValid = p2Path != null && java.io.File(p2Path).exists()
        android.util.Log.d("AfterVideo", "restorePane: index=$index p2Type=$p2Type p2ShareId=$p2ShareId p2InitialPath=$p2InitialPath pane1IsNetwork=$pane1IsNetwork pane1ShareId=$pane1ShareId")

        if (index == 1) {
            // Restore pane 1 to its saved type and path
            if (pane1IsNetwork && pane1ShareId != null) {
                android.util.Log.d("AfterVideo", "restorePane(1): restoring network share id=$pane1ShareId path=$pane1SharePath")
                setupNetworkPane(1, pane1ShareId!!, pane1SharePath, requestInitialFocus = true)
            } else {
                val savedLocalPath = pane1SharePath
                if (savedLocalPath.isNotEmpty() && java.io.File(savedLocalPath).exists()) {
                    android.util.Log.d("AfterVideo", "restorePane(1): restoring local path=$savedLocalPath mount=$internalPath")
                    setupLocalPane(1, internalPath, internalLabel, initialPath = savedLocalPath, requestInitialFocus = true)
                } else {
                    android.util.Log.d("AfterVideo", "restorePane(1): restoring local storage root")
                    setupLocalPane(1, internalPath, internalLabel, requestInitialFocus = true)
                }
            }
        } else {
            android.util.Log.d("AfterVideo", "restorePane(2): type=$p2Type shareId=$p2ShareId initPath=$p2InitialPath")
            when {
                p2Type == "network" && p2ShareId != null -> setupNetworkPane(2, p2ShareId, p2InitialPath, requestInitialFocus = true)
                p2Type == "apps" -> setupAppPane(2)
                p2Type == "local" && p2PathValid -> setupLocalPane(2, p2Path!!, p2Label ?: internalLabel, p2InitialPath, requestInitialFocus = true)
                else -> setupLocalPane(2, internalPath, internalLabel, p2InitialPath, requestInitialFocus = true)
            }
        }
    }

    private fun getPaneFragment(index: Int): Fragment? {
        return if (index == 1) getPane1() else getPane2()
    }

    private fun attachSwipeListener(fragment: Fragment) {
        val header = fragment.view?.findViewById<View>(R.id.headerLayout) ?: return
        val isVerticalSplit = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isVerticalSplit(this)

        header.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f
            private var startY = 0f
            private val minSwipeDistance = 100f // Slightly more for better feel

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isVerticalSplit) {
                            val endX = event.rawX
                            val deltaX = endX - startX
                            if (abs(deltaX) > minSwipeDistance) {
                                if (deltaX > 0) onSwipeDown() else onSwipeUp() // onSwipeDown pushes guide to right (expands left), up pushes to left
                            }
                        } else {
                            val endY = event.rawY
                            val deltaY = endY - startY
                            if (abs(deltaY) > minSwipeDistance) {
                                if (deltaY > 0) onSwipeDown() else onSwipeUp()
                            }
                        }
                        v.performClick()
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun onSwipeDown() {
        val nextState = when (currentState) {
            SwipeState.SPLIT -> SwipeState.BOTTOM_MIN
            SwipeState.TOP_MIN -> SwipeState.SPLIT
            else -> currentState
        }
        applyState(nextState)
    }

    private fun onSwipeUp() {
        val nextState = when (currentState) {
            SwipeState.SPLIT -> SwipeState.TOP_MIN
            SwipeState.BOTTOM_MIN -> SwipeState.SPLIT
            else -> currentState
        }
        applyState(nextState)
    }

    private fun applyState(state: SwipeState) {
        if (state == currentState) return
        currentState = state
        val guideline = findViewById<Guideline>(R.id.splitGuideline) ?: return
        
        val percent = when (state) {
            SwipeState.SPLIT -> 0.5f
            SwipeState.TOP_MIN -> 0.1f // ~Toolbar height
            SwipeState.BOTTOM_MIN -> 0.9f // ~Toolbar height from bottom
        }
        
        // Animate guideline
        val params = guideline.layoutParams as ConstraintLayout.LayoutParams
        val start = params.guidePercent
        
        val animator = android.animation.ValueAnimator.ofFloat(start, percent)
        animator.duration = 300
        animator.addUpdateListener { anim ->
            val p = guideline.layoutParams as ConstraintLayout.LayoutParams
            p.guidePercent = anim.animatedValue as Float
            guideline.layoutParams = p
        }
        animator.start()
    }

    private fun launchStoragePicker() {
        val intent = Intent(this, StorageBrowserActivity::class.java).apply {
            putExtra(FileBrowserActivity.EXTRA_PICKER_MODE, true)
            putExtra(StorageBrowserActivity.EXTRA_DRIVE_PICKER, true)
        }
        storagePickerLauncher.launch(intent)
    }

    // --- Unified File Operations (Called by Fragments) ---

    fun onActionRequested(sourceFragment: Fragment, action: String) {
        val targetFragment = if (sourceFragment === getPane1()) getPane2() else getPane1()
        if (targetFragment == null) return

        val sourceFiles = getSelectedItems(sourceFragment)
        if (sourceFiles.isEmpty()) return

        when (action) {
            "copy" -> showConfirmDialog(sourceFiles, targetFragment, isMove = false)
            "move" -> showConfirmDialog(sourceFiles, targetFragment, isMove = true)
            "delete" -> showDeleteConfirm(sourceFiles, sourceFragment)
        }
    }

    private fun getSelectedItems(fragment: Fragment): List<Any> {
        return if (fragment is FileBrowserFragment) {
            fragment.getSelectedFiles()
        } else if (fragment is NetworkBrowserFragment) {
            fragment.getSelectedFiles()
        } else if (fragment is AppBrowserFragment) {
            fragment.getSelectedFiles()
        } else {
            emptyList()
        }
    }

    private fun showConfirmDialog(files: List<Any>, target: Fragment, isMove: Boolean) {
        val actionLabel = if (isMove) getString(R.string.action_move) else getString(R.string.action_copy)
        val destName = getDestName(target)
        val message = getString(R.string.actionlabel_filessize_items_to_destname, actionLabel, files.size, destName)
        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)

        val layoutRes = if (isTv) R.layout.dialog_support_message_tv else R.layout.dialog_support_message
        val dialogView = layoutInflater.inflate(layoutRes, null)
        val imgIcon = dialogView.findViewById<android.widget.ImageView>(R.id.imgDialogIcon)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtDialogTitle)
        val txtMessage = dialogView.findViewById<TextView>(R.id.txtDialogMessage)
        val btnPositive = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogPositive)
        val btnNegative = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogNegative)

        imgIcon?.setImageResource(if (isMove) R.drawable.ic_move else R.drawable.ic_copy)
        txtTitle?.text = actionLabel
        txtMessage?.text = message
        btnPositive?.text = actionLabel
        btnNegative?.visibility = View.VISIBLE
        btnNegative?.setText(R.string.cancel)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        btnPositive?.setOnClickListener {
            dialog.dismiss()
            performTransfer(files, target, isMove)
        }

        btnNegative?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    private fun getDestName(fragment: Fragment): String {
        return if (fragment is FileBrowserFragment) {
            fragment.getCurrentDir().name.ifEmpty { "Storage" }
        } else if (fragment is NetworkBrowserFragment) {
            fragment.getCurrentPath().substringAfterLast("/", getString(R.string.root))
        } else {
            getString(R.string.destination_1)
        }
    }

    private fun showDeleteConfirm(files: List<Any>, source: Fragment) {
        val srcShare = (source as? za.kilowatch.ultimatefilemanager.network.NetworkBrowserFragment)?.getShare()
        val shareId = srcShare?.id
        val hasProtected = files.any { item ->
            when (item) {
                is java.io.File -> {
                    za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.isOrContainsProtected(this, item.absolutePath)
                }
                is za.kilowatch.ultimatefilemanager.network.NetworkFile -> {
                    za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.isOrContainsProtected(this, item.path, shareId)
                }
                else -> false
            }
        }
        if (hasProtected) {
            val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)
            za.kilowatch.ultimatefilemanager.settings.ProtectedFilesManager.showProtectedDeleteDialog(this, isTv)
            return
        }

        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)
        val layoutRes = if (isTv) R.layout.dialog_file_delete_confirm_tv else R.layout.dialog_file_delete_confirm
        val dialogView = layoutInflater.inflate(layoutRes, null)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtTitle)
        val txtDeleteMessage = dialogView.findViewById<TextView>(R.id.txtDeleteMessage)
        val btnDeleteConfirm = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDeleteConfirm)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        txtTitle?.setText(R.string.action_delete)
        txtDeleteMessage?.text = getString(R.string.delete_message_files, files.size)
        btnDeleteConfirm?.setText(R.string.delete_confirm)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        btnDeleteConfirm?.setOnClickListener {
            dialog.dismiss()
            performDelete(files, source)
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    private fun performDelete(items: List<Any>, source: Fragment) {
        val folderName = if (items.size == 1 && items[0] is java.io.File && (items[0] as java.io.File).isDirectory) (items[0] as java.io.File).name else ""
        val srcShare = (source as? za.kilowatch.ultimatefilemanager.network.NetworkBrowserFragment)?.getShare()
        val isNetwork = srcShare != null
        
        val isIndexed = if (isNetwork) {
            false
        } else {
            val firstItem = items.firstOrNull()
            if (firstItem is java.io.File) {
                val (storageId, _, _) = za.kilowatch.ultimatefilemanager.indexing.IndexingRepository.resolveStorageForPath(firstItem.absolutePath)
                UfmApplication.indexingRepository.isStorageFullyIndexed(storageId) && !UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)
            } else false
        }
        
        val progressDialog = za.kilowatch.ultimatefilemanager.indexing.IndexingUiHelper.showDeletionProgressDialog(this, folderName, isIndexing = isIndexed)

        lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0
            var failCount = 0
            for (item in items) {
                if (item is java.io.File) {
                    val path = item.absolutePath
                    val isSaf = item is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(path) ||
                                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@TwinWindowActivity, path)
                    val deleted = if (isSaf) {
                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.deleteRecursively(this@TwinWindowActivity, path)
                    } else if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(path)) {
                        za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(path)
                    } else if (item.isDirectory) {
                        item.deleteRecursively()
                    } else {
                        item.delete()
                    }
                    if (deleted) {
                        UfmApplication.indexingRepository.deleteTreeFromIndex(path)
                        successCount++
                    } else {
                        failCount++
                    }
                } else if (item is za.kilowatch.ultimatefilemanager.network.NetworkFile) {
                    val netShare = (source as? za.kilowatch.ultimatefilemanager.network.NetworkBrowserFragment)?.getShare()
                    if (netShare != null) {
                        try {
                            if (item.isDirectory) {
                                za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.deleteNetworkDirRecursively(netShare, item.path)
                            } else {
                                when (netShare.type) {
                                    za.kilowatch.ultimatefilemanager.network.ShareType.SMB -> za.kilowatch.ultimatefilemanager.network.SmbShareClient.deleteFile(netShare, item.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.FTP -> za.kilowatch.ultimatefilemanager.network.FtpShareClient.deleteFile(netShare, item.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.TV  -> if (item.isDirectory) za.kilowatch.ultimatefilemanager.network.TvShareClient.deleteDir(netShare, item.path) else za.kilowatch.ultimatefilemanager.network.TvShareClient.deleteFile(netShare, item.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.SFTP, za.kilowatch.ultimatefilemanager.network.ShareType.SCP -> za.kilowatch.ultimatefilemanager.network.SshShareClient.delete(netShare, item.path, false)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.ONEDRIVE -> za.kilowatch.ultimatefilemanager.network.OnedriveShareClient.deleteFile(netShare, item.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.GOOGLE_DRIVE -> za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient.deleteFile(netShare, item.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.DROPBOX -> za.kilowatch.ultimatefilemanager.network.DropboxShareClient.deleteFile(netShare, item.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.AWS_S3, za.kilowatch.ultimatefilemanager.network.ShareType.IDRIVE_E2 -> za.kilowatch.ultimatefilemanager.network.S3ShareClient.deleteFile(netShare, item.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.WEBDAV -> if (item.isDirectory) za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteDir(netShare, item.path) else za.kilowatch.ultimatefilemanager.network.WebDavShareClient.deleteFile(netShare, item.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.NFS -> if (item.isDirectory) za.kilowatch.ultimatefilemanager.network.NfsShareClient.deleteDir(netShare, item.path) else za.kilowatch.ultimatefilemanager.network.NfsShareClient.deleteFile(netShare, item.path)
                                    za.kilowatch.ultimatefilemanager.network.ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                                }
                            }
                            successCount++
                        } catch (e: Exception) {
                            failCount++
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                refreshFragment(source)
                if (failCount == 0) {
                    showPremiumSnackbar(getString(R.string.delete_success, successCount))
                } else {
                    showPremiumSnackbar(getString(R.string.delete_completed_with_errors_failcount_failed, failCount))
                }
            }
        }
    }

    private fun performTransfer(files: List<Any>, target: Fragment, isMove: Boolean) {
        var isCancelled = false
        val targetIsLocal = target is FileBrowserFragment
        val targetIsDir = if (targetIsLocal) (target as FileBrowserFragment).getCurrentDir() else null
        val targetNetShare = if (!targetIsLocal) (target as NetworkBrowserFragment).getShare() else null
        val targetNetPath = if (!targetIsLocal) (target as NetworkBrowserFragment).getCurrentPath() else null
        val sourceFragment = if (target === getPane1()) getPane2() else getPane1()
        val srcShare = (sourceFragment as? NetworkBrowserFragment)?.getShare()
            ?: (files.firstOrNull() as? NetworkFile)?.let {
                val shareId = (sourceFragment as? NetworkBrowserFragment)?.arguments?.getString("share_id")
                    ?: za.kilowatch.ultimatefilemanager.network.NetworkClipboard.sourceShareId
                if (shareId.isNotEmpty()) {
                    za.kilowatch.ultimatefilemanager.network.NetworkShareRepository.getInstance(this@TwinWindowActivity).getById(shareId)
                } else null
            }

        val totalFiles = files.size
        val fileCounter = IntArray(1) { 0 }

        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this@TwinWindowActivity)
        val layoutRes = if (isTv) R.layout.dialog_transfer_progress_tv else R.layout.dialog_transfer_progress
        val dialogView = layoutInflater.inflate(layoutRes, null)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtProgressTitle)
        val txtFiles = dialogView.findViewById<TextView>(R.id.txtProgressFiles)
        val txtCurrentFile = dialogView.findViewById<TextView>(R.id.txtProgressCurrentFile)
        val txtSize = dialogView.findViewById<TextView>(R.id.txtProgressSize)
        val progressFile = dialogView.findViewById<LinearProgressIndicator>(R.id.progressFile)

        txtTitle.text = if (isMove) getString(R.string.moving_files) else getString(R.string.copying_files_1)
        txtFiles.text = getString(R.string.item_0_totalfiles, totalFiles)

        val dialog = MaterialAlertDialogBuilder(this@TwinWindowActivity, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ ->
                isCancelled = true
                runCatching { currentTransferConnection?.close() }
                currentTransferConnection = null
            }
            .create()

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val onProgress: (String, Long, Long, Int, Int) -> Unit = { fileName, copied, total, index, totalCount ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                runCatching {
                    txtFiles.text = getString(R.string.item_index_totalcount, fileCounter[0], totalFiles)
                    txtCurrentFile.text = fileName
                    if (total > 0) {
                        val percent = ((copied * 100L) / total).toInt().coerceIn(0, 100)
                        progressFile.isIndeterminate = false
                        progressFile.progress = percent
                        val copiedMb = copied / (1024 * 1024)
                        val totalMb = total / (1024 * 1024)
                        txtSize.text = getString(R.string.copiedmb_mb_totalmb_mb_percent, copiedMb.toString(), totalMb.toString(), percent)
                    } else {
                        progressFile.isIndeterminate = true
                        txtSize.setText(R.string.processing)
                    }
                }
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                    if (target is FileBrowserFragment) {
                        val destDir = target.getCurrentDir()
                        val storageId = target.getStorageId()
                        val storageType = target.getStorageType()
                        val applyToAllRef = booleanArrayOf(false)
                        var globalAction: za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction? = null
                        
                        val db = za.kilowatch.ultimatefilemanager.indexing.UfmIndexingDatabase.getInstance(this@TwinWindowActivity)
                        val dao = db.fileIndexDao()
                        val metadataExtractor = za.kilowatch.ultimatefilemanager.indexing.MetadataExtractor(this@TwinWindowActivity)
                        val pendingIndices = mutableListOf<za.kilowatch.ultimatefilemanager.indexing.FileIndex>()

                        suspend fun flushIndices() {
                            if (pendingIndices.isNotEmpty()) {
                                dao.insertAll(pendingIndices.toList())
                                pendingIndices.clear()
                            }
                        }

                        suspend fun processItem(item: Any, currentDestPath: String) {
                            if (isCancelled) throw kotlinx.coroutines.CancellationException()
                            val itemName = itemBaseName(item)
                            val actualItem = if (item is AppItem) File(item.sourceDir) else item
                            
                            val isDestSaf = za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(currentDestPath) ||
                                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@TwinWindowActivity, currentDestPath)
                            val isSrcSaf = actualItem is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                                           (actualItem is File && (za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(actualItem.absolutePath) ||
                                           za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@TwinWindowActivity, actualItem.absolutePath)))

                            val destBase = if (isDestSaf) {
                                za.kilowatch.ultimatefilemanager.storage.SafFile(
                                    currentDestPath,
                                    itemName,
                                    (actualItem as? File)?.isDirectory ?: (actualItem as? NetworkFile)?.isDirectory ?: false
                                )
                            } else if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(currentDestPath)) {
                                za.kilowatch.ultimatefilemanager.storage.ShizukuFile(currentDestPath, itemName, true)
                            } else {
                                File(currentDestPath, itemName)
                            }

                            if (actualItem is File) {
                                // ── AppItem special case ────────────────────────────────────────
                                // sourceDir points to the APK *directory* on modern Android
                                // (e.g. /data/app/com.foo~xyz/). We must copy ONLY base.apk from
                                // that directory and rename it to "{AppName}.apk" — never recurse
                                // into the directory, as that would expose base.apk / split_config.*
                                // with their raw system filenames.
                                if (item is AppItem) {
                                    val useXapk = item.splitSourceDirs.isNotEmpty() || item.hasObb
                                    val finalDest = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.uniqueLocalFile(File(currentDestPath), itemName, this@TwinWindowActivity)

                                    fileCounter[0]++
                                    try {
                                        if (!useXapk) {
                                            // ── Simple APK: copy base.apk → AppName.apk ──────────────
                                            val src = File(item.sourceDir)
                                            val apkFile = if (src.isDirectory) {
                                                src.listFiles()?.firstOrNull { it.name == "base.apk" }
                                                    ?: src.listFiles()?.firstOrNull { it.extension == "apk" }
                                            } else src

                                            if (apkFile != null) {
                                                val writtenFile = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.copyLocalToLocalAtomic(
                                                    apkFile, finalDest,
                                                    za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH
                                                ) { c, t -> onProgress(itemName, c, t, fileCounter[0], totalFiles) }
                                                if (storageId.isNotEmpty() && !UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                                                    pendingIndices.add(metadataExtractor.extractMetadata(writtenFile, storageId, storageType, za.kilowatch.ultimatefilemanager.indexing.MetadataExtractor.HashAlgorithm.NONE))
                                                    if (pendingIndices.size >= 50) flushIndices()
                                                }
                                            }
                                        } else {
                                            // ── XAPK: zip base.apk + splits + OBBs + manifest ────────
                                            withContext(Dispatchers.IO) {
                                                val outStream = if (isDestSaf) {
                                                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.createFile(this@TwinWindowActivity, currentDestPath, finalDest.name)
                                                    za.kilowatch.ultimatefilemanager.storage.SafTreeManager.openOutputStream(this@TwinWindowActivity, finalDest.absolutePath)
                                                        ?: throw java.io.IOException("Cannot open SAF output stream for ${finalDest.absolutePath}")
                                                } else {
                                                    java.io.FileOutputStream(finalDest)
                                                }
                                                java.util.zip.ZipOutputStream(outStream).use { zip ->
                                                    fun addEntry(src: File, entryName: String) {
                                                        if (!src.exists()) return
                                                        zip.putNextEntry(java.util.zip.ZipEntry(entryName))
                                                        java.io.FileInputStream(src).use { it.copyTo(zip) }
                                                        zip.closeEntry()
                                                    }

                                                    // base.apk
                                                    val srcDir = File(item.sourceDir)
                                                    val baseApk = if (srcDir.isDirectory)
                                                        srcDir.listFiles()?.firstOrNull { it.name == "base.apk" }
                                                    else srcDir
                                                    if (baseApk != null) addEntry(baseApk, "base.apk")

                                                    // split APKs
                                                    item.splitSourceDirs.forEachIndexed { i, path ->
                                                        val f = File(path)
                                                        if (f.exists()) addEntry(f, f.name.ifEmpty { "split_$i.apk" })
                                                    }

                                                    // OBB files
                                                    try {
                                                        val obbDir = File(android.os.Environment.getExternalStorageDirectory(), "Android/obb/${item.packageName}")
                                                        obbDir.listFiles()?.filter { it.isFile && it.extension.equals("obb", ignoreCase = true) }
                                                            ?.forEach { addEntry(it, "obb/${it.name}") }
                                                    } catch (_: Exception) {}

                                                    // manifest.json
                                                    val pkgInfo = runCatching { packageManager.getPackageInfo(item.packageName, 0) }.getOrNull()
                                                    val version = pkgInfo?.versionName ?: "1.0"
                                                    val manifest = """{"xapk_version":2,"package_name":"${item.packageName}","name":"${item.name}","version_name":"$version"}"""
                                                    zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                                                    zip.write(manifest.toByteArray())
                                                    zip.closeEntry()
                                                }
                                            }
                                            if (storageId.isNotEmpty() && !UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                                                pendingIndices.add(metadataExtractor.extractMetadata(finalDest, storageId, storageType, za.kilowatch.ultimatefilemanager.indexing.MetadataExtractor.HashAlgorithm.NONE))
                                                if (pendingIndices.size >= 50) flushIndices()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        if (isCancelled) throw CancellationException()
                                        za.kilowatch.ultimatefilemanager.util.GoRoLog.e("TwinWindow", "AppItem extract error: ${e.message}")
                                    }
                                    if (finalDest.exists() && ApkExtractPreferenceManager.isEnabled(this@TwinWindowActivity)) {
                                        val baseName = finalDest.nameWithoutExtension
                                        val parentDir = finalDest.parentFile
                                        if (parentDir != null) {
                                            val appInfo = ApkMetadataExtractor.extractAppInfo(this@TwinWindowActivity, item.packageName)
                                            if (appInfo != null) {
                                                if (ApkExtractPreferenceManager.isExtractIcon(this@TwinWindowActivity)) {
                                                    ApkMetadataExtractor.saveIcon(appInfo, parentDir, baseName)
                                                }
                                                val fields = ApkExtractPreferenceManager.getSelectedFields(this@TwinWindowActivity)
                                                if (fields.isNotEmpty()) {
                                                    ApkMetadataExtractor.saveMetadataJson(appInfo, parentDir, baseName, fields)
                                                }
                                            }
                                        }
                                    }
                                    return  // ← never fall through to the generic directory traversal
                                }
                                // ── Generic File handling (non-AppItem) ────────────────────────
                                if (actualItem.isDirectory) {
                                    val hasConflict = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.localFileExists(
                                        File(currentDestPath), itemName, this@TwinWindowActivity
                                    )

                                    var effectiveDest = destBase
                                    if (hasConflict) {
                                        val resolvedAction = globalAction ?: withContext(Dispatchers.Main) {
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                                this@TwinWindowActivity, itemName, true, -1L, applyToAllRef
                                            ).also { if (applyToAllRef[0]) globalAction = it }
                                        }
                                        when (resolvedAction) {
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.CANCEL -> {
                                                isCancelled = true
                                                throw CancellationException()
                                            }
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.SKIP -> return
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                                                effectiveDest = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.uniqueLocalFolder(
                                                    File(currentDestPath), itemName, this@TwinWindowActivity
                                                )
                                            }
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.OVERWRITE -> {
                                                effectiveDest = destBase
                                            }
                                        }
                                    }

                                    if (isDestSaf) {
                                        if (!za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(this@TwinWindowActivity, effectiveDest.absolutePath)) {
                                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.mkdir(this@TwinWindowActivity, currentDestPath, effectiveDest.name)
                                        }
                                    } else if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(effectiveDest.absolutePath)) {
                                        if (!za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.exists(effectiveDest.absolutePath)) {
                                            za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.mkdir(effectiveDest.absolutePath)
                                        }
                                    } else {
                                        effectiveDest.mkdirs()
                                    }

                                    // Index the new folder immediately
                                    if (storageId.isNotEmpty() && !UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                                        pendingIndices.add(metadataExtractor.extractMetadata(effectiveDest, storageId, storageType, za.kilowatch.ultimatefilemanager.indexing.MetadataExtractor.HashAlgorithm.NONE))
                                        if (pendingIndices.size >= 50) flushIndices()
                                    }
                                    val children = if (isSrcSaf) {
                                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.listFiles(this@TwinWindowActivity, actualItem.absolutePath)
                                    } else {
                                        actualItem.listFiles()?.toList()
                                    }
                                    if (children != null) {
                                        for (child in children) { 
                                            if (isCancelled) break
                                            processItem(child, effectiveDest.absolutePath) 
                                        }
                                    }
                                    if (isMove && !isCancelled && item !is AppItem) { 
                                        try { 
                                            if (isSrcSaf) {
                                                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.delete(this@TwinWindowActivity, actualItem.absolutePath)
                                            } else if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(actualItem.absolutePath)) {
                                                za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(actualItem.absolutePath)
                                            } else {
                                                actualItem.delete() 
                                            }
                                            za.kilowatch.ultimatefilemanager.UfmApplication.indexingRepository.deleteTreeFromIndex(actualItem.absolutePath)
                                        } catch (_: Exception) {} 
                                        FileTagsManager.onPathMoved(this@TwinWindowActivity, actualItem.absolutePath, effectiveDest.absolutePath)
                                    } else if (!isCancelled && item !is AppItem) {
                                        FileTagsManager.onPathCopied(this@TwinWindowActivity, actualItem.absolutePath, effectiveDest.absolutePath)
                                    }
                                } else {
                                    val hasConflict = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.localFileExists(
                                        File(currentDestPath), itemName, this@TwinWindowActivity
                                    )
                                    val resolvedAction = if (hasConflict) {
                                        val destSize = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.localFileSize(
                                            File(currentDestPath), itemName, this@TwinWindowActivity
                                        )
                                        globalAction ?: withContext(Dispatchers.Main) {
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                                this@TwinWindowActivity, itemName, false, destSize, applyToAllRef
                                            ).also { if (applyToAllRef[0]) globalAction = it }
                                        }
                                    } else za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH

                                    if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.CANCEL) {
                                        isCancelled = true
                                        throw CancellationException()
                                    }
                                    if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.SKIP) return

                                    val finalDest = if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH)
                                        za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.uniqueLocalFile(File(currentDestPath), itemName, this@TwinWindowActivity)
                                    else destBase

                                    fileCounter[0]++
                                    try {
                                        val writtenFile = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.copyLocalToLocalAtomic(actualItem, finalDest, resolvedAction) { c, t -> onProgress(itemName, c, t, fileCounter[0], totalFiles) }
                                        if (isMove && item !is AppItem) {
                                            if (isSrcSaf) {
                                                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.delete(this@TwinWindowActivity, actualItem.absolutePath)
                                            } else if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(actualItem.absolutePath)) {
                                                za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(actualItem.absolutePath)
                                            } else {
                                                actualItem.delete()
                                            }
                                            za.kilowatch.ultimatefilemanager.UfmApplication.indexingRepository.deleteTreeFromIndex(actualItem.absolutePath)
                                            FileTagsManager.onPathMoved(this@TwinWindowActivity, actualItem.absolutePath, finalDest.absolutePath)
                                        } else if (item !is AppItem) {
                                            FileTagsManager.onPathCopied(this@TwinWindowActivity, actualItem.absolutePath, finalDest.absolutePath)
                                        }
                                        
                                        // Index immediately
                                        if (storageId.isNotEmpty() && !UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                                            pendingIndices.add(metadataExtractor.extractMetadata(writtenFile, storageId, storageType, za.kilowatch.ultimatefilemanager.indexing.MetadataExtractor.HashAlgorithm.NONE))
                                            if (pendingIndices.size >= 50) flushIndices()
                                        }
                                    } catch (e: Exception) {
                                        if (isCancelled) throw CancellationException()
                                        za.kilowatch.ultimatefilemanager.util.GoRoLog.e("TwinWindow", "Error: ${e.message}")
                                    }
                                }
                            } else if (actualItem is NetworkFile && srcShare != null) {
                                if (actualItem.isDirectory) {
                                    val hasConflict = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.localFileExists(
                                        File(currentDestPath), itemName, this@TwinWindowActivity
                                    )

                                    var effectiveDest = destBase
                                    if (hasConflict) {
                                        val resolvedAction = globalAction ?: withContext(Dispatchers.Main) {
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                                this@TwinWindowActivity, itemName, true, -1L, applyToAllRef
                                            ).also { if (applyToAllRef[0]) globalAction = it }
                                        }
                                        when (resolvedAction) {
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.CANCEL -> {
                                                isCancelled = true
                                                throw CancellationException()
                                            }
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.SKIP -> return
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                                                effectiveDest = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.uniqueLocalFolder(
                                                    File(currentDestPath), itemName, this@TwinWindowActivity
                                                )
                                            }
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.OVERWRITE -> {
                                                effectiveDest = destBase
                                            }
                                        }
                                    }

                                    if (isDestSaf) {
                                        if (!za.kilowatch.ultimatefilemanager.storage.SafTreeManager.exists(this@TwinWindowActivity, effectiveDest.absolutePath)) {
                                            za.kilowatch.ultimatefilemanager.storage.SafTreeManager.mkdir(this@TwinWindowActivity, currentDestPath, effectiveDest.name)
                                        }
                                    } else if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(effectiveDest.absolutePath)) {
                                        if (!za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.exists(effectiveDest.absolutePath)) {
                                            za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.mkdir(effectiveDest.absolutePath)
                                        }
                                    } else {
                                        effectiveDest.mkdirs()
                                    }

                                    if (storageId.isNotEmpty() && !UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                                        pendingIndices.add(metadataExtractor.extractMetadata(effectiveDest, storageId, storageType, za.kilowatch.ultimatefilemanager.indexing.MetadataExtractor.HashAlgorithm.NONE))
                                        if (pendingIndices.size >= 50) flushIndices()
                                    }
                                    val children = when(srcShare.type) {
                                        ShareType.SMB -> SmbShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.FTP -> FtpShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.TV -> TvShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.ONEDRIVE -> OnedriveShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.DROPBOX -> DropboxShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.WEBDAV -> WebDavShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.NFS -> NfsShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.DLNA -> DlnaShareClient.listFiles(srcShare, actualItem.path)
                                    }
                                    for (child in children) {
                                        if (isCancelled) break
                                        processItem(child, effectiveDest.absolutePath) 
                                    }
                                    if (isMove && !isCancelled) {
                                        try { TransferConflictHelper.deleteNetworkDirRecursively(srcShare, actualItem.path) } catch (_: Exception) {}
                                        FileTagsManager.onPathMoved(this@TwinWindowActivity, actualItem.path, effectiveDest.absolutePath)
                                    } else {
                                        FileTagsManager.onPathCopied(this@TwinWindowActivity, actualItem.path, effectiveDest.absolutePath)
                                    }
                                } else {
                                    val hasConflict = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.localFileExists(
                                        File(currentDestPath), itemName, this@TwinWindowActivity
                                    )
                                    val resolvedAction = if (hasConflict) {
                                        val destSize = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.localFileSize(
                                            File(currentDestPath), itemName, this@TwinWindowActivity
                                        )
                                        globalAction ?: withContext(Dispatchers.Main) {
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                                this@TwinWindowActivity, itemName, false, destSize, applyToAllRef
                                            ).also { if (applyToAllRef[0]) globalAction = it }
                                        }
                                    } else za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.OVERWRITE

                                    if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.CANCEL) {
                                        isCancelled = true
                                        throw CancellationException()
                                    }
                                    if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.SKIP) return

                                    val finalDest = if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH)
                                        za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.uniqueLocalFile(File(currentDestPath), itemName, this@TwinWindowActivity)
                                    else destBase

                                    fileCounter[0]++
                                    try {
                                        val writtenDest = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.downloadNetworkToLocalAtomic(
                                            srcShare, actualItem, finalDest, resolvedAction,
                                            onProgress = { c, t -> onProgress(itemName, c, t, fileCounter[0], totalFiles) },
                                            onConnectionReady = { conn -> currentTransferConnection = conn }
                                        )
                                        currentTransferConnection = null

                                        if (storageId.isNotEmpty() && !UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                                            pendingIndices.add(metadataExtractor.extractMetadata(writtenDest, storageId, storageType, za.kilowatch.ultimatefilemanager.indexing.MetadataExtractor.HashAlgorithm.NONE))
                                            if (pendingIndices.size >= 50) flushIndices()
                                        }

                                        if (isMove) {
                                            when(srcShare.type) {
                                                ShareType.SMB -> SmbShareClient.deleteFile(srcShare, actualItem.path)
                                                ShareType.FTP -> FtpShareClient.deleteFile(srcShare, actualItem.path)
                                                ShareType.TV -> TvShareClient.deleteFile(srcShare, actualItem.path)
                                                ShareType.SFTP, ShareType.SCP -> SshShareClient.delete(srcShare, actualItem.path, actualItem.isDirectory)
                                                ShareType.ONEDRIVE -> OnedriveShareClient.deleteFile(srcShare, actualItem.path)
                                                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.deleteFile(srcShare, actualItem.path)
                                                ShareType.DROPBOX -> DropboxShareClient.deleteFile(srcShare, actualItem.path)
                                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.deleteFile(srcShare, actualItem.path)
                                                ShareType.WEBDAV -> WebDavShareClient.deleteFile(srcShare, actualItem.path)
                                                ShareType.NFS -> NfsShareClient.deleteFile(srcShare, actualItem.path)
                                                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                                            }
                                            FileTagsManager.onPathMoved(this@TwinWindowActivity, actualItem.path, writtenDest.absolutePath)
                                        } else {
                                            FileTagsManager.onPathCopied(this@TwinWindowActivity, actualItem.path, writtenDest.absolutePath)
                                        }
                                    } catch (e: Exception) {
                                        if (isCancelled) throw CancellationException()
                                        za.kilowatch.ultimatefilemanager.util.GoRoLog.e("TwinWindow", "Error: ${e.message}")
                                    }
                                }
                            }
                        }

                        for (it in files) { 
                            coroutineContext.ensureActive()
                            processItem(it, destDir.absolutePath) 
                        }
                        flushIndices()

                    } else if (target is NetworkBrowserFragment) {
                        val dstShare = target.getShare()
                        val rawPath = target.getCurrentPath()
                        val dstPath = if (dstShare.type == ShareType.TV) rawPath else rawPath.removePrefix(dstShare.docIdPrefix).removePrefix("/")
                        val applyToAllRef = booleanArrayOf(false)
                        var globalAction: za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction? = null

                        suspend fun processItemNet(item: Any, currentDestPath: String) {
                            if (isCancelled) throw CancellationException()
                            val itemName = itemBaseName(item)
                            val actualItem = if (item is AppItem) File(item.sourceDir) else item
                            val targetPath = if (currentDestPath.isEmpty() || currentDestPath == "/") itemName else "${currentDestPath.trimEnd('/')}/$itemName"

                            if (actualItem is File) {
                                val isSrcSaf = actualItem is za.kilowatch.ultimatefilemanager.storage.SafFile ||
                                               za.kilowatch.ultimatefilemanager.storage.SafTreeManager.isSafPath(actualItem.absolutePath) ||
                                               za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@TwinWindowActivity, actualItem.absolutePath)

                                if (actualItem.isDirectory) {
                                    val conflictData = try {
                                        val list = when(dstShare.type) {
                                            ShareType.SMB -> SmbShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.FTP -> FtpShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.TV -> TvShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.ONEDRIVE -> OnedriveShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.DROPBOX -> DropboxShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.WEBDAV -> WebDavShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.NFS -> NfsShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.DLNA -> DlnaShareClient.listFiles(dstShare, currentDestPath)
                                        }
                                        Pair(za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.networkFileExists(itemName, list), list)
                                    } catch(_: Exception) { Pair(false, emptyList<NetworkFile>()) }

                                    val hasConflict = conflictData.first
                                    val destChildren = conflictData.second

                                    var effectiveDest = targetPath
                                    if (hasConflict) {
                                        val resolvedAction = globalAction ?: withContext(Dispatchers.Main) {
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                                this@TwinWindowActivity, itemName, true, -1L, applyToAllRef
                                            ).also { if (applyToAllRef[0]) globalAction = it }
                                        }
                                        when (resolvedAction) {
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.CANCEL -> {
                                                isCancelled = true
                                                throw CancellationException()
                                            }
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.SKIP -> return
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                                                effectiveDest = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.uniqueNetworkPath(currentDestPath, itemName, destChildren, isFolder = true)
                                            }
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.OVERWRITE -> {
                                                effectiveDest = targetPath
                                            }
                                        }
                                    }

                                    try {
                                        when(dstShare.type) {
                                            ShareType.SMB -> SmbShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.FTP -> FtpShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.TV -> TvShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.SFTP, ShareType.SCP -> SshShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.ONEDRIVE -> OnedriveShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.DROPBOX -> DropboxShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.WEBDAV -> WebDavShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.NFS -> NfsShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                                        }
                                    } catch(e: Exception) {
                                        za.kilowatch.ultimatefilemanager.util.GoRoLog.e("TwinWindow", "mkdir failed for effectiveDest=$effectiveDest: ${e.message}", e)
                                    }
                                    val children = if (isSrcSaf) {
                                        za.kilowatch.ultimatefilemanager.storage.SafTreeManager.listFiles(this@TwinWindowActivity, actualItem.absolutePath)
                                    } else {
                                        actualItem.listFiles()?.toList()
                                    }
                                    if (children != null) {
                                        for (child in children) { 
                                            if (isCancelled) break
                                            coroutineContext.ensureActive()
                                            processItemNet(child, effectiveDest) 
                                        }
                                    }
                                    if (isMove && !isCancelled && item !is AppItem) {
                                        try {
                                            if (isSrcSaf) {
                                                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.delete(this@TwinWindowActivity, actualItem.absolutePath)
                                            } else if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(actualItem.absolutePath)) {
                                                za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(actualItem.absolutePath)
                                            } else {
                                                actualItem.delete()
                                            }
                                        } catch (_: Exception) {}
                                        FileTagsManager.onPathMoved(this@TwinWindowActivity, actualItem.absolutePath, effectiveDest)
                                    } else if (!isCancelled && item !is AppItem) {
                                        FileTagsManager.onPathCopied(this@TwinWindowActivity, actualItem.absolutePath, effectiveDest)
                                    }
                                } else {
                                    // Conflict check against currentFiles (memory-only where possible, but here we just re-list or use an empty list for simplicity in common cases)
                                    val conflictData = try {
                                        val list = when(dstShare.type) {
                                            ShareType.SMB -> SmbShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.FTP -> FtpShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.TV -> TvShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.ONEDRIVE -> OnedriveShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.DROPBOX -> DropboxShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.WEBDAV -> WebDavShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.NFS -> NfsShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.DLNA -> DlnaShareClient.listFiles(dstShare, currentDestPath)
                                        }
                                        Pair(za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.networkFileExists(itemName, list), list)
                                    } catch(_: Exception) { Pair(false, emptyList<NetworkFile>()) }

                                    val hasConflict = conflictData.first
                                    val destChildren = conflictData.second

                                    val resolvedAction = if (hasConflict) {
                                        globalAction ?: withContext(Dispatchers.Main) {
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                                this@TwinWindowActivity, itemName, false, -1L, applyToAllRef
                                            ).also { if (applyToAllRef[0]) globalAction = it }
                                        }
                                    } else za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH

                                    if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.CANCEL) {
                                        isCancelled = true
                                        throw CancellationException()
                                    }
                                    if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.SKIP) return

                                    val finalPath = if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH)
                                        za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.uniqueNetworkPath(currentDestPath, itemName, destChildren)
                                    else targetPath

                                    fileCounter[0]++
                                    try {
                                        za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.uploadLocalToNetworkAtomic(
                                            actualItem, dstShare, finalPath,
                                            onProgress = { c, t -> onProgress(itemName, c, t, fileCounter[0], totalFiles) },
                                            onConnectionReady = { conn -> currentTransferConnection = conn }
                                        )
                                        currentTransferConnection = null
                                        if (isMove && item !is AppItem) {
                                            if (isSrcSaf) {
                                                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.delete(this@TwinWindowActivity, actualItem.absolutePath)
                                            } else if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(actualItem.absolutePath)) {
                                                za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.delete(actualItem.absolutePath)
                                            } else {
                                                actualItem.delete()
                                            }
                                            FileTagsManager.onPathMoved(this@TwinWindowActivity, actualItem.absolutePath, finalPath)
                                        } else if (item !is AppItem) {
                                            FileTagsManager.onPathCopied(this@TwinWindowActivity, actualItem.absolutePath, finalPath)
                                        }
                                    } catch (e: Exception) {
                                        if (isCancelled) throw CancellationException()
                                        za.kilowatch.ultimatefilemanager.util.GoRoLog.e("TwinWindow", "Error: ${e.message}")
                                    }
                                }
                            } else if (actualItem is NetworkFile && srcShare != null) {
                                if (actualItem.isDirectory) {
                                    val conflictData = try {
                                        val list = when(dstShare.type) {
                                            ShareType.SMB -> SmbShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.FTP -> FtpShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.TV -> TvShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.ONEDRIVE -> OnedriveShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.DROPBOX -> DropboxShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.WEBDAV -> WebDavShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.NFS -> NfsShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.DLNA -> DlnaShareClient.listFiles(dstShare, currentDestPath)
                                        }
                                        Pair(za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.networkFileExists(itemName, list), list)
                                    } catch(_: Exception) { Pair(false, emptyList<NetworkFile>()) }

                                    val hasConflict = conflictData.first
                                    val destChildren = conflictData.second

                                    var effectiveDest = targetPath
                                    if (hasConflict) {
                                        val resolvedAction = globalAction ?: withContext(Dispatchers.Main) {
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                                this@TwinWindowActivity, itemName, true, -1L, applyToAllRef
                                            ).also { if (applyToAllRef[0]) globalAction = it }
                                        }
                                        when (resolvedAction) {
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.CANCEL -> {
                                                isCancelled = true
                                                throw CancellationException()
                                            }
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.SKIP -> return
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                                                effectiveDest = za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.uniqueNetworkPath(currentDestPath, itemName, destChildren, isFolder = true)
                                            }
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.OVERWRITE -> {
                                                effectiveDest = targetPath
                                            }
                                        }
                                    }

                                    try {
                                        when(dstShare.type) {
                                            ShareType.SMB -> SmbShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.FTP -> FtpShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.TV -> TvShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.SFTP, ShareType.SCP -> SshShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.ONEDRIVE -> OnedriveShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.DROPBOX -> DropboxShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.WEBDAV -> WebDavShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.NFS -> NfsShareClient.mkdir(dstShare, effectiveDest)
                                            ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                                        }
                                    } catch(e: Exception) {
                                        za.kilowatch.ultimatefilemanager.util.GoRoLog.e("TwinWindow", "mkdir failed for effectiveDest=$effectiveDest: ${e.message}", e)
                                    }
                                    val children = when(srcShare.type) {
                                        ShareType.SMB -> SmbShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.FTP -> FtpShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.TV -> TvShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.ONEDRIVE -> OnedriveShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.DROPBOX -> DropboxShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.WEBDAV -> WebDavShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.NFS -> NfsShareClient.listFiles(srcShare, actualItem.path)
                                        ShareType.DLNA -> DlnaShareClient.listFiles(srcShare, actualItem.path)
                                    }
                                    for (child in children) { 
                                        if (isCancelled) break
                                        coroutineContext.ensureActive()
                                        processItemNet(child, effectiveDest) 
                                    }
                                    if (isMove && !isCancelled) {
                                        try { TransferConflictHelper.deleteNetworkDirRecursively(srcShare, actualItem.path) } catch (_: Exception) {}
                                        FileTagsManager.onPathMoved(this@TwinWindowActivity, actualItem.path, effectiveDest)
                                    } else if (!isCancelled) {
                                        FileTagsManager.onPathCopied(this@TwinWindowActivity, actualItem.path, effectiveDest)
                                    }
                                } else {
                                    val conflictData = try {
                                        val list = when(dstShare.type) {
                                            ShareType.SMB -> SmbShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.FTP -> FtpShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.TV -> TvShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.ONEDRIVE -> OnedriveShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.DROPBOX -> DropboxShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.WEBDAV -> WebDavShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.NFS -> NfsShareClient.listFiles(dstShare, currentDestPath)
                                            ShareType.DLNA -> DlnaShareClient.listFiles(dstShare, currentDestPath)
                                        }
                                        Pair(za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.networkFileExists(itemName, list), list)
                                    } catch(_: Exception) { Pair(false, emptyList<NetworkFile>()) }

                                    val hasConflict = conflictData.first
                                    val destChildren = conflictData.second

                                    val resolvedAction = if (hasConflict) {
                                        globalAction ?: withContext(Dispatchers.Main) {
                                            za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.showConflictDialog(
                                                this@TwinWindowActivity, itemName, false, -1L, applyToAllRef
                                            ).also { if (applyToAllRef[0]) globalAction = it }
                                        }
                                    } else za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH

                                    if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.CANCEL) {
                                        isCancelled = true
                                        throw kotlinx.coroutines.CancellationException()
                                    }
                                    if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.SKIP) return

                                    val finalPath = if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.KEEP_BOTH)
                                        za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.uniqueNetworkPath(currentDestPath, itemName, destChildren)
                                    else targetPath

                                    fileCounter[0]++
                                    try {
                                        val useTmp = dstShare.type != ShareType.AWS_S3 && dstShare.type != ShareType.IDRIVE_E2 && dstShare.type != ShareType.WEBDAV && dstShare.type != ShareType.NFS
                                        val tmpPath = if (useTmp) "$finalPath.ufm_tmp" else finalPath
                                        onProgress(itemName, 0, actualItem.size, fileCounter[0], totalFiles)
                                        za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.copyNetworkFileToNetwork(
                                            srcShare, actualItem, dstShare, tmpPath, onProgress, fileCounter[0], totalFiles,
                                            onConnectionReady = { conn -> currentTransferConnection = conn }
                                        )
                                        currentTransferConnection = null
                                        
                                        if (resolvedAction == za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.ConflictAction.OVERWRITE && useTmp) {
                                            try {
                                                when (dstShare.type) {
                                                    ShareType.SMB -> SmbShareClient.deleteFile(dstShare, finalPath)
                                                    ShareType.FTP -> FtpShareClient.deleteFile(dstShare, finalPath)
                                                    ShareType.TV -> TvShareClient.deleteFile(dstShare, finalPath)
                                                    ShareType.SFTP, ShareType.SCP -> SshShareClient.delete(dstShare, finalPath, false) // This is a file (tmpPath renamed to finalPath)
                                                    ShareType.ONEDRIVE -> OnedriveShareClient.deleteFile(dstShare, finalPath)
                                                    ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.deleteFile(dstShare, finalPath)
                                                    ShareType.DROPBOX -> DropboxShareClient.deleteFile(dstShare, finalPath)
                                                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.deleteFile(dstShare, finalPath)
                                                    ShareType.WEBDAV -> WebDavShareClient.deleteFile(dstShare, finalPath)
                                                    ShareType.NFS -> NfsShareClient.deleteFile(dstShare, finalPath)
                                                    ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                                                }
                                            } catch (_: Exception) {}
                                        }
                                        
                                        if (useTmp) {
                                            when (dstShare.type) {
                                            ShareType.SMB -> SmbShareClient.rename(dstShare, tmpPath, finalPath)
                                            ShareType.FTP -> FtpShareClient.rename(dstShare, tmpPath, finalPath)
                                            ShareType.TV -> TvShareClient.rename(dstShare, tmpPath, finalPath)
                                            ShareType.SFTP, ShareType.SCP -> SshShareClient.rename(dstShare, tmpPath, finalPath)
                                            ShareType.ONEDRIVE -> OnedriveShareClient.rename(dstShare, tmpPath, finalPath)
                                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.rename(dstShare, tmpPath, finalPath)
                                            ShareType.DROPBOX -> DropboxShareClient.rename(dstShare, tmpPath, finalPath)
                                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.rename(dstShare, tmpPath, finalPath)
                                            ShareType.WEBDAV -> WebDavShareClient.rename(dstShare, tmpPath, finalPath)
                                            ShareType.NFS -> NfsShareClient.rename(dstShare, tmpPath, finalPath)
                                            ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                                            }
                                        }
                                        
                                        if (isMove) {
                                            when (srcShare.type) {
                                                ShareType.SMB -> SmbShareClient.deleteFile(srcShare, actualItem.path)
                                                ShareType.FTP -> FtpShareClient.deleteFile(srcShare, actualItem.path)
                                                ShareType.TV -> TvShareClient.deleteFile(srcShare, actualItem.path)
                                                ShareType.SFTP, ShareType.SCP -> SshShareClient.delete(srcShare, actualItem.path, actualItem.isDirectory)
                                                ShareType.ONEDRIVE -> OnedriveShareClient.deleteFile(srcShare, actualItem.path)
                                                ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.deleteFile(srcShare, actualItem.path)
                                                ShareType.DROPBOX -> DropboxShareClient.deleteFile(srcShare, actualItem.path)
                                                ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.deleteFile(srcShare, actualItem.path)
                                                ShareType.WEBDAV -> WebDavShareClient.deleteFile(srcShare, actualItem.path)
                                                ShareType.NFS -> NfsShareClient.deleteFile(srcShare, actualItem.path)
                                                ShareType.DLNA -> throw UnsupportedOperationException("DLNA is read-only")
                                            }
                                            FileTagsManager.onPathMoved(this@TwinWindowActivity, actualItem.path, finalPath)
                                        } else {
                                            FileTagsManager.onPathCopied(this@TwinWindowActivity, actualItem.path, finalPath)
                                        }
                                    } catch (e: Exception) {
                                        if (isCancelled) throw CancellationException()
                                        za.kilowatch.ultimatefilemanager.util.GoRoLog.e("TwinWindow", "Error: ${e.message}")
                                    }
                                }
                            }
                        }
                        
                        for (it in files) {
                            coroutineContext.ensureActive()
                            processItemNet(it, dstPath)
                        }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        runCatching { dialog.dismiss() }
                    }
                    refreshFragment(getPane1())
                    refreshFragment(getPane2())
                     
                    // Clear selections
                    (getPane1() as? FileBrowserFragment)?.exitSelectionMode()
                    (getPane1() as? NetworkBrowserFragment)?.exitSelectionMode()
                    (getPane1() as? AppBrowserFragment)?.exitSelectionMode()
                    (getPane2() as? FileBrowserFragment)?.exitSelectionMode()
                    (getPane2() as? NetworkBrowserFragment)?.exitSelectionMode()
                    (getPane2() as? AppBrowserFragment)?.exitSelectionMode()

                    if (!isCancelled) {
                        (target as? FileBrowserFragment)?.getCurrentDir()?.let { destDir ->
                            za.kilowatch.ultimatefilemanager.sync.advanced.InstantSyncWatcher.notifyDirectoryChanged(this@TwinWindowActivity, destDir.absolutePath)
                        }
                        showPremiumSnackbar(if (isMove) getString(R.string.move_complete) else getString(R.string.copy_complete))
                    }
                }
            }
        }
    }

    private suspend fun countAllItems(items: List<Any>, share: NetworkShare? = null): Int {
        var count = 0
        for (item in items) {
            val actualItem = if (item is AppItem) File(item.sourceDir) else item
            if (actualItem is File) {
                count += if (actualItem.isDirectory) za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.countLocalFiles(actualItem) else 1
            } else if (actualItem is NetworkFile) {
                count += if (actualItem.isDirectory && share != null) {
                    za.kilowatch.ultimatefilemanager.util.TransferConflictHelper.countNetworkFiles(share, actualItem.path)
                } else {
                    1
                }
            }
        }
        return if (count == 0) items.size else count
    }

    private suspend fun syncFolderWithIndex(directory: File, storageId: String, storageType: String) {
        if (storageId.isEmpty()) return
        if (UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) return
        
        try {
            val db = za.kilowatch.ultimatefilemanager.indexing.UfmIndexingDatabase.getInstance(this@TwinWindowActivity)
            val dao = db.fileIndexDao()
            val metadataExtractor = za.kilowatch.ultimatefilemanager.indexing.MetadataExtractor(this@TwinWindowActivity)
            
            val actualFiles: List<File> = if (za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.canUseShizukuForPath(directory.absolutePath)) {
                za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper.listFiles(directory.absolutePath)
            } else if (za.kilowatch.ultimatefilemanager.storage.SafTreeManager.hasTreePermissionForPath(this@TwinWindowActivity, directory.absolutePath)) {
                za.kilowatch.ultimatefilemanager.storage.SafTreeManager.listFiles(this@TwinWindowActivity, directory.absolutePath)
            } else {
                directory.listFiles()?.toList() ?: emptyList<File>()
            }
            val actualFilePaths = actualFiles.map { it.absolutePath }.toSet()
            
            val fileIndices = actualFiles.map { 
                metadataExtractor.extractMetadata(it, storageId, storageType, za.kilowatch.ultimatefilemanager.indexing.MetadataExtractor.HashAlgorithm.NONE) 
            }
            if (fileIndices.isNotEmpty()) {
                dao.insertAll(fileIndices)
            }
            
            val existingInDb = dao.getFilesInFolder(directory.absolutePath)
            val stalePaths = existingInDb.map { it.path }.filter { it !in actualFilePaths }
            stalePaths.forEach { dao.deleteByPath(it) }
            
            za.kilowatch.ultimatefilemanager.util.GoRoLog.d("TwinWindow", "Synchronized folder to DB index: ${directory.absolutePath}")
        } catch (e: Exception) {
            za.kilowatch.ultimatefilemanager.util.GoRoLog.e("TwinWindow", "Error syncing folder: ${e.message}")
        }
    }

    private fun itemBaseName(item: Any): String = when (item) {
        is File        -> item.name
        is NetworkFile -> item.name
        is AppItem     -> {
            val useXapk = item.splitSourceDirs.isNotEmpty() || item.hasObb
            if (useXapk) "${item.name}.xapk" else "${item.name}.apk"
        }
        else -> ""
    }

    private fun refreshFragment(fragment: Fragment?) {
        if (fragment is FileBrowserFragment) fragment.refresh()
        else if (fragment is NetworkBrowserFragment) fragment.loadDirectory()
    }

    private fun getPane1(): Fragment? {
        val isVerticalSplit = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isVerticalSplit(this)
        val id = if (isVerticalSplit) R.id.paneLeft else R.id.paneTop
        return supportFragmentManager.findFragmentById(id) ?: pane1
    }

    private fun getPane2(): Fragment? {
        val isVerticalSplit = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isVerticalSplit(this)
        val id = if (isVerticalSplit) R.id.paneRight else R.id.paneBottom
        return supportFragmentManager.findFragmentById(id) ?: pane2
    }

    private fun switchActivePane() {
        val isVerticalSplit = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isVerticalSplit(this)
        val p1Id = if (isVerticalSplit) R.id.paneLeft else R.id.paneTop
        val p2Id = if (isVerticalSplit) R.id.paneRight else R.id.paneBottom
        val inPane1 = findViewById<View>(p1Id)?.hasFocus() == true
        val targetFragment = if (inPane1) getPane2() else getPane1()
        targetFragment?.view?.findViewById<View>(R.id.recyclerFiles)?.requestFocus()
            ?: findViewById<View>(if (inPane1) p2Id else p1Id)?.requestFocus()
    }

    private fun focusPane(paneNumber: Int) {
        val isVerticalSplit = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isVerticalSplit(this)
        val p1Id = if (isVerticalSplit) R.id.paneLeft else R.id.paneTop
        val p2Id = if (isVerticalSplit) R.id.paneRight else R.id.paneBottom
        val targetFragment = if (paneNumber == 1) getPane1() else getPane2()
        val targetContainerId = if (paneNumber == 1) p1Id else p2Id
        targetFragment?.view?.findViewById<View>(R.id.recyclerFiles)?.requestFocus()
            ?: findViewById<View>(targetContainerId)?.requestFocus()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (za.kilowatch.ultimatefilemanager.settings.KeyboardPreferenceManager.isMasterEnabled(this)) {
            val keyCode = event.keyCode
            val isDualPaneEnabled = za.kilowatch.ultimatefilemanager.settings.KeyboardPreferenceManager.isDualPaneSwitchEnabled(this)
            val isInputFocused = currentFocus is android.widget.EditText

            if (!isInputFocused && event.action == android.view.KeyEvent.ACTION_DOWN) {
                if (isDualPaneEnabled) {
                    if (keyCode == android.view.KeyEvent.KEYCODE_TAB || (event.isCtrlPressed && keyCode == android.view.KeyEvent.KEYCODE_W)) {
                        switchActivePane()
                        return true
                    }
                    if (keyCode == android.view.KeyEvent.KEYCODE_1 && !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed) {
                        focusPane(1)
                        return true
                    }
                    if (keyCode == android.view.KeyEvent.KEYCODE_2 && !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed) {
                        focusPane(2)
                        return true
                    }
                }
            }

            val focusedFrag = getFocusedFragment()
            if (focusedFrag is FileBrowserFragment && focusedFrag.handleKeyEvent(event)) {
                return true
            }
        }

        if (za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)) {
            val isVerticalSplit = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isVerticalSplit(this)
            if (!isVerticalSplit) {
                val isLeftOrRight = event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                if (isLeftOrRight && event.action == android.view.KeyEvent.ACTION_DOWN) {
                    val p1Id = R.id.paneTop
                    val p2Id = R.id.paneBottom
                    val focusedView = currentFocus
                    val inPane1 = findViewById<View>(p1Id)?.hasFocus() == true
                    val inPane2 = findViewById<View>(p2Id)?.hasFocus() == true
                    val activeFragment = if (inPane1) getPane1() else if (inPane2) getPane2() else null

                    if (activeFragment != null) {
                        val activeRecycler = activeFragment.view?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerFiles)
                        val isFileItem = activeRecycler != null && focusedView != null && activeRecycler.findContainingViewHolder(focusedView) != null

                        if (isFileItem) {
                            val pillsVisible = activeFragment.view?.findViewById<View>(R.id.layoutActionPillsScroll)?.visibility == View.VISIBLE
                            if (pillsVisible) {
                                val pillCopy = activeFragment.view?.findViewById<View>(R.id.btnPillCopy)
                                pillCopy?.requestFocus()
                                return true
                            } else {
                                val nextPaneFragment = if (inPane1) getPane2() else getPane1()
                                val nextRecyclerView = nextPaneFragment?.view?.findViewById<View>(R.id.recyclerFiles)
                                if (nextRecyclerView != null) {
                                    nextRecyclerView.requestFocus()
                                } else {
                                    findViewById<View>(p2Id)?.requestFocus()
                                }
                                if (inPane1) selectedPaneIndex = 2 else if (inPane2) selectedPaneIndex = 1
                                return true
                            }
                        }
                    }
                } else if (isLeftOrRight && event.action == android.view.KeyEvent.ACTION_UP) {
                    val focusedView = currentFocus
                    val inPane1 = findViewById<View>(R.id.paneTop)?.hasFocus() == true
                    val inPane2 = findViewById<View>(R.id.paneBottom)?.hasFocus() == true
                    val activeFragment = if (inPane1) getPane1() else if (inPane2) getPane2() else null
                    if (activeFragment != null) {
                        val activeRecycler = activeFragment.view?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerFiles)
                        val isFileItem = activeRecycler != null && focusedView != null && activeRecycler.findContainingViewHolder(focusedView) != null
                        if (isFileItem) return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun getFocusedFragment(): Fragment? {
        val isVerticalSplit = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isVerticalSplit(this)
        val p1Id = if (isVerticalSplit) R.id.paneLeft else R.id.paneTop
        val p2Id = if (isVerticalSplit) R.id.paneRight else R.id.paneBottom

        if (findViewById<View>(p1Id)?.hasFocus() == true) return getPane1()
        if (findViewById<View>(p2Id)?.hasFocus() == true) return getPane2()

        // Fallback: if neither has focus (e.g. focus is on a title), return pane 1
        return getPane1()
    }

    private fun dispatchBackPressToFragment(fragment: Fragment?): Boolean {
        return when (fragment) {
            is FileBrowserFragment -> fragment.handleBackPress()
            is NetworkBrowserFragment -> fragment.handleBackPress()
            is AppBrowserFragment -> fragment.handleBackPress()
            else -> false
        }
    }

    fun onPasteRequested(targetFragment: Fragment) {
        val hasNet = za.kilowatch.ultimatefilemanager.network.NetworkClipboard.hasItems()
        val hasLocal = za.kilowatch.ultimatefilemanager.storage.FileClipboard.hasItems()
        if (!hasNet && !hasLocal) return

        val clipboardItems = mutableListOf<Any>()
        val isExtract = (!hasNet && hasLocal && za.kilowatch.ultimatefilemanager.storage.FileClipboard.operation == za.kilowatch.ultimatefilemanager.storage.FileClipboard.Operation.EXTRACT)
        val isMove = if (hasNet) {
            za.kilowatch.ultimatefilemanager.network.NetworkClipboard.operation == za.kilowatch.ultimatefilemanager.network.NetworkClipboard.Operation.MOVE
        } else {
            za.kilowatch.ultimatefilemanager.storage.FileClipboard.operation == za.kilowatch.ultimatefilemanager.storage.FileClipboard.Operation.MOVE || isExtract
        }

        if (hasLocal) {
            clipboardItems.addAll(za.kilowatch.ultimatefilemanager.storage.FileClipboard.files)
        }
        if (hasNet) {
            clipboardItems.addAll(za.kilowatch.ultimatefilemanager.network.NetworkClipboard.files)
        }

        val actionLabel = if (isExtract) getString(R.string.extract_here) else if (isMove) getString(R.string.action_move) else getString(R.string.action_copy)
        val destName = getDestName(targetFragment)
        val message = getString(R.string.actionlabel_filessize_items_to_destname, actionLabel, clipboardItems.size, destName)

        val isTv = za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)
        val layoutRes = if (isTv) R.layout.dialog_support_message_tv else R.layout.dialog_support_message
        val dialogView = layoutInflater.inflate(layoutRes, null)
        val imgIcon = dialogView.findViewById<android.widget.ImageView>(R.id.imgDialogIcon)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtDialogTitle)
        val txtMessage = dialogView.findViewById<TextView>(R.id.txtDialogMessage)
        val btnPositive = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogPositive)
        val btnNegative = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDialogNegative)

        imgIcon?.setImageResource(if (isExtract) R.drawable.ic_extract else if (isMove) R.drawable.ic_move else R.drawable.ic_copy)
        txtTitle?.text = actionLabel
        txtMessage?.text = message
        btnPositive?.text = actionLabel
        btnNegative?.visibility = View.VISIBLE
        btnNegative?.setText(R.string.cancel)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()

        btnPositive?.setOnClickListener {
            dialog.dismiss()
            performTransfer(clipboardItems, targetFragment, isMove = isMove)
            // Clear clipboard
            za.kilowatch.ultimatefilemanager.storage.FileClipboard.clear()
            za.kilowatch.ultimatefilemanager.network.NetworkClipboard.clear()
            // Update paste fab on both fragments
            (getPane1() as? FileBrowserFragment)?.updatePasteFab()
            (getPane1() as? NetworkBrowserFragment)?.updatePasteFab()
            (getPane2() as? FileBrowserFragment)?.updatePasteFab()
            (getPane2() as? NetworkBrowserFragment)?.updatePasteFab()
        }

        btnNegative?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    private fun showPremiumSnackbar(message: String) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show()
    }

}

