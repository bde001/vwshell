package com.vwshell.auto.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vwshell.auto.R
import com.vwshell.auto.core.AppLauncher
import com.vwshell.auto.core.Prefs

/**
 * Телефон. Реальные действия: запуск штатного Bluetooth-настроек, Dialer, контактов, журнала.
 * Контакты/журнал читаются только при выданном пользователем разрешении; запрос разрешения
 * происходит лишь при явном нажатии соответствующей кнопки (никакого скрытого сбора данных).
 */
class PhoneFragment : BaseSectionFragment() {

    override val titleRes = R.string.phone_title

    private lateinit var adapter: SimpleRowAdapter
    private lateinit var emptyText: TextView
    private lateinit var note: TextView
    private enum class Mode { NONE, CONTACTS, CALLS }
    private var mode = Mode.NONE

    private val requestPerms = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // После ответа пользователя повторно обновляем состояние интерфейса.
        when (mode) {
            Mode.CONTACTS -> if (result[Manifest.permission.READ_CONTACTS] == true) loadContacts()
                else showDenied()
            Mode.CALLS -> if (result[Manifest.permission.READ_CALL_LOG] == true) loadCalls()
                else showDenied()
            else -> {}
        }
    }

    /** Runtime-запрос BLUETOOTH_CONNECT только при явном использовании Bluetooth-функций (Android 12+). */
    private val requestBtPerm = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        view?.let { updateBtStatus(it) }
        if (!granted) {
            Toast.makeText(requireContext(), R.string.phone_bt_perm_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_phone, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        note = view.findViewById(R.id.phoneNote)
        emptyText = view.findViewById(R.id.phoneEmpty)
        val list = view.findViewById<RecyclerView>(R.id.phoneList)
        list.layoutManager = LinearLayoutManager(requireContext())
        adapter = SimpleRowAdapter(onClick = { item ->
            val phone = item.payload as? String
            if (phone != null) {
                AppLauncher.launchIntentSafely(requireContext(),
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone)))
            }
        })
        list.adapter = adapter

        view.findViewById<View>(R.id.btnBtSettings).setOnClickListener {
            ensureBluetoothPermissionThen {
                if (!AppLauncher.openBluetoothSettings(requireContext()))
                    Toast.makeText(requireContext(), R.string.phone_no_bt, Toast.LENGTH_SHORT).show()
            }
        }
        view.findViewById<View>(R.id.btnDialpad).setOnClickListener {
            if (!AppLauncher.launchDialer(requireContext(), Prefs.get(requireContext()).phoneAppPackage))
                Toast.makeText(requireContext(), R.string.not_installed, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.btnCallLog).setOnClickListener {
            mode = Mode.CALLS
            if (granted(Manifest.permission.READ_CALL_LOG)) loadCalls()
            else if (shouldShowRationale(Manifest.permission.READ_CALL_LOG)) requestPerms.launch(arrayOf(Manifest.permission.READ_CALL_LOG))
            else openSystemDialerInstead(R.string.phone_calllog_limited)
        }
        view.findViewById<View>(R.id.btnContacts).setOnClickListener {
            mode = Mode.CONTACTS
            if (granted(Manifest.permission.READ_CONTACTS)) loadContacts()
            else if (shouldShowRationale(Manifest.permission.READ_CONTACTS)) requestPerms.launch(arrayOf(Manifest.permission.READ_CONTACTS))
            else showDenied()
        }

        updateBtStatus(view)
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED

    /**
     * false — пользователь отказал с «больше не спрашивать»: повторный запрос системно игнорируется,
     * вместо него показываем понятное объяснение и предлагаем штатное приложение.
     */
    private fun shouldShowRationale(permission: String): Boolean =
        !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
            requireActivity(), permission) ||
        !requestedOnce.contains(permission).also { requestedOnce.add(permission) }

    private val requestedOnce = HashSet<String>()

    private fun ensureBluetoothPermissionThen(after: () -> Unit) {
        if (AppLauncher.hasBluetoothPermission(requireContext())) { after(); return }
        if (AppLauncher.needsBluetoothPermission()) {
            requestBtPerm.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else after()
    }

    /** Журнал вызовов может быть недоступен без роли Dialer — открываем штатное приложение телефона. */
    private fun openSystemDialerInstead(noteRes: Int) {
        emptyText.visibility = View.VISIBLE
        emptyText.setText(noteRes)
        adapter.submit(emptyList())
        AppLauncher.launchDialer(requireContext(), Prefs.get(requireContext()).phoneAppPackage)
    }

    private fun showDenied() {
        emptyText.visibility = View.VISIBLE
        emptyText.setText(R.string.phone_denied_note)
        adapter.submit(emptyList())
    }

    private fun updateBtStatus(view: View) {
        val bt = AppLauncher.bluetoothState(requireContext())
        val tv = view.findViewById<TextView>(R.id.btStatus)
        when (bt) {
            null -> { tv.visibility = View.GONE } // адаптера нет / разрешение не выдано — скрываем, не выдумываем
            true -> { tv.visibility = View.VISIBLE; tv.setText(R.string.phone_bt_on) }
            false -> { tv.visibility = View.VISIBLE; tv.setText(R.string.phone_bt_off) }
        }
    }

    private fun loadContacts() {
        val rows = ArrayList<RowItem>()
        try {
            val c: Cursor? = requireContext().contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC LIMIT 200")
            c?.use {
                while (it.moveToNext()) {
                    rows.add(RowItem(it.getString(0) ?: "", it.getString(1) ?: "", payload = it.getString(1)))
                }
            }
        } catch (_: Exception) { /* провайдер недоступен */ }
        renderRows(rows, R.string.phone_empty)
    }

    private fun loadCalls() {
        val rows = ArrayList<RowItem>()
        try {
            val c: Cursor? = requireContext().contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE),
                null, null, CallLog.Calls.DATE + " DESC LIMIT 100")
            c?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: it.getString(1) ?: ""
                    val type = when (it.getInt(3)) {
                        CallLog.Calls.OUTGOING_TYPE -> "↗"; CallLog.Calls.MISSED_TYPE -> "✗"
                        else -> "↙"
                    }
                    rows.add(RowItem(name, "$type ${it.getString(1) ?: ""}", payload = it.getString(1)))
                }
            }
        } catch (_: Exception) {}
        renderRows(rows, R.string.phone_empty)
    }

    private fun renderRows(rows: List<RowItem>, emptyRes: Int) {
        adapter.submit(rows)
        emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        if (rows.isEmpty()) emptyText.setText(emptyRes)
        note.visibility = View.GONE
    }
}
