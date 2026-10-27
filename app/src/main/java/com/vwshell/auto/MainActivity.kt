package com.vwshell.auto

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
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
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.vwshell.auto.core.AppLauncher
import com.vwshell.auto.core.KeyLogger
import com.vwshell.auto.core.Prefs
import com.vwshell.auto.core.ShellState
import com.vwshell.auto.databinding.ActivityMainBinding
import com.vwshell.auto.ui.MediaFragment
import com.vwshell.auto.ui.PhoneFragment
import com.vwshell.auto.ui.RadioFragment
import com.vwshell.auto.ui.SettingsFragment
import com.vwshell.auto.widget.VWTile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VW Shell — оболочка в стиле Volkswagen Discover Pro.
 * Полноэкранный HOME-режим: без статус-бара и панели навигации, системные жесты
 * остаются рабочими (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE).
 *
 * Главный экран: две горизонтально листаемые страницы (ViewPager2), каждая — сетка ровно 3x2.
 * Под сеткой — индикатор из двух кружков (активный красный #E3182D). Нижней панели нет.
 *
 * Навигация: openSection() открывает ФАКТИЧЕСКИ переданный Fragment через replace()
 * с тегом = имя класса; повторное нажатие не создаёт дубликат. showHome() удаляет раздел.
 * Back возвращает домой; на главном экране событие отдаётся системе.
 */
class MainActivity : AppCompatActivity(), ShellState.Listener {

    private lateinit var binding: ActivityMainBinding
    private var lastClockText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupFullscreen()
        setupPager()
        ShellState.init(this)
        ShellState.addListener(this, this)

        // Восстановление после поворота/пробуждения: FragmentManager сам восстановит
        // фрагмент раздела; BaseSectionFragment.onViewCreated вызовет onSectionRestored().
        if (!hasRestoredSection()) showHome(silent = true)
        updateClock(force = true)
        updateStatusIcons()
        maybeShowHomeHint()
    }

    /** Есть ли восстановленный раздел после пересоздания Activity. */
    private fun hasRestoredSection(): Boolean =
        supportFragmentManager.findFragmentById(R.id.sectionContainer) is BaseSectionMarker

    /** Полноэкранный OEM-режим; системные жесты не перехватываются. */
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
        if (hasFocus) {
            setupFullscreen()
            updateClock(force = true)
        }
    }

    // ==================== Страницы главного меню (2 x сетка 3x2) ====================

    private data class TileSpec(
        val icon: Int,
        @StringRes val label: Int,
        val action: () -> Unit
    )

    private fun pageOneTiles(): List<TileSpec> = listOf(
        TileSpec(R.drawable.ic_radio, R.string.tile_radio) {
            openSection(RadioFragment::class.java, R.string.tile_radio) },
        TileSpec(R.drawable.ic_media, R.string.tile_media) {
            openSection(MediaFragment::class.java, R.string.tile_media) },
        TileSpec(R.drawable.ic_phone, R.string.tile_phone) {
            openSection(PhoneFragment::class.java, R.string.tile_phone) },
        TileSpec(R.drawable.ic_nav, R.string.tile_navigation) {
            // Прямой запуск Google Maps без промежуточного экрана выбора.
            if (!AppLauncher.launchMapsDirect(this, Prefs.get(this).navAppPackage)) {
                openInstallOrToast(Prefs.get(this).navAppPackage ?: "com.google.android.apps.maps",
                    R.string.nav_none)
            } },
        TileSpec(R.drawable.ic_car, R.string.tile_vehicle) {
            // Прямой запуск Car Scanner ELM OBD2; никаких фиктивных данных телеметрии.
            if (!AppLauncher.launchCarScanner(this, Prefs.get(this).vehicleAppPackage)) {
                openInstallOrToast(Prefs.get(this).vehicleAppPackage ?: Prefs.DEFAULT_VEHICLE_PKG,
                    R.string.vehicle_app_missing)
            } },
        TileSpec(R.drawable.ic_settings, R.string.tile_settings) {
            openSection(SettingsFragment::class.java, R.string.tile_settings) })
    )

    /** Вторая страница: подготовленная структура для будущих плиток (пока пустые ячейки). */
    private fun pageTwoTiles(): List<TileSpec> = emptyList()

    private fun setupPager() {
        binding.pager.adapter = HomePageAdapter(listOf(pageOneTiles(), pageTwoTiles()))
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updatePageDots(position)
        })
        updatePageDots(binding.pager.currentItem)
    }

    /** Ровно два кружка: активный красный, неактивный серый. */
    private fun updatePageDots(active: Int) {
        binding.dot0.setBackgroundResource(
            if (active == 0) R.drawable.page_dot_active else R.drawable.page_dot_inactive)
        binding.dot1.setBackgroundResource(
            if (active == 1) R.drawable.page_dot_active else R.drawable.page_dot_inactive)
    }

    private class HomePageAdapter(val pages: List<List<TileSpec>>) :
        RecyclerView.Adapter<HomePageAdapter.PageHolder>() {

        class PageHolder(val view: View) : RecyclerView.ViewHolder(view)

        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PageHolder {
            val layout = if (viewType == 0) R.layout.page_home_grid else R.layout.page_home_placeholder
            return PageHolder(android.view.LayoutInflater.from(parent.context)
                .inflate(layout, parent, false))
        }

        override fun getItemViewType(position: Int) = if (pages[position].isEmpty()) 1 else 0

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val tiles = pages[position]
            if (tiles.isEmpty()) return
            val ids = intArrayOf(R.id.tileA, R.id.tileB, R.id.tileC, R.id.tileD, R.id.tileE, R.id.tileF)
            tiles.forEachIndexed { i, spec ->
                val tile = holder.view.findViewById<VWTile>(ids[i])
                tile.setup(spec.icon, spec.label)
                tile.onTileClick = spec.action
            }
        }
    }

    /** Приложение не установлено: аккуратное сообщение + переход на страницу установки. */
    private fun openInstallOrToast(pkg: String, @StringRes messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
        AppLauncher.openInstallPage(this, pkg)
    }

    // ==================== Навигация между разделами ====================

    private var currentSectionTag: String? = null

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
        if (currentSectionTag != null || hasRestoredSection()) {
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(0, if (silent) 0 else R.anim.vw_exit)
                .remove/support_null_check
        }
    }

    companion object {
        private const val KEY_SECTION_TAG = "vw_section_tag"
    }
}
