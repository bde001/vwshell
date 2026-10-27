package com.vwshell.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.vwshell.auto.core.AppLauncher
import com.vwshell.auto.core.KeyLogger
import com.vwshell.auto.core.Prefs
import com.vwshell.auto.core.ShellState
import com.vwshell.auto.databinding.ActivityMainBinding
import com.vwshell.auto.ui.AppsFragment
import com.vwshell.auto.ui.MediaFragment
import com.vwshell.auto.ui.NavigationFragment
import com.vwshell.auto.ui.PhoneFragment
import com.vwshell.auto.ui.RadioFragment
import com.vwshell.auto.ui.SettingsFragment
import com.vwshell.auto.ui.SoundFragment
import com.vwshell.auto.ui.VehicleFragment
import com.vwshell.auto.widget.VWTile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VW Shell — оболочка в стиле Volkswagen Discover Pro.
 * Полноэкранный HOME-режим: без статус-бара и панели навигации, системные жесты
 * остаются рабочими (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE).
 *
 * Навигация: openSection() открывает ФАКТИЧЕСКИ переданный Fragment через replace()
 * с тегом = имя класса; повторное нажатие на тот же раздел не создаёт экземпляр заново.
 * showHome() удаляет раздел из контейнера. Back возвращает домой; на главном экране
 * событие отдаётся системе (выход из оболочки возможен — пользователь не заблокирован).
 */
class MainActivity : AppCompatActivity(), ShellState.Listener {

    private lateinit var binding: ActivityMainBinding
    private var lastClockText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupFullscreen()
        setupTiles()
        setupBottomNav()
        ShellState.init(this)
        ShellState.addListener(this, this)

