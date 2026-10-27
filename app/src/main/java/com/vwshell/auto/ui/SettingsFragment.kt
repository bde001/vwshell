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
import android.widget.Toast
import com.vwshell.auto.core.AppLauncher
import com.vwshell.auto.core.KeyLogger
import com.vwshell.auto.core.MediaMonitor
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
            view.findViewById<TextView>(R.id.valClockFormat).setText(
                if (prefs.clock24h) R.string.settings_clock_24 else R.string.settings_clock_12)
            val vna = view.findViewById<TextView>(R.id.valNotifAccess)
            vna.setText(if (MediaMonitor.isNotificationServiceEnabled(requireContext())) R.string.open else R.string.unavailable)
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

        // Яркость: предпочтительно — системные настройки дисплея. Прямая регулировка доступна
        // только при специальном доступе WRITE_SETTINGS (Android 13: ACTION_MANAGE_WRITE_SETTINGS,
        // это НЕ runtime-разрешение). Без доступа показываем честное состояние, ползунок не создаём.
        view.findViewById<View>(R.id.rowBrightness).setOnClickListener {
            val v = view.findViewById<TextView>(R.id.valBrightness)
            if (canWriteSystemSettings()) {
                openOrToast { AppLauncher.openDisplaySettings(requireContext()) }
            } else if (openOrToastWriteSettings()) {
                v.setText(R.string.settings_write_access_granted_explain)
            } else {
                v.setText(R.string.settings_brightness_unavailable)
            }
        }

        // Формат времени 12/24
        view.findViewById<View>(R.id.rowClockFormat).setOnClickListener {
            prefs.clock24h = !prefs.clock24h
            refreshValues()
        }

        // Раздел «Звук» из настроек
        view.findViewById<View>(R.id.rowSoundSection).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.vw_enter, R.anim.vw_exit)
                .replace(R.id.sectionContainer, SoundFragment::class.java, null, SoundFragment::class.java.name)
                .addToBackStack(null)
                .commit()
        }

        // Показать скрытые приложения
        view.findViewById<View>(R.id.rowResetHiddenApps).setOnClickListener {
            prefs.setHiddenApps(emptySet())
            Toast.makeText(requireContext(), R.string.apps_hidden_cleared, Toast.LENGTH_SHORT).show()
        }

        // Системные экраны: каждый через проверяемый Intent (на разных прошивках могут отсутствовать)
        view.findViewById<View>(R.id.rowAndroidSettings).setOnClickListener {
            openOrToast { AppLauncher.openAndroidSettings(requireContext()) }
        }
        view.findViewById<View>(R.id.rowManageApps).setOnClickListener {
            openOrToast { AppLauncher.launchIntentSafely(requireContext(),
                Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        view.findViewById<View>(R.id.rowBluetooth).setOnClickListener {
            openOrToast { AppLauncher.openBluetoothSettings(requireContext()) }
        }
        view.findViewById<View>(R.id.rowNotifAccess).setOnClickListener {
            openOrToast { AppLauncher.openNotificationListenerSettings(requireContext()) }
        }
        view.findViewById<View>(R.id.rowSoundSettings).setOnClickListener {
            openOrToast { AppLauncher.openSoundSettings(requireContext()) }
        }

        // Сброс настроек оболочки с подтверждением
        view.findViewById<View>(R.id.rowResetShell).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_reset)
                .setMessage(R.string.settings_reset_confirm)
                .setPositiveButton(R.string.yes) { _, _ ->
                    Prefs.get(requireContext()).resetAll(requireContext())
                    Toast.makeText(requireContext(), R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
                    activity?.recreate()
                }
                .setNegativeButton(R.string.no, null)
                .show()
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

    private fun openOrToast(action: () -> Boolean) {
        if (!action()) Toast.makeText(requireContext(), R.string.settings_screen_missing, Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        // После возвращения из системных настроек доступа обновляем индикатор честно.
        view?.findViewById<TextView>(R.id.valNotifAccess)?.setText(
            if (MediaMonitor.isNotificationServiceEnabled(requireContext())) R.string.open else R.string.unavailable)
    }

    private fun canWriteSystemSettings(): Boolean =
        Settings.System.canWrite(requireContext())

    /** Открывает системный экран выдачи WRITE_SETTINGS (специальный доступ Android 6+/13). */
    private fun openOrToastWriteSettings(): Boolean = try {
        val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:${requireContext().packageName}"))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i); true
    } catch (_: Exception) { false }

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
