package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.ProgressBar
import android.widget.Toast
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.remote.PinDialogHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Premium Encrypted Vault (PIN-gated).
 * This initial implementation protects access with a PIN and
 * lets users curate a list of folders in the vault.
 */
class VaultActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerVault: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var btnAddFolder: com.google.android.material.button.MaterialButton
    private lateinit var adapter: VaultAdapter
    private var encryptedPrefs: SharedPreferences? = null
    private var legacyPrefs: SharedPreferences? = null
    private var clipboardClearHandler: android.os.Handler? = null
    private var clipboardClearRunnable: Runnable? = null

    private val entries = mutableListOf<VaultEntry>()
    private var isUnlocked = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(VaultFolderPickerActivity.EXTRA_SELECTED_PATH)
            if (!path.isNullOrBlank()) {
                encryptFolder(File(path))
            }
        }
    }

    companion object {
        private const val PREFS_NAME     = "vault_prefs"
        private const val KEY_PIN        = "vault_pin"
        private const val KEY_RECOVERY   = "vault_recovery_hash"
        private const val META_FILE      = "metadata.json"
        private const val RECOVERY_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        // ── PBKDF2 constants ──
        private const val PBKDF2_ALGORITHM  = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 260_000
        private const val PBKDF2_KEY_LENGTH = 256
        private const val SALT_SIZE = 16
        private const val PREFS_SECURE = "vault_prefs_secure"
        private const val TAG = "VaultActivity"
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // SEC-§8.12: Prevent vault PIN and contents from appearing in recent-apps
        // thumbnails or being captured by screen recording / screenshot tools.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isTv = DeviceUtils.isTvDevice(this)
        setContentView(if (isTv) R.layout.activity_vault_tv else R.layout.activity_vault)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // TV layout already has safe-zone padding built in; only apply system bars
            v.setPadding(
                systemBars.left, systemBars.top,
                systemBars.right, systemBars.bottom
            )
            insets
        }

        legacyPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Initialize EncryptedSharedPreferences synchronously — this does Keystore
        // IPC but is acceptable for a user-initiated vault open action.
        initSecurePrefs()
        toolbar = findViewById(R.id.toolbar)
        recyclerVault = findViewById(R.id.recyclerVault)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        btnAddFolder = findViewById(R.id.btnAddFolder)

        if (isTv) {
            // TV layout has its own back button; toolbar is a hidden stub
            val btnBack = findViewById<android.widget.ImageView>(R.id.btnBack)
            btnBack?.setOnClickListener { navigateBack() }

            // Back button: white icon unfocused, black icon on yellow focus
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            val yellowCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
            val glassCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_glass_white_15))
            val accentCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_accent))

            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
            // Set initial white tint (app:tint removed from XML since we use background selector)
            btnBack?.imageTintList = whiteCsl

            // Add Folder button: white text + glass bg unfocused, black text + yellow bg focused
            btnAddFolder.setOnFocusChangeListener { _, hasFocus ->
                btnAddFolder.setTextColor(if (hasFocus) getColor(R.color.tv_button_focused_yellow_text) else getColor(R.color.tv_text_primary))
                btnAddFolder.backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                btnAddFolder.iconTint = if (hasFocus) blackCsl else accentCsl
            }
        } else {
            // Mobile: wire up back button directly
            val btnBack = findViewById<android.widget.ImageView>(R.id.btnBack)
            btnBack?.setOnClickListener { navigateBack() }
        }

        adapter = VaultAdapter(
            onOpen = { entry ->
                startActivity(Intent(this, VaultBrowserActivity::class.java).apply {
                    putExtra(VaultBrowserActivity.EXTRA_ENTRY_ID, entry.id)
                })
            },
            onDecrypt = { entry ->
                decryptEntry(entry)
            },
            onRemove = { entry ->
                confirmRemove(entry)
            }
        )

        recyclerVault.layoutManager = LinearLayoutManager(this)
        recyclerVault.adapter = adapter

        btnAddFolder.setOnClickListener {
            if (isUnlocked) {
                promptAddFolder()
            }
        }

        lockUi()
        unlockVault()
    }

    override fun onDestroy() {
        clipboardClearRunnable?.let { clipboardClearHandler?.removeCallbacks(it) }
        clipboardClearRunnable = null
        clipboardClearHandler = null
        if (BuildConfig.DEBUG) Log.i(TAG, "LOW-4: Clipboard auto-clear cancelled (activity destroyed)")
        scope.cancel()
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // If a vault card is expanded, collapse it instead of exiting
        if (!adapter.collapseExpanded()) {
            navigateBack()
        }
    }

    private fun navigateBack() {
        if (isTaskRoot) {
            val intent = Intent(this, za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity::class.java)
            startActivity(intent)
        }
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_vault, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_change_pin -> {
                if (isUnlocked) {
                    changePinFlow()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun unlockVault() {
        val stored = readPinHash()
        if (stored == null) {
            showSetPinFlow(generateRecoveryCode = true) {
                showSnackbar(getString(R.string.vault_pin_saved))
                isUnlocked = true
                showUnlockedUi()
            }
            return
        }

        // ── Legacy SHA-256 detected → migration path ──
        if (isLegacyHash(stored)) {
            if (BuildConfig.DEBUG) Log.i(TAG, "Legacy SHA-256 PIN hash detected — migrating to PBKDF2")
            showMigrationPinDialog(stored)
            return
        }

        // ── PBKDF2 hash → normal verify ──
        PinDialogHelper.showPinDialog(
            context = this,
            title = getString(R.string.vault_unlock_title),
            subtitle = getString(R.string.vault_unlock_subtitle),
            confirmText = getString(R.string.vault_unlock_title),
            showChangePin = true,
            onCancel = { finish() },
            onChangePin = { changePinFlow() },
            showRecoveryCode = true,
            onRecoveryCode = { showRecoveryFlow() }
        ) { pin ->
            scope.launch {
                if (verifyPbkdf2(pin, stored)) {
                    isUnlocked = true
                    showUnlockedUi()
                } else {
                    showSnackbar(getString(R.string.vault_pin_invalid))
                    unlockVault()
                }
            }
        }
    }

    /** PIN dialog that verifies against the legacy SHA-256 hash,
     *  then silently migrates to PBKDF2 on success. */
    private fun showMigrationPinDialog(legacyHash: String) {
        PinDialogHelper.showPinDialog(
            context = this,
            title = getString(R.string.vault_unlock_title),
            subtitle = getString(R.string.vault_unlock_subtitle),
            confirmText = getString(R.string.vault_unlock_title),
            showChangePin = false,
            showRecoveryCode = true,
            onCancel = { finish() },
            onRecoveryCode = { showRecoveryFlow() }
        ) { pin ->
            // Inline SHA-256 verification for migration only
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val enteredHash = md.digest(pin.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            if (enteredHash == legacyHash) {
                scope.launch {
                    migratePin(pin)
                    isUnlocked = true
                    showUnlockedUi()
                }
            } else {
                showSnackbar(getString(R.string.vault_pin_invalid))
                unlockVault()
            }
        }
    }

    private fun changePinFlow() {
        val stored = readPinHash() ?: return

        PinDialogHelper.showPinDialog(
            context = this,
            title = getString(R.string.vault_verify_pin_title),
            subtitle = getString(R.string.vault_verify_pin_subtitle),
            confirmText = getString(R.string.vault_verify_pin_title),
            onCancel = {}
        ) { pin ->
            scope.launch {
                if (!verifyPbkdf2(pin, stored)) {
                    showSnackbar(getString(R.string.vault_pin_invalid))
                    changePinFlow()
                    return@launch
                }

                showSetPinFlow(generateRecoveryCode = false) {
                    showSnackbar(getString(R.string.vault_pin_saved))
                }
            }
        }
    }

    /**
     * Two-step PIN setup: enter → confirm → irrecoverable warning → save.
     * If [generateRecoveryCode] is true, generates a new 16-char recovery code,
     * hashes + stores it, and shows it once after saving.
     */
    private fun showSetPinFlow(generateRecoveryCode: Boolean, onConfirmed: (() -> Unit)? = null) {
        PinDialogHelper.showPinDialog(
            context = this,
            title = getString(R.string.vault_set_pin_title),
            subtitle = getString(R.string.vault_set_pin_subtitle),
            confirmText = getString(R.string.vault_set_pin_title),
            onCancel = null
        ) { pin ->
            // Step 2: confirm PIN
            PinDialogHelper.showPinDialog(
                context = this,
                title = getString(R.string.vault_confirm_pin_title),
                subtitle = getString(R.string.vault_confirm_pin_subtitle),
                confirmText = getString(R.string.vault_confirm_pin_title),
                onCancel = null
            ) { confirmedPin ->
                if (pin != confirmedPin) {
                    showSnackbar(getString(R.string.vault_pin_mismatch))
                    showSetPinFlow(generateRecoveryCode, onConfirmed)  // restart
                    return@showPinDialog
                }

                // Step 3: irrecoverable warning
                val bgColor   = getColor(R.color.tv_bg_gradient_end)
                val white     = getColor(R.color.tv_text_primary)
                val black     = getColor(R.color.tv_button_focused_yellow_text)
                val yellow    = getColor(R.color.tv_button_focused_yellow)
                val yellowCsl = android.content.res.ColorStateList.valueOf(yellow)
                val glassCsl  = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())

                val warningDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(
                    this,
                    com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
                )
                    .setTitle(getString(R.string.vault_pin_warning_title))
                    .setMessage(getString(
                        if (generateRecoveryCode) R.string.vault_pin_warning_message
                        else R.string.vault_pin_warning_message_change
                    ))
                    .setIcon(R.drawable.ic_lock)
                    .setPositiveButton(getString(R.string.vault_pin_warning_confirm), null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()

                warningDialog.show()
                warningDialog.window?.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(bgColor)
                )
                val titleView = warningDialog.findViewById<android.widget.TextView>(
                    com.google.android.material.R.id.alertTitle
                ) ?: warningDialog.findViewById(
                    resources.getIdentifier("alertTitle", "id", "android")
                )
                titleView?.setTextColor(white)
                warningDialog.findViewById<android.widget.TextView>(android.R.id.message)
                    ?.setTextColor(white)
                warningDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                    backgroundTintList = yellowCsl; setTextColor(black)
                    setOnClickListener {
                        warningDialog.dismiss()
                        scope.launch {
                            // Save PIN with PBKDF2 off main thread
                            savePinHash(pbkdf2(pin))
                            onConfirmed?.invoke()

                            if (generateRecoveryCode) {
                                showNewRecoveryCode(bgColor, white, black, yellow, yellowCsl, glassCsl)
                            }
                        }
                    }
                }
                warningDialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
                    backgroundTintList = glassCsl; setTextColor(white)
                }
            }
        }
    }

    /** Generates, stores, and displays the one-time recovery code. */
    private fun showNewRecoveryCode(
        bgColor: Int, white: Int, black: Int, yellow: Int,
        yellowCsl: android.content.res.ColorStateList,
        glassCsl: android.content.res.ColorStateList
    ) {
        scope.launch {
            // Generate 16-char alphanumeric code & store PBKDF2 hash on background thread
            val code = (1..16).map { RECOVERY_CHARS.random() }.joinToString("")
            val hash = pbkdf2(code)
            saveRecoveryHash(hash)

            // Build a premium one-time display dialog
            val ctx = this@VaultActivity
            val dialogView = layoutInflater.inflate(R.layout.dialog_recovery_code_display, null)
            val txtCode   = dialogView.findViewById<android.widget.TextView>(R.id.txtCode)
            val txtBody   = dialogView.findViewById<android.widget.TextView>(R.id.txtRecoveryBody)
            val btnCopy   = dialogView.findViewById<android.widget.Button>(R.id.btnCopyCode)

            txtCode.text = code
            txtBody.text = getString(R.string.vault_recovery_code_body)
            btnCopy.text = getString(R.string.vault_recovery_code_copy)
            btnCopy.backgroundTintList = glassCsl
            btnCopy.setTextColor(white)
            btnCopy.setOnFocusChangeListener { _, hasFocus ->
                btnCopy.backgroundTintList = if (hasFocus) yellowCsl else glassCsl
                btnCopy.setTextColor(if (hasFocus) black else white)
            }
            btnCopy.setOnClickListener {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    getString(R.string.recovery_code), code
                ))

                // Cancel previous timer if re-copying
                clipboardClearRunnable?.let { clipboardClearHandler?.removeCallbacks(it) }

                // Ensure handler is initialised
                if (clipboardClearHandler == null) {
                    clipboardClearHandler = android.os.Handler(mainLooper)
                }

                // Schedule auto-clear in 60s
                val runnable = Runnable {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                    if (BuildConfig.DEBUG) Log.i(TAG, "LOW-4: Clipboard auto-clear fired")
                }
                clipboardClearRunnable = runnable
                clipboardClearHandler?.postDelayed(runnable, 60_000L)

                if (BuildConfig.DEBUG) Log.i(TAG, "LOW-4: Clipboard auto-clear scheduled in 60s")
                showSnackbar(getString(R.string.vault_recovery_code_copied_autoclear))
            }

            val codeDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(
                ctx,
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
            )
                .setView(dialogView)
                .setPositiveButton(getString(R.string.vault_recovery_code_done), null)
                .setCancelable(false)
                .create()

            codeDialog.setOnDismissListener {
                clipboardClearRunnable?.let { clipboardClearHandler?.removeCallbacks(it) }
                clipboardClearRunnable = null
            }
            codeDialog.show()
            codeDialog.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(bgColor)
            )
            codeDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                backgroundTintList = yellowCsl; setTextColor(black)
            }
        }
    }

    /** Shows the recovery code entry dialog; on success calls showSetPinFlow(generateRecoveryCode=true). */
    private fun showRecoveryFlow() {
        val storedHash = readRecoveryHash() ?: run {
            showSnackbar(getString(R.string.no_recovery_code_is_set))
            return
        }

        val bgColor   = getColor(R.color.tv_bg_gradient_end)
        val white     = getColor(R.color.tv_text_primary)
        val black     = getColor(R.color.tv_button_focused_yellow_text)
        val yellow    = getColor(R.color.tv_button_focused_yellow)
        val yellowCsl = android.content.res.ColorStateList.valueOf(yellow)
        val glassCsl  = android.content.res.ColorStateList.valueOf(0x26FFFFFF.toInt())

        // Build entry dialog
        val entryView = layoutInflater.inflate(R.layout.dialog_recovery_code_entry, null)
        val etCode    = entryView.findViewById<android.widget.EditText>(R.id.etRecoveryCode)
        val txtCounter = entryView.findViewById<android.widget.TextView>(R.id.txtCounter)
        val txtErr    = entryView.findViewById<android.widget.TextView>(R.id.txtRecoveryError)

        etCode.filters = arrayOf(android.text.InputFilter.LengthFilter(16))
        etCode.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val len = s?.length ?: 0
                txtCounter.text = getString(R.string.len_16)
            }
        })

        val entryDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(
            this,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
        )
            .setTitle(getString(R.string.vault_recovery_entry_title))
            .setView(entryView)
            .setPositiveButton(getString(R.string.vault_recovery_confirm), null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        entryDialog.show()
        entryDialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(bgColor)
        )
        val titleView = entryDialog.findViewById<android.widget.TextView>(
            com.google.android.material.R.id.alertTitle
        ) ?: entryDialog.findViewById(resources.getIdentifier("alertTitle", "id", "android"))
        titleView?.setTextColor(white)

        entryDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
            backgroundTintList = yellowCsl; setTextColor(black)
            setOnClickListener {
                val entered = etCode.text.toString().trim()
                if (entered.length != 16) {
                    txtErr.setText(R.string.code_must_be_exactly_16)
                    txtErr.visibility = android.view.View.VISIBLE
                    return@setOnClickListener
                }

                scope.launch {
                    val isValid = if (isLegacyHash(storedHash)) {
                        // Legacy SHA-256 recovery code — verify then migrate
                        val md = java.security.MessageDigest.getInstance("SHA-256")
                        val enteredHash = md.digest(entered.toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it) }
                        if (enteredHash == storedHash) {
                            if (BuildConfig.DEBUG) Log.i(TAG, "Legacy SHA-256 recovery code hash detected — migrating to PBKDF2")
                            migrateRecoveryCode(entered)
                            true
                        } else false
                    } else {
                        // PBKDF2 recovery code — verify directly
                        verifyPbkdf2(entered, storedHash)
                    }

                    if (!isValid) {
                        txtErr.text = getString(R.string.vault_recovery_invalid)
                        txtErr.visibility = android.view.View.VISIBLE
                        return@launch
                    }
                    entryDialog.dismiss()
                    // Correct — let user set a new PIN (and generate new recovery code)
                    showSetPinFlow(generateRecoveryCode = true) {
                        isUnlocked = true
                        showUnlockedUi()
                    }
                }
            }
        }
        entryDialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
            backgroundTintList = glassCsl; setTextColor(white)
        }
    }

    /** PBKDF2-HMAC-SHA256 hash with a random 16-byte salt.
     *  Returns the hash string in format: iterations:salt_hex:hash_hex */
    private suspend fun pbkdf2(password: String): String = withContext(Dispatchers.Default) {
        val salt = ByteArray(SALT_SIZE).apply { SecureRandom().nextBytes(this) }
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH)
        val key = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val hashHex = key.joinToString("") { "%02x".format(it) }
        "$PBKDF2_ITERATIONS:$saltHex:$hashHex"
    }

    /** Verify a password against a PBKDF2 hash stored in iterations:salt_hex:hash_hex format. */
    private suspend fun verifyPbkdf2(password: String, stored: String): Boolean = withContext(Dispatchers.Default) {
        if (isLegacyHash(stored)) return@withContext false
        val parts = stored.split(":")
        if (parts.size != 3) return@withContext false
        val iterations = parts[0].toIntOrNull() ?: return@withContext false
        val salt = parts[1].hexToByteArray() ?: return@withContext false
        val expectedHash = parts[2]
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, PBKDF2_KEY_LENGTH)
        val key = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        val computedHash = key.joinToString("") { "%02x".format(it) }
        java.security.MessageDigest.isEqual(
            computedHash.toByteArray(Charsets.UTF_8),
            expectedHash.toByteArray(Charsets.UTF_8)
        )
    }

    /** Save a PIN hash to preferences — tries encrypted prefs first, falls back to legacy. */
    private fun savePinHash(hash: String) {
        if (encryptedPrefs != null) {
            encryptedPrefs?.edit()?.putString(KEY_PIN, hash)?.commit()
        } else {
            legacyPrefs?.edit()?.putString(KEY_PIN, hash)?.apply()
        }
    }

    /** Save a recovery code hash to preferences — tries encrypted prefs first, falls back to legacy. */
    private fun saveRecoveryHash(hash: String) {
        if (encryptedPrefs != null) {
            encryptedPrefs?.edit()?.putString(KEY_RECOVERY, hash)?.commit()
        } else {
            legacyPrefs?.edit()?.putString(KEY_RECOVERY, hash)?.apply()
        }
    }

    /** Encrypt a plaintext string for storage in metadata.json.
     *  Returns "enc:<base64>" on success, or the original plaintext on failure. */
    private fun encryptField(plain: String): String {
        return try {
            "enc:" + VaultCrypto.encryptString(plain)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "MED-4: encryptField failed", e)
            plain
        }
    }

    /** Decrypt a field from metadata.json.
     *  Fields prefixed with "enc:" are decrypted; plaintext fields are returned as-is. */
    private fun decryptField(encrypted: String): String {
        if (!encrypted.startsWith("enc:")) return encrypted
        return try {
            VaultCrypto.decryptString(encrypted.removePrefix("enc:"))
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "MED-4: decryptField failed — using fallback", e)
            encrypted
        }
    }

    /** Returns true if the stored hash is the old unsalted SHA-256 format (64 hex chars, no colon). */
    private fun isLegacyHash(stored: String?): Boolean {
        return stored != null && stored.length == 64 && ":" !in stored
    }

    /** Read the PIN hash — try encrypted prefs first, fall back to legacy plain prefs. */
    private fun readPinHash(): String? {
        encryptedPrefs?.getString(KEY_PIN, null)?.let { return it }
        val legacy = legacyPrefs?.getString(KEY_PIN, null)
        if (legacy != null && encryptedPrefs != null) {
            savePinHash(legacy)
            legacyPrefs?.edit()?.remove(KEY_PIN)?.apply()
        }
        return legacy
    }

    /** Read the recovery code hash — try encrypted prefs first, fall back to legacy plain prefs. */
    private fun readRecoveryHash(): String? {
        encryptedPrefs?.getString(KEY_RECOVERY, null)?.let { return it }
        val legacy = legacyPrefs?.getString(KEY_RECOVERY, null)
        if (legacy != null && encryptedPrefs != null) {
            saveRecoveryHash(legacy)
            legacyPrefs?.edit()?.remove(KEY_RECOVERY)?.apply()
        }
        return legacy
    }

    /** Initialise EncryptedSharedPreferences on a background thread.
     *  On failure, encryptedPrefs stays null and PIN ops fall back gracefully. */
    private fun initSecurePrefs() {
        try {
            val masterKey = MasterKey.Builder(applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            encryptedPrefs = EncryptedSharedPreferences.create(
                applicationContext,
                PREFS_SECURE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init EncryptedSharedPreferences — PIN ops will fail", e)
        }
    }

    /** Scan all existing vault entries and re-encrypt any that still have
     *  plaintext metadata fields. Creates a .bak backup before overwriting,
     *  verifies the encrypted file reads back correctly, then removes the backup. */
    private fun migrateMetadataEncryption() {
        val base = vaultBaseDir()
        if (!base.exists()) return
        var migratedCount = 0

        base.listFiles()?.forEach { entryDir ->
            if (!entryDir.isDirectory) return@forEach
            val metaFile = File(entryDir, META_FILE)
            if (!metaFile.exists()) return@forEach

            try {
                val json = JSONObject(metaFile.readText())

                // Skip if already encrypted
                val displayName = json.optString("displayName", "")
                val originalRoot = json.optString("originalRoot", "")
                if (displayName.startsWith("enc:") || originalRoot.startsWith("enc:")) return@forEach

                // Plaintext detected — back up
                val entryId = json.optString("id", "unknown")
                if (BuildConfig.DEBUG) Log.i(TAG, "MED-4: Re-encrypting metadata for entry $entryId…")

                val bakFile = File(entryDir, "$META_FILE.bak")
                metaFile.copyTo(bakFile, overwrite = true)

                // Encrypt fields
                json.put("displayName", encryptField(displayName))
                json.put("originalRoot", encryptField(originalRoot))
                val filesArr = json.getJSONArray("files")
                val encryptedFiles = JSONArray()
                for (i in 0 until filesArr.length()) {
                    encryptedFiles.put(encryptField(filesArr.getString(i)))
                }
                json.put("files", encryptedFiles)
                metaFile.writeText(json.toString())

                // Verify the encrypted version can be read back
                val verifyJson = JSONObject(metaFile.readText())
                val verifyName = verifyJson.optString("displayName", "")
                if (!verifyName.startsWith("enc:") || verifyName.length <= 4) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "MED-4: Verification failed — restoring from backup")
                    bakFile.copyTo(metaFile, overwrite = true)
                    return@forEach
                }

                bakFile.delete()
                if (BuildConfig.DEBUG) Log.i(TAG, "MED-4: Backup verified — deleted metadata.json.bak")
                migratedCount++

                // Re-load vault adapter to show decrypted names on main thread
                runOnUiThread { loadVault() }
            } catch (_: Exception) {
                // Failed to process this entry — skip it
            }
        }

        if (migratedCount > 0 && BuildConfig.DEBUG) {
            Log.i(TAG, "MED-4: Migration complete — $migratedCount entries re-encrypted")
        }
    }

    /** Migrate the user's PIN from legacy SHA-256 to PBKDF2.
     *  Saves to encrypted prefs, then deletes the old plain-text vault_prefs file. */
    private suspend fun migratePin(pin: String) {
        if (encryptedPrefs == null) return
        val hash = pbkdf2(pin)
        savePinHash(hash)
        // Delete old plain prefs file
        File(applicationContext.filesDir, "shared_prefs/${PREFS_NAME}.xml").delete()
        File(applicationContext.filesDir, "shared_prefs/${PREFS_NAME}.xml.bak")?.delete()
        if (BuildConfig.DEBUG) Log.i(TAG, "PIN migration complete — old vault_prefs deleted")
    }

    /** Migrate the recovery code hash from legacy SHA-256 to PBKDF2.
     *  Called from showRecoveryFlow() when the plaintext code is available. */
    private suspend fun migrateRecoveryCode(code: String) {
        if (encryptedPrefs == null) return
        saveRecoveryHash(pbkdf2(code))
        if (BuildConfig.DEBUG) Log.i(TAG, "Recovery code migration complete")
    }

    private fun lockUi() {
        recyclerVault.visibility = View.GONE
        layoutEmpty.visibility = View.GONE
        btnAddFolder.isEnabled = false
        adapter.submitList(emptyList())
    }

    private fun showUnlockedUi() {
        btnAddFolder.isEnabled = true
        loadVault()
        // One-time migration: re-encrypt any legacy plaintext metadata
        scope.launch {
            withContext(Dispatchers.IO) { migrateMetadataEncryption() }
        }
    }

    private fun loadVault() {
        if (!isUnlocked) return
        entries.clear()
        entries.addAll(readEntries())
        val sorted = entries.sortedBy { it.displayName.lowercase() }
        adapter.submitList(sorted)
        updateEmptyState(sorted.isEmpty())
    }

    private fun promptAddFolder() {
        val intent = Intent(this, VaultFolderPickerActivity::class.java)
        folderPicker.launch(intent)
    }

    private fun confirmRemove(entry: VaultEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vault_delete_title)
            .setMessage(getString(R.string.vault_delete_message))
            .setPositiveButton(R.string.vault_delete_confirm) { _, _ ->
                removeEntry(entry)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recyclerVault.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(findViewById(R.id.main), message, Snackbar.LENGTH_SHORT)
            .setBackgroundTint(getColor(R.color.ufm_surface_variant))
            .setTextColor(getColor(R.color.ufm_text_primary))
            .show()
    }

    private fun vaultBaseDir(): File = File(filesDir, "vault")

    private fun encryptFolder(root: File) {
        if (!root.exists() || !root.isDirectory) return
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val entryId = UUID.randomUUID().toString()
                    val entryDir = File(vaultBaseDir(), entryId)
                    entryDir.mkdirs()

                    // Filter out system files and hidden files
                    val files = root.walkTopDown()
                        .filter { it.isFile }
                        .filter { !isSystemFile(it) }
                        .filter { !isHiddenFile(it) }
                        .toList()
                    val relativeList = mutableListOf<String>()

                    files.forEach { file ->
                        val relative = file.relativeTo(root).path
                        val encryptedFile = File(entryDir, "$relative.enc")
                        VaultCrypto.encryptFile(file, encryptedFile)
                        relativeList.add(relative)
                        file.delete()
                    }

                    // Clean empty directories after encryption
                    root.walkBottomUp().forEach { dir ->
                        if (dir.isDirectory && dir.listFiles()?.isEmpty() == true) {
                            dir.delete()
                        }
                    }

                    val metadata = JSONObject().apply {
                        put("id", entryId)
                        put("displayName", encryptField(root.name))
                        put("originalRoot", encryptField(root.absolutePath))
                        put("files", JSONArray(relativeList.map { encryptField(it) }))
                    }
                    File(entryDir, META_FILE).writeText(metadata.toString())
                    true
                } catch (_: Exception) {
                    false
                }
            }

            if (!success) {
                showSnackbar(getString(R.string.vault_encryption_failed))
            }
            // Refresh the adapter directly — don't use loadVault() because its
            // isUnlocked guard may fail if the folder picker recreated the activity.
            // Reading entries from disk is safe (only shows what's on disk).
            val currentEntries = readEntries()
            if (currentEntries.isNotEmpty()) {
                val sorted = currentEntries.sortedBy { it.displayName.lowercase() }
                adapter.submitList(sorted)
                updateEmptyState(false)
                layoutEmpty.visibility = View.GONE
                recyclerVault.visibility = View.VISIBLE
            } else {
                loadVault()
            }
        }
    }

    /**
     * Checks if a file is a system file that should not be encrypted.
     */
    private fun isSystemFile(file: File): Boolean {
        val path = file.absolutePath.lowercase()
        
        val systemPaths = listOf(
            "/system/", "/proc/", "/sys/", "/dev/", "/data/system/",
            "/cache/", "/data/cache/", "/data/dalvik-cache/",
            "/data/app/"
        )
        
        if (systemPaths.any { path.startsWith(it) }) return true
        
        val systemFilePatterns = listOf(
            ".nomedia", "thumbs.db", "desktop.ini", ".ds_store",
            ".trash-", ".spotlight-", ".fseventsd", ".temporaryitems",
            ".localized", ".com.apple.timemachine.donotpresent"
        )
        
        return systemFilePatterns.any { file.name.lowercase() == it.lowercase() }
    }

    /**
     * Checks if a file is hidden (starts with dot) or in a hidden directory.
     */
    private fun isHiddenFile(file: File): Boolean {
        if (file.name.startsWith(".")) return true
        
        var parent = file.parentFile
        while (parent != null) {
            if (parent.name.startsWith(".")) return true
            parent = parent.parentFile
        }
        
        return false
    }

    private fun decryptEntry(entry: VaultEntry) {
        val progressView = layoutInflater.inflate(R.layout.dialog_vault_progress, null)
        val txtProgress = progressView.findViewById<TextView>(R.id.txtVaultProgress)
        val progressBar = progressView.findViewById<ProgressBar>(R.id.progressVault)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vault_decrypt)
            .setView(progressView)
            .setCancelable(false)
            .create()
        dialog.show()

        val initialTotal = entry.files.size.coerceAtLeast(1)
        txtProgress.text = getString(R.string.vault_decrypting, 0, initialTotal)
        progressBar.progress = 0

        scope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val entryDir = File(vaultBaseDir(), entry.id)
                    val originalRoot = File(entry.originalRoot)
                    val total = entry.files.size.coerceAtLeast(1)
                    runOnUiThread {
                        txtProgress.text = getString(R.string.vault_decrypting, 0, total)
                        progressBar.progress = 0
                    }
                    entry.files.forEachIndexed { index, relative ->
                        val encryptedFile = File(entryDir, "$relative.enc")
                        val outputFile = File(originalRoot, relative)
                        VaultCrypto.decryptFile(encryptedFile, outputFile)
                        encryptedFile.delete()

                        val current = index + 1
                        val percent = ((current.toFloat() / total.toFloat()) * 100).toInt()
                        runOnUiThread {
                            txtProgress.text = getString(R.string.vault_decrypting, current, total)
                            progressBar.progress = percent
                        }
                    }
                    entryDir.deleteRecursively()
                    true
                } catch (_: Exception) {
                    false
                }
            }

            dialog.dismiss()
            if (!success) {
                showSnackbar(getString(R.string.vault_decryption_failed))
            } else {
                Toast.makeText(this@VaultActivity, getString(R.string.vault_decrypt_complete), Toast.LENGTH_SHORT).show()
            }
            loadVault()
        }
    }

    private fun removeEntry(entry: VaultEntry) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val entryDir = File(vaultBaseDir(), entry.id)
                val originalRoot = File(entry.originalRoot)
                entry.files.forEach { relative ->
                    File(entryDir, "$relative.enc").delete()
                    File(originalRoot, relative).delete()
                }
                entryDir.deleteRecursively()
            }
            loadVault()
        }
    }

    private fun readEntries(): List<VaultEntry> {
        val base = vaultBaseDir()
        if (!base.exists()) return emptyList()

        return base.listFiles()?.mapNotNull { dir ->
            try {
                val metadataFile = File(dir, META_FILE)
                if (!metadataFile.exists()) return@mapNotNull null
                val json = JSONObject(metadataFile.readText())
                val filesJson = json.getJSONArray("files")
                val files = mutableListOf<String>()
                for (i in 0 until filesJson.length()) {
                    files.add(decryptField(filesJson.getString(i)))
                }
                VaultEntry(
                    id = json.getString("id"),
                    displayName = decryptField(json.getString("displayName")),
                    originalRoot = decryptField(json.getString("originalRoot")),
                    files = files
                )
            } catch (_: Exception) {
                // Try backup file if main metadata.json failed
                try {
                    val bakFile = File(dir, "$META_FILE.bak")
                    if (!bakFile.exists()) return@mapNotNull null
                    val json = JSONObject(bakFile.readText())
                    val filesJson = json.getJSONArray("files")
                    val files = mutableListOf<String>()
                    for (i in 0 until filesJson.length()) {
                        files.add(decryptField(filesJson.getString(i)))
                    }
                    VaultEntry(
                        id = json.getString("id"),
                        displayName = decryptField(json.getString("displayName")),
                        originalRoot = decryptField(json.getString("originalRoot")),
                        files = files
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } ?: emptyList()
    }

    private inner class VaultAdapter(
        private val onOpen: (VaultEntry) -> Unit,
        private val onDecrypt: (VaultEntry) -> Unit,
        private val onRemove: (VaultEntry) -> Unit
    ) : RecyclerView.Adapter<VaultAdapter.VH>() {

        private val items = mutableListOf<VaultEntry>()
        private var expandedPosition: Int = RecyclerView.NO_ID.toInt()
        private val isTv = DeviceUtils.isTvDevice(this@VaultActivity)

        fun submitList(newItems: List<VaultEntry>) {
            items.clear()
            items.addAll(newItems)
            expandedPosition = RecyclerView.NO_ID.toInt()
            notifyDataSetChanged()
        }

        /** Collapse any expanded card and return focus to the RecyclerView item.
         *  Returns true if a card was collapsed (caller should consume the Back event). */
        fun collapseExpanded(): Boolean {
            if (expandedPosition == RecyclerView.NO_ID.toInt()) return false
            val prev = expandedPosition
            expandedPosition = RecyclerView.NO_ID.toInt()
            notifyItemChanged(prev)
            // Move focus back to the card row
            recyclerVault.post {
                recyclerVault.layoutManager?.findViewByPosition(prev)?.requestFocus()
            }
            return true
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val card: MaterialCardView = itemView.findViewById(R.id.cardVaultEntry)
            val txtName: TextView = itemView.findViewById(R.id.txtVaultName)
            val txtPath: TextView = itemView.findViewById(R.id.txtVaultPath)
            val txtFileCount: TextView = itemView.findViewById(R.id.txtFileCount)
            val layoutActions: LinearLayout = itemView.findViewById(R.id.layoutVaultActions)
            val btnOpen: MaterialButton = itemView.findViewById(R.id.btnVaultOpen)
            val btnDecrypt: MaterialButton = itemView.findViewById(R.id.btnVaultDecrypt)
            val btnRemove: MaterialButton = itemView.findViewById(R.id.btnVaultRemove)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val layoutRes = if (isTv) R.layout.item_vault_entry_tv else R.layout.item_vault_entry
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(layoutRes, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            val isExpanded = position == expandedPosition

            holder.txtName.text = entry.displayName
            holder.txtPath.text = entry.originalRoot
            holder.txtFileCount.text = entry.files.size.toString() + " files"

            // Show/hide action buttons
            holder.layoutActions.visibility = if (isExpanded || !isTv) View.VISIBLE else View.GONE

            // Toggle descendantFocusability so D-pad can reach buttons when expanded
            holder.card.descendantFocusability = if (isExpanded && isTv)
                android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            else
                android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS

            // Highlight card stroke when expanded
            holder.card.strokeWidth = if (isExpanded && isTv)
                (2 * holder.itemView.context.resources.displayMetrics.density).toInt()
            else
                (1 * holder.itemView.context.resources.displayMetrics.density).toInt()

            holder.card.setStrokeColor(
                if (isExpanded && isTv)
                    holder.itemView.context.getColor(R.color.ufm_primary)
                else
                    holder.itemView.context.getColor(R.color.ufm_surface_variant)
            )

            // On TV: card click/OK expands; on mobile: buttons always visible
            holder.card.setOnClickListener {
                if (isTv) {
                    val prev = expandedPosition
                    expandedPosition = if (isExpanded) RecyclerView.NO_ID.toInt() else position
                    if (prev != RecyclerView.NO_ID.toInt() && prev != position) notifyItemChanged(prev)
                    notifyItemChanged(position)
                    // Move focus to first button after the layout has been updated
                    if (expandedPosition == position) {
                        holder.layoutActions.post {
                            holder.layoutActions.visibility = View.VISIBLE
                            holder.btnOpen.requestFocus()
                        }
                    }
                }
            }

            // Back key from any button collapses the card
            val backKeyListener = View.OnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    collapseExpanded()
                    true
                } else false
            }
            holder.btnOpen.setOnKeyListener(backKeyListener)
            holder.btnDecrypt.setOnKeyListener(backKeyListener)
            holder.btnRemove.setOnKeyListener(backKeyListener)

            // On TV: make focused button clearly readable — white text + subtle scale
            if (isTv) {
                val ctx = holder.itemView.context
                val yellow = ctx.getColor(R.color.tv_button_focused_yellow)
                val black = ctx.getColor(R.color.tv_button_focused_yellow_text)
                val dangerColor = ctx.getColor(R.color.ufm_denied)
                val yellowCsl = android.content.res.ColorStateList.valueOf(yellow)
                val glassCsl = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.tv_glass_white_15))
                val transparentCsl = android.content.res.ColorStateList.valueOf(0x00000000)

                fun wireTvBtn(btn: MaterialButton, defaultTextColor: Int, defaultBgCsl: android.content.res.ColorStateList) {
                    btn.setOnFocusChangeListener { _, hasFocus ->
                        btn.setTextColor(if (hasFocus) black else defaultTextColor)
                        btn.backgroundTintList = if (hasFocus) yellowCsl else defaultBgCsl
                        // No scale animation — weight-based buttons overflow card edges when scaled
                    }
                }

                wireTvBtn(holder.btnOpen, ctx.getColor(R.color.tv_text_primary), glassCsl)
                wireTvBtn(holder.btnDecrypt, ctx.getColor(R.color.tv_accent), transparentCsl)
                wireTvBtn(holder.btnRemove, dangerColor, transparentCsl)
            }

            holder.btnOpen.setOnClickListener { onOpen(entry) }
            holder.btnDecrypt.setOnClickListener { onDecrypt(entry) }
            holder.btnRemove.setOnClickListener { onRemove(entry) }
        }

        override fun getItemCount(): Int = items.size
    }
}