        // Восстановление после поворота/пробуждения: FragmentManager сам восстановит
        // фрагмент раздела; BaseSectionFragment.onViewCreated вызовет onSectionRestored().
        if (!hasRestoredSection()) showHome(silent = true)
        updateClock(force = true)
        updateStatusIcons()
    }

    /** Есть ли восстановленный раздел после пересоздания Activity. */
    private fun hasRestoredSection(): Boolean =
        supportFragmentManager.findFragmentById(R.id.sectionContainer) is BaseSectionMarker

    /** Полноэкранный OEM-режим; системные жесты проводимые не перехватываются. */
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
        if (hasFocus) {
            setupFullscreen()
            updateClock(force = true)
        }
    }

    // ==================== Плитки главного экрана ====================

    private fun setupTiles() {
        binding.tileRadio.setup(R.drawable.ic_radio, R.string.tile_radio)
        binding.tileMedia.setup(R.drawable.ic_media, R.string.tile_media)
        binding.tilePhone.setup(R.drawable.ic_phone, R.string.tile_phone)
        binding.tileNav.setup(R.drawable.ic_nav, R.string.tile_navigation)
        binding.tileVehicle.setup(R.drawable.ic_car, R.string.tile_vehicle)
        binding.tileSound.setup(R.drawable.ic_volume, R.string.tile_sound)
        binding.tileApps.setup(R.drawable.ic_apps, R.string.tile_apps)
        binding.tileSettings.setup(R.drawable.ic_settings, R.string.tile_settings)

        binding.tileRadio.onTileClick = { openSection(RadioFragment::class.java, R.string.tile_radio) }
        binding.tileMedia.onTileClick = { openSection(MediaFragment::class.java, R.string.tile_media) }
        binding.tilePhone.onTileClick = { openSection(PhoneFragment::class.java, R.string.tile_phone) }
        binding.tileNav.onTileClick = { openSection(NavigationFragment::class.java, R.string.tile_navigation) }
        binding.tileVehicle.onTileClick = { openSection(VehicleFragment::class.java, R.string.tile_vehicle) }
        binding.tileSound.onTileClick = { openSection(SoundFragment::class.java, R.string.tile_sound) }
        binding.tileApps.onTileClick = { openSection(AppsFragment::class.java, R.string.tile_apps) }
        binding.tileSettings.onTileClick = { openSection(SettingsFragment::class.java, R.string.tile_settings) }
    }

    /** Постоянная нижняя навигационная панель. */
    private fun setupBottomNav() {
        binding.bottomBar.navHome.setOnClickListener { showHome() }
        binding.bottomBar.navRadio.setOnClickListener { openSection(RadioFragment::class.java, R.string.tile_radio) }
        binding.bottomBar.navMedia.setOnClickListener { openSection(MediaFragment::class.java, R.string.tile_media) }
        binding.bottomBar.navApps.setOnClickListener { openSection(AppsFragment::class.java, R.string.tile_apps) }
        binding.bottomBar.navSettings.setOnClickListener { openSection(SettingsFragment::class.java, R.string.tile_settings) }
    }

    // ==================== Навигация между разделами ====================

    private var currentSectionTag: String? = null

    /** Открывает ФАКТИЧЕСКИ переданный фрагмент (исправление критического дефекта). */
    private fun openSection(cls: Class<out Fragment>, titleRes: Int) {
        val tag = cls.name
        if (currentSectionTag == tag &&
            supportFragmentManager.findFragmentByTag(tag) is BaseSectionMarker) return
        // replace() удаляет предыдущий фрагмент раздела — лишние экземпляры не накапливаются.
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.vw_enter, R.anim.vw_exit)
            .replace(R.id.sectionContainer, cls, null, tag)
            .commit()
        binding.homeContainer.visibility = View.INVISIBLE
        binding.sectionContainer.visibility = View.VISIBLE
        binding.topBar.modeText.setText(titleRes)
        currentSectionTag = tag
        updateNavHighlight(tag)
    }

    fun showHome() = showHome(silent = false)

    private fun showHome(silent: Boolean) {
        if (currentSectionTag != null || hasRestoredSection()) {
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(0, if (silent) 0 else R.anim.vw_exit)
                .replace(R.id.sectionContainer, EmptyFragment::class.java, null, null)
                .commit()
        }
        binding.sectionContainer.visibility = View.GONE
        binding.homeContainer.visibility = View.VISIBLE
        binding.topBar.modeText.setText(R.string.default_mode)
        currentSectionTag = null
        updateNavHighlight(null)
    }

    /** Красная подсветка активного пункта нижней панели (OEM-стиль, без синего). */
    private fun updateNavHighlight(tag: String?) {
        val items = mapOf<String?, View>(
            null to binding.bottomBar.navHome,
            RadioFragment::class.java.name to binding.bottomBar.navRadio,
            MediaFragment::class.java.name to binding.bottomBar.navMedia,
            AppsFragment::class.java.name to binding.bottomBar.navApps,
            SettingsFragment::class.java.name to binding.bottomBar.navSettings)
        items.forEach { (t, v) -> v.isSelected = t == tag }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SECTION_TAG, currentSectionTag)
    }

    /** Пустой fragment-заглушка для очистки контейнера разделов. */
    class EmptyFragment : Fragment()

    /** Маркер реальных разделов (проверка состояния после восстановления Activity). */
    interface BaseSectionMarker

    /** Интерфейс часов раздела (реализован в BaseSectionFragment). */
    interface BaseSectionHeaderClockHolder { fun refreshClock() }

    /** Восстановление открытого раздела после конфигурационных изменений. Вызывается из фрагмента. */
    fun onSectionRestored(tag: String?, titleRes: Int) {
        if (tag == null) return
        currentSectionTag = tag
        binding.sectionContainer.visibility = View.VISIBLE
        binding.homeContainer.visibility = View.INVISIBLE
        binding.topBar.modeText.setText(titleRes)
        updateNavHighlight(tag)
    }

    override fun onBackPressed() {
        if (currentSectionTag != null || hasRestoredSection()) showHome()
        // На HOME-экране Back не перехватывается — как в штатной системе, но выход не блокируется.
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ==================== Верхняя панель: часы, статус ====================

    private var timeTickReceiver: BroadcastReceiver? = null

    private val timeTickListener = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Системное событие TIME_TICK — обновление ровно раз в минуту, без поллинга.
            updateClock()
        }
    }

    private fun updateClock(force: Boolean = false) {
        if (!::binding.isInitialized) return
        val fmt = if (Prefs.get(this).clock24h) "HH:mm" else "hh:mm a"
        val text = SimpleDateFormat(fmt, Locale.getDefault()).format(Date())
        if (force || text != lastClockText) {
            binding.topBar.clockText.text = text
            binding.topBar.dateText.text =
                SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date())
            lastClockText = text
            // Часы в шапке активного раздела обновляются тем же событием.
            (supportFragmentManager.findFragmentById(R.id.sectionContainer)
                as? BaseSectionHeaderClockHolder)?.refreshClock()
        }
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

    // ==================== Мини-плеер / источник в нижней панели ====================

    override fun onMediaStateChanged(snap: ShellState.MediaSnapshot?) {
        if (!::binding.isInitialized) return
        val mp = binding.miniPlayer
        if (snap == null || snap.controller == null) {
            mp.miniPlayerRoot.visibility = View.GONE
            binding.bottomBar.sourceIcon.visibility = View.GONE
            binding.bottomBar.sourceText.setText(R.string.source_none)
            // Климат: только при наличии реальных данных от CAN-провайдера → скрыт.
            binding.bottomBar.climateText.visibility = View.GONE
            return
        }
        mp.miniPlayerRoot.visibility = View.VISIBLE
        mp.miniTitle.text = snap.title ?: getString(R.string.media_track_unknown)
        mp.miniArtist.text = snap.artist ?: ""
        mp.miniInfo.text = snap.album ?: ""
        if (snap.artwork != null) {
            mp.miniArt.imageTintList = null
            mp.miniArt.setImageBitmap(snap.artwork)
        } else {
            mp.miniArt.setImageResource(R.drawable.ic_album)
            mp.miniArt.imageTintList = android.content.res.ColorStateList.valueOf(
                resources.getColor(R.color.vw_text_disabled, null))
        }
        mp.btnMiniPlay.setImageResource(if (snap.playing) R.drawable.ic_pause else R.drawable.ic_play)
        // Команды доступны только если сессия объявила соответствующие действия.
        mp.btnMiniPlay.isEnabled = snap.canPlay() || snap.canPause()
        mp.btnMiniPrev.isEnabled = snap.canSkipPrev()
        mp.btnMiniNext.isEnabled = snap.canSkipNext()
        mp.btnMiniPlay.alpha = if (mp.btnMiniPlay.isEnabled) 1f else 0.35f
        mp.btnMiniPrev.alpha = if (mp.btnMiniPrev.isEnabled) 1f else 0.35f
        mp.btnMiniNext.alpha = if (mp.btnMiniNext.isEnabled) 1f else 0.35f
        mp.btnMiniPlay.setOnClickListener {
            val c = snap.controller ?: return@setOnClickListener
            when {
                snap.playing && snap.canPause() -> c.transportControls.pause()
                snap.canPlay() -> c.transportControls.play()
            }
        }
        mp.btnMiniPrev.setOnClickListener {
            if (snap.canSkipPrev()) snap.controller?.transportControls?.skipToPrevious()
        }
        mp.btnMiniNext.setOnClickListener {
            if (snap.canSkipNext()) snap.controller?.transportControls?.skipToNext()
        }

        val pkgLabel = AppLauncher.appLabel(this, snap.sourcePackage) ?: snap.sourcePackage ?: ""
        binding.bottomBar.sourceIcon.visibility = View.VISIBLE
        binding.bottomBar.sourceIcon.setImageResource(
            if (snap.sourcePackage?.contains("bluetooth", true) == true) R.drawable.ic_bluetooth else R.drawable.ic_media)
        val state = if (snap.playing) getString(R.string.media_state_playing)
        else getString(R.string.media_state_paused)
        binding.bottomBar.sourceText.text = "$state — $pkgLabel"
        binding.bottomBar.climateText.visibility = View.GONE
    }

    // ==================== Жизненный цикл ====================

    override fun onResume() {
        super.onResume()
        if (timeTickReceiver == null) {
            timeTickReceiver = timeTickListener
            registerReceiver(timeTickListener, IntentFilter(Intent.ACTION_TIME_TICK))
        }
        updateClock(force = true)
        ShellState.refresh()
        updateStatusIcons()
        if (currentSectionTag == null && !hasRestoredSection())
            binding.topBar.modeText.setText(R.string.default_mode)
    }

    override fun onPause() {
        timeTickReceiver?.let { runCatching { unregisterReceiver(it) } }
        timeTickReceiver = null
        super.onPause()
    }

    override fun onDestroy() {
        ShellState.removeListener(this)
        ShellState.save(this)
        super.onDestroy()
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

    companion object {
        private const val KEY_SECTION_TAG = "vw_section_tag"
    }
}
