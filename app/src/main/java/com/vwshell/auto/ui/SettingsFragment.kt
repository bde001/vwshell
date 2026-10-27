package com.vwshell.auto.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.vwshell.auto.BuildConfig
import com.vwshell.auto.R
import com.vwshell.auto.core.AppLauncher
import com.vwshell.auto.core.KeyLogger
import com.vwshell.auto.core.Prefs

/** Настройки оболочки: назначение приложений, поведение, информация. Строгий OEM-стиль. */
class SettingsFragment : BaseSectionFragment() {

    override val titleRes = R.string.settings_title

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = Prefs.get(requireContext())

        fun labelFor(pkg: String?): String {
            if (pkg == null) return getString(R.string.nav_ask)
            val li = AppLauncher.installedLaunchableApps(requireContext()).firstOrNull { it.packageName == pkg }
            return li?.label ?: pkg
        }

        val valNav = view.findViewById<TextView>(R.id.valNavApp)
        val valMedia = view.findViewById<TextView>(R.id.valMediaApp)
        val valRadio = view.findViewById<TextView>(R.id.valRadioApp)
        val valPhone = view.findViewById<TextView>(R.id.valPhoneApp)

        fun refreshValues() {
            valNav.text = labelFor(prefs.navAppPackage)
            valMedia.text = labelFor(prefs.mediaAppPackage)
            valRadio.text = labelFor(prefs.radioAppPackage)
            valPhone.text = labelFor(prefs.phoneAppPackage)
            val valBright = view.findViewById<TextView>(R.id.valBrightness)
            valBright.text = if (canWriteSystemSettings()) systemBrightnessPercent()?.toString() + "%"
                             else getString(R.string.unavailable)
        }
        refreshValues()

        view.findViewById<View>(R.id.rowNavApp).setOnClickListener {
            pickApp(getString(R.string.settings_nav_app)) { pkg -> prefs.navAppPackage = pkg; refreshValues() }
        }
        view.findViewById<View>(R.id.rowMediaApp).setOnClickListener {
            pickApp(getString(R.string.settings_media_app)) { pkg -> prefs.mediaAppPackage = pkg; refreshValues() }
        }
        view.findViewById<View>(R.id.rowRadioApp).setOnClickListener {
            pickApp(getString(R.string.settings_radio_app)) { pkg -> prefs.radioAppPackage = pkg; refreshValues() }
        }
        view.findViewById<View>(R.id.rowPhoneApp).setOnClickListener {
            pickApp(getString(R.string.settings_phone_app)) { pkg -> prefs.phoneAppPackage = pkg; refreshValues() }
        }

        view.findViewById<View>(R.id.rowBrightness).setOnClickListener {
            val v = view.findViewById<TextView>(R.id.valBrightness)
            if (canWriteSystemSettings()) {
                try {
                    startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { v.setText(R.string.settings_brightness_unavailable) }
            } else {
                v.setText(R.string.settings_brightness_unavailable)
            }
        }

        view.findViewById<View>(R.id.rowSetHome).setOnClickListener {
            // Штатный Android-механизм: системный выбор приложения Home по умолчанию
            if (!AppLauncher.openDefaultAppSettings(requireContext())) {
                val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                AppLauncher.launchIntentSafely(requireContext(), i)
            }
        }

        view.findViewById<View>(R.id.rowKeylog).setOnClickListener {
            val snapshot = KeyLogger.snapshot()
            val text = if (snapshot.isEmpty()) getString(R.string.keylog_empty)
                       else snapshot.joinToString("\n")
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.keylog_title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        view.findViewById<TextView>(R.id.aboutText).text =
            getString(R.string.settings_about_text, BuildConfig.VERSION_NAME)
    }

    private fun canWriteSystemSettings(): Boolean =
        Settings.System.canWrite(requireContext())

    private fun systemBrightnessPercent(): Int? = try {
        val cur = Settings.System.getInt(requireContext().contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        Math.round(cur * 100 / 255f)
    } catch (_: Exception) { null }

    private fun pickApp(title: String, onPicked: (String?) -> Unit) {
        val apps = AppLauncher.installedLaunchableApps(requireContext())
        val names = (listOf(getString(R.string.nav_ask)) + apps.map { it.label }).toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setItems(names) { _, which ->
                onPicked(if (which == 0) null else apps[which - 1].packageName)
            }
            .show()
    }
}
