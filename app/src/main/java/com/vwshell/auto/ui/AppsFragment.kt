package com.vwshell.auto.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vwshell.auto.R
import com.vwshell.auto.core.AppEntry
import com.vwshell.auto.core.AppLauncher
import com.vwshell.auto.core.Prefs

/**
 * App Drawer: реальные установленные запускаемые приложения (через PackageManager + <queries>).
 * Иконки и названия — настоящие. Долгое нажатие: избранное/скрытие. Поиск по названию.
 */
class AppsFragment : BaseSectionFragment() {

    override val titleRes = R.string.apps_title

    private lateinit var adapter: AppsAdapter
    private lateinit var search: EditText
    private lateinit var empty: TextView
    private var allApps: List<AppEntry> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_apps, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        search = view.findViewById(R.id.appsSearch)
        empty = view.findViewById(R.id.appsEmpty)
        val list = view.findViewById<RecyclerView>(R.id.appsGrid)
        list.layoutManager = GridLayoutManager(requireContext(), 6)
        adapter = AppsAdapter(onOpen = { entry ->
            if (!AppLauncher.launchPackage(requireContext(), entry.packageName))
                Toast.makeText(requireContext(), R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }, onLongPress = { entry -> showAppMenu(entry) })
        list.adapter = adapter

        loadApps()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = filter(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadApps() {
        val prefs = Prefs.get(requireContext())
        val hidden = prefs.hiddenApps()
        val favs = prefs.favoriteApps()
        allApps = AppLauncher.installedLaunchableApps(requireContext())
            .filter { !hidden.contains(it.packageName) && it.packageName != requireContext().packageName }
        // Избранные — в начале, в сохранённом порядке; остальные — по алфавиту.
        allApps = favs.mapNotNull { pkg -> allApps.firstOrNull { it.packageName == pkg } } +
            allApps.filter { !favs.contains(it.packageName) }
        adapter.submit(allApps)
        empty.visibility = if (allApps.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun filter(q: String) {
        val rows = if (q.isBlank()) allApps
                   else allApps.filter { it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
        adapter.submit(rows)
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(if (allApps.isEmpty()) R.string.apps_empty else R.string.apps_no_results)
    }

    private fun showAppMenu(entry: AppEntry) {
        val prefs = Prefs.get(requireContext())
        val isFav = prefs.favoriteApps().contains(entry.packageName)
        val options = arrayOf(
            getString(if (isFav) R.string.apps_unfav else R.string.apps_fav),
            getString(R.string.apps_hide),
            getString(R.string.apps_info)
        )
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(entry.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val favs = prefs.favoriteApps().toMutableList()
                        if (isFav) favs.remove(entry.packageName) else favs.add(entry.packageName)
                        prefs.setFavoriteApps(favs); loadApps()
                    }
                    1 -> {
                        val h = prefs.hiddenApps(); h.add(entry.packageName)
                        prefs.setHiddenApps(h); loadApps()
                    }
                    2 -> try {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:" + entry.packageName)))
                    } catch (_: Exception) {
                        Toast.makeText(requireContext(), R.string.settings_screen_missing, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    /** Сброс скрытых приложений — кнопка в пустом состоянии или из настроек. */
    companion object
}

private class AppsAdapter(
    private val onOpen: (AppEntry) -> Unit,
    private val onLongPress: (AppEntry) -> Unit
) : RecyclerView.Adapter<AppsAdapter.VH>() {

    private var items: List<AppEntry> = emptyList()
    fun submit(newItems: List<AppEntry>) { items = newItems; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.appIcon)
        val label: TextView = v.findViewById(R.id.appLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.row_app_grid, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.label.text = item.label
        val pm = holder.itemView.context.packageManager
        val d: Drawable? = try {
            if (item.activity != null)
                pm.getActivityIcon(ComponentNameHolder(item.packageName, item.activity))
            else pm.getApplicationIcon(item.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            try { pm.getApplicationIcon(item.packageName) } catch (_: Exception) { null }
        }
        holder.icon.setImageDrawable(d ?: pm.defaultActivityIcon)
        holder.itemView.setOnClickListener { onOpen(item) }
        holder.itemView.setOnLongClickListener { onLongPress(item); true }
    }

    override fun getItemCount(): Int = items.size

    private fun ComponentNameHolder(pkg: String, act: String) =
        android.content.ComponentName(pkg, act)
}
