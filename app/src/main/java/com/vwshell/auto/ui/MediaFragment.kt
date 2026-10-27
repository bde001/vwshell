package com.vwshell.auto.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.vwshell.auto.MainActivity
import com.vwshell.auto.R
import com.vwshell.auto.core.AppLauncher
import com.vwshell.auto.core.MediaMonitor
import com.vwshell.auto.core.ShellState
import com.vwshell.auto.media.AudioItem
import com.vwshell.auto.media.LocalAudioRepository
import com.vwshell.auto.media.LocalPlayerEngine
import java.util.Locale

/**
 * Медиацентр VW Shell — собственный экран в стиле Discover Pro, два реальных источника:
 *
 * 1) ЛОКАЛЬНЫЕ ФАЙЛЫ: медиатека через MediaStore (READ_MEDIA_AUDIO на Android 13),
 *    воспроизведение движком LocalPlayerEngine (MediaPlayer + аудиофокус + публикация
 *    MediaSession). Показываются только реально найденные треки и реальные метаданные.
 *
 * 2) ВНЕШНИЕ ИСТОЧНИКИ (Bluetooth-телефон, USB-плеер): управление реальной активной
 *    медиасессией через MediaController. ВАЖНО: оболочка управляет воспроизведением НА
 *    ИСТОЧНИКЕ и отображает его метаданные; приём Bluetooth-A2DP-потока внутрь приложения
 *    требует системного A2DP Sink / OEM-поддержки и здесь НЕ эмулируется.
 *
 * Транспортные команды выполняются только если сессия объявила соответствующие ACTION_*.
 */
class MediaFragment : BaseSectionFragment(), ShellState.Listener, LocalPlayerEngine.Listener {

    override val titleRes = R.string.media_title

    private lateinit var localArt: ImageView
    private lateinit var localTrack: TextView
    private lateinit var localArtist: TextView
    private lateinit var localSeek: SeekBar
    private lateinit var localPos: TextView
    private lateinit var localDur: TextView
    private lateinit var btnLocalPlay: ImageButton
    private lateinit var volumeSeek: SeekBar
    private lateinit var localStatus: TextView
    private lateinit var grantAudioBtn: MaterialButton
    private lateinit var rescanBtn: MaterialButton

    private lateinit var extSourceIcon: ImageView
    private lateinit var extAlbumArt: ImageView
    private lateinit var extTrack: TextView
    private lateinit var extArtist: TextView
    private lateinit var extStatus: TextView
    private lateinit var btnExtPlay: ImageButton
    private lateinit var btnExtPrev: View
    private lateinit var btnExtNext: View
    private lateinit var grantAccessBtn: MaterialButton

    private var controller: MediaController? = null
    private var engine: LocalPlayerEngine? = null
    private var playlist: List<AudioItem> = emptyList()
    private var seeking = false
    private val trackAdapter = SimpleRowAdapter(onClick = { item ->
        val idx = item.payload as? Int ?: return@SimpleRowAdapter
        engine?.setPlaylist(playlist, idx)
    })

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_media, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        localArt = view.findViewById(R.id.localArt)
        localTrack = view.findViewById(R.id.localTrack)
        localArtist = view.findViewById(R.id.localArtist)
        localSeek = view.findViewById(R.id.localSeek)
        localPos = view.findViewById(R.id.localPos)
        localDur = view.findViewById(R.id.localDur)
        btnLocalPlay = view.findViewById(R.id.btnLocalPlay)
        volumeSeek = view.findViewById(R.id.volumeSeek)
        localStatus = view.findViewById(R.id.localStatus)
        grantAudioBtn = view.findViewById(R.id.btnGrantAudio)
        rescanBtn = view.findViewById(R.id.btnRescan)

        extSourceIcon = view.findViewById(R.id.extSourceIcon)
        extAlbumArt = view.findViewById(R.id.extAlbumArt)
        extTrack = view.findViewById(R.id.extTrack)
        extArtist = view.findViewById(R.id.extArtist)
        extStatus = view.findViewById(R.id.extStatus)
        btnExtPlay = view.findViewById(R.id.btnExtPlay)
        btnExtPrev = view.findViewById(R.id.btnExtPrev)
        btnExtNext = view.findViewById(R.id.btnExtNext)
        grantAccessBtn = view.findViewById(R.id.btnGrantMediaAccess)

        view.findViewById<RecyclerView>(R.id.localList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = trackAdapter
        }

        view.findViewById<View>(R.id.btnLocalPrev).setOnClickListener { engine?.previous() }
        view.findViewById<View>(R.id.btnLocalNext).setOnClickListener { engine?.next() }
        btnLocalPlay.setOnClickListener { engine?.togglePlayPause() }
        grantAudioBtn.setOnClickListener { (activity as? MainActivity)?.requestAudioPermission() }
        rescanBtn.setOnClickListener { loadLibrary() }

        localSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                sb?.let { engine?.seekTo(it.progress.toLong()) }
            }
        })

        // Системная громкость — поддерживаемый механизм AudioManager (поток MUSIC).
        val am = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        volumeSeek.max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeSeek.progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnExtPrev.setOnClickListener { extTransport(PlaybackState.ACTION_SKIP_TO_PREVIOUS) }
        btnExtNext.setOnClickListener { extTransport(PlaybackState.ACTION_SKIP_TO_NEXT) }
        btnExtPlay.setOnClickListener { extToggle() }
        grantAccessBtn.setOnClickListener { openNotificationListenerSettings() }
    }

    override fun onResume() {
        super.onResume()
        engine = LocalPlayerEngine.get(requireContext()).also { it.addListener(this) }
        ShellState.addListener(this, this)
        ShellState.refresh()
        loadLibrary()
    }

    override fun onPause() {
        ShellState.removeListener(this)
        engine?.removeListener(this)
        super.onPause()
    }

    // ==================== Локальная медиатека ====================

    /** Загружается ТОЛЬКО при наличии разрешения; иначе — честное состояние и кнопка запроса. */
    private fun loadLibrary() {
        if (!isAdded) return
        val act = activity as? MainActivity ?: return
        if (!act.hasAudioPermission()) {
            grantAudioBtn.visibility = View.VISIBLE
            rescanBtn.visibility = View.GONE
            localStatus.setText(R.string.media_no_permission_audio)
            playlist = emptyList()
            updateTrackButtons()
            return
        }
        grantAudioBtn.visibility = View.GONE
        rescanBtn.visibility = View.VISIBLE
        playlist = LocalAudioRepository.load(requireContext())
        if (playlist.isEmpty()) localStatus.setText(R.string.media_local_empty)
        updateTrackButtons()
    }

    /** Список — только реально найденные MediaStore композиции; кнопки активны при реальном плейлисте. */
    private fun updateTrackButtons() {
        if (!::trackAdapter.isInitialized) return
        trackAdapter.submit(playlist.mapIndexed { i, it ->
            RowItem(it.displayTitle, "${it.displayArtist} · ${formatTime(it.durationMs)}", payload = i)
        })
        val prev = view?.findViewById<View>(R.id.btnLocalPrev)
        val next = view?.findViewById<View>(R.id.btnLocalNext)
        val hasEngine = engine?.hasPlaylist == true
        prev?.alpha = if (hasEngine) 1f else 0.35f
        next?.alpha = if (hasEngine) 1f else 0.35f
        btnLocalPlay.alpha = if (hasEngine) 1f else 0.35f
    }

    fun onAudioPermissionResult(granted: Boolean) {
        if (!isAdded) return
        if (granted) loadLibrary()
        else Toast.makeText(requireContext(), R.string.media_no_permission_audio, Toast.LENGTH_LONG).show()
    }

    // ---- LocalPlayerEngine.Listener: реальные события движка ----

    override fun onPlayerEvent(state: Int, item: AudioItem?, positionMs: Long, durationMs: Long) {
        if (!isAdded || !::localTrack.isInitialized) return
        if (item == null) {
            localTrack.text = ""
            localArtist.text = ""
            localSeek.isEnabled = false
            localSeek.max = 0
            localSeek.progress = 0
            localPos.text = "0:00"; localDur.text = "0:00"
            btnLocalPlay.setImageResource(R.drawable.ic_play)
            updateTrackButtons()
            if (state == PlaybackState.STATE_ERROR) localStatus.setText(R.string.media_load_failed)
            else if (playlist.isEmpty()) localStatus.setText(R.string.media_local_empty)
            else localStatus.setText(R.string.media_local_stopped)
            return
        }
        localTrack.text = item.displayTitle
        localArtist.text = item.displayArtist
        localSeek.isEnabled = state != PlaybackState.STATE_ERROR
        if (!seeking) {
            localSeek.max = (durationMs / 1000).toInt().coerceAtLeast(1)
            localSeek.progress = (positionMs / 1000).toInt().coerceIn(0, localSeek.max)
        }
        localPos.text = formatTime(positionMs)
        localDur.text = formatTime(durationMs)
        when (state) {
            PlaybackState.STATE_PLAYING -> {
                btnLocalPlay.setImageResource(R.drawable.ic_pause)
                localStatus.setText(R.string.media_playing_local)
            }
            PlaybackState.STATE_PAUSED -> {
                btnLocalPlay.setImageResource(R.drawable.ic_play)
                localStatus.setText(R.string.media_local_paused)
            }
            PlaybackState.STATE_BUFFERING -> btnLocalPlay.setImageResource(R.drawable.ic_play)
            PlaybackState.STATE_ERROR -> localStatus.setText(R.string.media_load_failed)
            else -> {}
        }
        updateTrackButtons()
    }

    override fun onArtworkReady(item: AudioItem?, bitmap: Bitmap?) {
        if (!isAdded || !::localArt.isInitialized) return
        if (bitmap != null) {
            localArt.scaleType = ImageView.ScaleType.FIT_CENTER
            localArt.imageTintList = null
            localArt.setImageBitmap(bitmap)
        } else {
            // Реальной обложки у файла нет — нейтральная векторная заглушка, без фальшивых картинок.
            localArt.setImageResource(R.drawable.ic_album)
        }
    }

    // ==================== Внешняя медиасессия (ShellState) ====================

    override fun onMediaStateChanged(snap: ShellState.MediaSnapshot?) {
        if (!isAdded || !::extTrack.isInitialized) return
        val c = snap?.controller
        controller = c
        val hasAccess = MediaMonitor.isNotificationServiceEnabled(requireContext())
        if (c == null) {
            extSourceIcon.visibility = View.GONE
            extAlbumArt.visibility = View.GONE
            extTrack.text = ""
            extArtist.text = ""
            btnExtPlay.setImageResource(R.drawable.ic_play)
            btnExtPlay.alpha = 0.35f
            btnExtPrev.alpha = 0.35f
            btnExtNext.alpha = 0.35f
            grantAccessBtn.visibility = if (hasAccess) View.GONE else View.VISIBLE
            extStatus.setText(if (hasAccess) R.string.media_no_session else R.string.media_need_access)
            return
        }
        grantAccessBtn.visibility = View.GONE
        val pkgLabel = AppLauncher.appLabel(requireContext(), snap.sourcePackage) ?: snap.sourcePackage ?: ""
        extStatus.text = getString(R.string.media_source_of, pkgLabel)
        extSourceIcon.visibility = View.VISIBLE
        extSourceIcon.setImageResource(
            if (snap.sourcePackage?.contains("bluetooth", true) == true) R.drawable.ic_bluetooth
            else R.drawable.ic_usb)
        extTrack.text = snap.title ?: getString(R.string.media_track_unknown)
        extArtist.text = snap.artist ?: ""
        // Обложка: только реально переданная сессией битмап.
        extAlbumArt.visibility = View.VISIBLE
        val bmp = snap.artwork
        if (bmp != null) {
            extAlbumArt.imageTintList = null
            extAlbumArt.scaleType = ImageView.ScaleType.FIT_CENTER
            extAlbumArt.setImageBitmap(bmp)
        } else {
            extAlbumArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
            extAlbumArt.setImageResource(R.drawable.ic_album)
            extAlbumArt.imageTintList = android.content.res.ColorStateList.valueOf(
                resources.getColor(R.color.vw_text_disabled, null))
        }
        btnExtPlay.setImageResource(if (snap.playing) R.drawable.ic_pause else R.drawable.ic_play)
        // Кнопки активны только при реально объявленных действиях источника.
        btnExtPlay.alpha = if (snap.canPlay() || snap.canPause()) 1f else 0.35f
        btnExtPrev.alpha = if (snap.canSkipPrev()) 1f else 0.35f
        btnExtNext.alpha = if (snap.canSkipNext()) 1f else 0.35f
    }

    private fun extTransport(action: Long) {
        val c = controller ?: return toastNoSession()
        val allowed = try { c.playbackState?.actions ?: 0L } catch (_: IllegalStateException) { 0L }
        if (allowed and action == 0L) return toastUnsupported()
        when (action) {
            PlaybackState.ACTION_SKIP_TO_PREVIOUS -> c.transportControls.skipToPrevious()
            PlaybackState.ACTION_SKIP_TO_NEXT -> c.transportControls.skipToNext()
        }
    }

    private fun extToggle() {
        val c = controller ?: return toastNoSession()
        val ps = try { c.playbackState } catch (_: IllegalStateException) { null }
        when (MediaSessionLogic.playOrPause(ps)) {
            true -> c.transportControls.play()
            false -> c.transportControls.pause()
            null -> toastUnsupported()
        }
    }

    private fun toastNoSession() =
        Toast.makeText(requireContext(), R.string.media_no_session, Toast.LENGTH_SHORT).show()

    private fun toastUnsupported() =
        Toast.makeText(requireContext(), R.string.media_action_unsupported, Toast.LENGTH_SHORT).show()

    /** Notification Listener Access — специальный доступ; runtime-диалог неприменим. */
    private fun openNotificationListenerSettings() {
        try {
            startActivity(android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.media_settings_missing, Toast.LENGTH_LONG).show()
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val total = ms / 1000
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }
}
