package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.io.File
import javax.crypto.AEADBadTagException

class IconPackImportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACK_PATH = "pack_path"
    }

    private var isTv = false
    private lateinit var progressBar: View
    private lateinit var tvError: TextView
    private lateinit var layoutContent: View
    private var rvImportPreview: RecyclerView? = null
    private var tvImportContainer: LinearLayout? = null
    private lateinit var btnImportConfirm: MaterialButton

    private var packPath: String? = null
    private var importedOverrides: Map<String, IconOverride> = emptyMap()
    private var importPasswordAttempts = 0
    private val MAX_IMPORT_ATTEMPTS = 3

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)

        packPath = intent.getStringExtra(EXTRA_PACK_PATH)
        if (packPath.isNullOrEmpty()) {
            finish()
            return
        }

        if (isTv) {
            setContentView(R.layout.activity_icon_pack_import_tv)
        } else {
            setContentView(R.layout.activity_icon_pack_import)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tvPad = if (isTv) (27 * resources.displayMetrics.density).toInt() else 0
            v.setPadding(
                systemBars.left + tvPad, systemBars.top + tvPad,
                systemBars.right + tvPad, systemBars.bottom + tvPad
            )
            insets
        }

        setupViews()
        loadPackFile()
    }

    private fun setupViews() {
        progressBar = findViewById(R.id.progressBar)
        tvError = findViewById(R.id.tvError)
        layoutContent = findViewById(R.id.layoutContent)
        btnImportConfirm = findViewById(R.id.btnImportConfirm)

        if (isTv) {
            tvImportContainer = findViewById(R.id.importPreviewContainer)
        } else {
            rvImportPreview = findViewById(R.id.rvImportPreview)
            rvImportPreview?.layoutManager = LinearLayoutManager(this)
        }

        val btnBack = findViewById<ImageView?>(R.id.btnBack)
        btnBack?.setOnClickListener { finish() }

        btnImportConfirm.setOnClickListener {
            performImport()
        }

        if (isTv) {
            setupTvButtonFocus(btnImportConfirm)
        }
    }

    private fun loadPackFile() {
        progressBar.visibility = View.VISIBLE
        tvError.visibility = View.GONE
        layoutContent.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(packPath!!)
                if (!file.exists() || file.length() > 10L * 1024 * 1024) {
                    showError(getString(R.string.icon_pack_invalid_error))
                    return@launch
                }

                val bytes = file.readBytes()
                val format = ThemePackManager.detectFormat(bytes)

                withContext(Dispatchers.Main) {
                    when (format) {
                        ThemePackManager.ThemePackFormat.V2_ENCRYPTED -> {
                            importPasswordAttempts = 0
                            showImportPasswordDialog(file) { password ->
                                decryptWithPassword(file, password)
                            }
                        }
                        ThemePackManager.ThemePackFormat.V1_LEGACY,
                        ThemePackManager.ThemePackFormat.V2_PLAIN -> {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val (success, overrides) = ThemePackManager.performImport(
                                    this@IconPackImportActivity, file, null
                                )
                                showImportResult(success, overrides)
                            }
                        }
                        ThemePackManager.ThemePackFormat.UNKNOWN -> {
                            showError(getString(R.string.theme_import_unsupported_format))
                        }
                    }
                }
            } catch (e: Exception) {
                showError(getString(R.string.icon_pack_invalid_error))
            }
        }
    }

    private fun showImportPasswordDialog(file: File, onPassword: (String) -> Unit) {
        val isTv = DeviceUtils.isTvDevice(this)
        val dialogView = LayoutInflater.from(this).inflate(
            if (isTv) R.layout.dialog_theme_import_password_tv
            else R.layout.dialog_theme_import_password,
            null
        )

        val edtPassword = dialogView.findViewById<TextInputEditText>(R.id.edtPassword)
        val btnDecrypt = dialogView.findViewById<Button>(R.id.btnDecrypt)

        val dialog = MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)
            .setView(dialogView)
            .setCancelable(true)
            .setOnCancelListener { finish() }
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnDecrypt.setOnClickListener {
            val pw = edtPassword.text?.toString() ?: ""
            if (pw.isEmpty()) return@setOnClickListener
            dialog.dismiss()
            onPassword(pw)
        }

        dialog.show()

        if (isTv) {
            val yellow = getColor(R.color.tv_button_focused_yellow)
            val black = getColor(R.color.tv_button_focused_yellow_text)
            btnDecrypt.backgroundTintList = ColorStateList.valueOf(yellow)
            btnDecrypt.setTextColor(black)
            btnDecrypt.setOnFocusChangeListener { _, hasFocus ->
                btnDecrypt.backgroundTintList =
                    if (hasFocus) ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow))
                    else ColorStateList.valueOf(yellow)
            }
            btnDecrypt.requestFocus()
        }
    }

    private fun decryptWithPassword(file: File, password: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (success, overrides) = ThemePackManager.performImport(
                    this@IconPackImportActivity, file, password
                )
                showImportResult(success, overrides)
            } catch (e: AEADBadTagException) {
                importPasswordAttempts++
                if (importPasswordAttempts >= MAX_IMPORT_ATTEMPTS) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@IconPackImportActivity,
                            R.string.theme_import_too_many_attempts, Toast.LENGTH_LONG).show()
                        finish()
                    }
                } else {
                    val remaining = MAX_IMPORT_ATTEMPTS - importPasswordAttempts
                    withContext(Dispatchers.Main) {
                        showError(getString(R.string.theme_import_wrong_password, remaining))
                        showImportPasswordDialog(file) { pw -> decryptWithPassword(file, pw) }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError(getString(R.string.icon_pack_invalid_error))
                }
            }
        }
    }

    private suspend fun showImportResult(success: Boolean, overrides: Map<String, IconOverride>) {
        withContext(Dispatchers.Main) {
            progressBar.visibility = View.GONE

            if (!success || overrides.isEmpty()) {
                tvError.text = getString(R.string.icon_pack_invalid_error)
                tvError.visibility = View.VISIBLE
                return@withContext
            }

            importedOverrides = overrides
            layoutContent.visibility = View.VISIBLE

            if (isTv) {
                populateTvPreview(overrides)
            } else {
                val iconList = overrides.keys.toList()
                rvImportPreview?.adapter = object : RecyclerView.Adapter<ImportPreviewViewHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImportPreviewViewHolder {
                        val view = LayoutInflater.from(parent.context)
                            .inflate(android.R.layout.simple_list_item_2, parent, false)
                        return ImportPreviewViewHolder(view)
                    }
                    override fun onBindViewHolder(holder: ImportPreviewViewHolder, position: Int) {
                        val iconId = iconList[position]
                        holder.text1.text = iconId
                        val override = overrides[iconId]
                        val typeLabel = when {
                            !override?.customPath.isNullOrEmpty() -> "Custom image"
                            override?.builtinRes != 0 -> "Built-in icon"
                            else -> "Unknown"
                        }
                        holder.text2.text = typeLabel
                    }
                    override fun getItemCount(): Int = iconList.size
                }
            }
        }
    }

    private suspend fun showError(message: String) {
        withContext(Dispatchers.Main) {
            progressBar.visibility = View.GONE
            tvError.text = message
            tvError.visibility = View.VISIBLE
        }
    }

    private fun populateTvPreview(overrides: Map<String, IconOverride>) {
        val container = tvImportContainer ?: return
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val iconList = overrides.keys.toList()
        for (iconId in iconList) {
            val view = inflater.inflate(android.R.layout.simple_list_item_2, container, false)
            view.isFocusable = false
            view.isClickable = false
            if (view is ViewGroup) {
                view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }

            val text1 = view.findViewById<TextView>(android.R.id.text1)
            val text2 = view.findViewById<TextView>(android.R.id.text2)

            text1.text = iconId
            text1.setTextColor(getColor(R.color.tv_text_primary))

            val override = overrides[iconId]
            val typeLabel = when {
                !override?.customPath.isNullOrEmpty() -> "Custom image"
                override?.builtinRes != 0 -> "Built-in icon"
                else -> "Unknown"
            }
            text2.text = typeLabel
            text2.setTextColor(getColor(R.color.tv_text_secondary))

            container.addView(view)
        }
    }

    private fun performImport() {
        progressBar.visibility = View.VISIBLE
        layoutContent.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.Main) {
            withContext(Dispatchers.IO) {
                ThemePackManager.applyOverrides(this@IconPackImportActivity, importedOverrides)
            }

            progressBar.visibility = View.GONE
            Toast.makeText(
                this@IconPackImportActivity,
                R.string.icon_pack_import_success,
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun setupTvButtonFocus(btn: MaterialButton) {
        val yellowFill = getColor(R.color.tv_button_focused_yellow)
        val blackText = getColor(R.color.tv_button_focused_yellow_text)
        val defaultBg = getColor(R.color.btn_save_bg_tint)
        val defaultText = getColor(android.R.color.white)

        btn.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(yellowFill)
                btn.setTextColor(blackText)
            } else {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(defaultBg)
                btn.setTextColor(defaultText)
            }
        }
    }

    class ImportPreviewViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text1: TextView = itemView.findViewById(android.R.id.text1)
        val text2: TextView = itemView.findViewById(android.R.id.text2)
    }
}
