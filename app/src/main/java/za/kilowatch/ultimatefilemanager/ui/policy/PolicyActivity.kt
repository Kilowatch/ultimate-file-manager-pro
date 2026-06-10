package za.kilowatch.ultimatefilemanager.ui.policy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.settings.ThemeHelper
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import za.kilowatch.ultimatefilemanager.settings.FontSizeHelper
import za.kilowatch.ultimatefilemanager.settings.LocaleHelper

/**
 * Displays either the Terms & Conditions or Privacy Policy.
 * Supports both Mobile and TV form factors with appropriate styling.
 *
 * Usage from anywhere in the app:
 *   PolicyActivity.startTerms(context)
 *   PolicyActivity.startPrivacyPolicy(context)
 */
class PolicyActivity : AppCompatActivity() {

    private lateinit var contentContainer: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView

    private var isTv = false

    companion object {
        private const val EXTRA_TYPE = "extra_policy_type"
        const val TYPE_TERMS   = "terms"
        const val TYPE_PRIVACY = "privacy"

        fun startTerms(context: Context) = context.startActivity(
            Intent(context, PolicyActivity::class.java)
                .putExtra(EXTRA_TYPE, TYPE_TERMS)
        )

        fun startPrivacyPolicy(context: Context) = context.startActivity(
            Intent(context, PolicyActivity::class.java)
                .putExtra(EXTRA_TYPE, TYPE_PRIVACY)
        )
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isTv = DeviceUtils.isTvDevice(this)
        if (isTv) {
            setContentView(R.layout.activity_policy_tv)
        } else {
            setContentView(R.layout.activity_policy)
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

        val type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_TERMS

        // Views
        contentContainer = findViewById(R.id.contentContainer)
        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)

        // Set title
        tvTitle.text = when (type) {
            TYPE_PRIVACY -> getString(R.string.pp_screen_title)
            else         -> getString(R.string.tc_screen_title)
        }

        // Back navigation
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

        // Build views from string resources and add to container
        val views = when (type) {
            TYPE_PRIVACY -> PolicyViewBuilder.buildPrivacyViews(this, isTv)
            else         -> PolicyViewBuilder.buildTermsViews(this, isTv)
        }
        views.forEach { contentContainer.addView(it) }

        val acceptanceUi = PolicyViewBuilder.buildAcceptanceUi(this, isTv, type)
        val bottomContainer = findViewById<LinearLayout>(R.id.bottomContainer)
        
        val prefsKey = if (type == TYPE_TERMS) "terms_accepted_time" else "privacy_accepted_time"
        val prefs = getSharedPreferences("acceptance_prefs", Context.MODE_PRIVATE)
        val acceptedTime = prefs.getLong(prefsKey, 0L)

        if (isTv && bottomContainer != null && acceptedTime == 0L) {
            bottomContainer.addView(acceptanceUi)
        } else {
            contentContainer.addView(acceptanceUi)
        }

        if (isTv) {
            val scrollContainer = findViewById<View>(R.id.scrollContainer)
            val scrollView = findViewById<androidx.core.widget.NestedScrollView>(R.id.scrollView)
            val bottomContainer = findViewById<LinearLayout>(R.id.bottomContainer)
            
            // If the content is short and doesn't require scrolling, reveal immediately
            scrollView.post {
                val maxScroll = (scrollView.getChildAt(0)?.height ?: 0) - scrollView.height
                if (maxScroll <= 0 && bottomContainer?.visibility == View.GONE) {
                    bottomContainer.visibility = View.VISIBLE
                }
            }

            // Reveal when mapped via joystick, remote scroll, or touch scroll
            scrollView.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { v, _, scrollY, _, _ ->
                val maxScroll = (v.getChildAt(0)?.height ?: 0) - v.height
                if (scrollY >= (maxScroll * 0.98)) {
                    if (bottomContainer?.visibility == View.GONE) {
                        bottomContainer.visibility = View.VISIBLE
                    }
                }
            })

            scrollContainer.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    val scrollAmount = (80 * resources.displayMetrics.density).toInt()
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            val maxScroll = (scrollView.getChildAt(0)?.height ?: 0) - scrollView.height
                            if (scrollView.scrollY < maxScroll) {
                                scrollView.smoothScrollBy(0, scrollAmount)
                                true // consumed
                            } else {
                                // Reached the bottom
                                if (bottomContainer?.visibility == View.GONE) {
                                    bottomContainer.visibility = View.VISIBLE
                                    bottomContainer.post {
                                        bottomContainer.findViewWithTag<View>("acceptance_checkbox")?.requestFocus()
                                    }
                                    true
                                } else if (currentFocus == scrollContainer) {
                                    val cb = bottomContainer?.findViewWithTag<View>("acceptance_checkbox")
                                    if (cb != null) {
                                        cb.requestFocus()
                                        true
                                    } else {
                                        false
                                    }
                                } else {
                                    false
                                }
                            }
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            // If focus is currently on something inside the UI, let the system handle UP naturally
                            val focusedView = currentFocus
                            val isFocusingInsideContent = focusedView != null && 
                                                          focusedView != scrollContainer && 
                                                          focusedView.parent != null
                                                          
                            if (isFocusingInsideContent) {
                                false
                            } else if (scrollView.scrollY > 0) {
                                scrollView.smoothScrollBy(0, -scrollAmount)
                                true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            scrollContainer.requestFocus()
        }
    }
}