package za.kilowatch.ultimatefilemanager.server

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper
import za.kilowatch.ultimatefilemanager.storage.FileBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity
import za.kilowatch.ultimatefilemanager.storage.VaultCrypto
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException

/**
 * Create or edit an FTP/SFTP server profile.
 *
 * Fields:
 * - Username (required)
 * - Password + Confirm Password (must match, encrypted via AES-256)
 * - Read-only toggle
 * - Default location (picked via StorageBrowserActivity in picker mode)
 */
class ServerProfileEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        private val SUPPORTED_KEY_ALGORITHMS = setOf(
            "ssh-rsa",
            "ssh-ed25519",
            "ssh-dss",
            "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp384",
            "ecdsa-sha2-nistp521",
            "sk-ssh-ed25519@openssh.com",
            "sk-ecdsa-sha2-nistp256@openssh.com"
        )
        private const val SSH2_HEADER = "---- BEGIN SSH2 PUBLIC KEY ----"
        private const val SSH2_FOOTER = "---- END SSH2 PUBLIC KEY ----"
        private const val SSH2_COMMENT_PREFIX = "Comment:"

        /** Converts SSH2 format keys to OpenSSH format. Returns the original if already OpenSSH. */
        private fun normalizePublicKeys(content: String): String {
            val result = StringBuilder()
            val lines = content.lines()
            var i = 0
            while (i < lines.size) {
                val trimmed = lines[i].trim()
                if (trimmed == SSH2_HEADER) {
                    i++
                    val comment = StringBuilder()
                    val keyData = StringBuilder()
                    while (i < lines.size) {
                        val line = lines[i].trim()
                        i++
                        if (line == SSH2_FOOTER) break
                        if (line.startsWith(SSH2_COMMENT_PREFIX)) {
                            comment.append(line.removePrefix(SSH2_COMMENT_PREFIX).trim().trim('"'))
                            continue
                        }
                        keyData.append(line.replace(" ", "").replace("\t", ""))
                    }
                    val algo = extractAlgorithmFromSsh2(keyData.toString())
                    if (algo != null) {
                        if (result.isNotEmpty()) result.append('\n')
                        result.append("$algo ${keyData.toString()} ${comment.ifEmpty { "imported-key" }}".trimEnd())
                    }
                } else {
                    if (result.isNotEmpty()) result.append('\n')
                    result.append(lines[i])
                }
                i++
            }
            return result.toString()
        }

        private fun extractAlgorithmFromSsh2(base64Data: String): String? = runCatching {
            val raw = Base64.decode(base64Data, Base64.DEFAULT)
            val dis = DataInputStream(ByteArrayInputStream(raw))
            val algoLen = dis.readInt()
            val algoBytes = ByteArray(algoLen)
            dis.readFully(algoBytes)
            String(algoBytes, Charsets.UTF_8)
        }.getOrNull()
    }

    private lateinit var profileRepo: FtpServerProfileRepository
    private var editingProfile: FtpServerProfile? = null

    private lateinit var editUsername: TextInputEditText
    private lateinit var editPassword: TextInputEditText
    private lateinit var editConfirmPassword: TextInputEditText
    private lateinit var txtPasswordError: TextView
    private lateinit var switchReadOnly: MaterialSwitch
    private lateinit var txtDefaultLocation: TextView
    private lateinit var editAuthorizedKeys: TextInputEditText
    private lateinit var btnPickAuthorizedKeys: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var btnDelete: MaterialButton
    private lateinit var txtHeaderTitle: TextView

    private var selectedLocationUri: String = ""
    private var selectedLocationLabel: String = ""
    private var selectedLocationType: LocationType = LocationType.LOCAL
    private var selectedLocationMetaId: String? = null

    /** Receives the selected folder/location from [StorageBrowserActivity]. */
    private lateinit var folderPickerLauncher: ActivityResultLauncher<Intent>

    /** Receives a selected public key file from the system file picker. */
    private lateinit var keyPickerLauncher: ActivityResultLauncher<Intent>

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        za.kilowatch.ultimatefilemanager.settings.ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val layoutRes = if (za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)) {
            R.layout.activity_server_profile_edit_tv
        } else {
            R.layout.activity_server_profile_edit
        }
        setContentView(layoutRes)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Register picker result before the activity moves to STARTED.
        folderPickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult

                val uri    = data.getStringExtra(StorageBrowserActivity.RESULT_URI)    ?: return@registerForActivityResult
                val label  = data.getStringExtra(StorageBrowserActivity.RESULT_LABEL)  ?: uri
                val typeStr= data.getStringExtra(StorageBrowserActivity.RESULT_TYPE)
                val metaId = data.getStringExtra(StorageBrowserActivity.RESULT_META_ID)

                selectedLocationUri   = uri
                selectedLocationLabel = label
                selectedLocationType  = when (typeStr) {
                    "LOCAL"        -> LocationType.LOCAL
                    "SMB"          -> LocationType.SMB
                    "FTP"          -> LocationType.FTP
                    "SFTP"         -> LocationType.SFTP
                    "TV"           -> LocationType.TV
                    "GOOGLE_DRIVE" -> LocationType.GOOGLE_DRIVE
                    "ONEDRIVE"     -> LocationType.ONEDRIVE
                    else           -> LocationType.LOCAL
                }
                selectedLocationMetaId = metaId
                txtDefaultLocation.text = label
            }
        }

        keyPickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val path = result.data?.getStringExtra(FileBrowserActivity.RESULT_SELECTED_PATH)
                if (path != null) handleKeyPicked(path)
            }
        }

        profileRepo = FtpServerProfileRepository.getInstance(this)

        bindViews()
        setupClickListeners()
        loadProfile()
    }

    private fun bindViews() {
        editUsername        = findViewById(R.id.editUsername)
        editPassword        = findViewById(R.id.editPassword)
        editConfirmPassword = findViewById(R.id.editConfirmPassword)
        txtPasswordError    = findViewById(R.id.txtPasswordError)
        switchReadOnly      = findViewById(R.id.switchReadOnly)
        txtDefaultLocation  = findViewById(R.id.txtDefaultLocation)
        editAuthorizedKeys  = findViewById(R.id.editAuthorizedKeys)
        btnPickAuthorizedKeys = findViewById(R.id.btnPickAuthorizedKeys)
        btnSave             = findViewById(R.id.btnSave)
        btnDelete           = findViewById(R.id.btnDelete)
        txtHeaderTitle      = findViewById(R.id.txtHeaderTitle)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun showSelectLocationGuide() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_server_guide, null)
        val imgHero = dialogView.findViewById<ImageView>(R.id.imgGuideHero)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvGuideTitle)
        val tvDesc = dialogView.findViewById<TextView>(R.id.tvGuideDesc)
        val tvStep1 = dialogView.findViewById<TextView>(R.id.tvGuideStep1)
        val tvStep2 = dialogView.findViewById<TextView>(R.id.tvGuideStep2)
        val tvStep3 = dialogView.findViewById<TextView>(R.id.tvGuideStep3)
        val btnProceed = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnProceedGuide)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancelGuide)

        imgHero?.setImageResource(R.drawable.ic_folder)
        tvTitle?.text = getString(R.string.select_default_location)
        tvDesc?.text = "Choose the root storage directory for this server profile"
        tvStep1?.text = "1. Select your target storage (Internal, SD Card, USB, or Network)."
        tvStep2?.text = "2. Navigate inside the folder you want this user to access."
        tvStep3?.text = "3. Tap 'Select This Folder' to confirm your selection."
        btnProceed?.text = "Browse Storage"
        btnProceed?.setIconResource(R.drawable.ic_folder)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        btnProceed?.setOnClickListener {
            dialog.dismiss()
            folderPickerLauncher.launch(
                Intent(this, StorageBrowserActivity::class.java).apply {
                    putExtra(StorageBrowserActivity.EXTRA_LOCATION_PICKER, true)
                }
            )
        }
        btnCancel?.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showPickPublicKeyGuide() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_server_guide, null)
        val imgHero = dialogView.findViewById<ImageView>(R.id.imgGuideHero)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvGuideTitle)
        val tvDesc = dialogView.findViewById<TextView>(R.id.tvGuideDesc)
        val tvStep1 = dialogView.findViewById<TextView>(R.id.tvGuideStep1)
        val tvStep2 = dialogView.findViewById<TextView>(R.id.tvGuideStep2)
        val tvStep3 = dialogView.findViewById<TextView>(R.id.tvGuideStep3)
        val btnProceed = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnProceedGuide)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancelGuide)

        imgHero?.setImageResource(R.drawable.ic_lock)
        tvTitle?.text = getString(R.string.authorized_keys_pick)
        tvDesc?.text = "Select an OpenSSH or SSH2 public key to authenticate this user"
        tvStep1?.text = "1. Browse your storage to locate your public key file (.pub, .txt, etc.)."
        tvStep2?.text = "2. Tap on the key file to select and import it."
        tvStep3?.text = "3. The key data will be verified for SSH passwordless authentication."
        btnProceed?.text = "Browse for Key"
        btnProceed?.setIconResource(R.drawable.ic_lock)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        btnProceed?.setOnClickListener {
            dialog.dismiss()
            keyPickerLauncher.launch(
                Intent(this, StorageBrowserActivity::class.java).apply {
                    putExtra(StorageBrowserActivity.EXTRA_KEYFILE_PICKER, true)
                }
            )
        }
        btnCancel?.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun setupClickListeners() {
        val openLocationPicker = View.OnClickListener {
            showSelectLocationGuide()
        }

        findViewById<MaterialButton>(R.id.btnSelectLocation).setOnClickListener(openLocationPicker)
        findViewById<View>(R.id.layoutDefaultLocationCard)?.setOnClickListener(openLocationPicker)

        btnPickAuthorizedKeys.setOnClickListener {
            showPickPublicKeyGuide()
        }

        btnSave.setOnClickListener { saveProfile() }

        btnDelete.setOnClickListener {
            editingProfile?.let { profile ->
                val dialogView = layoutInflater.inflate(R.layout.dialog_delete_profile_confirm, null)
                val txtMsg = dialogView.findViewById<TextView>(R.id.txtDeleteProfileMsg)
                val btnConfirm = dialogView.findViewById<View>(R.id.btnConfirmDeleteProfile)
                val btnCancel = dialogView.findViewById<View>(R.id.btnCancelDeleteProfile)

                txtMsg?.text = getString(R.string.delete_profile_confirm, profile.username)

                val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                    .setView(dialogView)
                    .create()
                dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

                btnConfirm?.setOnClickListener {
                    dialog.dismiss()
                    profileRepo.delete(profile.id)
                    Toast.makeText(this, R.string.profile_deleted, Toast.LENGTH_SHORT).show()
                    finish()
                }
                btnCancel?.setOnClickListener {
                    dialog.dismiss()
                }
                dialog.show()
            }
        }
    }

    private fun handleKeyPicked(path: String) {
        val text = try {
            File(path).readText()
        } catch (e: Exception) {
            Log.e("ServerProfileEdit", "Failed to read key file", e)
            Toast.makeText(this, R.string.error_reading_key_file, Toast.LENGTH_SHORT).show()
            return
        }

        val normalized = normalizePublicKeys(text)
        val validation = validatePublicKeyFile(normalized)
        if (validation != null) {
            MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
                .setTitle(R.string.authorized_keys_invalid_title)
                .setMessage(validation)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }

        val existing = editAuthorizedKeys.text?.toString()?.trim() ?: ""
        val append = if (existing.isEmpty()) normalized else "\n$normalized"
        editAuthorizedKeys.setText(existing + append)
        editAuthorizedKeys.requestFocus()
    }

    /** Validates that [content] contains only SSH public keys. Returns null if valid,
     *  or an error message string explaining the first problem found. */
    private fun validatePublicKeyFile(content: String): String? {
        val lines = content.lines()
        var lineIndex = 0
        for (rawLine in lines) {
            lineIndex++
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            // Reject private keys
            if (trimmed.startsWith("-----BEGIN")) {
                return getString(R.string.authorized_keys_is_private)
            }

            val parts = trimmed.split(" ")
            if (parts.size < 2) {
                return getString(R.string.authorized_keys_bad_format, lineIndex, trimmed.take(40))
            }

            val algorithm = parts[0]
            if (!SUPPORTED_KEY_ALGORITHMS.contains(algorithm)) {
                return getString(R.string.authorized_keys_bad_format, lineIndex, trimmed.take(40))
            }

            // Verify the base64 key data is decodable
            try {
                Base64.decode(parts[1], Base64.DEFAULT)
            } catch (e: Exception) {
                return getString(R.string.authorized_keys_bad_format, lineIndex, trimmed.take(40))
            }
        }
        return null
    }

    private fun loadProfile() {
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileId != null) {
            editingProfile = profileRepo.getById(profileId)
            editingProfile?.let { profile ->
                txtHeaderTitle.text = getString(R.string.edit_profile)
                editUsername.setText(profile.username)
                switchReadOnly.isChecked = profile.readOnly
                selectedLocationUri   = profile.defaultLocationUri
                selectedLocationLabel = profile.defaultLocationLabel
                selectedLocationType  = profile.locationType
                selectedLocationMetaId= profile.locationMetaId
                txtDefaultLocation.text = profile.defaultLocationLabel.ifEmpty {
                    profile.defaultLocationUri
                }
                editAuthorizedKeys.setText(profile.authorizedKeys)
                btnDelete.visibility = View.VISIBLE
                // Password fields left empty — user can enter new password or leave blank to keep
            }
        }
    }

    private fun saveProfile() {
        val username        = editUsername.text?.toString()?.trim() ?: ""
        val password        = editPassword.text?.toString() ?: ""
        val confirmPassword = editConfirmPassword.text?.toString() ?: ""
        val rawKeys         = editAuthorizedKeys.text?.toString()?.trim() ?: ""
        val authorizedKeys  = normalizePublicKeys(rawKeys)
        val hasKeys         = authorizedKeys.isNotEmpty()

        if (username.isEmpty()) {
            editUsername.error = getString(R.string.username_required)
            return
        }

        val existingByUsername = profileRepo.getByUsername(username)
        if (existingByUsername != null && existingByUsername.id != editingProfile?.id) {
            editUsername.error = getString(R.string.username_already_exists)
            return
        }

        val isNewProfile = editingProfile == null
        if (isNewProfile && password.isEmpty() && !hasKeys) {
            editPassword.error = getString(R.string.password_required)
            return
        }

        if (password.isNotEmpty()) {
            if (password != confirmPassword) {
                txtPasswordError.visibility = View.VISIBLE
                return
            } else {
                txtPasswordError.visibility = View.GONE
            }
        }

        if (selectedLocationUri.isEmpty()) {
            Toast.makeText(this, R.string.please_select_location, Toast.LENGTH_SHORT).show()
            return
        }

        val encryptedPassword = if (password.isNotEmpty()) {
            VaultCrypto.encryptString(password)
        } else {
            editingProfile?.encryptedPassword ?: ""
        }

        val profile = FtpServerProfile(
            id                 = editingProfile?.id ?: java.util.UUID.randomUUID().toString(),
            username           = username,
            encryptedPassword  = encryptedPassword,
            defaultLocationUri = selectedLocationUri,
            defaultLocationLabel = selectedLocationLabel,
            locationType       = selectedLocationType,
            locationMetaId     = selectedLocationMetaId,
            readOnly           = switchReadOnly.isChecked,
            authorizedKeys     = authorizedKeys,
            isCredentialsStripped = false
        )

        profileRepo.save(profile)
        editAuthorizedKeys.setText(authorizedKeys)
        Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Called from TV layout via android:onClick="toggleReadOnly" */
    fun toggleReadOnly(view: View) {
        switchReadOnly.toggle()
    }
}
