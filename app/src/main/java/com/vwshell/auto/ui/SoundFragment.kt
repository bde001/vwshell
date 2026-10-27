package com.vwshell.auto.ui

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.vwshell.auto.R
import com.vwshell.auto.core.AppLauncher
import com.vwshell.auto.core.Prefs

/**
 * Звук. Реальные возможности стандартного Android API для стороннего приложения:
 *  — громкость потоков (AudioManager.adjustStreamVolume/setStreamVolume) — РАБОТАЕТ;
 *  — системный эквалайзер/аудиоэффекты — только через ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL
 *    (появляется, если на устройстве есть поддерживающий эффект-приложение);
 *  — баланс L/R и фейдер перед/зад — НЕ являются частью public API обычного приложения:
 *    ползунки физически отсутствуют, функция честно помечена «Недоступно на этом устройстве»
 *    с объяснением причины. Предпочтения сохраняются в Prefs для будущего OEM-провайдера.
 */
class SoundFragment : BaseSectionFragment() {

    override val titleRes = R.string.sound_title

    private lateinit var audio: AudioManager
    private lateinit var streamsInfo: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_sound, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        audio = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        streamsInfo = view.findViewById(R.id.soundStreamsInfo)

        bindStream(view.findViewById(R.id.sbMedia), AudioManager.STREAM_MUSIC, view.findViewById(R.id.valMedia))
        bindStream(view.findViewById(R.id.sbAlarm), AudioManager.STREAM_ALARM, view.findViewById(R.id.valAlarm))
        bindStream(view.findViewById(R.id.sbNotification), AudioManager.STREAM_NOTIFICATION, view.findViewById(R.id.valNotif))
        bindStream(view.findViewById(R.id.sbVoice), AudioManager.STREAM_VOICE_CALL, view.findViewById(R.id.valVoice))

        renderStreamsList()

        // Эквалайзер: реальный переход к системному аудио-панели (если прошивка её предоставляет).
        view.findViewById<View>(R.id.btnEqualizer).setOnClickListener {
            val ok = AppLauncher.openAudioEffectsPanel(requireContext())
            if (!ok) Toast.makeText(requireContext(), R.string.sound_eq_unavailable, Toast.LENGTH_LONG).show()
        }
        // Настройки звука Android.
        view.findViewById<View>(R.id.btnSystemSoundSettings).setOnClickListener {
            if (!AppLauncher.openSoundSettings(requireContext()))
                Toast.makeText(requireContext(), R.string.settings_screen_missing, Toast.LENGTH_SHORT).show()
        }

        // Баланс/фейдер: показываем сохранённые предпочтения, но честно сообщаем об отсутствии воздействия.
        val prefs = Prefs.get(requireContext())
        view.findViewById<TextView>(R.id.balLrValue).text = formatBalance(prefs.balanceLr)
        view.findViewById<TextView>(R.id.balFrValue).text = formatBalance(prefs.balanceFrontRear)
        view.findViewById<TextView>(R.id.soundBalNote).setText(R.string.sound_balance_unavailable)
    }

    private fun formatBalance(v: Int): String {
        val abs = Math.abs(v)
        return when {
            v == 0 -> "0"
            v < 0 -> "L$abs"
            else -> "R$abs"
        }
    }

    private fun bindStream(sb: SeekBar, stream: Int, valueText: TextView) {
        val max = try { audio.getStreamMaxVolume(stream) } catch (_: Exception) { 0 }
        val cur = try { audio.getStreamVolume(stream) } catch (_: Exception) { 0 }
        sb.max = max
        sb.progress = cur
        valueText.text = "$cur / $max"
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                // Реальное воздействие на громкость конкретного потока.
                try {
                    audio.setStreamVolume(stream, progress.coerceIn(0, max), 0)
                } catch (_: SecurityException) {
                    Toast.makeText(requireContext(), R.string.sound_stream_locked, Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
                valueText.text = "${sb.progress} / $max"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /** Список реально доступных потоков — без выдуманных пунктов. */
    private fun renderStreamsList() {
        val names = mutableListOf<String>()
        for ((id, label) in listOf(
            AudioManager.STREAM_MUSIC to getString(R.string.sound_stream_media),
            AudioManager.STREAM_VOICE_CALL to getString(R.string.sound_stream_call),
            AudioManager.STREAM_RING to getString(R.string.sound_stream_ring),
            AudioManager.STREAM_ALARM to getString(R.string.sound_stream_alarm),
            AudioManager.STREAM_NOTIFICATION to getString(R.string.sound_stream_notif)
        )) {
            try {
                audio.getStreamMaxVolume(id)
                names.add(label)
            } catch (_: Exception) { /* поток недоступен на этом оборудовании */ }
        }
        streamsInfo.text = getString(R.string.sound_streams_of, names.joinToString(", "))
    }
}
