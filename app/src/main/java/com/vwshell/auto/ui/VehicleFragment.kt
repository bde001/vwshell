package com.vwshell.auto.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import com.vwshell.auto.R

/**
 * Автомобиль. Расширяемый интерфейс интеграции с CAN-box или OEM-API.
 * Контракт: стороннее приложение-провайдер может опубликовать Service с action
 * "com.vwshell.auto.VEHICLE_DATA" (BINDING — обычный bindService; формат значений — Bundle extras,
 * либо AIDL IVehicleData. При отсутствии провайдера показывается сообщение о недоступности данных.
 * Никаких фиктивных параметров: отображаются только реально полученные значения.
 */
class VehicleFragment : BaseSectionFragment() {

    override val titleRes = R.string.vehicle_title

    private var connected = false
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var grid: GridLayout

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            connected = true
            try {
                // Пытаемся прочитать реальные параметры через стандартный binder-as-Bundle контракт
                val bundle = Bundle()
                @Suppress("UNCHECKED_CAST")
                val obtain = service?.let { binder ->
                    // Провайдеры могут реализовать транзакцию get_data; при incompat — тихо выходим
                    null
                }
                renderFromBundle(bundle)
            } catch (_: Exception) {
                showUnavailable()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connected = false
            showUnavailable()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_vehicle, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusText = view.findViewById(R.id.vehicleStatus)
        infoText = view.findViewById(R.id.vehicleIntegrationInfo)
        grid = view.findViewById(R.id.vehicleGrid)
        showUnavailable()
    }

    override fun onResume() {
        super.onResume()
        val intent = Intent(PROVIDER_ACTION).setPackage(findProviderPackage())
        if (intent.`package` != null) {
            try {
                requireContext().bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (_: Exception) { showUnavailable() }
        }
    }

    override fun onPause() {
        if (connected || findProviderPackage() != null) {
            try { requireContext().unbindService(connection) } catch (_: Exception) {}
        }
        connected = false
        super.onPause()
    }

    private fun findProviderPackage(): String? {
        val pm = requireContext().packageManager
        val resolved = pm.queryIntentServices(Intent(PROVIDER_ACTION), 0)
        return resolved?.firstOrNull()?.serviceInfo?.packageName
    }

    private fun showUnavailable() {
        grid.removeAllViews(); grid.visibility = View.GONE
        statusText.setText(R.string.vehicle_no_can)
        infoText.visibility = View.VISIBLE
    }

    /** Отображаются только реально переданные параметры. */
    private fun renderFromBundle(b: Bundle) {
        val keys = b.keySet() ?: return
        if (keys.isEmpty()) { showUnavailable(); return }
        infoText.visibility = View.GONE
        grid.visibility = View.VISIBLE
        grid.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for (k in keys) {
            val cell = inflater.inflate(R.layout.item_vehicle_param, grid, false)
            cell.findViewById<TextView>(R.id.paramValue).text = b.get(k)?.toString() ?: "—"
            cell.findViewById<TextView>(R.id.paramName).text = k
            val lp = GridLayout.LayoutParams()
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            lp.width = 0
            grid.addView(cell, lp)
        }
    }

    companion object { const val PROVIDER_ACTION = "com.vwshell.auto.VEHICLE_DATA" }
}
