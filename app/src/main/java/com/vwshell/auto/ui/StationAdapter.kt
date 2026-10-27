package com.vwshell.auto.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.vwshell.auto.R

/**
 * Список реально сохранённых пользователем FM-частот.
 * Выбранная станция отмечается тонкой красной линией (стиль Discover Pro).
 */
class StationAdapter(
    private val onClick: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<StationAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val freq: android.widget.TextView = v.findViewById(R.id.stationFreq)
        val marker: View = v.findViewById(R.id.stationMarker)
    }

    private var items: List<Int> = emptyList()
    private var selected: Int = -1

    fun submit(freqsTenths: List<Int>, selectedTenths: Int) {
        items = freqsTenths
        selected = selectedTenths
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.row_radio_station, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = items[position]
        holder.freq.text = com.vwshell.auto.core.FmBand.format(f)
        holder.marker.visibility = if (f == selected) View.VISIBLE else View.INVISIBLE
        holder.itemView.setOnClickListener { onClick?.invoke(f) }
    }

    override fun getItemCount(): Int = items.size
}
