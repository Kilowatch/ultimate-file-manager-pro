package za.kilowatch.ultimatefilemanager.viewer

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.CheckBox
import android.webkit.MimeTypeMap
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import za.kilowatch.ultimatefilemanager.R

/**
 * Central routing helper that dispatches file opens to built-in viewer activities.
 * When external apps can also handle the file, shows a premium choice dialog.
 * Returns false for unsupported types so callers can fall back to ACTION_VIEW.
 */
object FileViewerRouter {

    // ── Plain text / code ────────────────────────────────────────────────────
    // NOTE: keep in sync with LanguageRegistry.kt — every extension that
    // supports syntax highlighting must be listed here so the file is
    // routed to TextViewerActivity.
    val TEXT_EXTENSIONS = setOf(
        // Core text
        "txt", "log", "rtf",
        // Config / data
        "json", "xml", "yaml", "yml", "toml", "properties", "ini", "cfg", "conf",
        "xsd", "xsl", "xslt", "plist", "svg",
        // Web
        "html", "htm", "xhtml", "css", "scss", "less", "sass",
        "js", "mjs", "cjs", "jsx", "ts", "tsx",
        // Scripting
        "py", "pyw", "pyx", "pxd", "pyi", "rb", "erb", "rhtml", "rxml", "rjs",
        "rake", "gemspec", "php", "phtml", "php3", "php4", "php5", "php7", "phps", "phpt",
        "pl", "pm", "t", "pod", "lua", "wlua",
        // Shell
        "sh", "bash", "zsh", "ksh", "csh", "tcsh", "bat",
        // JVM
        "java", "jav", "jsh", "kt", "kts", "ktm", "gradle",
        "cs", "csx",
        // C-family
        "c", "h", "cpp", "hpp", "cc", "hh", "cxx", "hxx", "cp", "cx",
        "c++", "h++",
        // Modern
        "go", "rs", "rlib", "swift", "swiftmodule", "dart",
        // Data
        "sql", "ddl", "dml", "pks", "pkb", "fnc", "prc", "trg", "vw",
        // Docs
        "md", "markdown", "mdown", "mdwn"
    )

    // ── DAT (auto-detect text vs binary) ─────────────────────────────────────
    val DAT_EXTENSIONS = setOf("dat")

    // ── Office OOXML (ZIP-based, POI XWPF/XSSF/XSLF) ────────────────────────
    private val OFFICE_OOXML_EXTENSIONS = setOf(
        "docx", "docm", "dotx", "dotm",
        "pptx", "pptm", "ppsx", "potx", "potm",
        "vsdx"
    )

    // ── Office legacy binary (POI HWPF/HSSF/HSLF) ───────────────────────────
    private val OFFICE_LEGACY_EXTENSIONS = setOf(
        "doc", "dot",
        "ppt", "pps", "pot"
    )

    // ── Spreadsheet ──────────────────────────────────────────────────────────
    private val SPREADSHEET_EXTENSIONS = setOf(
        "xls", "xlsx", "csv", "xlsm", "xltx", "xltm", "xlt", "xlsb"
    )

    // ── Images ───────────────────────────────────────────────────────────────
    val IMAGE_EXTENSIONS = setOf(
        // Common lossy
        "jpg", "jpeg",
        // Lossless / transparency
        "png", "apng",
        // Animated
        "gif", "webp",
        // Modern compressed
        "heic", "heif", "avif",
        // Legacy
        "bmp", "ico",
        // Vector
        "svg",
        // Professional
        "tiff", "tif",
        // RAW (routed to viewer; decoded where Android/Coil supports it)
        "dng", "cr2", "nef", "arw"
    )

    // ── PDF ──────────────────────────────────────────────────────────────────
    private val PDF_EXTENSIONS = setOf("pdf")

    // ── Archives ─────────────────────────────────────────────────────────────
    private val ZIP_EXTENSIONS = setOf("zip", "7z")
    private val PACKAGE_EXTENSIONS = setOf("apk", "xapk", "apks")

