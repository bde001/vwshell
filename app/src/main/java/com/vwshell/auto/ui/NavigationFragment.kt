package com.vwshell.auto.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vwshell.auto.R
import com.vwshell.auto.core.AppLauncher
import com.vwshell.auto.core.Prefs

/**
 * Навигация: запуск реально установленных навигационных приложений
 * (Google Maps, Яндекс Навигатор и любые обработчики geo:-интентов).
 * Фальшивой карты нет — оболочка только запускает настоящий навигатор.
 */
class NavigationFragment : BaseSectionFragment() {

    override val titleRes = R.string.nav_title

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_navigation, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = Prefs.get(requireContext())
        val address = view.findViewById<EditText>(R.id.navAddress)
        val empty = view.findViewById<View>(R.id.navEmpty)
        val list = view.findViewById<RecyclerView>(R.id.navAppList)
        list.layoutManager = LinearLayoutManager(requireContext())

        val apps = AppLauncher.navigationApps(requireContext())
        val rows = apps.mapTo(mutableListOf()) { RowItem(it.label, it.packageName,
            checked = prefs.navAppPackage == it.packageName, payload = it.packageName) }
        lateinit var adapter: SimpleRowAdapter
        adapter = SimpleRowAdapter(rows, onClick = { item ->
            prefs.navAppPackage = item.payload as? String
            for (i in rows.indices) rows[i] = rows[i].copy(checked = rows[i].payload == item.payload)
            adapter.submit(rows)
        })
        list.adapter = adapter
        empty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE

        view.findViewById<View>(R.id.btnNavLaunch).setOnClickListener {
            val q = address.text?.toString()?.trim().orEmpty()
            val pkg = prefs.navAppPackage
            val ok = if (q.isNotEmpty()) AppLauncher.openGeo(requireContext(), q, pkg)
                     else AppLauncher.launchPackage(requireContext(), pkg) ||
                          AppLauncher.openGeo(requireContext(), "", null)
            if (!ok) android.widget.Toast.makeText(requireContext(), R.string.not_installed,
                android.widget.Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.btnNavRoute).setOnClickListener {
            // Открыть выбор навигатора системным chooser через geo:
            AppLauncher.openGeo(requireContext(), address.text?.toString()?.trim().orEmpty(), null)
        }
    }
}
