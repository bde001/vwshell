package com.vwshell.auto.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
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
import com.vwshell.auto.core.Prefs

/**
 * Радио. Реальный приём FM возможен только через тюнер устройства:
 * — если прошивка предоставляет FM-интенты/приложение, кнопка «Открыть штатное радио» запускает его;
 * — список станций формируется ТОЛЬКО из реально сохранённых пользователем частот (избранное).
 * Фиктивных станций и RT-текста нет: без доступа к тюнеру интерфейс честно сообщает об этом.
 */
class RadioFragment : BaseSectionFragment() {

    override val titleRes = R.string.radio_title

    private var freqKhz = 880 // нижняя граница FM-диапазона как стартовая настройка UI
    private lateinit var prefs: Prefs
    private lateinit var freqText: TextView
    private lateinit var nameText: TextView
    private lateinit var statusText: TextView
    private lateinit var stationsEmpty: TextView
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
        val favBtn = view.findViewById<ImageButton>(R.id.btnFav)

        freqKhz = prefs.lastFreqKhz() ?: freqKhz
        renderFrequency()

        val list = view.findViewById<RecyclerView>(R.id.stationList)
        list.layoutManager = LinearLayoutManager(requireContext())
        adapter = SimpleRowAdapter(onClick = { item ->
            (item.payload as? Int)?.let { f -> setFrequency(f) }
        })
        list.adapter = adapter
        refreshStations()

        view.findViewById<View>(R.id.btnPrev).setOnClickListener { step(-1) }
        view.findViewById<View>(R.id.btnNext).setOnClickListener { step(+1) }
        view.findViewById<View>(R.id.btnSearch).setOnClickListener { seekUp() }
        favBtn.setOnClickListener {
            val added = prefs.toggleFavorite(freqKhz)
            favBtn.setImageResource(if (added) R.drawable.ic_star else R.drawable.ic_star_outline)
            refreshStations()
        }
        favBtn.setImageResource(
            if (prefs.favorites().contains(freqKhz.toString())) R.drawable.ic_star else R.drawable.ic_star_outline
        )

        view.findViewById<View>(R.id.btnOpenRadioApp).setOnClickListener {
            val ok = AppLauncher.launchRadio(requireContext(), prefs.radioAppPackage)
            if (!ok) Toast.makeText(requireContext(), R.string.not_installed, Toast.LENGTH_SHORT).show()
        }

        // Статус реального тюнера
        if (!AppLauncher.hasFmTuner(requireContext())) {
            statusText.setText(R.string.radio_no_tuner)
        } else {
            statusText.setText(R.string.radio_tuner_intent)
            registerTunerReceiver()
        }
    }

    private fun registerTunerReceiver() {
        // Системные широковещательные события изменения FM-частоты (если прошивка их рассылает)
        try {
            tunerReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val khz = intent?.getIntExtra("frequency", -1) ?: -1
                    if (khz in 76000..108000) { freqKhz = khz / 10; renderFrequency() }
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
        // Переключение между реально сохранёнными пресетами, если они есть
        val presets = prefs.presets()
        if (presets.isNotEmpty()) {
            val idx = presets.indexOfFirst { it <= freqKhz }.let { if (it < 0) 0 else it }
            val next = ((idx + dir) % presets.size + presets.size) % presets.size
            setFrequency(presets[next])
        } else {
            setFrequency((freqKhz + dir * 100).coerceIn(870, 1080)) // шаг 1.0 МГц в пределах FM-диапазона
        }
    }

    /** Реальная функция поиска возможна только через API тюнера; без него — имитация запрещена. */
    private fun seekUp() {
        Toast.makeText(requireContext(), R.string.radio_no_tuner, Toast.LENGTH_LONG).show()
    }

    private fun setFrequency(khz: Int) {
        freqKhz = khz.coerceIn(870, 1080)
        prefs.setLastFreq(freqKhz)
        renderFrequency()
    }

    private fun renderFrequency() {
        freqText.text = String.format(java.util.Locale.getDefault(), "%.1f", freqKhz / 10.0)
        nameText.text = ""
        updateFavIcon()
    }

    private fun updateFavIcon() {
        try {
            requireView().findViewById<ImageButton>(R.id.btnFav).setImageResource(
                if (prefs.favorites().contains(freqKhz.toString())) R.drawable.ic_star else R.drawable.ic_star_outline)
        } catch (_: Exception) {}
    }

    private fun refreshStations() {
        val favs = prefs.presets()
        val rows = favs.map { RowItem(String.format(java.util.Locale.getDefault(), "%.1f", it / 10.0), "",
            checked = true, payload = it) }
        adapter.submit(rows)
        stationsEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        stationsEmpty.setText(R.string.radio_favorites)
    }
}
