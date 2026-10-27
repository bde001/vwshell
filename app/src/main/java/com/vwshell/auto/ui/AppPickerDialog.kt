package com.vwshell.auto.ui

import android.content.Context
import android.graphics.Color
import androidx.appcompat.app.AlertDialog
import com.vwshell.auto.R
import com.vwshell.auto.core.AppEntry

/**
 * Единый выбор приложения в стиле VW Shell: чёрно-графитовый список, белая типографика,
 * тонкие тёмные разделители. Никаких Material You / синих акцентов.
 */
object AppPickerDialog {

    fun show(
        context: Context,
        titleRes: Int,
        apps: List<AppEntry>,
        onPick: (AppEntry) -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        if (apps.isEmpty()) return
        val labels = apps.map { it.label }.toTypedArray()
        val dialog = AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setAdapter(
                android.widget.ArrayAdapter(context, R.layout.row_picker_item, R.id.pickerText, labels)
            ) { d, which ->
                d.dismiss()
                onPick(apps[which])
            }
            .setOnCancelListener { onCancel?.invoke() }
            .create()
        dialog.window?.setBackgroundDrawableResource(R.color.vw_tile_bg)
        dialog.show()
        dialog.listView.divider = android.graphics.drawable.ColorDrawable(Color.parseColor("#FF2A2D30"))
    }
}
