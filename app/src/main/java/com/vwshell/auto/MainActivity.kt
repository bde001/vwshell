package com.vwshell.auto

import android.media.session.MediaController
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.vwshell.auto.R
import com.vwshell.auto.ui.MediaFragment
import com.vwshell.auto.ui.NavigationFragment
import com.vwshell.auto.ui.PhoneFragment
import com.vwshell.auto.ui.RadioFragment
import com.vwshell.auto.ui.SettingsFragment
import com.vwshell.auto.ui.VehicleFragment
import com.vwshell.auto.core.AppLauncher
import com.vwshell.auto.core.KeyLogger
import com.vwshell.auto.core.MediaMonitor
import com.vwshell.auto.core.Prefs
import com.vwshell.auto.databinding.ActivityMainBinding
import com.vwshell.auto.widget.VWTile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VW Shell — оболочка в стиле Volkswagen Discover Pro.
 * Полноэкранный HOME-режим: без статус-бара и панели навигации, с корректной
 * обработкой системных жестов (insets скрываются, жесты проводимые не перехватываются).
 */
class MainActivity : AppCompatActivity(), MediaMonitor.Listener {

    private lateinit var binding: ActivityMainBinding
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val handler = Handler(Looper.getMainLooper())

    private val clockTicker = object : Runnable {
        override fun run() {
            binding.topBar.clockText.text = clockFormat.format(Date())
            // Часы обновляются раз в 10 секунд — минимальная нагрузка, main thread не блокируется.
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupFullscreen()
        setupTiles()
        updateStatusIcons()
        MediaMonitor.get(this).addListener(this)
    }

    /** Полноэкранный OEM-режим; системные жесты остаются рабочими (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE). */
    private fun setupFullscreen() {
        window.setDecorFitsSystemWindows(false)
        val controller = window.insetsController
        if (controller != null) {
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // После пробуждения/возврата из других приложений снова скрываем системные панели.
        if (hasFocus) setupFullscreen()
    }

    private fun setupTiles() {
        binding.tileRadio.setup(R.drawable.ic_radio, R.string.tile_radio)
        binding.tileMedia.setup(R.drawable.ic_media, R.string.tile_media)
        binding.tilePhone.setup(R.drawable.ic_phone, R.string.tile_phone)
        binding.tileNav.setup(R.drawable.ic_nav, R.string.tile_navigation)
        binding.tileVehicle.setup(R.drawable.ic_car, R.string.tile_vehicle)
        binding.tileSettings.setup(R.drawable.ic_settings, R.string.tile_settings)

        binding.tileRadio.onTileClick = { openSection(RadioFragment::class.java, R.string.tile_radio) }
        binding.tileMedia.onTileClick = { openSection(MediaFragment::class.java, R.string.tile_media) }
        binding.tilePhone.onTileClick = { openSection(PhoneFragment::class.java, R.string.tile_phone) }
        binding.tileNav.onTileClick = { openSection(NavigationFragment::class.java, R.string.tile_navigation) }
        binding.tileVehicle.onTileClick = { openSection(VehicleFragment::class.java, R.string.tile_vehicle) }
        binding.tileSettings.onTileClick = { openSection(SettingsFragment::class.java, R.string.tile_settings) }
    }

    // ==================== Навигация между разделами ====================

    private var currentSectionTag: String? = null

    private fun openSection(cls: Class<out Fragment>, titleRes: Int) {
        val tag = cls.name
        if (currentSectionTag == tag) return
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.vw_enter, R.anim.vw_exit)
            .replace(R.id.sectionContainer, EmptyFragment::class.java, null, null)
            .commit()
        binding.sectionContainer.visibility = View.VISIBLE
        binding.topBar.modeText.setText(titleRes)
        currentSectionTag = tag
    }

    fun showHome() {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.vw_enter, R.anim.vw_exit)
            .replace(R.id.sectionContainer, EmptyFragment::class.java, null, null)
            .commit()
        binding.sectionContainer.visibility = View.GONE
        binding.topBar.modeText.setText(R.string.default_mode)
        currentSectionTag = null
    }

    /** Пустый fragment-заглушка для очистки контейнера разделов. */
    class EmptyFragment : Fragment()

    override fun onBackPressed() {
        if (currentSectionTag != null) showHome()
        // На HOME-экране Back не обрабатывается — как в штатной системе.
    }

    // ==================== Верхняя/нижняя панель ====================

    override fun onResume() {
        super.onResume()
        handler.post(clockTicker)
        MediaMonitor.get(this).refresh()
        updateStatusIcons()
        if (currentSectionTag == null) binding.topBar.modeText.setText(R.string.default_mode)
    }

    override fun onPause() {
        handler.removeCallbacks(clockTicker)
        super.onPause()
    }

    override fun onDestroy() {
        MediaMonitor.get(this).removeListener(this)
        super.onDestroy()
    }

    /** Индикаторы показывают только реально доступные данные; при недоступности скрываются. */
    private fun updateStatusIcons() {
        val bt = AppLauncher.bluetoothState(this)
        binding.topBar.iconBluetooth.visibility = if (bt == null) View.GONE else View.VISIBLE
        binding.topBar.iconBluetooth.alpha = if (bt == true) 1f else 0.35f

        // Сотовая сеть: TelephonyManager доступен на магнитолах с SIM. Без данных — скрыть.
        val level = try { AppLauncher.signalLevel(this) } catch (_: Throwable) { null }
        if (level != null) {
            binding.topBar.iconSignal.visibility = View.VISIBLE
            binding.topBar.iconSignal.setImageResource(R.drawable.ic_signal)
            binding.topBar.iconSignal.alpha = (0.4f + 0.6f * level / 4f)
        } else {
            binding.topBar.iconSignal.visibility = View.GONE
        }
    }

    // ==================== Медиа-статус в нижней панели ====================

    override fun onActiveSource(c: MediaController?, sourcePackage: String?) {
        if (!::binding.isInitialized) return
        if (c == null) {
            binding.bottomBar.sourceIcon.visibility = View.GONE
            binding.bottomBar.sourceText.setText(R.string.source_none)
            return
        }
        val title = c.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = c.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        val playing = c.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        binding.bottomBar.sourceIcon.visibility = View.VISIBLE
        binding.bottomBar.sourceIcon.setImageResource(
            if (sourcePackage?.contains("bluetooth", true) == true) R.drawable.ic_bluetooth else R.drawable.ic_media)
        val label = when {
            title != null -> listOfNotNull(artist, title).joinToString(" — ")
            sourcePackage != null -> sourcePackage
            else -> getString(R.string.source_none)
        }
        val state = if (playing) getString(R.string.media_state_playing) else getString(R.string.media_state_paused)
        binding.bottomBar.sourceText.text = "$state — $label"
        // Климат: отображается только при наличии реальных данных от CAN-провайдера — сейчас недоступен → скрыт.
        binding.bottomBar.climateText.visibility = View.GONE
    }

    // ==================== Аппаратные клавиши (руль/D-pad) ====================

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                // Стандартная обработка системой и активной MediaSession.
                return false
            }
            KeyEvent.KEYCODE_HOME -> { showHome(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val v = currentFocus
                if (v is VWTile) { v.performClick(); return true }
                if (v != null) return v.dispatchKeyEvent(event)
                return false
            }
            else -> {
                if (!KeyLogger.isKnownKeyCode(keyCode)) {
                    KeyLogger.logUnknown(this, event)
                    return false
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
