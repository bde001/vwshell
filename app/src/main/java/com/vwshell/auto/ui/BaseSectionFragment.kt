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

/** Общий каркас раздела: заголовок, кнопка «назад», часы в шапке. */
abstract class BaseSectionFragment : Fragment() {

    protected abstract val titleRes: Int

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.sectionTitle).setText(titleRes)
        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            (activity as? MainActivity)?.showHome() ?: activity?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onResume() {
        super.onResume()
        updateClock()
        view?.postDelayed({ if (isResumed) updateClock() }, 20_000)
    }

    protected fun updateClock() {
        view?.findViewById<TextView>(R.id.headerClock)?.text = clockFormat.format(Date())
    }
}