    // ── Audio ─────────────────────────────────────────────────────────────────
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "wav", "ogg", "m4a", "aac", "flac", "opus", "wma", "amr"
    )

    // ── Video ─────────────────────────────────────────────────────────────────
    val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "webm", "flv", "3gp", "ts"
    )

    /** All extensions this router can handle internally. */
    val ALL_SUPPORTED: Set<String> = TEXT_EXTENSIONS + DAT_EXTENSIONS +
        OFFICE_OOXML_EXTENSIONS + OFFICE_LEGACY_EXTENSIONS + SPREADSHEET_EXTENSIONS +
        IMAGE_EXTENSIONS + PDF_EXTENSIONS + ZIP_EXTENSIONS +
        AUDIO_EXTENSIONS + VIDEO_EXTENSIONS + PACKAGE_EXTENSIONS

    fun canOpenInternally(extension: String): Boolean =
        extension.lowercase() in ALL_SUPPORTED

    fun isAudio(extension: String): Boolean = extension.lowercase() in AUDIO_EXTENSIONS
    fun isVideo(extension: String): Boolean = extension.lowercase() in VIDEO_EXTENSIONS


    /**
     * Opens the file. If a built-in viewer is available AND external apps exist,
     * shows a premium choice dialog. Otherwise opens directly.
     * @param transitionView Optional view whose bounds are used as the shared element
     *    for the enter transition into [ImageViewerActivity]. Pass null to skip.
     * @return true if handled (dialog shown or viewer launched), false if caller should fall back.
     */
    fun openFile(context: Context, file: File, transitionView: android.view.View? = null, isNetwork: Boolean = false): Boolean {
        val ext = file.extension.lowercase()
        if (ext !in ALL_SUPPORTED) return false

        // Check for saved default preference (local vs network context)
        val defaultAction = DefaultOpenManager.getDefaultAction(context, ext, isNetwork = isNetwork)
        if (defaultAction != DefaultOpenManager.Action.ASK) {
            when (defaultAction) {
                DefaultOpenManager.Action.INTERNAL -> {
                    // Forward transitionView so the shared element animation still fires
                    openInBuiltInViewer(context, file, transitionView)
                    return true
                }
                DefaultOpenManager.Action.EXTERNAL -> {
                    val preferred = DefaultOpenManager.getPreferredPackage(context, ext, isNetwork = isNetwork)
                    openWithExternalApp(
                        context, file,
                        preferredPackage = preferred,
                        // If the user chose EXTERNAL but we don't know which app yet,
                        // keep remember=true so the callback saves it this time.
                        remember = preferred == null,
                        extension = ext,
                        isNetwork = isNetwork
                    )
                    return true
                }
                DefaultOpenManager.Action.PLAYER -> {
                    openInPlayer(context, file)
                    return true
                }
                DefaultOpenManager.Action.SLIDESHOW -> {
                    val parentDir = file.parentFile
                    val files = parentDir?.listFiles() ?: emptyArray()
                    val filesToConsider = files.filter { it.isFile && !it.name.startsWith(".") }
                        .filter { f -> f.extension.lowercase() in IMAGE_EXTENSIONS || f.extension.lowercase() in VIDEO_EXTENSIONS }
                    openInSlideShow(context, file, filesToConsider)
                    return true
                }
                else -> {} // ASK is excluded by the outer check; other actions are no-ops
            }
        }

        // Check if external apps can handle this file
        val hasExternalApps = hasExternalHandler(context, file)

        if (hasExternalApps) {
            // Show choice dialog
            showOpenWithDialog(context, file, isNetwork = isNetwork)
        } else {
            // No external apps — open directly in built-in viewer
            openInBuiltInViewer(context, file, transitionView)
        }
        return true
    }

    /**
     * Opens the file directly in the appropriate built-in viewer (no dialog).
     * If [transitionView] is non-null and the target is [ImageViewerActivity],
     * launches with a shared element transition for a premium feel.
     */
    private fun openInBuiltInViewer(context: Context, file: File, transitionView: android.view.View? = null) {
        val ext = file.extension.lowercase()
        val isImage = ext in IMAGE_EXTENSIONS
        val intent = when (ext) {
            in IMAGE_EXTENSIONS -> Intent(context, ImageViewerActivity::class.java)
            in PDF_EXTENSIONS -> Intent(context, PdfViewerActivity::class.java)
            "zip" -> Intent(context, ZipViewerActivity::class.java)
            "7z" -> Intent(context, SevenZipViewerActivity::class.java)
            in AUDIO_EXTENSIONS, in VIDEO_EXTENSIONS -> {
                // Mobile: UFMPlayerActivity (ExoPlayer with background playback)
                // TV: keep the old MediaPlayerActivity (new features not supported on TV)
                if (!za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(context)) {
                    openInPlayer(context, file)
                    return
                }
                Intent(context, MediaPlayerActivity::class.java).apply {
                    putExtra(FileViewerRouter.EXTRA_IS_VIDEO, ext !in AUDIO_EXTENSIONS)
                }
            }
            in SPREADSHEET_EXTENSIONS -> Intent(context, SpreadsheetViewerActivity::class.java)
            in TEXT_EXTENSIONS, in DAT_EXTENSIONS, in OFFICE_OOXML_EXTENSIONS, in OFFICE_LEGACY_EXTENSIONS -> 
                Intent(context, TextViewerActivity::class.java)
            in PACKAGE_EXTENSIONS -> 
                Intent(context, za.kilowatch.ultimatefilemanager.ui.PackageInstallerActivity::class.java).apply {
                    data = Uri.fromFile(file)
                }
            else -> return
        }
        intent.putExtra(EXTRA_FILE_PATH, file.absolutePath)
        intent.putExtra(EXTRA_FILE_NAME, file.name)

        // Shared element transition — only for images and only when a source view is provided
        if (isImage && transitionView != null && context is android.app.Activity) {
            val transitionName = transitionView.transitionName
            if (!transitionName.isNullOrEmpty()) {
                intent.putExtra(EXTRA_TRANSITION_NAME, transitionName)
                val options = android.app.ActivityOptions
                    .makeSceneTransitionAnimation(context, transitionView, transitionName)
                context.startActivity(intent, options.toBundle())
                return
            }
        }
        context.startActivity(intent)
    }

    /**
     * Opens the file in UFMPlayerActivity (ExoPlayer) with a playlist built from the
     * parent directory's media files. Supports both local and network files.
     */
    private fun openInPlayer(context: Context, file: File) {
        val ext = file.extension.lowercase()
        if (ext !in AUDIO_EXTENSIONS && ext !in VIDEO_EXTENSIONS) return

        val parentDir = file.parentFile
        val playlist = parentDir?.listFiles { f ->
            val e = f.extension.lowercase()
            e in AUDIO_EXTENSIONS || e in VIDEO_EXTENSIONS
        }?.map { it.absolutePath }?.toCollection(java.util.ArrayList()) ?: java.util.ArrayList<String>().apply { add(file.absolutePath) }

        val intent = Intent(context, UFMPlayerActivity::class.java).apply {
            putExtra(EXTRA_FILE_PATH, file.absolutePath)
            putExtra("initialPath", file.absolutePath)
            putStringArrayListExtra("playlist", playlist)
        }
        context.startActivity(intent)
    }

    private fun openInSlideShow(context: Context, file: File, filesToConsider: List<File>) {
        val playlist = filesToConsider.map { it.absolutePath }.toCollection(java.util.ArrayList())
        val intent = Intent(context, SlideShowActivity::class.java).apply {
            putExtra(EXTRA_FILE_PATH, file.absolutePath)
            putExtra("initialPath", file.absolutePath)
            putStringArrayListExtra("playlist", playlist)
        }
        context.startActivity(intent)
    }

    /**
     * Checks whether any external application can open this file type.
     */
    private fun hasExternalHandler(context: Context, file: File): Boolean {
        return try {
            val ext = file.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val activities = context.packageManager.queryIntentActivities(
                intent, PackageManager.MATCH_DEFAULT_ONLY
            )
            activities.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Shows a premium-styled "Open with" dialog letting the user choose
     * between UFM's built-in viewer or an external app.
     */
    private fun showOpenWithDialog(context: Context, file: File, isNetwork: Boolean = false) {
        if (context !is Activity) {
            openInBuiltInViewer(context, file)
            return
        }

        val dp = { px: Int -> (px * context.resources.displayMetrics.density).toInt() }

        // ── Theme-aware colors ──
        val bgColor      = ContextCompat.getColor(context, R.color.tv_dialog_background)
        val textPrimary  = ContextCompat.getColor(context, R.color.tv_text_primary)
        val textSecondary= ContextCompat.getColor(context, R.color.tv_text_secondary)

        // ── Dialog background ──
        val dialogBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(bgColor)
        }

        // ── Root layout ──
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = dialogBg
            setPadding(dp(24), dp(28), dp(24), dp(20))
        }

        // ── Title ──
        val title = TextView(context).apply {
            text = context.getString(R.string.open_with_1)
            textSize = 22f
            setTextColor(textPrimary)
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })

        // ── Filename subtitle ──
        val subtitle = TextView(context).apply {
            text = file.name
            textSize = 13f
            setTextColor(textSecondary)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        root.addView(subtitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(24) })

        // ── Remember checkbox ──
        val checkbox = CheckBox(context).apply {
            text = context.getString(R.string.remember_my_choice)
            setTextColor(textSecondary)
            textSize = 14f
            buttonTintList = android.content.res.ColorStateList.valueOf(textSecondary)
        }
        root.addView(checkbox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { 
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(16)
        })

        val ext = file.extension.lowercase()
        val isVideo = ext in VIDEO_EXTENSIONS
        val isAudio = ext in AUDIO_EXTENSIONS
        val isImage = ext in IMAGE_EXTENSIONS

        val dialog = AlertDialog.Builder(context)
            .setView(root)
            .setCancelable(true)
            .create()

        // ── UFM Viewer (shown for images, documents, text, etc. — NOT audio/video) ──
        if (!isVideo && !isAudio) {
            val ufmBtn = createChoiceButton(
                context, dp,
                icon = "📂",
                label = context.getString(R.string.ufm_viewer),
                description = context.getString(R.string.open_with_builtin_viewer),
                gradientColors = intArrayOf(
                    Color.parseColor("#0284C7"),
                    Color.parseColor("#0369A1")
                )
            ) {
                if (checkbox.isChecked) {
                    DefaultOpenManager.setDefaultAction(context, ext, isNetwork = isNetwork, DefaultOpenManager.Action.INTERNAL)
                }
                dialog.dismiss()
                openInBuiltInViewer(context, file)
            }
            root.addView(ufmBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) })
        }

        // ── UFM Slide Show (only shown for Images on local storage) ──
        if (isImage && !isNetwork) {
            val parentDir = file.parentFile
            val files = parentDir?.listFiles() ?: emptyArray()
            val filesToConsider = files.filter { it.isFile && !it.name.startsWith(".") }
            val onlyImagesOrVideos = filesToConsider.isNotEmpty() && filesToConsider.all { f ->
                val e = f.extension.lowercase()
                e in IMAGE_EXTENSIONS || e in VIDEO_EXTENSIONS
            }

            if (onlyImagesOrVideos) {
                val slideShowBtn = createChoiceButton(
                    context, dp,
                    icon = "🖼️",
                    label = context.getString(R.string.ufm_slideshow),
                    description = context.getString(R.string.ufm_slideshow_desc),
                    gradientColors = intArrayOf(
                        Color.parseColor("#8B5CF6"),
                        Color.parseColor("#6D28D9")
                    )
                ) {
                    if (checkbox.isChecked) {
                        DefaultOpenManager.setDefaultAction(context, ext, isNetwork = isNetwork, DefaultOpenManager.Action.SLIDESHOW)
                    }
                    dialog.dismiss()
                    openInSlideShow(context, file, filesToConsider)
                }
                root.addView(slideShowBtn, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) })
            }
        }

        // ── UFM Media Player (only shown for audio/video) ──
        if (isVideo || isAudio) {
            val playerBtn = createChoiceButton(
                context, dp,
                icon = "▶️",
                label = context.getString(R.string.ufm_media_player),
                description = context.getString(R.string.ufm_media_player_desc),
                gradientColors = intArrayOf(
                    Color.parseColor("#10B981"),
                    Color.parseColor("#059669")
                )
            ) {
                if (checkbox.isChecked) {
                    DefaultOpenManager.setDefaultAction(context, ext, isNetwork = isNetwork, DefaultOpenManager.Action.PLAYER)
                }
                dialog.dismiss()
                openInPlayer(context, file)
            }
            root.addView(playerBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) })
        }

        // ── External apps button — theme-aware neutral ──
        val externalBtn = createChoiceButton(
            context, dp,
            icon = "🔗",
            label = context.getString(R.string.external_app),
            description = context.getString(R.string.choose_another_app_to_open),
            gradientColors = intArrayOf(bgColor, bgColor),
            labelColor = textPrimary,
            descColor = textSecondary
        ) {
            val remember = checkbox.isChecked
            if (remember) {
                DefaultOpenManager.setDefaultAction(context, ext, isNetwork = isNetwork, DefaultOpenManager.Action.EXTERNAL)
            }
            dialog.dismiss()
            openWithExternalApp(context, file,
                preferredPackage = null,
                remember = remember,
                extension = ext,
                isNetwork = isNetwork
            )
        }
        root.addView(externalBtn)

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()

        // Set dialog width
        dialog.window?.setLayout(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /**
     * Creates a premium choice button with gradient background, icon, label and description.
     */
    private fun createChoiceButton(
        context: Context,
        dp: (Int) -> Int,
        icon: String,
        label: String,
        description: String,
        gradientColors: IntArray,
        labelColor: Int = Color.WHITE,
        descColor: Int = Color.parseColor("#AAFFFFFF"),
        onClick: () -> Unit
    ): LinearLayout {
        val btnBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            colors = gradientColors
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
        }

        val focusedBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#FBBF24")) // Yellow
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = btnBg
            setPadding(dp(16), dp(16), dp(16), dp(16))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true

            // Icon
            val iconView = TextView(this.context).apply {
                text = icon
                textSize = 28f
            }
            addView(iconView, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                gravity = Gravity.CENTER_VERTICAL
            })

            // Text container
            val textContainer = LinearLayout(this.context).apply {
                orientation = LinearLayout.VERTICAL
            }

            val labelView = TextView(this.context).apply {
                text = label
                textSize = 16f
                setTextColor(labelColor)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
            textContainer.addView(labelView)

            val descView = TextView(this.context).apply {
                text = description
                textSize = 12f
                setTextColor(descColor)
            }
            textContainer.addView(descView)

            addView(textContainer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            // Arrow
            val arrow = TextView(this.context).apply {
                text = "›"
                textSize = 24f
                setTextColor(descColor)
            }
            addView(arrow)

            // TV focus states: yellow bg, black text when focused
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    background = focusedBg
                    labelView.setTextColor(Color.parseColor("#0F0F0F"))
                    descView.setTextColor(Color.parseColor("#333333"))
                    arrow.setTextColor(Color.parseColor("#333333"))
                } else {
                    background = btnBg
                    labelView.setTextColor(labelColor)
                    descView.setTextColor(descColor)
                    arrow.setTextColor(descColor)
                }
            }

            setOnClickListener { onClick() }
        }
    }

    /**
     * Opens the file with an external app.
     *
     * If [preferredPackage] is set and still installed, launches directly into that app.
     * Otherwise shows the system chooser, and -- when [remember] is true -- registers a
     * one-shot broadcast receiver so the chosen component is persisted for future opens.
     */
    private fun openWithExternalApp(
        context: Context,
        file: File,
        preferredPackage: String? = null,
        remember: Boolean = false,
        extension: String = file.extension,
        isNetwork: Boolean = false
    ) {
        val tag = "UFM.ExternalApp"
        val ext = extension.lowercase()
        android.util.Log.d(tag, "openWithExternalApp: ext=$ext preferred=$preferredPackage remember=$remember isNetwork=$isNetwork")

        // -- Build URI --
        val mimeType: String
        val uri: Uri
        try {
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            android.util.Log.d(tag, "  uri=$uri  mime=$mimeType")
        } catch (e: Exception) {
            android.util.Log.e(tag, "  Failed to build URI: ${e.message}", e)
            return
        }

        // -- 1. Try direct launch into the preferred app --
        if (preferredPackage != null) {
            android.util.Log.d(tag, "  Trying direct launch into $preferredPackage")
            val pm = context.packageManager
            val installed = try { pm.getPackageInfo(preferredPackage, 0); true }
                            catch (_: PackageManager.NameNotFoundException) { false }
            if (installed) {
                val directIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    setPackage(preferredPackage)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val resolves = pm.queryIntentActivities(directIntent, PackageManager.MATCH_DEFAULT_ONLY)
                android.util.Log.d(tag, "  Direct resolves: ${resolves.size}")
                if (resolves.isNotEmpty()) {
                    try {
                        context.startActivity(directIntent)
                        android.util.Log.d(tag, "  Direct launch OK")
                        return
                    } catch (e: Exception) {
                        android.util.Log.e(tag, "  Direct launch failed, falling to chooser: ${e.message}", e)
                    }
                } else {
                    android.util.Log.d(tag, "  Preferred app cannot handle mime -- falling to chooser")
                }
            } else {
                android.util.Log.d(tag, "  Preferred package not installed -- clearing pref")
            }
            DefaultOpenManager.clearPreferredPackage(context, ext, isNetwork = isNetwork)
        }

        // -- 2. Build system chooser --
        android.util.Log.d(tag, "  Building chooser")
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(viewIntent, context.getString(R.string.open_with))

        // -- 3. Best-effort: attach selection callback (non-fatal if this fails) --
        if (remember && context is Activity) {
            try {
                android.util.Log.d(tag, "  Attaching callback (API ${android.os.Build.VERSION.SDK_INT})")
                val callbackAction = "${context.packageName}.CHOSEN_EXTERNAL_APP_$ext"
                // setPackage makes the intent semi-explicit — required on API 34+ when using FLAG_MUTABLE
                val callbackIntent = Intent(callbackAction).apply { setPackage(context.packageName) }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        ctx.unregisterReceiver(this)
                        @Suppress("DEPRECATION")
                        val component = intent.getParcelableExtra<ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT)
                        android.util.Log.d(tag, "  CHOSEN_COMPONENT: $component")
                        if (component != null) {
                            DefaultOpenManager.setPreferredPackage(ctx, ext, isNetwork = isNetwork, component.packageName)
                            android.util.Log.d(tag, "  Saved pkg: ${component.packageName}")
                        }
                    }
                }
                val piFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                else PendingIntent.FLAG_UPDATE_CURRENT
                val pi = PendingIntent.getBroadcast(context, ext.hashCode(), callbackIntent, piFlags)
                android.util.Log.d(tag, "  PendingIntent OK, flags=$piFlags")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, IntentFilter(callbackAction), Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(receiver, IntentFilter(callbackAction))
                }
                android.util.Log.d(tag, "  Receiver registered OK")
                chooser.putExtra(Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER, pi.intentSender)
            } catch (e: Exception) {
                android.util.Log.e(tag, "  Callback setup FAILED (chooser still opens): ${e.message}", e)
            }
        } else {
            android.util.Log.d(tag, "  No callback: remember=$remember isActivity=${context is Activity}")
        }

        // -- 4. Always launch the chooser -- completely independent of callback setup --
        try {
            android.util.Log.d(tag, "  startActivity(chooser)")
            context.startActivity(chooser)
            android.util.Log.d(tag, "  Chooser launched OK")
        } catch (e: Exception) {
            android.util.Log.e(tag, "  startActivity(chooser) FAILED: ${e.message}", e)
        }
    }

    const val EXTRA_FILE_PATH = "extra_file_path"
    const val EXTRA_FILE_NAME = "extra_file_name"
    const val EXTRA_IS_VIDEO  = "extra_is_video"
    /** Used to wire the shared element return transition in [ImageViewerActivity]. */
    const val EXTRA_TRANSITION_NAME = "extra_transition_name"
    /** When true, [TextViewerActivity] starts in edit mode immediately. */
    const val EXTRA_START_IN_EDIT_MODE = "extra_start_in_edit_mode"
}
