package com.retroid.translator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.retroid.translator.fold.FoldPosture
import com.retroid.translator.fold.FoldPostureProvider
import com.retroid.translator.fold.FoldState
import com.retroid.translator.packs.LanguagePackPreferences
import com.retroid.translator.packs.PackInventory
import com.retroid.translator.packs.PackStatus
import com.retroid.translator.settings.FoldAwareLayoutHost
import com.retroid.translator.settings.LayoutPreferences
import com.retroid.translator.settings.ManagePacksFragment
import com.retroid.translator.settings.ScreenMode
import com.retroid.translator.settings.SettingsHubFragment
import com.retroid.translator.settings.SettingsTab
import com.retroid.translator.ui.ConversationsFragment
import com.retroid.translator.ui.LearnFragment
import com.retroid.translator.ui.PracticeFragment
import com.retroid.translator.ui.TranslateFragment
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    "Mic permission denied — voice input, conversations, and recording won't work until it's granted.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    /**
     * Wake-lock reliability fix (docs/specs/fold5-adaptation.md §4):
     * ContinuousListeningService's persistent "Listening for conversation..."
     * notification needs this runtime-granted on Android 13+ to actually be
     * shown to the user - the foreground service itself still starts and
     * keeps the mic pipeline alive even if this is denied (Android does not
     * gate starting a foreground service on POST_NOTIFICATIONS, only on
     * whether its notification is visible), so denial is not fatal to the
     * feature, just to the honest-visibility half of why a foreground
     * service was chosen over a silent wake lock. No error Toast on denial
     * for that reason - unlike mic permission, this isn't a hard blocker.
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Camera OCR translate (docs/specs/fold5-adaptation.md "Camera OCR
     * translate" section) - same [ActivityResultContracts.RequestPermission]
     * launcher pattern as [micPermissionLauncher] above, requested eagerly
     * at startup for the same reason mic is (so it's very likely already
     * granted by the time the user taps the new camera button, rather than
     * making that tap the very first time they see the system prompt).
     * [TranslateFragment][com.retroid.translator.ui.TranslateFragment]'s
     * camera button also defensively re-checks/re-requests before
     * launching the capture screen, mirroring [beginMicCapture]'s own
     * defensive re-check for mic permission.
     */
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    "Camera permission denied — the camera OCR translate button won't work until it's granted.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    /**
     * Which of the three settings-managed tabs (see [SettingsTab]) is
     * currently on screen - null while Conversations is showing (excluded
     * from the layout-variant system, see [SettingsTab]'s doc comment) or
     * while a Settings screen ([SettingsHubFragment] or one of its
     * destinations) is on screen instead of any tab. Read by the
     * fold-auto-switch coordinator below to know which tab's configured
     * cover variant to apply.
     */
    private var activeTab: SettingsTab? = null

    // -------------------------------------------------------------------
    // Fold-triggered auto-switch state - see onPostureForAutoSwitch's doc
    // comment for the detection heuristic and its known limits.
    // -------------------------------------------------------------------
    private var foldPostureProvider: FoldPostureProvider? = null
    private var everSawOpenFoldPosture = false
    private var currentlyTreatingAsFoldClosed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportFragmentManager.addOnBackStackChangedListener {
            supportActionBar?.setDisplayHomeAsUpEnabled(supportFragmentManager.backStackEntryCount > 0)
        }

        requestMicPermissionIfNeeded()
        requestNotificationPermissionIfNeeded()
        requestCameraPermissionIfNeeded()

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        if (savedInstanceState == null) {
            showTab(TranslateFragment(), SettingsTab.TRANSLATE)
        }
        bottomNav.setOnItemSelectedListener { item ->
            val (fragment, tab) = when (item.itemId) {
                R.id.nav_translate -> TranslateFragment() to SettingsTab.TRANSLATE
                R.id.nav_conversations -> ConversationsFragment() to null
                R.id.nav_practice -> PracticeFragment() to SettingsTab.PRACTICE
                R.id.nav_learn -> LearnFragment() to SettingsTab.LEARN
                else -> return@setOnItemSelectedListener false
            }
            showTab(fragment, tab)
            true
        }

        observeFoldAutoSwitch()
        checkBulkPackDownloadPrompt()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showSettingsHub()
                true
            }
            android.R.id.home -> {
                supportFragmentManager.popBackStack()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // -------------------------------------------------------------------
    // Navigation - tabs (bottom nav) vs. Settings (toolbar icon)
    // -------------------------------------------------------------------

    /** Switches the visible tab. Also clears any Settings back stack, so leaving Settings via bottom nav doesn't leave stale back-stack entries behind it. */
    private fun showTab(fragment: Fragment, tab: SettingsTab?) {
        activeTab = tab
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
        supportActionBar?.title = getString(R.string.app_name)
        applyForceCompactIfNeeded(fragment)
    }

    private fun showSettingsHub() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, SettingsHubFragment())
            .addToBackStack("settings_hub")
            .commit()
    }

    /** Re-applies the manual force-compact preference (Fold behavior screen) to a newly-shown tab, if it implements [FoldAwareLayoutHost] and the toggle is on. */
    private fun applyForceCompactIfNeeded(fragment: Fragment) {
        val host = fragment as? FoldAwareLayoutHost ?: return
        if (LayoutPreferences.isForceCompactLayoutEnabled(this)) {
            val variantId = LayoutPreferences.getVariant(this, host.settingsTab, ScreenMode.COVER)
            Log.i(TAG, "force-compact is on: applying cover layout variant=$variantId to tab=${host.settingsTab}")
            host.applyCoverLayout(variantId)
        }
    }

    // -------------------------------------------------------------------
    // Fold-triggered auto-switch (settings-foundation task item 4)
    // -------------------------------------------------------------------

    private fun observeFoldAutoSwitch() {
        val provider = FoldPostureProvider(this)
        foldPostureProvider = provider
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                provider.postureFlow().collect { state -> onPostureForAutoSwitch(state) }
            }
        }
    }

    /**
     * Decides whether a [FoldState] emission represents "the device just
     * folded closed" and, if so, pushes the active tab's configured cover
     * variant via [FoldAwareLayoutHost].
     *
     * Detection heuristic and its limits (documented explicitly per this
     * project's evidence-based house style - see README.md /
     * docs/specs/fold5-adaptation.md): [FoldPosture.CLOSED_COVER] is, by
     * [FoldPostureProvider]'s own doc comment, never produced by
     * [FoldPostureProvider.postureFlow] - `WindowInfoTracker`/`FoldingFeature`
     * describe the hinge *within* the currently-hosted window, and a window
     * that has moved entirely onto the cover display has no hinge to report
     * at all, which reads identically to [FoldPosture.NO_FOLDING_FEATURE].
     * Steady-state [FoldPosture.NO_FOLDING_FEATURE] is therefore NOT treated
     * as "closed" here (it would misfire on a plain non-foldable device, or
     * on the very first `WindowLayoutInfo` emission before any real posture
     * has been observed). Instead, only a *transition* from a previously-
     * observed open posture (book-portrait or tabletop-landscape, this
     * session) to [FoldPosture.NO_FOLDING_FEATURE] is treated as a
     * fold-closing signal - the best signal available from this API without
     * a lower-level `DeviceStateManager` dependency this app doesn't
     * otherwise need.
     *
     * Verification status: this transition logic was exercised against
     * `adb shell cmd device_state state 0` (CLOSED) on the real target
     * device (RFCW80CK2RW) during development. That simulation did NOT
     * reproduce a physical fold-while-foregrounded - it backgrounded/froze
     * this app entirely and brought `com.samsung.android.app.find` to the
     * foreground instead (confirmed via `dumpsys activity activities` /
     * `dumpsys window displays` showing that package as
     * `topResumedActivity`/`mFocusedApp`), so `postureFlow` never emitted
     * during that test. This code path compiles and runs (verified: no
     * crash across normal open/rotate posture changes on-device), but the
     * open→no-feature "just folded closed" branch specifically has NOT been
     * exercised with real evidence and needs a real physical fold to
     * confirm, per this project's no-unverified-claims standard.
     */
    private fun onPostureForAutoSwitch(state: FoldState) {
        Log.d(TAG, "fold-auto-switch: posture=${state.posture} activeTab=$activeTab autoSwitchEnabled=${LayoutPreferences.isAutoSwitchOnFoldEnabled(this)}")
        val isOpenPosture = state.posture != FoldPosture.NO_FOLDING_FEATURE
        if (isOpenPosture) {
            everSawOpenFoldPosture = true
            if (currentlyTreatingAsFoldClosed) {
                currentlyTreatingAsFoldClosed = false
                if (LayoutPreferences.isAutoSwitchOnFoldEnabled(this)) {
                    Log.i(TAG, "fold-auto-switch: unfolded again, reverting active tab to its default layout")
                    (currentTabFragment() as? FoldAwareLayoutHost)?.applyDefaultLayout()
                }
            }
            return
        }

        // posture == NO_FOLDING_FEATURE from here down.
        if (!everSawOpenFoldPosture || currentlyTreatingAsFoldClosed) return
        currentlyTreatingAsFoldClosed = true
        if (!LayoutPreferences.isAutoSwitchOnFoldEnabled(this)) return

        val tab = activeTab
        val host = currentTabFragment() as? FoldAwareLayoutHost
        if (tab == null || host == null) {
            Log.i(TAG, "fold-auto-switch: fold-close signal, but activeTab=$tab / current fragment doesn't implement FoldAwareLayoutHost yet - no-op")
            return
        }
        val variantId = LayoutPreferences.getVariant(this, tab, ScreenMode.COVER)
        Log.i(TAG, "fold-auto-switch: applying cover layout variant=$variantId to tab=$tab")
        host.applyCoverLayout(variantId)
    }

    private fun currentTabFragment(): Fragment? = supportFragmentManager.findFragmentById(R.id.fragmentContainer)

    // -------------------------------------------------------------------
    // Auto-download all language packs (docs/specs/galaxy-tab-s9fe-adaptation.md).
    // A one-time confirmation, shown at first launch if Wi-Fi is already
    // connected, or as soon as Wi-Fi first becomes available afterward if
    // it wasn't yet - "first launch (or first-Wi-Fi-connection)" per that
    // spec's wording. Declining or accepting both mark the prompt as shown
    // (isHasPromptedBulkDownload) so it is never shown again; every pack
    // stays individually downloadable/deletable afterward from Settings ->
    // Language packs (ManagePacksFragment) regardless of this prompt's
    // outcome. No ongoing/ambient networking results from this beyond the
    // one registered NetworkCallback while the prompt hasn't fired yet -
    // unregistered in onDestroy, and never re-registered once the prompt
    // has been shown once.
    // -------------------------------------------------------------------

    private var wifiWaitCallback: ConnectivityManager.NetworkCallback? = null

    private fun checkBulkPackDownloadPrompt() {
        if (LanguagePackPreferences.hasPromptedBulkDownload(this)) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val activeNetwork = cm.activeNetwork
        val onWifiNow = activeNetwork != null &&
            cm.getNetworkCapabilities(activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (onWifiNow) {
            offerBulkDownloadPrompt()
            return
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (!LanguagePackPreferences.hasPromptedBulkDownload(this@MainActivity)) {
                        offerBulkDownloadPrompt()
                    }
                    unregisterWifiWaitCallback()
                }
            }
        }
        wifiWaitCallback = callback
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't register Wi-Fi wait callback for bulk-download prompt (non-fatal)", e)
            wifiWaitCallback = null
        }
    }

    private fun unregisterWifiWaitCallback() {
        val callback = wifiWaitCallback ?: return
        wifiWaitCallback = null
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try { cm.unregisterNetworkCallback(callback) } catch (e: Exception) { /* already gone, fine */ }
    }

    private fun offerBulkDownloadPrompt() {
        if (isFinishing || isDestroyed) return
        PackStatus.fetchDownloadedTranslationCodes { downloadedCodes ->
            if (isFinishing || isDestroyed) return@fetchDownloadedTranslationCodes
            val remaining = PackInventory.all().filterNot { PackStatus.isDownloaded(app, it, downloadedCodes) }
            if (remaining.isEmpty()) {
                // Nothing to offer (e.g. a previous manual round of downloads
                // already covered everything) - mark as prompted so this
                // check doesn't keep re-running on every launch.
                LanguagePackPreferences.setHasPromptedBulkDownload(this, true)
                return@fetchDownloadedTranslationCodes
            }
            val sizeMiB = remaining.sumOf { it.approxSizeMiB }
            val sizeText = if (sizeMiB >= 1024) "%.1fGB".format(sizeMiB / 1024.0) else "${sizeMiB}MB"
            AlertDialog.Builder(this)
                .setTitle("Download all language packs?")
                .setMessage(
                    "Download every translation pack, voice-input pack, and natural voice now " +
                        "(~$sizeText over Wi-Fi, one-time)? Everything works fully offline afterward - " +
                        "no ongoing network use beyond an explicit \"Check for updates\" later. " +
                        "You can manage or delete individual packs anytime from Settings > Language packs."
                )
                .setPositiveButton("Download") { _, _ ->
                    LanguagePackPreferences.setHasPromptedBulkDownload(this, true)
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragmentContainer, ManagePacksFragment.newInstanceAutoStart())
                        .addToBackStack("language_packs")
                        .commit()
                }
                .setNegativeButton("Not now") { _, _ ->
                    LanguagePackPreferences.setHasPromptedBulkDownload(this, true)
                }
                .setCancelable(false)
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterWifiWaitCallback()
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun requestMicPermissionIfNeeded() {
        if (!hasMicPermission()) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun requestCameraPermissionIfNeeded() {
        if (!hasCameraPermission()) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return // permission didn't exist before API 33
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val app: TranslatorApp get() = application as TranslatorApp

    companion object {
        private const val TAG = "MainActivity"
    }
}
