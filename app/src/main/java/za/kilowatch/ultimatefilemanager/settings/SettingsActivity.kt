package za.kilowatch.ultimatefilemanager.settings

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DeviceUtils

/**
 * Settings hub screen.
 * Shows Font Size and Analytics toggle.
 */
class SettingsActivity : AppCompatActivity() {

    private var isTv = false
    // Persisted through recreate() to prevent looping when restartPending is still true.
    private var handledFontChange = false
    private var handledLocaleChange = false

    private lateinit var switchAnalytics: SwitchMaterial
    private lateinit var txtAnalyticsSubtitle: TextView

    private lateinit var switchHiddenFiles: SwitchMaterial
    private lateinit var txtHiddenFilesSubtitle: TextView

    private lateinit var switchMediaThumbnails: SwitchMaterial
    private lateinit var txtMediaThumbnailsSubtitle: TextView

    private lateinit var switchCacheCopy: SwitchMaterial
    private lateinit var txtCacheCopySubtitle: TextView

    private lateinit var switchQuickTransfer: SwitchMaterial
    private lateinit var txtQuickTransferSubtitle: TextView

    private lateinit var switchNetworkOpenCache: SwitchMaterial
    private lateinit var txtNetworkOpenCacheSubtitle: TextView

    private lateinit var switchRecycleBin: SwitchMaterial
    private lateinit var txtRecycleBinSubtitle: TextView

    private var switchTwinWindowLayout: SwitchMaterial? = null
    private var txtTwinWindowLayoutSubtitle: TextView? = null

    private var switchTwinWindowStartup: SwitchMaterial? = null
    private var txtTwinWindowStartupSubtitle: TextView? = null

    private var switchSideBySideVideo: SwitchMaterial? = null
    private var txtSideBySideVideoSubtitle: TextView? = null

    private var switchSideBySideVideoShowControlsOnRepeat: SwitchMaterial? = null
    private var txtSideBySideVideoShowControlsOnRepeatSubtitle: TextView? = null

    private var switchAutoplayNext: SwitchMaterial? = null
    private var txtAutoplayNextSubtitle: TextView? = null

    // Media Player settings
    private var switchBackgroundVideoMode: SwitchMaterial? = null
    private var txtBackgroundVideoSubtitle: TextView? = null
    private var switchMiniPlayer: SwitchMaterial? = null
    private var txtMiniPlayerSubtitle: TextView? = null
    private var switchResumeAfterInterruption: SwitchMaterial? = null
    private var txtResumeAfterInterruptionSubtitle: TextView? = null

    private var switchBreadcrumbs: SwitchMaterial? = null
    private var txtBreadcrumbsSubtitle: TextView? = null

    private lateinit var switchScrollingText: SwitchMaterial
    private lateinit var txtScrollingTextSubtitle: TextView

    private lateinit var switchGridIndicators: SwitchMaterial
    private lateinit var txtGridIndicatorsSubtitle: TextView

    private lateinit var switchTipJarPopup: SwitchMaterial
    private lateinit var txtTipJarPopupSubtitle: TextView

    private var switchLeftHandedFab: SwitchMaterial? = null
    private var txtLeftHandedFabSubtitle: TextView? = null

    private lateinit var cardNetworkThumbnails: MaterialCardView
    private lateinit var txtVideoThumbnailTimeSubtitle: TextView
    private lateinit var txtApkExtractSubtitle: TextView

    private var cardSearchContainer: MaterialCardView? = null
    private var edtSettingsSearch: EditText? = null
    private var btnSearchClear: ImageView? = null
    private var switchSettingsSearch: SwitchMaterial? = null
    private var txtSettingsSearchSubtitle: TextView? = null
    private val originalVisibilities = HashMap<View, Int>()



