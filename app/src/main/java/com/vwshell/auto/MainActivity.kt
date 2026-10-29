package com.vwshell.auto

import android.Manifest
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.vwshell.auto.core.AppLauncher
import com.vwshell.auto.core.KeyLogger
import com.vwshell.auto.core.PlayerSource
import com.vwshell.auto.core.Prefs
import com.vwshell.auto.core.ShellState
import com.vwshell.auto.databinding.ActivityMainBinding
import com.vwshell.auto.media.LocalPlayerEngine
import com.vwshell.auto.ui.AppsFragment
import com.vwshell.auto.ui.AppPickerDialog
import com.vwshell.auto.ui.BaseSectionFragment
import com.vwshell.auto.ui.BaseSectionHeaderClockHolder
import com.vwshell.auto.ui.MediaFragment
import com.vwshell.auto.ui.NavigationFragment
import com.vwshell.auto.ui.PhoneFragment
import com.vwshell.auto.ui.RadioFragment
import com.vwshell.auto.ui.SettingsFragment
import com.vwshell.auto.widget.VWTile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VW Shell — оболочка в стиле Volkswagen Discover Pro (Android 10–13).
 *
 * Полноэкранный HOME-режим: системные панели скрыты, жесты не перехвачены.
 * Главный экран: ровно две листаемые страницы (ViewPager2), каждая — сетка 3x2;
 * под сеткой — два кружка-индикатора (активный красный #E3182D). Нижней панели нет.
 *
 * Навигация: openSection() открывает ФАКТИЧЕСКИ переданный Fragment через replace()
 * с тегом = имя класса (исправление дефекта с EmptyFragment); showHome() удаляет раздел;
 * Back возвращает домой, на главном экране событие отдаётся системе.
 */
class MainActivity : AppCompatActivity(), ShellState.Listener, BaseSectionHeaderClockHolder, PlayerSource.Listener {

    /** Маркер: фрагмент является разделом оболочки (для восстановления после пересоздания Activity). */
    interface BaseSectionMarker

    /** Хост синхронизации chrome раздела (см. BaseSectionHeaderClockHolder в ui/BaseSectionFragment.kt). */
    private lateinit var binding: ActivityMainBinding
    private var lastClockText: String? = null
    private var currentSectionTag: String? = null
    private var receiverRegistered = false
    private val transportBoundMarker = Any()

    private val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateClock()
            sectionFragments().forEach { it.refreshClock() }
        }
    }

    /** Запрос READ_MEDIA_AUDIO (Android 13) / READ_EXTERNAL_STORAGE (10–11) из плеера MediaFragment. */
    private val audioPermLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            (sectionFragments().firstOrNull { it is MediaFragment } as? MediaFragment)
                ?.onAudioPermissionResult(granted)
        }

    // ==================== Life cycle ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupFullscreen()
        setupPager()
        ShellState.load(this)
        ShellState.init(this)
        ShellState.addListener(this, this)
        PlayerSource.restoreFromPrefs(this)
        PlayerSource.addListener(this)
        binding.pager.adapter?.notifyDataSetChanged()

        binding.topBar.modeText.text = "" // на главном экране заголовок раздела пуст

        // Восстановление после поворота/пробуждения: FragmentManager сам восстановит
        // фрагмент раздела; BaseSectionFragment.onViewCreated вызовет onSectionRestored().
        if (!hasRestoredSection()) showHome(silent = true) else syncMiniPlayer()
        updateClock(force = true)
        updateStatusIcons()
        maybeShowHomeHint()
        applyHomeTileLayout()
    }

    companion object {
        const val REQ_AUDIO_PERM_HINT = 101
    }

    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, timeTickReceiver,
                IntentFilter(Intent.ACTION_TIME_TICK), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        updateClock(force = true)
        updateStatusIcons()
        ShellState.refresh()
    }

    override fun onPause() {
        if (receiverRegistered) {
            try { unregisterReceiver(timeTickReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        ShellState.save(this)
        super.onPause()
    }

    override fun onDestroy() {
        ShellState.removeListener(this)
        PlayerSource.removeListener(this)
        super.onDestroy()
    }

    private fun hasRestoredSection(): Boolean =
        supportFragmentManager.findFragmentById(R.id.sectionContainer) is BaseSectionMarker

    /** Полноэкранный OEM-режим; системные жесты и диалоги разрешений остаются рабочими. */
    private fun setupFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val controller = window.insetsController
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                return
            }
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupFullscreen()
            updateClock(force = true)
        }
    }

    // ==================== Часы (по системному TIME_TICK, без поллинга) ====================

    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEE, d MMM", Locale.getDefault())

    private fun updateClock(force: Boolean = false) {
        if (!::binding.isInitialized) return
        val now = Date()
        val t = clockFmt.format(now)
        if (force || t != lastClockText) {
            lastClockText = t
            binding.topBar.clockText.text = t
            binding.topBar.dateText.text = dateFmt.format(now)
        }
    }

    /** Индикаторы статуса: только реально определяемые данные, иначе — скрыты. */
    private fun updateStatusIcons() {
        val bt = AppLauncher.bluetoothState(this)
        binding.topBar.iconBluetooth.visibility = if (bt == true) View.VISIBLE else View.INVISIBLE
        binding.topBar.iconSignal.visibility = View.GONE // сигнал сети без READ_PHONE_STATE недоступен — скрываем честно
    }

    // ==================== Страницы главного меню (2 x фиксированная сетка 3x2) ====================

    /**
     * Плитка страницы. empty=true — прозрачный некликабельный плейсхолдер (геометрия сетки не гуляет).
     */
    private data class TileSpec(
        val icon: Int = 0,
        @StringRes val label: Int = 0,
        val highlight: (() -> Boolean)? = null,
        val onLong: (() -> Unit)? = null,
        val empty: Boolean = false,
        val action: (() -> Unit)? = null,
    )

    /**
     * Обобщённый механизм «выбор и запуск приложения» (AppConnect / Звук / Автомобиль):
     * сохранённый пакет проверяется на установку; при удалении — очистка выбора + повторный диалог;
     * пустой список кандидатов — понятное сообщение, без падений и мёртвых кнопок.
     */
    private fun pickAndLaunchApp(
        getSaved: () -> String?,
        setSaved: (String?) -> Unit,
        titleRes: Int,
        emptyRes: Int,
        appListProvider: () -> List<com.vwshell.auto.core.AppEntry>,
    ) {
        val saved = getSaved()
        if (saved != null && AppLauncher.isPackageInstalled(this, saved)) {
            if (!AppLauncher.launchPackage(this, saved)) {
                Toast.makeText(this, R.string.vehicle_app_missing, Toast.LENGTH_LONG).show()
            }
            return
        }
        if (saved != null && !AppLauncher.isPackageInstalled(this, saved)) {
            setSaved(null) // приложение удалено — очищаем выбор честно
            Toast.makeText(this, R.string.vehicle_removed, Toast.LENGTH_LONG).show()
        }
        val apps = appListProvider()
        if (apps.isEmpty()) {
            Toast.makeText(this, emptyRes, Toast.LENGTH_LONG).show()
            return
        }
        AppPickerDialog.show(
            context = this,
            titleRes = titleRes,
            apps = apps,
            onPick = { entry ->
                setSaved(entry.packageName)
                if (!AppLauncher.launchPackage(this, entry.packageName)) {
                    Toast.makeText(this, R.string.vehicle_app_missing, Toast.LENGTH_LONG).show()
                }
            },
            onCancel = null,
        )
    }

    /** Выбор диагностического приложения для плитки «Автомобиль». */
    private fun showVehicleAppChooser() {
        showAppChooser(
            titleRes = R.string.app_pick_vehicle,
            emptyRes = R.string.app_none_available,
            setSaved = { Prefs.get(this).vehicleAppPackage = it },
            appListProvider = { AppLauncher.diagnosticApps(this) },
        )
    }

    /** Запускает выбранный диагностический пакет, затем Car Scanner Free/Pro. */
    private fun launchVehicleApp() {
        val prefs = Prefs.get(this)
        if (AppLauncher.launchCarScanner(this, prefs.vehicleAppPackage)) return

        // Если Car Scanner не установлен, предлагаем реально найденные диагностические приложения.
        val candidates = AppLauncher.diagnosticApps(this)
        if (candidates.isNotEmpty()) {
            showVehicleAppChooser()
        } else {
            AppLauncher.openInstallPage(this, AppLauncher.CAR_SCANNER_FREE)
        }
    }

    /** Совместимый fallback для медиасессий, которые реагируют на media-button события. */
    private fun dispatchMediaKey(
        controller: android.media.session.MediaController,
        keyCode: Int,
    ): Boolean = try {
        val down = controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        val up = controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        down || up
    } catch (_: Exception) {
        false
    }

    /** Диалог смены выбранного приложения (долгое нажатие на плитку). */
    private fun showAppChooser(
        titleRes: Int,
        emptyRes: Int,
        setSaved: (String?) -> Unit,
        appListProvider: () -> List<com.vwshell.auto.core.AppEntry>,
    ) {
        val apps = appListProvider()
        if (apps.isEmpty()) {
            Toast.makeText(this, emptyRes, Toast.LENGTH_LONG).show()
            return
        }
        AppPickerDialog.show(
            context = this,
            titleRes = titleRes,
            apps = apps,
            onPick = { entry ->
                setSaved(entry.packageName)
                if (!AppLauncher.launchPackage(this, entry.packageName)) {
                    Toast.makeText(this, R.string.vehicle_app_missing, Toast.LENGTH_LONG).show()
                }
            },
            onCancel = null,
        )
    }

    private fun pageOneTiles(): List<TileSpec> = listOf(
        TileSpec(R.drawable.ic_radio, R.string.tile_radio) {
            openSection(RadioFragment::class.java, R.string.tile_radio)
        },
        TileSpec(R.drawable.ic_bluetooth, R.string.tile_bluetooth,
            highlight = { PlayerSource.kind == PlayerSource.Kind.EXTERNAL }) {
            if (!PlayerSource.toggle(this)) {
                Toast.makeText(this, R.string.media_no_external_session, Toast.LENGTH_LONG).show()
            }
        },
        TileSpec(R.drawable.ic_apps, R.string.tile_appconnect,
            onLong = { showAppChooser(R.string.app_pick_appconnect, R.string.app_none_available,
                { Prefs.get(this).appConnectAppPackage = it }) { AppLauncher.appConnectCandidates(this) } }) {
            pickAndLaunchApp(
                getSaved = { Prefs.get(this).appConnectAppPackage },
                setSaved = { Prefs.get(this).appConnectAppPackage = it },
                titleRes = R.string.app_pick_appconnect,
                emptyRes = R.string.app_none_available,
                appListProvider = { AppLauncher.appConnectCandidates(this) })
        },
        TileSpec(R.drawable.ic_car, R.string.tile_vehicle,
            onLong = { showVehicleAppChooser() }) {
            launchVehicleApp()
        },
        TileSpec(R.drawable.ic_media, R.string.tile_media) {
            openSection(MediaFragment::class.java, R.string.tile_media)
        },
        TileSpec(R.drawable.ic_nav, R.string.tile_navigation) {
            if (!AppLauncher.launchMapsDirect(this, Prefs.get(this).navAppPackage)) {
                openInstallOrToast(Prefs.get(this).navAppPackage ?: "com.google.android.apps.maps", R.string.nav_none)
            }
        }
    )

    /** Вторая страница сохраняет ту же сетку 3x2; настройки и системные утилиты вынесены сюда. */
    private fun pageTwoTiles(): List<TileSpec> = listOf(
        TileSpec(R.drawable.ic_volume, R.string.tile_sound,
            onLong = { showAppChooser(R.string.app_pick_sound, R.string.app_no_sound_apps,
                { Prefs.get(this).soundAppPackage = it }) { AppLauncher.soundCandidates(this) } }) {
            pickAndLaunchApp(
                getSaved = { Prefs.get(this).soundAppPackage },
                setSaved = { Prefs.get(this).soundAppPackage = it },
                titleRes = R.string.app_pick_sound,
                emptyRes = R.string.app_no_sound_apps,
                appListProvider = { AppLauncher.soundCandidates(this) })
        },
        TileSpec(R.drawable.ic_settings, R.string.tile_settings) {
            openSection(SettingsFragment::class.java, R.string.tile_settings)
        },
        TileSpec(R.drawable.ic_apps, R.string.page2_apps) {
            openSection(AppsFragment::class.java, R.string.apps_title)
        },
        TileSpec(R.drawable.ic_settings, R.string.page2_sys_settings) {
            if (!AppLauncher.launchPackage(this, "com.android.settings")) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.sys_settings_missing, Toast.LENGTH_LONG).show()
                }
            }
        },
        TileSpec(empty = true),
        TileSpec(empty = true)
    )

    /** Применяет сохранённые отступы ко всем видимым сеткам. */
    fun applyHomeTileLayout() {
        if (!::binding.isInitialized) return
        val pagerRecycler = binding.pager.getChildAt(0) as? RecyclerView ?: return
        for (pageIndex in 0 until pagerRecycler.childCount) {
            val page = pagerRecycler.getChildAt(pageIndex) as? android.widget.GridLayout ?: continue
            for (i in 0 until page.childCount) applyTileMargins(page.getChildAt(i))
        }
    }

    private fun applyTileMargins(view: View) {
        val params = view.layoutParams as? android.widget.GridLayout.LayoutParams ?: return
        val prefs = Prefs.get(this)
        val density = resources.displayMetrics.density
        val horizontal = (prefs.tileSpacingDp * density).toInt()
        val vertical = (prefs.tileVerticalSpacingDp * density).toInt()
        params.setMargins(horizontal, vertical, horizontal, vertical)
        view.layoutParams = params
    }

    override fun onPlayerSourceChanged(kind: PlayerSource.Kind) {
        if (!::binding.isInitialized) return
        binding.pager.adapter?.notifyDataSetChanged()
    }

    private fun setupPager() {
        binding.pager.adapter = HomePageAdapter(listOf(pageOneTiles(), pageTwoTiles()), ::applyTileMargins)
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updatePageDots(position)
        })
        updatePageDots(binding.pager.currentItem)
    }

    /** Ровно два кружка: активный красный #E3182D, неактивный серый. */
    private fun updatePageDots(active: Int) {
        binding.dot0.setBackgroundResource(
            if (active == 0) R.drawable.page_dot_active else R.drawable.page_dot_inactive)
        binding.dot1.setBackgroundResource(
            if (active == 1) R.drawable.page_dot_active else R.drawable.page_dot_inactive)
    }

    private class HomePageAdapter(
        val pages: List<List<TileSpec>>,
        private val applyMargins: (View) -> Unit
    ) :
        RecyclerView.Adapter<HomePageAdapter.PageHolder>() {

        class PageHolder(val view: View) : RecyclerView.ViewHolder(view)

        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PageHolder {
            val layout = if (viewType == 0) R.layout.page_home_grid else R.layout.page_home_placeholder
            return PageHolder(android.view.LayoutInflater.from(parent.context)
                .inflate(layout, parent, false))
        }

        override fun getItemViewType(position: Int) =
            if (pages[position].none { !it.empty }) 1 else 0

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val tiles = pages[position]
            if (tiles.isEmpty()) return
            val ids = intArrayOf(R.id.tileA, R.id.tileB, R.id.tileC, R.id.tileD,
                R.id.tileE, R.id.tileF)
            tiles.forEachIndexed { i, spec ->
                val tile = holder.view.findViewById<VWTile>(ids[i])
                if (spec.empty) {
                    // Прозрачный некликабельный плейсхолдер: геометрия сетки не гуляет,
                    // слушатели не вешаются (action == null — без NPE).
                    tile.visibility = View.INVISIBLE
                    tile.isClickable = false
                    tile.isLongClickable = false
                    tile.onTileClick = null
                    tile.onTileLongClick = null
                } else {
                    tile.visibility = View.VISIBLE
                    tile.setup(spec.icon, spec.label)
                    tile.onTileClick = spec.action
                    tile.onTileLongClick = spec.onLong
                    tile.setHighlighted(spec.highlight?.invoke() == true)
                }
                applyMargins(tile)
            }
        }
    }

    /** Приложение не установлено: аккуратное сообщение + переход на страницу установки. */
    private fun openInstallOrToast(pkg: String, @StringRes messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
        AppLauncher.openInstallPage(this, pkg)
    }

    // ==================== Навигация между разделами ====================

    /** Открывает ФАКТИЧЕСКИ переданный фрагмент (исправление критического дефекта). */
    private fun openSection(cls: Class<out Fragment>, @StringRes titleRes: Int) {
        val tag = cls.name
        if (currentSectionTag == tag &&
            supportFragmentManager.findFragmentByTag(tag) is BaseSectionMarker) return
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.vw_enter, R.anim.vw_exit)
            .replace(R.id.sectionContainer, cls, null, tag)
            .commit()
        binding.homeContainer.visibility = View.INVISIBLE
        binding.sectionContainer.visibility = View.VISIBLE
        binding.topBar.modeText.setText(titleRes)
        currentSectionTag = tag
    }

    fun showHome() = showHome(silent = false)

    private fun showHome(silent: Boolean) {
        val f = supportFragmentManager.findFragmentById(R.id.sectionContainer)
        if (f != null) {
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(0, if (silent) 0 else R.anim.vw_exit)
                .remove(f)
                .commit()
        }
        currentSectionTag = null
        binding.sectionContainer.visibility = View.GONE
        binding.homeContainer.visibility = View.VISIBLE
        binding.topBar.modeText.text = ""
        applyHomeTileLayout()
    }

    /** Вызывается из BaseSectionFragment.onViewCreated — синхронизирует chrome после восстановления. */
    override fun onSectionRestored(tag: String, @StringRes titleRes: Int) {
        currentSectionTag = tag
        binding.homeContainer.visibility = View.INVISIBLE
        binding.sectionContainer.visibility = View.VISIBLE
        binding.topBar.modeText.setText(titleRes)
    }

    private fun sectionFragments(): List<BaseSectionFragment> =
        supportFragmentManager.fragments.filterIsInstance<BaseSectionFragment>()

    override fun onBackPressed() {
        if (currentSectionTag != null || hasRestoredSection()) showHome()
        else @Suppress("DEPRECATION") super.onBackPressed() // на главном экране HOME-приложение не перехватывает Back
    }

    // ==================== Мини-плеер главного экрана ====================

    override fun onMediaStateChanged(snap: ShellState.MediaSnapshot?) {
        if (!::binding.isInitialized) return
        syncMiniPlayer()
    }

    private fun syncMiniPlayer() {
        val mini = binding.miniPlayer.root
        val local = LocalPlayerEngine.peekOrNull(this)
        val snap = ShellState.current
        val playing = local?.isPlaying == true || (snap?.playing == true && snap.controller != null)
        val title: String? = local?.currentItem?.displayTitle ?: snap?.title
        val artist: String? = local?.currentItem?.displayArtist ?: snap?.artist

        if (title == null && !playing) {
            mini.visibility = View.GONE
            return
        }
        mini.visibility = View.VISIBLE
        mini.findViewById<TextView>(R.id.miniTitle).text = title ?: getString(R.string.media_track_unknown)
        mini.findViewById<TextView>(R.id.miniArtist).text = artist ?: ""
        val play = mini.findViewById<ImageButton>(R.id.btnMiniPlay)
        play.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        // Кнопки активны только если реальный источник объявил действия.
        val canTransport = local?.hasPlaylist == true || snap?.controller != null
        play.alpha = if (canTransport) 1f else 0.35f
        mini.findViewById<View>(R.id.btnMiniPrev).alpha =
            if (local?.hasPlaylist == true || snap?.controller != null) 1f else 0.35f
        mini.findViewById<View>(R.id.btnMiniNext).alpha =
            if (local?.hasPlaylist == true || snap?.controller != null) 1f else 0.35f

        if (mini.getTag() !== transportBoundMarker) {
            mini.setTag(transportBoundMarker)
            play.setOnClickListener { toggleTransport() }
            mini.findViewById<View>(R.id.btnMiniPrev).setOnClickListener { skipTransport(next = false) }
            mini.findViewById<View>(R.id.btnMiniNext).setOnClickListener { skipTransport(next = true) }
        }
    }

    /** Приоритет: собственный локальный плеер; иначе — внешняя медиасессия (только объявленные действия). */
    private fun toggleTransport() {
        val local = LocalPlayerEngine.peekOrNull(this)
        if (local != null && local.hasPlaylist) { local.togglePlayPause(); return }
        val snap = ShellState.current ?: return
        val c = snap.controller ?: return
        val actions = try { c.playbackState?.actions ?: 0L } catch (_: Exception) { 0L }
        when {
            actions and android.media.session.PlaybackState.ACTION_PLAY_PAUSE != 0L -> dispatchMediaKey(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            snap.playing && snap.canPause() -> runCatching { c.transportControls.pause() }
                .onFailure { dispatchMediaKey(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
            !snap.playing && snap.canPlay() -> runCatching { c.transportControls.play() }
                .onFailure { dispatchMediaKey(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
            else -> dispatchMediaKey(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
    }

    private fun skipTransport(next: Boolean) {
        val local = LocalPlayerEngine.peekOrNull(this)
        if (local != null && local.hasPlaylist) { if (next) local.next() else local.previous(); return }
        val snap = ShellState.current ?: return
        val c = snap.controller ?: return
        if (next) {
            if (!snap.canSkipNext()) return unsupported()
            c.transportControls.skipToNext()
        } else {
            if (!snap.canSkipPrev()) return unsupported()
            c.transportControls.skipToPrevious()
        }
    }

    private fun unsupported() =
        Toast.makeText(this, R.string.media_action_unsupported, Toast.LENGTH_SHORT).show()

    // ==================== Разрешения от имени MediaFragment ====================

    fun requestAudioPermission() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        audioPermLauncher.launch(perm)
    }

    fun hasAudioPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, perm) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // ==================== Launcher / ROLE_HOME ====================

    /** Активна ли оболочка как домашний экран (проверяется реально, без догадок). */
    private fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            packageManager.resolveActivity(home,
                android.content.pm.PackageManager.ResolveInfoFlags.of(0L))
        else @Suppress("DEPRECATION")
            packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == packageName
    }

    /** Onboarding: если оболочка ещё не Home — фирменный запрос назначения через системный механизм. */
    private fun maybeShowHomeHint() {
        if (Prefs.get(this).homeHintDismissed || isDefaultHome()) return
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.home_hint_title)
            .setMessage(R.string.home_hint_message)
            .setPositiveButton(R.string.home_hint_action) { _, _ -> requestHomeRole() }
            .setNegativeButton(R.string.home_hint_later) { d, _ ->
                Prefs.get(this).homeHintDismissed = true; d.dismiss()
            }
            .setCancelable(false)
            .create()
        dlg.window?.decorView?.setBackgroundResource(R.color.vw_background)
        dlg.show()
    }

    /** RoleManager.ROLE_HOME (Android 10+) — штатный системный механизм с подтверждением пользователя. */
    private fun requestHomeRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
                return
            }
        }
        // Fallback: системный выбор приложения Home (может отсутствовать на OEM-прошивках).
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.home_settings_missing, Toast.LENGTH_LONG).show()
        }
        Prefs.get(this).homeHintDismissed = true
    }

    // ==================== Аппаратные клавиши (руль) ====================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { toggleTransport(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { skipTransport(true); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { skipTransport(false); return true }
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE -> {
                    // Стандартная обработка системы (AudioManager) — не перехватываем.
                }
                else -> KeyLogger.logUnknown(this, event)
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
