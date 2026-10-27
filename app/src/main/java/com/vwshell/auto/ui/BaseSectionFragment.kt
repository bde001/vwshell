package com.vwshell.auto.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.vwshell.auto.MainActivity
import com.vwshell.auto.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Интерфейс для обновления часов раздела системным событием TIME_TICK (без опроса). */
interface BaseSectionHeaderClock {
    fun refreshClock()
}

/** Общий каркас раздела: заголовок, кнопка «назад», часы в шапке. */
abstract class BaseSectionFragment : Fragment(), MainActivity.BaseSectionMarker,
    MainActivity.BaseSectionHeaderClockHolder, BaseSectionHeaderClock {

    protected abstract val titleRes: Int

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.sectionTitle).setText(titleRes)
        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            (activity as? MainActivity)?.showHome() ?: activity?.onBackPressedDispatcher?.onBackPressed()
        }
        // Раздел мог быть восстановлен после поворота/пробуждения — синхронизируем верхнюю панель и состояние HOME.
        (activity as? MainActivity)?.onSectionRestored(javaClass.name, titleRes)
    }

    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onResume() {
        super.onResume()
        updateClock()
    }

    /** Обновление часов по системному событию TIME_TICK (см. MainActivity) — без самодельного поллинга. */
    fun refreshClock() = updateClock()

    protected fun updateClock() {
        view?.findViewById<TextView>(R.id.headerClock)?.text = clockFormat.format(Date())
    }
}