    companion object {
        private const val PREFS_ANALYTICS = "analytics_prefs"
        private const val KEY_ANALYTICS_ENABLED = "analytics_enabled"

        fun start(context: android.content.Context) =
            context.startActivity(Intent(context, SettingsActivity::class.java))
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        handledFontChange = savedInstanceState?.getBoolean("font_handled", false) ?: false
        handledLocaleChange = savedInstanceState?.getBoolean("locale_handled", false) ?: false
        enableEdgeToEdge()

        isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_settings_tv)
        } else {
            setContentView(R.layout.activity_settings)
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

        // Back button
        val btnBack = findViewById<ImageView?>(R.id.btnBack)
        if (isTv) {
            val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
            val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
            btnBack?.imageTintList = whiteCsl
            btnBack?.setOnFocusChangeListener { _, hasFocus ->
                btnBack.imageTintList = if (hasFocus) blackCsl else whiteCsl
            }
        }
        btnBack?.setOnClickListener { finish() }

        // List view size toolbar button
        val btnListViewSize = findViewById<ImageView?>(R.id.btnListViewSize)
        if (isTv) {
            btnListViewSize?.let { btn ->
                val whiteCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_text_primary))
                val blackCsl = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
                btn.imageTintList = whiteCsl
                btn.setOnFocusChangeListener { _, hasFocus ->
                    btn.imageTintList = if (hasFocus) blackCsl else whiteCsl
                }
                btn.setOnClickListener { showTvListSizeOptions() }
            }
        } else {
            btnListViewSize?.setOnClickListener { showListSizeBottomSheet() }
        }

        // Font Size row
        val cardFontSize = findViewById<MaterialCardView>(R.id.cardFontSize)
        cardFontSize.setOnClickListener {
            startActivity(Intent(this, FontSizeActivity::class.java))
        }

        // File Tags row (Mobile Only)
        val cardFileTags = findViewById<MaterialCardView?>(R.id.cardFileTags)
        if (isTv) {
            cardFileTags?.visibility = View.GONE
        } else {
            cardFileTags?.setOnClickListener {
                startActivity(Intent(this, FileTagsSettingsActivity::class.java))
            }
        }

        // Analytics toggle
        val cardAnalytics = findViewById<MaterialCardView>(R.id.cardAnalytics)
        switchAnalytics = findViewById(R.id.switchAnalytics)
        txtAnalyticsSubtitle = findViewById(R.id.txtAnalyticsSubtitle)

        val prefs = getSharedPreferences(PREFS_ANALYTICS, Context.MODE_PRIVATE)
        val analyticsEnabled = prefs.getBoolean(KEY_ANALYTICS_ENABLED, true)
        switchAnalytics.isChecked = analyticsEnabled
        updateAnalyticsSubtitle(analyticsEnabled)

        // Tapping the whole card or just the switch both toggle it
        cardAnalytics.setOnClickListener { toggleAnalytics(prefs) }
        switchAnalytics.setOnCheckedChangeListener(null) // avoid double-fire; card handles it

        // Hidden Files toggle
        val cardHiddenFiles = findViewById<MaterialCardView>(R.id.cardHiddenFiles)
        switchHiddenFiles = findViewById(R.id.switchHiddenFiles)
        txtHiddenFilesSubtitle = findViewById(R.id.txtHiddenFilesSubtitle)

        val hiddenFilesEnabled = za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
        switchHiddenFiles.isChecked = hiddenFilesEnabled
        updateHiddenFilesSubtitle(hiddenFilesEnabled)

        cardHiddenFiles.setOnClickListener { toggleHiddenFiles() }
        switchHiddenFiles.setOnCheckedChangeListener(null)

        // Recycle Bin toggle
        val cardRecycleBin = findViewById<MaterialCardView>(R.id.cardRecycleBin)
        switchRecycleBin = findViewById(R.id.switchRecycleBin)
        txtRecycleBinSubtitle = findViewById(R.id.txtRecycleBinSubtitle)

        val recycleBinEnabled = za.kilowatch.ultimatefilemanager.recycle.RecycleBinSettingsManager.isEnabled(this)
        switchRecycleBin.isChecked = recycleBinEnabled
        updateRecycleBinSubtitle(recycleBinEnabled)

        cardRecycleBin.setOnClickListener { toggleRecycleBin() }
        switchRecycleBin.setOnCheckedChangeListener(null)

        // Media Thumbnails toggle
        val cardMediaThumbnails = findViewById<MaterialCardView>(R.id.cardMediaThumbnails)
        switchMediaThumbnails = findViewById(R.id.switchMediaThumbnails)
        txtMediaThumbnailsSubtitle = findViewById(R.id.txtMediaThumbnailsSubtitle)

        val thumbnailsEnabled = za.kilowatch.ultimatefilemanager.settings.ThumbnailPreferenceManager.isEnabled(this)
        switchMediaThumbnails.isChecked = thumbnailsEnabled
        updateThumbnailsSubtitle(thumbnailsEnabled)

        cardMediaThumbnails.setOnClickListener { toggleMediaThumbnails() }
        switchMediaThumbnails.setOnCheckedChangeListener(null)

        // Cache Copying toggle
        val cardCacheCopy = findViewById<MaterialCardView>(R.id.cardCacheCopy)
        switchCacheCopy = findViewById(R.id.switchCacheCopy)
        txtCacheCopySubtitle = findViewById(R.id.txtCacheCopySubtitle)

        val cacheCopyEnabled = za.kilowatch.ultimatefilemanager.settings.CacheCopyPreferenceManager.isEnabled(this)
        switchCacheCopy.isChecked = cacheCopyEnabled
        updateCacheCopySubtitle(cacheCopyEnabled)

        cardCacheCopy.setOnClickListener { toggleCacheCopy() }
        switchCacheCopy.setOnCheckedChangeListener(null)

        // Quick Transfer toggle
        val cardQuickTransfer = findViewById<MaterialCardView>(R.id.cardQuickTransfer)
        switchQuickTransfer = findViewById(R.id.switchQuickTransfer)
        txtQuickTransferSubtitle = findViewById(R.id.txtQuickTransferSubtitle)

        val quickTransferEnabled = za.kilowatch.ultimatefilemanager.settings.QuickTransferPreferenceManager.isEnabled(this)
        switchQuickTransfer.isChecked = quickTransferEnabled
        updateQuickTransferSubtitle(quickTransferEnabled)

        cardQuickTransfer.setOnClickListener { toggleQuickTransfer() }
        switchQuickTransfer.setOnCheckedChangeListener(null)

        // Network Open Cache toggle
        val cardNetworkOpenCache = findViewById<MaterialCardView>(R.id.cardNetworkOpenCache)
        switchNetworkOpenCache = findViewById(R.id.switchNetworkOpenCache)
        txtNetworkOpenCacheSubtitle = findViewById(R.id.txtNetworkOpenCacheSubtitle)

        val networkOpenCacheEnabled = za.kilowatch.ultimatefilemanager.settings.NetworkOpenCachePreferenceManager.isEnabled(this)
        switchNetworkOpenCache.isChecked = networkOpenCacheEnabled
        updateNetworkOpenCacheSubtitle(networkOpenCacheEnabled)

        cardNetworkOpenCache.setOnClickListener { toggleNetworkOpenCache() }
        switchNetworkOpenCache.setOnCheckedChangeListener(null)

        // Twin Window Layout toggle
        val cardTwinWindowLayout = findViewById<MaterialCardView?>(R.id.cardTwinWindowLayout)
        if (cardTwinWindowLayout != null) {
            switchTwinWindowLayout = findViewById(R.id.switchTwinWindowLayout)
            txtTwinWindowLayoutSubtitle = findViewById(R.id.txtTwinWindowLayoutSubtitle)

            val twinWindowVerticalEnabled = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isVerticalSplit(this)
            switchTwinWindowLayout?.isChecked = twinWindowVerticalEnabled
            updateTwinWindowLayoutSubtitle(twinWindowVerticalEnabled)

            cardTwinWindowLayout.setOnClickListener { toggleTwinWindowLayout() }
            switchTwinWindowLayout?.setOnCheckedChangeListener(null)
        }

        // Twin Window Default Startup toggle
        val cardTwinWindowStartup = findViewById<MaterialCardView?>(R.id.cardTwinWindowStartup)
        if (cardTwinWindowStartup != null) {
            switchTwinWindowStartup = findViewById(R.id.switchTwinWindowStartup)
            txtTwinWindowStartupSubtitle = findViewById(R.id.txtTwinWindowStartupSubtitle)

            val twinWindowStartupEnabled = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isDefaultStartup(this)
            switchTwinWindowStartup?.isChecked = twinWindowStartupEnabled
            updateTwinWindowStartupSubtitle(twinWindowStartupEnabled)

            cardTwinWindowStartup.setOnClickListener { toggleTwinWindowStartup() }
            switchTwinWindowStartup?.setOnCheckedChangeListener(null)
        }

        // Side-by-Side Video toggle
        val cardSideBySideVideo = findViewById<MaterialCardView?>(R.id.cardSideBySideVideo)
        if (cardSideBySideVideo != null) {
            switchSideBySideVideo = findViewById(R.id.switchSideBySideVideo)
            txtSideBySideVideoSubtitle = findViewById(R.id.txtSideBySideVideoSubtitle)

            val sideBySideEnabled = SideBySideVideoPreferenceManager.isEnabled(this)
            switchSideBySideVideo?.isChecked = sideBySideEnabled
            updateSideBySideVideoSubtitle(sideBySideEnabled)

            cardSideBySideVideo.setOnClickListener { toggleSideBySideVideo() }
            switchSideBySideVideo?.setOnCheckedChangeListener(null)
        }

        // Side-by-Side Video Show Controls On Repeat toggle
        val cardSideBySideVideoShowControlsOnRepeat = findViewById<MaterialCardView?>(R.id.cardSideBySideVideoShowControlsOnRepeat)
        if (cardSideBySideVideoShowControlsOnRepeat != null) {
            switchSideBySideVideoShowControlsOnRepeat = findViewById(R.id.switchSideBySideVideoShowControlsOnRepeat)
            txtSideBySideVideoShowControlsOnRepeatSubtitle = findViewById(R.id.txtSideBySideVideoShowControlsOnRepeatSubtitle)

            val showControlsOnRepeatEnabled = SideBySideVideoPreferenceManager.isShowControlsOnRepeat(this)
            switchSideBySideVideoShowControlsOnRepeat?.isChecked = showControlsOnRepeatEnabled
            updateSideBySideVideoShowControlsOnRepeatSubtitle(showControlsOnRepeatEnabled)

            cardSideBySideVideoShowControlsOnRepeat.setOnClickListener { toggleSideBySideVideoShowControlsOnRepeat() }
            switchSideBySideVideoShowControlsOnRepeat?.setOnCheckedChangeListener(null)
        }

        // Auto-play Next File toggle
        val cardAutoplayNext = findViewById<MaterialCardView?>(R.id.cardAutoplayNext)
        if (cardAutoplayNext != null) {
            switchAutoplayNext = findViewById(R.id.switchAutoplayNext)
            txtAutoplayNextSubtitle = findViewById(R.id.txtAutoplayNextSubtitle)

            val autoplayEnabled = AutoplayPreferenceManager.isEnabled(this)
            switchAutoplayNext?.isChecked = autoplayEnabled
            updateAutoplayNextSubtitle(autoplayEnabled)

            cardAutoplayNext.setOnClickListener { toggleAutoplayNext() }
            switchAutoplayNext?.setOnCheckedChangeListener(null)
        }

        // Background Video Mode
        val cardBackgroundVideoMode = findViewById<MaterialCardView?>(R.id.cardBackgroundVideoMode)
        if (cardBackgroundVideoMode != null) {
            switchBackgroundVideoMode = findViewById(R.id.switchBackgroundVideoMode)
            txtBackgroundVideoSubtitle = findViewById(R.id.txtBackgroundVideoSubtitle)
            val mode = PlayerPreferencesManager.getBackgroundVideoMode(this)
            switchBackgroundVideoMode?.isChecked = mode == BackgroundVideoMode.PIP
            updateBackgroundVideoSubtitle(mode)
            cardBackgroundVideoMode.setOnClickListener { toggleBackgroundVideoMode() }
            switchBackgroundVideoMode?.setOnCheckedChangeListener(null)
        }

        // Mini-Player toggle
        val cardMiniPlayer = findViewById<MaterialCardView?>(R.id.cardMiniPlayer)
        if (cardMiniPlayer != null) {
            switchMiniPlayer = findViewById(R.id.switchMiniPlayer)
            txtMiniPlayerSubtitle = findViewById(R.id.txtMiniPlayerSubtitle)
            val mpEnabled = PlayerPreferencesManager.isMiniPlayerEnabled(this)
            switchMiniPlayer?.isChecked = mpEnabled
            updateMiniPlayerSubtitle(mpEnabled)
            cardMiniPlayer.setOnClickListener { toggleMiniPlayer() }
            switchMiniPlayer?.setOnCheckedChangeListener(null)
        }

        // Resume After Interruption toggle
        val cardResumeAfterInterruption = findViewById<MaterialCardView?>(R.id.cardResumeAfterInterruption)
        if (cardResumeAfterInterruption != null) {
            switchResumeAfterInterruption = findViewById(R.id.switchResumeAfterInterruption)
            txtResumeAfterInterruptionSubtitle = findViewById(R.id.txtResumeAfterInterruptionSubtitle)
            val resumeEnabled = PlayerPreferencesManager.isResumeAfterInterruption(this)
            switchResumeAfterInterruption?.isChecked = resumeEnabled
            updateResumeAfterInterruptionSubtitle(resumeEnabled)
            cardResumeAfterInterruption.setOnClickListener { toggleResumeAfterInterruption() }
            switchResumeAfterInterruption?.setOnCheckedChangeListener(null)
        }

        // Breadcrumbs toggle (mobile only)
        val cardBreadcrumbs = findViewById<MaterialCardView?>(R.id.cardBreadcrumbs)
        if (cardBreadcrumbs != null) {
            switchBreadcrumbs = findViewById(R.id.switchBreadcrumbs)
            txtBreadcrumbsSubtitle = findViewById(R.id.txtBreadcrumbsSubtitle)

            val breadcrumbsEnabled = BreadcrumbsPreferenceManager.isEnabled(this)
            switchBreadcrumbs?.isChecked = breadcrumbsEnabled
            updateBreadcrumbsSubtitle(breadcrumbsEnabled)

            cardBreadcrumbs.setOnClickListener { toggleBreadcrumbs() }
            switchBreadcrumbs?.setOnCheckedChangeListener(null)
        }

        // Scrolling Text toggle
        val cardScrollingText = findViewById<MaterialCardView>(R.id.cardScrollingText)
        switchScrollingText = findViewById(R.id.switchScrollingText)
        txtScrollingTextSubtitle = findViewById(R.id.txtScrollingTextSubtitle)

        val scrollingTextEnabled = ScrollingTextPreferenceManager.isEnabled(this)
        switchScrollingText.isChecked = scrollingTextEnabled
        updateScrollingTextSubtitle(scrollingTextEnabled)

        cardScrollingText.setOnClickListener { toggleScrollingText() }
        switchScrollingText.setOnCheckedChangeListener(null)

        // Left-handed FAB toggle (Mobile Only)
        val cardLeftHandedFab = findViewById<MaterialCardView?>(R.id.cardLeftHandedFab)
        if (cardLeftHandedFab != null) {
            switchLeftHandedFab = findViewById(R.id.switchLeftHandedFab)
            txtLeftHandedFabSubtitle = findViewById(R.id.txtLeftHandedFabSubtitle)

            val leftHanded = LeftHandedFabPreferenceManager.isLeftHanded(this)
            switchLeftHandedFab?.isChecked = leftHanded
            updateLeftHandedFabSubtitle(leftHanded)

            cardLeftHandedFab.setOnClickListener { toggleLeftHandedFab() }
            switchLeftHandedFab?.setOnCheckedChangeListener(null)
        }

        // Grid Indicators toggle — ON = hide, OFF = show (default)
        val cardGridIndicators = findViewById<MaterialCardView>(R.id.cardGridIndicators)
        switchGridIndicators = findViewById(R.id.switchGridIndicators)
        txtGridIndicatorsSubtitle = findViewById(R.id.txtGridIndicatorsSubtitle)

        val gridIndicatorsHidden = GridIndicatorsPreferenceManager.isHidden(this)
        switchGridIndicators.isChecked = gridIndicatorsHidden
        updateGridIndicatorsSubtitle(gridIndicatorsHidden)

        cardGridIndicators.setOnClickListener { toggleGridIndicators() }
        switchGridIndicators.setOnCheckedChangeListener(null)

        // Tip Jar Progress Popup toggle — ON = show, OFF = hide (default ON)
        val cardTipJarPopup = findViewById<MaterialCardView>(R.id.cardTipJarPopup)
        if (cardTipJarPopup != null) {
            switchTipJarPopup = findViewById(R.id.switchTipJarPopup)
            txtTipJarPopupSubtitle = findViewById(R.id.txtTipJarPopupSubtitle)

            val popupEnabled = za.kilowatch.ultimatefilemanager.billing.LoyaltyPrefs.isTipJarPopupEnabled(this)
            switchTipJarPopup.isChecked = popupEnabled
            updateTipJarPopupSubtitle(popupEnabled)

            cardTipJarPopup.setOnClickListener { toggleTipJarPopup() }
            switchTipJarPopup.setOnCheckedChangeListener(null)
        }

        // File Server System Tiles row (mobile-only)
        val cardFileServerTiles = findViewById<MaterialCardView?>(R.id.cardFileServerTiles)
        cardFileServerTiles?.setOnClickListener {
            startActivity(Intent(this, FileServerTilesActivity::class.java))
        }

        // Video Thumbnail Time row
        val cardVideoThumbnailTime = findViewById<MaterialCardView>(R.id.cardVideoThumbnailTime)
        txtVideoThumbnailTimeSubtitle = findViewById(R.id.txtVideoThumbnailTimeSubtitle)
        updateVideoThumbnailTimeSubtitle()
        cardVideoThumbnailTime.setOnClickListener {
            startActivity(Intent(this, VideoThumbnailTimeActivity::class.java))
        }

        // Network Thumbnails row
        cardNetworkThumbnails = findViewById(R.id.cardNetworkThumbnails)
        cardNetworkThumbnails.setOnClickListener {
            startActivity(Intent(this, NetworkThumbnailSettingsActivity::class.java))
        }

        // Storage Indexer row
        val cardDbViewer = findViewById<MaterialCardView>(R.id.cardDbViewer)
        cardDbViewer.setOnClickListener {
            startActivity(Intent(this, StorageIndexerActivity::class.java))
        }

        // Manage Custom Drive Names row
        val cardStorageRename = findViewById<MaterialCardView>(R.id.cardStorageRename)
        cardStorageRename?.setOnClickListener {
            startActivity(Intent(this, za.kilowatch.ultimatefilemanager.settings.renamer.StorageRenameActivity::class.java))
        }

        // Manage Favorites row
        val cardFavorites = findViewById<MaterialCardView>(R.id.cardFavorites)
        cardFavorites.setOnClickListener {
            startActivity(Intent(this, ManageFavoritesActivity::class.java))
        }

        // Folder Sort Overrides row
        val cardFolderSort = findViewById<MaterialCardView?>(R.id.cardFolderSort)
        cardFolderSort?.setOnClickListener {
            startActivity(Intent(this, FolderSortManagerActivity::class.java))
        }

        // Default Applications row
        val cardDefaultApps = findViewById<MaterialCardView>(R.id.cardDefaultApps)
        cardDefaultApps.setOnClickListener {
            startActivity(Intent(this, DefaultAppsActivity::class.java))
        }

        // Long Press Duration row
        val cardLongPressDuration = findViewById<MaterialCardView>(R.id.cardLongPressDuration)
        cardLongPressDuration.setOnClickListener {
            startActivity(Intent(this, LongPressDurationActivity::class.java))
        }

        // Controls Auto-Hide Duration row
        val cardControlsTimeout = findViewById<MaterialCardView>(R.id.cardControlsTimeout)
        cardControlsTimeout.setOnClickListener {
            startActivity(Intent(this, ControlsTimeoutActivity::class.java))
        }

        // Long Press Toolbar Icons row
        val cardToolbarIcons = findViewById<MaterialCardView?>(R.id.cardToolbarIcons)
        cardToolbarIcons?.setOnClickListener {
            startActivity(Intent(this, ToolbarIconsActivity::class.java))
        }

        // APK / XAPK Extract row
        val cardApkExtract = findViewById<MaterialCardView>(R.id.cardApkExtract)
        txtApkExtractSubtitle = findViewById(R.id.txtApkExtractSubtitle)
        cardApkExtract.setOnClickListener {
            startActivity(Intent(this, ExtractApkSettingsActivity::class.java))
        }

        // TV focus highlight
        if (isTv) {
            setupTvCardFocus(cardFontSize)
            setupTvCardFocus(cardAnalytics)
            setupTvCardFocus(cardDbViewer)
            cardStorageRename?.let { setupTvCardFocus(it) }
            setupTvCardFocus(cardHiddenFiles)
            setupTvCardFocus(cardRecycleBin)
            setupTvCardFocus(cardMediaThumbnails)
            findViewById<MaterialCardView>(R.id.cardVideoThumbnailTime)?.let { setupTvCardFocus(it) }
            setupTvCardFocus(cardNetworkThumbnails)
            setupTvCardFocus(cardCacheCopy)
            findViewById<MaterialCardView?>(R.id.cardQuickTransfer)?.let { setupTvCardFocus(it) }
            findViewById<MaterialCardView?>(R.id.cardNetworkOpenCache)?.let { setupTvCardFocus(it) }
            cardTwinWindowLayout?.let { setupTvCardFocus(it) }
            cardTwinWindowStartup?.let { setupTvCardFocus(it) }
            findViewById<MaterialCardView?>(R.id.cardSideBySideVideo)?.let { setupTvCardFocus(it) }
            findViewById<MaterialCardView?>(R.id.cardAutoplayNext)?.let { setupTvCardFocus(it) }
            findViewById<MaterialCardView?>(R.id.cardBackgroundVideoMode)?.let { setupTvCardFocus(it) }
            findViewById<MaterialCardView?>(R.id.cardMiniPlayer)?.let { setupTvCardFocus(it) }
            findViewById<MaterialCardView?>(R.id.cardResumeAfterInterruption)?.let { setupTvCardFocus(it) }
            cardBreadcrumbs?.let { setupTvCardFocus(it) }
            setupTvCardFocus(cardFavorites)
            cardFolderSort?.let { setupTvCardFocus(it) }
            setupTvCardFocus(cardDefaultApps)
            setupTvCardFocus(cardLongPressDuration)
            findViewById<MaterialCardView?>(R.id.cardControlsTimeout)?.let { setupTvCardFocus(it) }
            cardToolbarIcons?.let { setupTvCardFocus(it) }
            findViewById<MaterialCardView?>(R.id.cardDefaultStartScreen)?.let { setupTvCardFocus(it) }
            findViewById<MaterialCardView?>(R.id.cardLanguage)?.let { setupTvCardFocus(it) }
            findViewById<MaterialCardView?>(R.id.cardMainMenuViewMode)?.let { setupTvCardFocus(it) }
            findViewById<MaterialCardView?>(R.id.cardAppearance)?.let { setupTvCardFocus(it) }
            findViewById<MaterialCardView?>(R.id.cardDefaultIconColors)?.let { setupTvCardFocus(it) }
            findViewById<MaterialCardView?>(R.id.cardApkExtract)?.let { setupTvCardFocus(it) }
            findViewById<MaterialCardView?>(R.id.cardBackupRestore)?.let { setupTvCardFocus(it) }
            findViewById<MaterialCardView?>(R.id.cardAutoBackup)?.let { setupTvCardFocus(it) }
            findViewById<MaterialCardView?>(R.id.cardIcons)?.let { setupTvCardFocus(it) }
            findViewById<MaterialCardView?>(R.id.cardScrollingText)?.let { setupTvCardFocus(it) }
            findViewById<MaterialCardView?>(R.id.cardGridIndicators)?.let { setupTvCardFocus(it) }
            findViewById<MaterialCardView?>(R.id.cardTipJarPopup)?.let { setupTvCardFocus(it) }
        }

        // Icons row
        val cardIcons = findViewById<MaterialCardView?>(R.id.cardIcons)
        cardIcons?.setOnClickListener {
            startActivity(Intent(this, IconCustomizationActivity::class.java))
        }

        // Main Menu View Mode row
        val cardMainMenuViewMode = findViewById<MaterialCardView?>(R.id.cardMainMenuViewMode)
        cardMainMenuViewMode?.setOnClickListener {
            startActivity(Intent(this, MainMenuViewModeActivity::class.java))
        }

        // Default Start Screen row
        val cardDefaultStartScreen = findViewById<MaterialCardView?>(R.id.cardDefaultStartScreen)
        cardDefaultStartScreen?.setOnClickListener {
            startActivity(Intent(this, StartScreenPreferenceActivity::class.java))
        }

        // Language Selection row
        val cardLanguage = findViewById<MaterialCardView?>(R.id.cardLanguage)
        cardLanguage?.setOnClickListener {
            startActivity(Intent(this, LanguageActivity::class.java))
        }

        // Appearance / Theme row
        val cardAppearance = findViewById<MaterialCardView?>(R.id.cardAppearance)
        cardAppearance?.setOnClickListener {
            startActivity(Intent(this, ThemeActivity::class.java))
        }

        // Folder Transitions row (Mobile Only - completely hidden on TV)
        val cardFolderTransitions = findViewById<MaterialCardView?>(R.id.cardFolderTransitions)
        if (za.kilowatch.ultimatefilemanager.util.DeviceUtils.isTvDevice(this)) {
            cardFolderTransitions?.visibility = android.view.View.GONE
        } else {
            val switchFolderTransitions = findViewById<com.google.android.material.switchmaterial.SwitchMaterial?>(R.id.switchFolderTransitions)
            switchFolderTransitions?.isChecked = za.kilowatch.ultimatefilemanager.util.AnimationHelper.areFolderTransitionsEnabled(this)
            cardFolderTransitions?.setOnClickListener {
                val newState = !(switchFolderTransitions?.isChecked ?: true)
                switchFolderTransitions?.isChecked = newState
                za.kilowatch.ultimatefilemanager.util.AnimationHelper.setFolderTransitionsEnabled(this, newState)
            }
        }

        // Default Icon Colors row
        val cardDefaultIconColors = findViewById<MaterialCardView?>(R.id.cardDefaultIconColors)
        cardDefaultIconColors?.setOnClickListener {
            if (DeviceUtils.isTvDevice(this)) {
                startActivity(Intent(this, DefaultIconColorTvActivity::class.java))
            } else {
                DefaultIconColorBottomSheet().show(supportFragmentManager, "DefaultIconColorBottomSheet")
            }
        }

        // Backup & Restore row
        val cardBackupRestore = findViewById<MaterialCardView?>(R.id.cardBackupRestore)
        cardBackupRestore?.setOnClickListener {
            startActivity(Intent(this, BackupRestoreActivity::class.java))
        }

        // Auto Backup row
        val cardAutoBackup = findViewById<MaterialCardView?>(R.id.cardAutoBackup)
        cardAutoBackup?.setOnClickListener {
            startActivity(Intent(this, AutoBackupActivity::class.java))
        }

        // Initialize settings search view bindings
        cardSearchContainer = findViewById(R.id.cardSearchContainer)
        edtSettingsSearch = findViewById(R.id.edtSettingsSearch)
        btnSearchClear = findViewById(R.id.btnSearchClear)
        switchSettingsSearch = findViewById(R.id.switchSettingsSearch)
        txtSettingsSearchSubtitle = findViewById(R.id.txtSettingsSearchSubtitle)

        // Capture original visibilities of settings list children
        val layoutSettingsList = findViewById<android.widget.LinearLayout>(R.id.layoutSettingsList)
        if (layoutSettingsList != null) {
            for (i in 0 until layoutSettingsList.childCount) {
                val child = layoutSettingsList.getChildAt(i)
                originalVisibilities[child] = child.visibility
            }
        }

        // Setup search toggle card listener
        val cardSettingsSearch = findViewById<MaterialCardView>(R.id.cardSettingsSearch)
        cardSettingsSearch?.setOnClickListener {
            val sw = switchSettingsSearch ?: return@setOnClickListener
            val newValue = !sw.isChecked
            SettingsSearchPreferenceManager.setEnabled(this, newValue)
            updateSearchContainerVisibility(newValue)
        }

        // Setup text listener for search edit text
        edtSettingsSearch?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                btnSearchClear?.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                filterSettings(query)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Setup clear search button listener
        btnSearchClear?.setOnClickListener {
            edtSettingsSearch?.text = null
        }

        // Focus listeners for TV search card highlighting
        if (isTv) {
            val yellowFill  = getColor(R.color.tv_button_focused_yellow)
            val glassColor  = getColor(R.color.tv_glass_white_10)
            val primaryText = getColor(R.color.tv_text_primary)
            val secondText  = getColor(R.color.tv_text_secondary)
            val imgSearchIcon = findViewById<ImageView>(R.id.imgSearchIcon)

            val updateSearchFocus = {
                val hasFocus = edtSettingsSearch?.hasFocus() == true || btnSearchClear?.hasFocus() == true
                if (hasFocus) {
                    cardSearchContainer?.setCardBackgroundColor(yellowFill)
                    edtSettingsSearch?.setTextColor(getColor(R.color.tv_button_focused_yellow_text))
                    edtSettingsSearch?.setHintTextColor(getColor(R.color.tv_button_focused_yellow_text))
                    imgSearchIcon?.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
                    btnSearchClear?.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.tv_button_focused_yellow_text))
                } else {
                    cardSearchContainer?.setCardBackgroundColor(glassColor)
                    edtSettingsSearch?.setTextColor(primaryText)
                    edtSettingsSearch?.setHintTextColor(secondText)
                    imgSearchIcon?.imageTintList = android.content.res.ColorStateList.valueOf(secondText)
                    btnSearchClear?.imageTintList = android.content.res.ColorStateList.valueOf(secondText)
                }
            }
            edtSettingsSearch?.setOnFocusChangeListener { _, _ -> updateSearchFocus() }
            btnSearchClear?.setOnFocusChangeListener { _, _ -> updateSearchFocus() }
            
            cardSettingsSearch?.let { setupTvCardFocus(it) }
        }

        // Apply initial search container visibility from preference manager
        val isSearchEnabled = SettingsSearchPreferenceManager.isEnabled(this)
        updateSearchContainerVisibility(isSearchEnabled)

        applyListSize()
    }

    override fun onResume() {
        super.onResume()
        applyListSize()
        // Refresh the font size subtitle
        val txtFontSizeSubtitle = findViewById<TextView?>(R.id.txtFontSizeSubtitle)
        txtFontSizeSubtitle?.text = when (FontSizeHelper.getSavedSize(this)) {
            FontSizeHelper.FONT_SMALL  -> getString(R.string.font_size_small)
            FontSizeHelper.FONT_LARGE  -> getString(R.string.font_size_large)
            else                       -> getString(R.string.font_size_normal)
        }

        // Refresh language subtitle
        val txtLanguageSubtitle = findViewById<TextView?>(R.id.txtLanguageSubtitle)
        txtLanguageSubtitle?.text = when (LocaleHelper.getSavedLocale(this)) {
            LocaleHelper.LOCALE_EN -> getString(R.string.language_english)
            LocaleHelper.LOCALE_DE -> getString(R.string.language_german)
            LocaleHelper.LOCALE_JA -> getString(R.string.language_japanese)
            LocaleHelper.LOCALE_AR -> getString(R.string.language_arabic)
            LocaleHelper.LOCALE_ES -> getString(R.string.language_spanish)
            LocaleHelper.LOCALE_FR -> getString(R.string.language_french)
            LocaleHelper.LOCALE_HI -> getString(R.string.language_hindi)
            LocaleHelper.LOCALE_ID -> getString(R.string.language_indonesian)
            LocaleHelper.LOCALE_KO -> getString(R.string.language_korean)
            LocaleHelper.LOCALE_PT -> getString(R.string.language_portuguese)
            LocaleHelper.LOCALE_RU -> getString(R.string.language_russian)
            LocaleHelper.LOCALE_TR -> getString(R.string.language_turkish)
            LocaleHelper.LOCALE_UK -> getString(R.string.language_ukrainian)
            else                   -> getString(R.string.language_system_default)
        }

        // Refresh analytics subtitle (in case something changed externally)
        if (::switchAnalytics.isInitialized) {
            val prefs = getSharedPreferences(PREFS_ANALYTICS, Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean(KEY_ANALYTICS_ENABLED, true)
            switchAnalytics.isChecked = enabled
            updateAnalyticsSubtitle(enabled)
        }

        // Refresh hidden files subtitle
        if (::switchHiddenFiles.isInitialized) {
            val enabled = za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled
            switchHiddenFiles.isChecked = enabled
            updateHiddenFilesSubtitle(enabled)
        }

        // Refresh media thumbnails subtitle
        if (::switchMediaThumbnails.isInitialized) {
            val enabled = za.kilowatch.ultimatefilemanager.settings.ThumbnailPreferenceManager.isEnabled(this)
            switchMediaThumbnails.isChecked = enabled
            updateThumbnailsSubtitle(enabled)
        }

        // Refresh video thumbnail time subtitle
        if (::txtVideoThumbnailTimeSubtitle.isInitialized) {
            updateVideoThumbnailTimeSubtitle()
        }

        // Refresh cache copying subtitle
        if (::switchCacheCopy.isInitialized) {
            val enabled = za.kilowatch.ultimatefilemanager.settings.CacheCopyPreferenceManager.isEnabled(this)
            switchCacheCopy.isChecked = enabled
            updateCacheCopySubtitle(enabled)
        }

        // Refresh quick transfer subtitle
        if (::switchQuickTransfer.isInitialized) {
            val enabled = za.kilowatch.ultimatefilemanager.settings.QuickTransferPreferenceManager.isEnabled(this)
            switchQuickTransfer.isChecked = enabled
            updateQuickTransferSubtitle(enabled)
        }

        // Refresh network open cache subtitle
        if (::switchNetworkOpenCache.isInitialized) {
            val enabled = za.kilowatch.ultimatefilemanager.settings.NetworkOpenCachePreferenceManager.isEnabled(this)
            switchNetworkOpenCache.isChecked = enabled
            updateNetworkOpenCacheSubtitle(enabled)
        }

        // Refresh Twin Window layout subtitle (mobile only)
        switchTwinWindowLayout?.let { sw ->
            val enabled = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isVerticalSplit(this)
            sw.isChecked = enabled
            updateTwinWindowLayoutSubtitle(enabled)
        }

        // Refresh Twin Window startup subtitle
        switchTwinWindowStartup?.let { sw ->
            val enabled = za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.isDefaultStartup(this)
            sw.isChecked = enabled
            updateTwinWindowStartupSubtitle(enabled)
        }

        // Refresh Side-by-Side Video subtitle
        switchSideBySideVideo?.let { sw ->
            val enabled = SideBySideVideoPreferenceManager.isEnabled(this)
            sw.isChecked = enabled
            updateSideBySideVideoSubtitle(enabled)
        }

        // Refresh Side-by-Side Video Show Controls On Repeat subtitle
        switchSideBySideVideoShowControlsOnRepeat?.let { sw ->
            val enabled = SideBySideVideoPreferenceManager.isShowControlsOnRepeat(this)
            sw.isChecked = enabled
            updateSideBySideVideoShowControlsOnRepeatSubtitle(enabled)
        }

        // Refresh Auto-play Next subtitle
        switchAutoplayNext?.let { sw ->
            val enabled = AutoplayPreferenceManager.isEnabled(this)
            sw.isChecked = enabled
            updateAutoplayNextSubtitle(enabled)
        }

        // Refresh Media Player settings
        txtBackgroundVideoSubtitle?.let {
            val mode = PlayerPreferencesManager.getBackgroundVideoMode(this)
            switchBackgroundVideoMode?.isChecked = mode == BackgroundVideoMode.PIP
            updateBackgroundVideoSubtitle(mode)
        }
        switchMiniPlayer?.let { sw ->
            val enabled = PlayerPreferencesManager.isMiniPlayerEnabled(this)
            sw.isChecked = enabled
            updateMiniPlayerSubtitle(enabled)
        }
        switchResumeAfterInterruption?.let { sw ->
            val enabled = PlayerPreferencesManager.isResumeAfterInterruption(this)
            sw.isChecked = enabled
            updateResumeAfterInterruptionSubtitle(enabled)
        }

        // Refresh Breadcrumbs subtitle
        switchBreadcrumbs?.let { sw ->
            val enabled = BreadcrumbsPreferenceManager.isEnabled(this)
            sw.isChecked = enabled
            updateBreadcrumbsSubtitle(enabled)
        }

        // Refresh Scrolling Text subtitle
        if (::switchScrollingText.isInitialized) {
            val enabled = ScrollingTextPreferenceManager.isEnabled(this)
            switchScrollingText.isChecked = enabled
            updateScrollingTextSubtitle(enabled)
        }

        // Refresh Left-handed FAB subtitle
        switchLeftHandedFab?.let { sw ->
            val leftHanded = LeftHandedFabPreferenceManager.isLeftHanded(this)
            sw.isChecked = leftHanded
            updateLeftHandedFabSubtitle(leftHanded)
        }

        // Refresh Grid Indicators subtitle
        if (::switchGridIndicators.isInitialized) {
            val hidden = GridIndicatorsPreferenceManager.isHidden(this)
            switchGridIndicators.isChecked = hidden
            updateGridIndicatorsSubtitle(hidden)
        }

        // Refresh Tip Jar Popup subtitle
        if (::switchTipJarPopup.isInitialized) {
            val enabled = za.kilowatch.ultimatefilemanager.billing.LoyaltyPrefs.isTipJarPopupEnabled(this)
            switchTipJarPopup.isChecked = enabled
            updateTipJarPopupSubtitle(enabled)
        }

        // Refresh Main Menu View Mode subtitle
        val txtMainMenuViewModeSubtitle = findViewById<TextView?>(R.id.txtMainMenuViewModeSubtitle)
        val mode = za.kilowatch.ultimatefilemanager.storage.MainMenuViewModeManager.loadViewMode(this)
        txtMainMenuViewModeSubtitle?.text = if (mode == za.kilowatch.ultimatefilemanager.storage.MainMenuViewModeManager.ViewMode.LIST) {
            getString(R.string.layout_list)
        } else {
            val cols = za.kilowatch.ultimatefilemanager.storage.MainMenuViewModeManager.loadColumnCount(this)
            getString(R.string.layout_grid) + " ($cols ${getString(R.string.column_count).lowercase()})"
        }

        // Refresh Default Start Screen subtitle
        val txtDefaultStartScreenSubtitle = findViewById<TextView?>(R.id.txtDefaultStartScreenSubtitle)
        val usesTwinWindowDefault = TwinWindowPreferenceManager.isDefaultStartup(this)
        val currentStartScreenId = DefaultStartScreenPreferenceManager.getStartScreenId(this)
        val subtitleText = if (usesTwinWindowDefault) {
            getString(R.string.start_screen_twin_window)
        } else {
            when {
                currentStartScreenId == DefaultStartScreenPreferenceManager.ID_TWIN_WINDOW -> getString(R.string.start_screen_twin_window)
                currentStartScreenId == DefaultStartScreenPreferenceManager.ID_FILE_SERVER -> getString(R.string.start_screen_file_server)
                currentStartScreenId.startsWith(DefaultStartScreenPreferenceManager.PREFIX_STORAGE) -> {
                    val storageId = currentStartScreenId.removePrefix(DefaultStartScreenPreferenceManager.PREFIX_STORAGE)
                    val connectedStorages = za.kilowatch.ultimatefilemanager.storage.StorageBrowserActivity.getConnectedStorages(this, localOnly = false)
                    val targetStorage = connectedStorages.find { it.id == storageId }
                    targetStorage?.label ?: getString(R.string.start_screen_storage_browser)
                }
                else -> getString(R.string.start_screen_storage_browser)
            }
        }
        txtDefaultStartScreenSubtitle?.text = subtitleText

        // Refresh Long Press Duration subtitle
        val txtLongPressDurationSubtitle = findViewById<TextView?>(R.id.txtLongPressDurationSubtitle)
        txtLongPressDurationSubtitle?.text = LongPressDurationManager.formatSaved(this)

        // Refresh Controls Auto-Hide Duration subtitle
        val txtControlsTimeoutSubtitle = findViewById<TextView?>(R.id.txtControlsTimeoutSubtitle)
        txtControlsTimeoutSubtitle?.text = ControlsTimeoutManager.formatSaved(this)

        // Refresh APK Extract subtitle
        if (::txtApkExtractSubtitle.isInitialized) {
            updateApkExtractSubtitle()
        }

        // Refresh Default Icon Colors subtitle
        val txtIconColors = findViewById<TextView?>(R.id.txtDefaultIconColorsSubtitle)
        txtIconColors?.let {
            val hasLight  = DefaultIconColorManager.getCustomColor(this, ThemeHelper.THEME_LIGHT) != null
            val hasDark   = DefaultIconColorManager.getCustomColor(this, ThemeHelper.THEME_DARK) != null
            val hasAmoled = DefaultIconColorManager.getCustomColor(this, ThemeHelper.THEME_AMOLED) != null
            it.text = if (hasLight || hasDark || hasAmoled) {
                getString(R.string.default_icon_color_custom_set)
            } else {
                getString(R.string.default_icon_color_uses_default)
            }
        }

        // Refresh settings search toggle and visibility
        updateSearchContainerVisibility(SettingsSearchPreferenceManager.isEnabled(this))

        // Apply custom icon overrides to settings cards
        applySettingsCardIcons()
    }

    private fun updateApkExtractSubtitle() {
        val enabled = ApkExtractPreferenceManager.isEnabled(this)
        txtApkExtractSubtitle.text = if (enabled) {
            val icon = if (ApkExtractPreferenceManager.isExtractIcon(this)) "icon" else ""
            val fieldCount = ApkExtractPreferenceManager.getSelectedFields(this).size
            val parts = mutableListOf<String>()
            if (icon.isNotEmpty()) parts.add(icon)
            if (fieldCount > 0) parts.add("$fieldCount fields")
            val detail = if (parts.isNotEmpty()) " (${parts.joinToString(" + ")})" else ""
            getString(R.string.settings_apk_extract_subtitle_on) + detail
        } else {
            getString(R.string.settings_apk_extract_subtitle_off)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("font_handled", handledFontChange)
        outState.putBoolean("locale_handled", handledLocaleChange)
    }

    private fun toggleAnalytics(prefs: android.content.SharedPreferences) {
        val newValue = !switchAnalytics.isChecked
        switchAnalytics.isChecked = newValue
        prefs.edit().putBoolean(KEY_ANALYTICS_ENABLED, newValue).apply()
        za.kilowatch.ultimatefilemanager.util.Analytics.setAnalyticsEnabled(this, newValue)
        updateAnalyticsSubtitle(newValue)
    }

    private fun toggleHiddenFiles() {
        val newValue = !switchHiddenFiles.isChecked
        switchHiddenFiles.isChecked = newValue
        za.kilowatch.ultimatefilemanager.settings.HiddenFilesManager.isShowHiddenFilesEnabled = newValue
        updateHiddenFilesSubtitle(newValue)
    }

    private fun updateAnalyticsSubtitle(enabled: Boolean) {
        txtAnalyticsSubtitle.text = if (enabled) {
            getString(R.string.settings_analytics_subtitle_on)
        } else {
            getString(R.string.settings_analytics_subtitle_off)
        }
    }

    private fun updateHiddenFilesSubtitle(enabled: Boolean) {
        txtHiddenFilesSubtitle.text = if (enabled) {
            getString(R.string.hidden_files_are_visible)
        } else {
            getString(R.string.hidden_files_are_not_visible)
        }
    }

    private fun toggleRecycleBin() {
        val newValue = !switchRecycleBin.isChecked
        switchRecycleBin.isChecked = newValue
        za.kilowatch.ultimatefilemanager.recycle.RecycleBinSettingsManager.setEnabled(this, newValue)
        updateRecycleBinSubtitle(newValue)
    }

    private fun updateRecycleBinSubtitle(enabled: Boolean) {
        txtRecycleBinSubtitle.text = if (enabled) {
            getString(R.string.recycle_bin_title)
        } else {
            getString(R.string.settings_recycle_bin_summary)
        }
    }

    private fun toggleMediaThumbnails() {
        val newValue = !switchMediaThumbnails.isChecked
        switchMediaThumbnails.isChecked = newValue
        za.kilowatch.ultimatefilemanager.settings.ThumbnailPreferenceManager.setEnabled(this, newValue)
        updateThumbnailsSubtitle(newValue)
    }

    private fun toggleTwinWindowLayout() {
        val sw = switchTwinWindowLayout ?: return
        val newValue = !sw.isChecked
        sw.isChecked = newValue
        za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.setVerticalSplit(this, newValue)
        updateTwinWindowLayoutSubtitle(newValue)
    }

    private fun updateThumbnailsSubtitle(enabled: Boolean) {
        txtMediaThumbnailsSubtitle.text = if (enabled) {
            getString(R.string.settings_media_thumbnails_subtitle_on)
        } else {
            getString(R.string.settings_media_thumbnails_subtitle_off)
        }
    }

    private fun updateVideoThumbnailTimeSubtitle() {
        val pct = za.kilowatch.ultimatefilemanager.settings.VideoThumbnailTimePreferenceManager.getPercent(this)
        txtVideoThumbnailTimeSubtitle.text = VideoThumbnailTimePreferenceManager.formatPercent(this, pct)
    }

    private fun toggleCacheCopy() {
        val newValue = !switchCacheCopy.isChecked
        switchCacheCopy.isChecked = newValue
        za.kilowatch.ultimatefilemanager.settings.CacheCopyPreferenceManager.setEnabled(this, newValue)
        updateCacheCopySubtitle(newValue)
    }

    private fun updateCacheCopySubtitle(enabled: Boolean) {
        txtCacheCopySubtitle.text = if (enabled) {
            getString(R.string.settings_cache_copy_subtitle_on)
        } else {
            getString(R.string.settings_cache_copy_subtitle_off)
        }
    }

    private fun toggleQuickTransfer() {
        val newValue = !switchQuickTransfer.isChecked
        switchQuickTransfer.isChecked = newValue
        za.kilowatch.ultimatefilemanager.settings.QuickTransferPreferenceManager.setEnabled(this, newValue)
        updateQuickTransferSubtitle(newValue)
    }

    private fun updateQuickTransferSubtitle(enabled: Boolean) {
        txtQuickTransferSubtitle.text = if (enabled) {
            getString(R.string.settings_quick_transfer_subtitle_on)
        } else {
            getString(R.string.settings_quick_transfer_subtitle_off)
        }
    }

    private fun toggleNetworkOpenCache() {
        val newValue = !switchNetworkOpenCache.isChecked
        switchNetworkOpenCache.isChecked = newValue
        za.kilowatch.ultimatefilemanager.settings.NetworkOpenCachePreferenceManager.setEnabled(this, newValue)
        updateNetworkOpenCacheSubtitle(newValue)
    }

    private fun updateNetworkOpenCacheSubtitle(enabled: Boolean) {
        txtNetworkOpenCacheSubtitle.text = if (enabled) {
            getString(R.string.settings_network_open_cache_subtitle_on)
        } else {
            getString(R.string.settings_network_open_cache_subtitle_off)
        }
    }

    private fun updateTwinWindowLayoutSubtitle(enabled: Boolean) {
        txtTwinWindowLayoutSubtitle?.text = if (enabled) {
            getString(R.string.settings_twin_window_layout_subtitle_vertical)
        } else {
            getString(R.string.settings_twin_window_layout_subtitle_horizontal)
        }
    }

    private fun toggleTwinWindowStartup() {
        val sw = switchTwinWindowStartup ?: return
        val newValue = !sw.isChecked
        sw.isChecked = newValue
        za.kilowatch.ultimatefilemanager.settings.TwinWindowPreferenceManager.setDefaultStartup(this, newValue)
        updateTwinWindowStartupSubtitle(newValue)
    }

    private fun updateTwinWindowStartupSubtitle(enabled: Boolean) {
        txtTwinWindowStartupSubtitle?.text = if (enabled) {
            getString(R.string.settings_twin_window_startup_subtitle_on)
        } else {
            getString(R.string.settings_twin_window_startup_subtitle_off)
        }
    }

    private fun toggleSideBySideVideo() {
        val sw = switchSideBySideVideo ?: return
        val newValue = !sw.isChecked
        sw.isChecked = newValue
        SideBySideVideoPreferenceManager.setEnabled(this, newValue)
        updateSideBySideVideoSubtitle(newValue)
    }

    private fun updateSideBySideVideoSubtitle(enabled: Boolean) {
        txtSideBySideVideoSubtitle?.text = if (enabled) {
            getString(R.string.settings_side_by_side_video_subtitle_on)
        } else {
            getString(R.string.settings_side_by_side_video_subtitle_off)
        }
    }

    private fun toggleSideBySideVideoShowControlsOnRepeat() {
        val sw = switchSideBySideVideoShowControlsOnRepeat ?: return
        val newValue = !sw.isChecked
        sw.isChecked = newValue
        SideBySideVideoPreferenceManager.setShowControlsOnRepeat(this, newValue)
        updateSideBySideVideoShowControlsOnRepeatSubtitle(newValue)
    }

    private fun updateSideBySideVideoShowControlsOnRepeatSubtitle(enabled: Boolean) {
        txtSideBySideVideoShowControlsOnRepeatSubtitle?.text = if (enabled) {
            getString(R.string.settings_side_by_side_video_show_controls_on_repeat_subtitle_on)
        } else {
            getString(R.string.settings_side_by_side_video_show_controls_on_repeat_subtitle_off)
        }
    }

    private fun toggleAutoplayNext() {
        val sw = switchAutoplayNext ?: return
        val newValue = !sw.isChecked
        sw.isChecked = newValue
        AutoplayPreferenceManager.setEnabled(this, newValue)
        updateAutoplayNextSubtitle(newValue)
    }

    private fun updateAutoplayNextSubtitle(enabled: Boolean) {
        txtAutoplayNextSubtitle?.text = if (enabled) {
            getString(R.string.settings_autoplay_next_subtitle_on)
        } else {
            getString(R.string.settings_autoplay_next_subtitle_off)
        }
    }

    // ── Media Player Settings ──────────────────────────────────────

    private fun toggleBackgroundVideoMode() {
        val newMode = PlayerPreferencesManager.cycleBackgroundVideoMode(this)
        switchBackgroundVideoMode?.isChecked = newMode == BackgroundVideoMode.PIP
        updateBackgroundVideoSubtitle(newMode)
    }

    private fun updateBackgroundVideoSubtitle(mode: BackgroundVideoMode) {
        txtBackgroundVideoSubtitle?.text = when (mode) {
            BackgroundVideoMode.PIP -> getString(R.string.settings_background_video_subtitle_pip)
            BackgroundVideoMode.AUDIO_ONLY -> getString(R.string.settings_background_video_subtitle_audio)
        }
    }

    private fun toggleMiniPlayer() {
        val sw = switchMiniPlayer ?: return
        val newValue = !sw.isChecked
        sw.isChecked = newValue
        PlayerPreferencesManager.setMiniPlayerEnabled(this, newValue)
        updateMiniPlayerSubtitle(newValue)
    }

    private fun updateMiniPlayerSubtitle(enabled: Boolean) {
        txtMiniPlayerSubtitle?.text = if (enabled) {
            getString(R.string.settings_mini_player_subtitle_on)
        } else {
            getString(R.string.settings_mini_player_subtitle_off)
        }
    }

    private fun toggleResumeAfterInterruption() {
        val sw = switchResumeAfterInterruption ?: return
        val newValue = !sw.isChecked
        sw.isChecked = newValue
        PlayerPreferencesManager.setResumeAfterInterruption(this, newValue)
        updateResumeAfterInterruptionSubtitle(newValue)
    }

    private fun updateResumeAfterInterruptionSubtitle(enabled: Boolean) {
        txtResumeAfterInterruptionSubtitle?.text = if (enabled) {
            getString(R.string.settings_resume_playback_subtitle_on)
        } else {
            getString(R.string.settings_resume_playback_subtitle_off)
        }
    }

    private fun toggleBreadcrumbs() {
        val sw = switchBreadcrumbs ?: return
        val newValue = !sw.isChecked
        sw.isChecked = newValue
        BreadcrumbsPreferenceManager.setEnabled(this, newValue)
        updateBreadcrumbsSubtitle(newValue)
    }

    private fun updateBreadcrumbsSubtitle(enabled: Boolean) {
        txtBreadcrumbsSubtitle?.text = if (enabled) {
            getString(R.string.settings_breadcrumbs_subtitle_on)
        } else {
            getString(R.string.settings_breadcrumbs_subtitle_off)
        }
    }

    private fun toggleScrollingText() {
        val newValue = !switchScrollingText.isChecked
        switchScrollingText.isChecked = newValue
        ScrollingTextPreferenceManager.setEnabled(this, newValue)
        updateScrollingTextSubtitle(newValue)
    }

    private fun updateScrollingTextSubtitle(enabled: Boolean) {
        txtScrollingTextSubtitle.text = if (enabled) {
            getString(R.string.settings_scrolling_text_subtitle_on)
        } else {
            getString(R.string.settings_scrolling_text_subtitle_off)
        }
    }

    private fun toggleLeftHandedFab() {
        val sw = switchLeftHandedFab ?: return
        val newValue = !sw.isChecked
        sw.isChecked = newValue
        LeftHandedFabPreferenceManager.setLeftHanded(this, newValue)
        updateLeftHandedFabSubtitle(newValue)
    }

    private fun updateLeftHandedFabSubtitle(leftHanded: Boolean) {
        txtLeftHandedFabSubtitle?.text = if (leftHanded) {
            getString(R.string.settings_left_handed_fab_subtitle_on)
        } else {
            getString(R.string.settings_left_handed_fab_subtitle_off)
        }
    }

    private fun toggleGridIndicators() {
        val newValue = !switchGridIndicators.isChecked
        switchGridIndicators.isChecked = newValue
        GridIndicatorsPreferenceManager.setHidden(this, newValue)
        updateGridIndicatorsSubtitle(newValue)
    }

    private fun toggleTipJarPopup() {
        val newValue = !switchTipJarPopup.isChecked
        switchTipJarPopup.isChecked = newValue
        za.kilowatch.ultimatefilemanager.billing.LoyaltyPrefs.setTipJarPopupEnabled(this, newValue)
        updateTipJarPopupSubtitle(newValue)
    }

    private fun updateTipJarPopupSubtitle(enabled: Boolean) {
        txtTipJarPopupSubtitle.text = if (enabled) {
            getString(R.string.settings_tip_jar_popup_subtitle_on)
        } else {
            getString(R.string.settings_tip_jar_popup_subtitle_off)
        }
    }

    private fun updateGridIndicatorsSubtitle(hidden: Boolean) {
        txtGridIndicatorsSubtitle.text = if (hidden) {
            getString(R.string.settings_grid_indicators_subtitle_on)
        } else {
            getString(R.string.settings_grid_indicators_subtitle_off)
        }
    }

    private fun setupTvCardFocus(card: MaterialCardView) {
        val yellowFill  = getColor(R.color.tv_button_focused_yellow)
        val blackText   = getColor(R.color.tv_button_focused_yellow_text)
        val glassColor  = getColor(R.color.tv_glass_white_10)
        val primaryText = getColor(R.color.tv_text_primary)
        val secondText  = getColor(R.color.tv_text_secondary)

        card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                card.setCardBackgroundColor(yellowFill)
                setChildTextColors(card, blackText)
            } else {
                card.setCardBackgroundColor(glassColor)
                setChildTextColorsTwo(card, primaryText, secondText)
            }
        }
    }

    private fun setChildTextColors(view: android.view.View, color: Int) {
        if (view is android.widget.TextView) { view.setTextColor(color); return }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) setChildTextColors(view.getChildAt(i), color)
        }
    }

    private fun setChildTextColorsTwo(view: android.view.View, primary: Int, secondary: Int) {
        if (view is android.widget.TextView) {
            view.setTextColor(if (view.textSize > resources.displayMetrics.density * 14) primary else secondary)
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) setChildTextColorsTwo(view.getChildAt(i), primary, secondary)
        }
    }

    private fun applySettingsCardIcons() {
        data class CardIcon(val cardId: Int, val iconId: String, val defaultRes: Int)

        val cards = listOf(
            CardIcon(R.id.cardSettingsSearch, "settings_search_bar", R.drawable.ic_search),
            CardIcon(R.id.cardDefaultStartScreen, "settings_default_start_screen", R.drawable.ic_storage_internal),
            CardIcon(R.id.cardLanguage, "settings_language", R.drawable.ic_language),
            CardIcon(R.id.cardAppearance, "settings_appearance", R.drawable.ic_theme),
            CardIcon(R.id.cardFolderTransitions, "settings_folder_transitions", R.drawable.ic_transitions),
            CardIcon(R.id.cardIcons, "settings_icons", R.drawable.ic_palette),
            CardIcon(R.id.cardDefaultIconColors, "settings_default_icon_colors", R.drawable.ic_tune),
            CardIcon(R.id.cardBackupRestore, "settings_backup_restore", R.drawable.ic_export),
            CardIcon(R.id.cardAutoBackup, "settings_auto_backup", R.drawable.ic_cloud),
            CardIcon(R.id.cardMainMenuViewMode, "settings_main_menu_layout", R.drawable.ic_view_list),
            CardIcon(R.id.cardFontSize, "settings_font_size", R.drawable.ic_font_size),
            CardIcon(R.id.cardApkExtract, "settings_apk_extract", R.drawable.ic_file_apk),
            CardIcon(R.id.cardLongPressDuration, "settings_long_press", R.drawable.ic_long_press),
            CardIcon(R.id.cardControlsTimeout, "settings_controls_timeout", R.drawable.ic_controls_timeout),
            CardIcon(R.id.cardToolbarIcons, "settings_toolbar_icons", R.drawable.ic_star),
            CardIcon(R.id.cardFavorites, "settings_favorites", R.drawable.ic_star),
            CardIcon(R.id.cardFolderSort, "settings_folder_sort", R.drawable.ic_sort),
            CardIcon(R.id.cardStorageRename, "settings_custom_drive_names", R.drawable.ic_edit),
            CardIcon(R.id.cardDefaultApps, "settings_default_apps", R.drawable.ic_apps),
            CardIcon(R.id.cardFileServerTiles, "settings_file_server_tiles", R.drawable.ic_ufm_ftp),
            CardIcon(R.id.cardHiddenFiles, "settings_hidden_files", R.drawable.ic_eye),
            CardIcon(R.id.cardRecycleBin, "settings_recycle_bin", R.drawable.ic_delete),
            CardIcon(R.id.cardMediaThumbnails, "settings_media_thumbnails", R.drawable.ic_photo_video),
            CardIcon(R.id.cardVideoThumbnailTime, "settings_video_thumbnail_time", R.drawable.ic_photo_video),
            CardIcon(R.id.cardNetworkThumbnails, "settings_network_thumbnails", R.drawable.ic_cloud),
            CardIcon(R.id.cardCacheCopy, "settings_cache_copy", R.drawable.ic_copy),
            CardIcon(R.id.cardQuickTransfer, "settings_quick_transfer", R.drawable.ic_copy),
            CardIcon(R.id.cardNetworkOpenCache, "settings_network_open_cache", R.drawable.ic_cloud),
            CardIcon(R.id.cardSideBySideVideo, "settings_side_by_side_video", R.drawable.ic_play),
            CardIcon(R.id.cardSideBySideVideoShowControlsOnRepeat, "settings_side_by_side_video_show_controls_on_repeat", R.drawable.ic_repeat),
            CardIcon(R.id.cardAutoplayNext, "settings_autoplay_next", R.drawable.ic_play),
            CardIcon(R.id.cardBackgroundVideoMode, "settings_background_video", R.drawable.ic_play),
            CardIcon(R.id.cardMiniPlayer, "settings_mini_player", R.drawable.ic_list_view_custom),
            CardIcon(R.id.cardResumeAfterInterruption, "settings_resume_interruption", R.drawable.ic_phone),
            CardIcon(R.id.cardAnalytics, "settings_analytics", R.drawable.ic_tune),
            CardIcon(R.id.cardScrollingText, "settings_scrolling_text", R.drawable.ic_font_size),
            CardIcon(R.id.cardGridIndicators, "settings_grid_indicators", R.drawable.ic_view_list),
            CardIcon(R.id.cardTipJarPopup, "settings_tip_jar_popup", R.drawable.ic_tip_jar_glow)
        )

        for (card in cards) {
            val cardView = findViewById<android.view.ViewGroup?>(card.cardId) ?: continue
            val iconView = findIconImageView(cardView) ?: continue
            IconCustomizationManager.applyToView(this, iconView, card.iconId, card.defaultRes)
        }
    }

    private fun findIconImageView(view: android.view.View): android.widget.ImageView? {
        if (view is android.widget.ImageView && view.id != R.id.imgExpandArrow
            && view.id != R.id.btnBack) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findIconImageView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun updateSearchContainerVisibility(enabled: Boolean) {
        cardSearchContainer?.visibility = if (enabled) View.VISIBLE else View.GONE
        switchSettingsSearch?.isChecked = enabled
        txtSettingsSearchSubtitle?.text = if (enabled) {
            getString(R.string.settings_search_bar_subtitle_on)
        } else {
            getString(R.string.settings_search_bar_subtitle_off)
        }

        if (!enabled) {
            edtSettingsSearch?.text = null
            filterSettings("")
        }
    }

    private fun filterSettings(query: String) {
        val layoutSettingsList = findViewById<android.widget.LinearLayout>(R.id.layoutSettingsList) ?: return
        val layoutNoResults = findViewById<View>(R.id.layoutNoResults)
        var anyVisible = false

        for (i in 0 until layoutSettingsList.childCount) {
            val child = layoutSettingsList.getChildAt(i)
            if (child.id == R.id.layoutNoResults) continue

            val initialVisibility = originalVisibilities[child] ?: View.VISIBLE
            if (initialVisibility == View.GONE) {
                child.visibility = View.GONE
                continue
            }

            if (isCardMatching(child, query)) {
                child.visibility = View.VISIBLE
                anyVisible = true
            } else {
                child.visibility = View.GONE
            }
        }

        if (query.isNotEmpty() && !anyVisible) {
            layoutNoResults?.visibility = View.VISIBLE
        } else {
            layoutNoResults?.visibility = View.GONE
        }
    }

    private fun isCardMatching(card: View, query: String): Boolean {
        if (query.isEmpty()) return true
        val sb = StringBuilder()
        getAllTextFromView(card, sb)
        val text = sb.toString()
        return text.contains(query, ignoreCase = true)
    }

    private fun getAllTextFromView(view: View, sb: StringBuilder) {
        if (view is SwitchMaterial) return
        if (view is TextView) {
            sb.append(view.text).append(" ")
        } else if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                getAllTextFromView(view.getChildAt(i), sb)
            }
        }
    }

    private fun showListSizeBottomSheet() {
        val sheet = SettingsListSizeBottomSheet.newInstance()
        sheet.onSettingsChanged = {
            applyListSize()
        }
        sheet.show(supportFragmentManager, SettingsListSizeBottomSheet.TAG)
    }

    private fun showTvListSizeOptions() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_view_mode_options_tv, null)
        val imgDialogIcon = dialogView.findViewById<ImageView>(R.id.imgDialogIcon)
        val txtDialogTitle = dialogView.findViewById<TextView>(R.id.txtDialogTitle)
        val layoutColumns = dialogView.findViewById<View>(R.id.layoutColumns)
        val layoutListSize = dialogView.findViewById<View>(R.id.layoutListSize)
        val btnTvClose = dialogView.findViewById<View>(R.id.btnTvClose)

        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Translucent_NoTitleBar)
            .setView(dialogView)
            .create()

        btnTvClose.setOnClickListener { dialog.dismiss() }

        imgDialogIcon.setImageResource(R.drawable.ic_list_view_custom)
        txtDialogTitle.text = getString(R.string.settings_list_size_title)
        layoutListSize.visibility = View.VISIBLE
        layoutColumns.visibility = View.GONE

        val currentSize = SettingsListSizeManager.loadItemSize(this)
        val cardLarge = dialogView.findViewById<MaterialCardView>(R.id.cardSizeLarge)
        val cardMedium = dialogView.findViewById<MaterialCardView>(R.id.cardSizeMedium)
        val cardSmall = dialogView.findViewById<MaterialCardView>(R.id.cardSizeSmall)
        val rbLarge = dialogView.findViewById<RadioButton>(R.id.rbSizeLarge)
        val rbMedium = dialogView.findViewById<RadioButton>(R.id.rbSizeMedium)
        val rbSmall = dialogView.findViewById<RadioButton>(R.id.rbSizeSmall)

        rbLarge.isChecked = currentSize == SettingsListSizeManager.ItemSize.LARGE
        rbMedium.isChecked = currentSize == SettingsListSizeManager.ItemSize.MEDIUM
        rbSmall.isChecked = currentSize == SettingsListSizeManager.ItemSize.SMALL

        val activeColor = getColor(R.color.tv_accent)
        val inactiveColor = getColor(R.color.tv_glass_border)

        cardLarge.strokeColor = if (currentSize == SettingsListSizeManager.ItemSize.LARGE) activeColor else inactiveColor
        cardMedium.strokeColor = if (currentSize == SettingsListSizeManager.ItemSize.MEDIUM) activeColor else inactiveColor
        cardSmall.strokeColor = if (currentSize == SettingsListSizeManager.ItemSize.SMALL) activeColor else inactiveColor

        setupTvCardFocus(cardLarge)
        setupTvCardFocus(cardMedium)
        setupTvCardFocus(cardSmall)

        cardLarge.setOnClickListener {
            SettingsListSizeManager.saveItemSize(this, SettingsListSizeManager.ItemSize.LARGE)
            applyListSize()
            dialog.dismiss()
        }
        cardMedium.setOnClickListener {
            SettingsListSizeManager.saveItemSize(this, SettingsListSizeManager.ItemSize.MEDIUM)
            applyListSize()
            dialog.dismiss()
        }
        cardSmall.setOnClickListener {
            SettingsListSizeManager.saveItemSize(this, SettingsListSizeManager.ItemSize.SMALL)
            applyListSize()
            dialog.dismiss()
        }

        dialog.show()
    }

    private data class ListSizeConfig(
        val cardHeightDp: Int,
        val padH: Int,
        val padV: Int,
        val frameSize: Int,
        val iconSize: Int,
        val titleSize: Float,
        val subSize: Float
    )

    private fun applyListSize() {
        val list = findViewById<android.widget.LinearLayout>(R.id.layoutSettingsList) ?: return
        val currentSize = SettingsListSizeManager.loadItemSize(this)
        val density = resources.displayMetrics.density

        val cfg = when (currentSize) {
            SettingsListSizeManager.ItemSize.LARGE -> {
                if (isTv) ListSizeConfig(0, 24, 20, 48, 44, 22f, 16f)
                else ListSizeConfig(0, 20, 18, 52, 26, 17f, 13f)
            }
            SettingsListSizeManager.ItemSize.MEDIUM -> {
                if (isTv) ListSizeConfig(0, 20, 12, 40, 36, 18f, 14f)
                else ListSizeConfig(0, 16, 12, 44, 22, 15f, 12f)
            }
            SettingsListSizeManager.ItemSize.SMALL -> {
                if (isTv) ListSizeConfig(0, 16, 8, 32, 28, 15f, 12f)
                else ListSizeConfig(0, 12, 8, 36, 18, 13f, 11f)
            }
        }

        val padHPx = (cfg.padH * density).toInt()
        val padVPx = (cfg.padV * density).toInt()
        val framePx = (cfg.frameSize * density).toInt()
        val iconPx = (cfg.iconSize * density).toInt()
        val titleSp = cfg.titleSize
        val subSp = cfg.subSize

        fun applyToViewGroup(vg: android.view.ViewGroup) {
            for (i in 0 until vg.childCount) {
                val child = vg.getChildAt(i)
                if (child is MaterialCardView) {
                    val cardLp = child.layoutParams
                    if (cfg.cardHeightDp > 0) {
                        cardLp.height = (cfg.cardHeightDp * density).toInt()
                    } else {
                        cardLp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    child.layoutParams = cardLp

                    val inner = child.getChildAt(0) as? android.view.ViewGroup ?: continue
                    if (cfg.cardHeightDp == 0) {
                        val innerLp = inner.layoutParams
                        innerLp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        inner.layoutParams = innerLp
                    }
                    inner.setPadding(padHPx, padVPx, padHPx, padVPx)

                    if (inner.childCount > 0) {
                        val firstChild = inner.getChildAt(0)
                        if (firstChild is android.widget.FrameLayout) {
                            val lp = firstChild.layoutParams
                            lp.width = framePx
                            lp.height = framePx
                            firstChild.layoutParams = lp

                            if (firstChild.childCount > 0) {
                                (firstChild.getChildAt(0) as? ImageView)?.let { img ->
                                    val imgLp = img.layoutParams
                                    imgLp.width = iconPx
                                    imgLp.height = iconPx
                                    img.layoutParams = imgLp
                                }
                            }
                        } else if (firstChild is ImageView) {
                            val imgLp = firstChild.layoutParams
                            imgLp.width = iconPx
                            imgLp.height = iconPx
                            firstChild.layoutParams = imgLp
                        }
                    }

                    if (inner.childCount > 1) {
                        val textBlock = inner.getChildAt(1) as? android.view.ViewGroup
                        textBlock?.let { tb ->
                            if (tb.childCount > 0) {
                                (tb.getChildAt(0) as? TextView)?.textSize = titleSp
                            }
                            if (tb.childCount > 1) {
                                (tb.getChildAt(1) as? TextView)?.textSize = subSp
                            }
                        }
                    }
                } else if (child is android.view.ViewGroup) {
                    applyToViewGroup(child)
                }
            }
        }

        applyToViewGroup(list)
    }
}
