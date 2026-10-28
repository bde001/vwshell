package com.vwshell.auto.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vwshell.auto.R
import com.vwshell.auto.core.AppLauncher
import com.vwshell.auto.core.FmBand
import com.vwshell.auto.core.Prefs

/**
 * Радио в стиле Discover Pro. Реальный приём FM возможен только через тюнер устройства:
 * — если прошивка предоставляет FM-интенты/приложение, кнопка «Открыть штатное радио» запускает его;
 * — список станций формируется ТОЛЬКО из реально сохранённых пользователем частот (избранное);
 * — без доступа к тюнеру изменение цифры на экране честно подписывается как настройка UI,
 *   а не реальная настройка радиосигнала. RDS/RT-текст не выдумывается.
 *
 * Единицы: десятые доли МГц (875 = 87.5, 1080 = 108.0). Шаг 0.1 МГц = 1, шаг 1.0 МГц = 10.
 */
class RadioFragment : BaseSectionFragment() {

    override val titleRes = R.string.radio_title

    private var freqTenths = FmBand.DEFAULT
    private lateinit var prefs: Prefs
    private lateinit var freqText: TextView
    private lateinit var nameText: TextView
    private lateinit var statusText: TextView
    private lateinit var stationsEmpty: TextView
    private lateinit var favBtn: ImageButton
    private lateinit var adapter: SimpleRowAdapter
    private var tunerReceiver: BroadcastReceiver? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_radio, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs.get(requireContext())

        freqText = view.findViewById(R.id.radioFreq)
        nameText = view.findViewById(R.id.radioStationName)
        statusText = view.findViewById(R.id.radioStatus)
        stationsEmpty = view.findViewById(R.id.stationsEmpty)
        favBtn = view.findViewById(R.id.btnFav)

        freqTenths = prefs.lastFreqTenths()?.let { if (FmBand.isValid(it)) it else FmBand.DEFAULT } ?: FmBand.DEFAULT
        renderFrequency()

        val list = view.findViewById<RecyclerView>(R.id.stationList)
        list.layoutManager = LinearLayoutManager(requireContext())
        adapter = SimpleRowAdapter(onClick = { item ->
            (item.payload as? Int)?.let { f -> setFrequency(f) }
        })
        list.adapter = adapter
        refreshStations()

        // Предыдущая/следующая — по реально сохранённым пресетам; если их нет — шаг 1.0 МГц.
        view.findViewById<View>(R.id.btnRadioPrev).setOnClickListener { step(-1) }
        view.findViewById<View>(R.id.btnRadioNext).setOnClickListener { step(+1) }
        // Долгое нажатие — тонкая подстройка 0.1 МГц (как крутилка seek на штатном радио).
        view.findViewById<View>(R.id.btnRadioPrev).setOnLongClickListener { fineStep(-1); true }
        view.findViewById<View>(R.id.btnRadioNext).setOnLongClickListener { fineStep(+1); true }
        favBtn.setOnClickListener {
            val added = prefs.toggleFavorite(freqTenths)
            Toast.makeText(requireContext(),
                if (added) R.string.radio_fav_added else R.string.radio_fav_removed,
                Toast.LENGTH_SHORT).show()
            refreshStations()
            updateFavIcon()
        }

        // Нижняя панель режимов (декоративная навигация по образцу VW):
        // «Источник» реально открывает штатное радио, если оно назначено; остальные режимы — UI-заглушки.
        view.findViewById<View>(R.id.modeSource).setOnClickListener {
            val ok = AppLauncher.launchRadio(requireContext(), prefs.radioAppPackage)
            if (!ok) Toast.makeText(requireContext(), R.string.not_installed, Toast.LENGTH_SHORT).show()
        }
        listOf(R.id.modeStation, R.id.modeMemory, R.id.modePreset, R.id.modeManual, R.id.modeSettings)
            .forEach { id ->
                view.findViewById<View>(id).setOnClickListener {
                    Toast.makeText(requireContext(), R.string.unavailable, Toast.LENGTH_SHORT).show()
                }
            }

        // Статус реального тюнера
        if (!AppLauncher.hasFmTuner(requireContext())) {
            statusText.setText(R.string.radio_no_tuner)
        } else {
            statusText.setText(R.string.radio_tuner_ui_only)
            registerTunerReceiver()
        }
    }

    /** Приёмник OEM-широковещания изменения частоты: обновляет UI только реальными данными тюнера. */
    private fun registerTunerReceiver() {
        try {
            tunerReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val khz = intent?.getIntExtra("frequency", -1) ?: -1
                    if (khz in 76000..108000) {
                        val tenths = (khz / 100).toInt() // kHz → десятые доли МГц
                        freqTenths = FmBand.clamp(tenths)
                        prefs.setLastFreq(freqTenths)
                        renderFrequency()
                        refreshStations()
                    }
                }
            }
            requireContext().registerReceiver(
                tunerReceiver,
                IntentFilter("fmradio.stereodatachange") // OEM-акция некоторых тюнеров; отсутствие — не ошибка
            )
        } catch (_: Exception) { /* приёмник не обязателен */ }
    }

    override fun onDestroyView() {
        tunerReceiver?.let { try { requireContext().unregisterReceiver(it) } catch (_: Exception) {} }
        tunerReceiver = null
        super.onDestroyView()
    }

    private fun step(dir: Int) {
        val presets = prefs.presets()
        val next = FmBand.nextPreset(presets, freqTenths, dir)
            ?: FmBand.step(freqTenths, dir, FmBand.STEP_COARSE) // 1.0 МГц = 10 единиц
        setFrequency(next)
    }

    private fun fineStep(dir: Int) = setFrequency(FmBand.step(freqTenths, dir, FmBand.STEP_FINE)) // 0.1 МГц = 1

    private fun setFrequency(tenths: Int) {
        freqTenths = FmBand.clamp(tenths)
        prefs.setLastFreq(freqTenths)
        renderFrequency()
        refreshStations()
    }

    private fun renderFrequency() {
        freqText.text = FmBand.format(freqTenths)
        // Название станции (RDS PS/RT) доступно только при реальном сигнале тюнера — не выдумываем.
        nameText.text = ""
        updateFavIcon()
    }

    private fun updateFavIcon() {
        favBtn.setImageResource(
            if (prefs.favorites().contains(freqTenths.toString())) R.drawable.ic_star else R.drawable.ic_star_outline)
    }

    private fun refreshStations() {
        val favs = prefs.presets()
        val rows = favs.map {
            RowItem(FmBand.format(it), "", checked = it == freqTenths, payload = it)
        }
        adapter.submit(rows)
        stationsEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }
}
