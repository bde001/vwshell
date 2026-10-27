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
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.vwshell.auto.MainActivity
import com.vwshell.auto.R
import com.vwshell.auto.core.AppLauncher
import com.vwshell.auto.core.MediaMonitor
import com.vwshell.auto.core.Prefs
import com.vwshell.auto.core.PlayerSource
import com.vwshell.auto.core.ShellState
import com.vwshell.auto.media.AudioItem
import com.vwshell.auto.media.LocalAudioRepository
import com.vwshell.auto.media.LocalPlayerEngine
import java.util.Locale

/**
 * Единый экран аудиоплеера VW Shell в стиле Discover Pro.
 *
 * Компоновка: слева — текущий трек (обложка, название, исполнитель, транспорт, прогресс,
 * громкость); справа — прокручиваемый плейлист с выделением текущего трека.
 *
 * ОДИН интерфейс для двух реальных источников; выбор источника хранится в общем состоянии
 * PlayerSource (его же использует Bluetooth-плитка главного экрана — рассинхрон невозможен):
 *
 * 1) LOCAL — медиатека MediaStore (READ_MEDIA_AUDIO на Android 13), движок LocalPlayerEngine;
 * 2) EXTERNAL — управление реальной активной внешней медиасессией Android через MediaController
 *    (BT-телефон, USB-плеер). Приём Bluetooth-A2DP-потока «внутрь» приложения НЕ поддерживается
 *    и не эмулируется — оболочка только отображает метаданные и шлёт транспортные команды
 *    реальному источнику, если он объявил соответствующие ACTION_*.
 */
class MediaFragment : BaseSectionFragment(), ShellState.Listener, LocalPlayerEngine.Listener,
    PlayerSource.Listener {

    override val titleRes = R.string.media_title

    // Левая колонка «Сейчас играет» (общая для обоих источников)
    private lateinit var npArt: ImageView
    private lateinit var npTrack: TextView
    private lateinit var npArtist: TextView
    private lateinit var npSeek: SeekBar
    private lateinit var npPos: TextView
    private lateinit var npDur: TextView
    private lateinit var npPosCenter: TextView
    private lateinit var npDurCenter: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnPrev: View
    private lateinit var btnNext: View
    private lateinit var volumeSeek: SeekBar          // скрыт (горизонтальная громкость убрана)
    private lateinit var volumeSeekVertical: SeekBar  // вертикальная громкость слева
    private lateinit var volumeColumn: View
    private lateinit var playerStatus: View           // нижняя текстовая строка статуса убрана

    // Панель источника
    private lateinit var sourceIcon: ImageView
    private lateinit var sourceLabel: TextView
    private lateinit var btnSwitchSource: ImageButton

    // Правая колонка: плейлист
    private lateinit var trackList: RecyclerView
    private lateinit var playlistEmpty: TextView

    // Служебные кнопки (появляются только при реальной необходимости)
    private lateinit var grantAudioBtn: MaterialButton
    private lateinit var rescanBtn: MaterialButton
    private lateinit var grantAccessBtn: MaterialButton

    private var controller: MediaController? = null
    private var engine: LocalPlayerEngine? = null
    private var playlist: List<AudioItem> = emptyList()
    private var seeking = false
    private var lastSnap: ShellState.MediaSnapshot? = null

    private val trackAdapter = SimpleRowAdapter(onClick = { item ->
        val idx = item.payload as? Int ?: return@SimpleRowAdapter
        PlayerSource.setKind(PlayerSource.Kind.LOCAL)
        Prefs.get(requireContext()).mediaSourceKind = PlayerSource.Kind.LOCAL.name
        engine?.setPlaylist(playlist, idx)
    })

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_media, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        npArt = view.findViewById(R.id.npArt)
        npTrack = view.findViewById(R.id.npTrack)
        npArtist = view.findViewById(R.id.npArtist)
        npSeek = view.findViewById(R.id.npSeek)
        npPos = view.findViewById(R.id.npPos)
        npDur = view.findViewById(R.id.npDur)
        npPosCenter = view.findViewById(R.id.npPosCenter)
        npDurCenter = view.findViewById(R.id.npDurCenter)
        btnPlay = view.findViewById(R.id.btnPlay)
        btnPrev = view.findViewById(R.id.btnPrev)
        btnNext = view.findViewById(R.id.btnNext)
        volumeSeek = view.findViewById(R.id.volumeSeek)
        volumeSeekVertical = view.findViewById(R.id.volumeSeekVertical)
        volumeColumn = view.findViewById(R.id.volumeColumn)
        playerStatus = view.findViewById(R.id.playerStatus)

        sourceIcon = view.findViewById(R.id.sourceIcon)
        sourceLabel = view.findViewById(R.id.sourceLabel)
        btnSwitchSource = view.findViewById(R.id.btnSwitchSource)

        trackList = view.findViewById(R.id.trackList)
        playlistEmpty = view.findViewById(R.id.playlistEmpty)
        grantAudioBtn = view.findViewById(R.id.btnGrantAudio)
        rescanBtn = view.findViewById(R.id.btnRescan)
        grantAccessBtn = view.findViewById(R.id.btnGrantMediaAccess)

        trackList.layoutManager = LinearLayoutManager(requireContext())
        trackList.adapter = trackAdapter
        listBound = true

        btnPrev.setOnClickListener { onPrev() }
        btnNext.setOnClickListener { onNext() }
        btnPlay.setOnClickListener { onPlayPause() }

        // Переключатель источника: то же общее состояние PlayerSource, что и у Bluetooth-плитки.
        btnSwitchSource.setOnClickListener {
            if (!PlayerSource.toggle(requireContext())) {
                Toast.makeText(requireContext(), R.string.media_no_external_session,
                    Toast.LENGTH_LONG).show()
            }
        }

        npSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                val pos = sb?.progress?.toLong()?.times(1000) ?: return
                val ext = PlayerSource.externalController(lastSnap)
                if (ext != null) {
                    val allowed = try { ext.playbackState?.actions ?: 0L }
                    catch (_: IllegalStateException) { 0L }
                    if (allowed and PlaybackState.ACTION_SEEK_TO == 0L) return toastUnsupported()
                    try { ext.transportControls.seekTo(pos) } catch (_: Exception) { toastNoSession() }
                } else {
                    engine?.seekTo(pos)
                }
            }
        })

        // Системная громкость — поддерживаемый механизм AudioManager (поток MUSIC).
        // Вертикальный регулятор слева; горизонтальный скрыт. Синхронизируем оба.
        val am = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        volumeSeek.max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeSeek.progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeSeekVertical.max = volumeSeek.max
        volumeSeekVertical.progress = volumeSeek.progress
        val volListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
                if (sb !== volumeSeek) { volumeSeek.progress = p } else { volumeSeekVertical.progress = p }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        volumeSeek.setOnSeekBarChangeListener(volListener)
        volumeSeekVertical.setOnSeekBarChangeListener(volListener)

        view.findViewById<ImageButton>(R.id.btnRescanIcon).setOnClickListener { loadLibrary() }

        grantAudioBtn.setOnClickListener { (activity as? MainActivity)?.requestAudioPermission() }
        rescanBtn.setOnClickListener { loadLibrary() }
        grantAccessBtn.setOnClickListener { openNotificationListenerSettings() }
    }

    override fun onResume() {
        super.onResume()
        engine = LocalPlayerEngine.get(requireContext()).also { it.addListener(this) }
        ShellState.addListener(this, this)
        PlayerSource.addListener(this)
        PlayerSource.restoreFromPrefs(requireContext())
        ShellState.refresh()
        loadLibrary()
        render()
    }

    override fun onPause() {
        PlayerSource.removeListener(this)
        ShellState.removeListener(this)
        engine?.removeListener(this)
        super.onPause()
    }

    override fun onDestroyView() {
        // Адаптер привязан к уничтожаемой RecyclerView — отвязываем до пересоздания View.
        listBound = false
        trackList.adapter = null
        if (::volumeSeek.isInitialized) volumeSeek.setOnSeekBarChangeListener(null)
        if (::volumeSeekVertical.isInitialized) volumeSeekVertical.setOnSeekBarChangeListener(null)
        super.onDestroyView()
    }

    // ==================== Рендер единого экрана по общему состоянию ====================

    /** Полная перерисовка «Сейчас играет» в зависимости от выбранного PlayerSource источника. */
    private fun render() {
        if (!listBound || !::npTrack.isInitialized) return
        when (PlayerSource.kind) {
            PlayerSource.Kind.LOCAL -> renderLocal()
            PlayerSource.Kind.EXTERNAL -> renderExternal()
        }
        updateSourceBar()
        updatePlaylistPanel()
    }

    private fun updateSourceBar() {
        val external = PlayerSource.kind == PlayerSource.Kind.EXTERNAL
        sourceLabel.setText(if (external) R.string.tile_bluetooth_source else R.string.media_local_title)
        sourceIcon.setImageResource(if (external) R.drawable.ic_bluetooth else R.drawable.ic_media)
        val tint = if (external) R.color.vw_accent else R.color.vw_text_secondary
        sourceIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), tint)
        // Красная подсветка кнопки переключения при активном внешнем источнике.
        btnSwitchSource.imageTintList = ContextCompat.getColorStateList(
            requireContext(), if (external) R.color.vw_accent else R.color.vw_icon_tint)
        // Вертикальная громкость — регулятор локального плеера; при внешнем источнике неактивна.
        volumeColumn.isEnabled = !external
        volumeSeekVertical.isEnabled = !external
        volumeSeekVertical.alpha = if (external) 0.35f else 1f
    }

    private fun renderLocal() {
        npSeek.isEnabled = engine?.hasPlaylist == true
        npSeek.max = 0
        updatePlaylistHighlightOnly()
    }

    private fun renderExternal() {
        applyExternalSnapshot(lastSnap)
    }

    /** Плейлист относится к локальной медиатеке: при внешнем источнике — честное сообщение. */
    private fun updatePlaylistPanel() {
        if (PlayerSource.kind == PlayerSource.Kind.EXTERNAL) {
            playlistEmpty.visibility = View.VISIBLE
            playlistEmpty.setText(R.string.media_playlist_external_hint)
            return
        }
        if (!isLocalGranted()) {
            playlistEmpty.visibility = View.VISIBLE
            playlistEmpty.setText(R.string.media_no_permission_audio)
            return
        }
        playlistEmpty.visibility = if (playlist.isEmpty()) View.VISIBLE else View.GONE
        if (playlist.isEmpty()) playlistEmpty.setText(R.string.media_local_empty)
    }

    private fun isLocalGranted(): Boolean = (activity as? MainActivity)?.hasAudioPermission() == true

    // ==================== Транспорт: маршрутизация по выбранному источнику ====================

    private fun onPlayPause() {
        val snap = lastSnap
        if (PlayerSource.allowsExternal(snap)) {
            val c = snap!!.controller!!
            val ps = try { c.playbackState } catch (_: IllegalStateException) { null }
            when (MediaSessionLogic.playOrPause(ps)) {
                true -> c.transportControls.play()
                false -> c.transportControls.pause()
                null -> toastUnsupported()
            }
            return
        }
        if (PlayerSource.kind == PlayerSource.Kind.EXTERNAL) {
            // Источник выбран, но сессия пропала (источник отключился) — понятное сообщение.
            toastNoSession(); return
        }
        val e = engine
        if (e == null || !e.hasPlaylist) { toastNoLocalPlaylist(); return }
        e.togglePlayPause()
    }

    private fun onNext() {
        val snap = lastSnap
        if (PlayerSource.allowsExternal(snap)) {
            if (!PlayerSource.externalCanSkipNext(snap)) return toastUnsupported()
            snap!!.controller!!.transportControls.skipToNext()
            return
        }
        if (PlayerSource.kind == PlayerSource.Kind.EXTERNAL) { toastNoSession(); return }
        val e = engine ?: return
        if (!e.hasPlaylist) { toastNoLocalPlaylist(); return }
        e.next()
    }

    private fun onPrev() {
        val snap = lastSnap
        if (PlayerSource.allowsExternal(snap)) {
            if (!PlayerSource.externalCanSkipPrev(snap)) return toastUnsupported()
            snap!!.controller!!.transportControls.skipToPrevious()
            return
        }
        if (PlayerSource.kind == PlayerSource.Kind.EXTERNAL) { toastNoSession(); return }
        val e = engine ?: return
        if (!e.hasPlaylist) { toastNoLocalPlaylist(); return }
        e.previous()
    }

    // ==================== Локальная медиатека ====================

    /** Загружается ТОЛЬКО при наличии разрешения; иначе — честное состояние и кнопка запроса. */
    private fun loadLibrary() {
        if (!isAdded) return
        val act = activity as? MainActivity ?: return
        if (!act.hasAudioPermission()) {
            grantAudioBtn.visibility = View.VISIBLE
            rescanBtn.visibility = View.GONE
            playlist = emptyList()
            trackAdapter.submit(emptyList())
            return
        }
        grantAudioBtn.visibility = View.GONE
        rescanBtn.visibility = View.VISIBLE
        playlist = LocalAudioRepository.load(requireContext())
        trackAdapter.submit(playlist.mapIndexed { i, it ->
            RowItem(it.displayTitle, "${it.displayArtist} · ${formatTime(it.durationMs)}", payload = i)
        })
        updatePlaylistHighlightOnly()
    }

    private var listBound = false

    /** Выделение текущего трека жирным белым + маркер; остальные — нейтральные. */
    private fun updatePlaylistHighlightOnly() {
        if (!listBound) return
        val curIdx = engine?.currentItem?.let { ci -> playlist.indexOfFirst { it.id == ci.id } } ?: -1
        trackAdapter.submit(playlist.mapIndexed { i, it ->
            RowItem(
                it.displayTitle,
                "${it.displayArtist} · ${formatTime(it.durationMs)}",
                checked = PlayerSource.kind == PlayerSource.Kind.LOCAL && i == curIdx,
                payload = i
            )
        })
    }

    fun onAudioPermissionResult(granted: Boolean) {
        if (!isAdded) return
        if (granted) loadLibrary()
        else Toast.makeText(requireContext(), R.string.media_no_permission_audio, Toast.LENGTH_LONG).show()
        render()
    }

    // ---- LocalPlayerEngine.Listener: реальные события движка (только когда источник LOCAL) ----

    override fun onPlayerEvent(state: Int, item: AudioItem?, positionMs: Long, durationMs: Long) {
        if (!isAdded || !::npTrack.isInitialized) return
        if (PlayerSource.kind == PlayerSource.Kind.LOCAL) {
            if (item == null) {
                npTrack.text = ""
                npArtist.text = ""
                npSeek.isEnabled = false
                npSeek.max = 0
                npSeek.progress = 0
                setTimes("0:00", "0:00")
                btnPlay.setImageResource(R.drawable.ic_play)
                setTransportAlpha(localActive = false)
                // Нижняя строка статуса убрана с экрана: ошибки видны по неактивным кнопкам/плейлисту.
            } else {
                npTrack.text = item.displayTitle
                npArtist.text = item.displayArtist
                npSeek.isEnabled = state != PlaybackState.STATE_ERROR
                if (!seeking) {
                    npSeek.max = (durationMs / 1000).toInt().coerceAtLeast(1)
                    npSeek.progress = (positionMs / 1000).toInt().coerceIn(0, npSeek.max)
                }
                setTimes(formatTime(positionMs), formatTime(durationMs))
                when (state) {
                    PlaybackState.STATE_PLAYING -> btnPlay.setImageResource(R.drawable.ic_pause)
                    PlaybackState.STATE_PAUSED -> btnPlay.setImageResource(R.drawable.ic_play)
                    PlaybackState.STATE_BUFFERING -> btnPlay.setImageResource(R.drawable.ic_play)
                    else -> {}
                }
                setTransportAlpha(localActive = true)
            }
            updatePlaylistHighlightOnly()
        }
        if (PlayerSource.kind == PlayerSource.Kind.LOCAL && item != null && item !== engine?.currentItem) {
            // событие не о текущем треке — ничего не делаем (защита от гонок движка)
        }
    }

    override fun onArtworkReady(item: AudioItem?, bitmap: Bitmap?) {
        if (!isAdded || !::npArt.isInitialized) return
        if (PlayerSource.kind != PlayerSource.Kind.LOCAL) return
        if (bitmap != null) {
            npArt.scaleType = ImageView.ScaleType.FIT_CENTER
            npArt.imageTintList = null
            npArt.setImageBitmap(bitmap)
        } else {
            // Реальной обложки у файла нет — нейтральная векторная заглушка, без фальшивых картинок.
            npArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
            npArt.setImageResource(R.drawable.ic_album)
        }
    }

    private fun setTransportAlpha(localActive: Boolean) {
        val a = if (localActive) 1f else 0.35f
        btnPlay.alpha = a; btnPrev.alpha = a; btnNext.alpha = a
    }

    // ==================== Внешняя медиасессия (ShellState) ====================

    override fun onMediaStateChanged(snap: ShellState.MediaSnapshot?) {
        if (!isAdded || !::npTrack.isInitialized) return
        lastSnap = snap
        controller = snap?.controller
        val hasAccess = MediaMonitor.isNotificationServiceEnabled(requireContext())
        grantAccessBtn.visibility =
            if (snap?.controller != null || hasAccess) View.GONE else View.VISIBLE

        // Если внешний источник выбран, а сессия пропала (источник отключился) —
        // честно возвращаем UI в состояние «нет источника», не молча переключая выбор.
        if (PlayerSource.kind == PlayerSource.Kind.EXTERNAL) {
            applyExternalSnapshot(snap)
            updateSourceBar()
            updatePlaylistPanel()
            return
        }
        // Локальный источник: обновляем только панель источника/плейлиста, «Сейчас играет» — у движка.
        updateSourceBar()
        updatePlaylistPanel()
    }

    private fun applyExternalSnapshot(snap: ShellState.MediaSnapshot?) {
        val c = snap?.controller
        if (c == null) {
            npTrack.text = ""
            npArtist.text = ""
            npSeek.isEnabled = false
            npSeek.max = 0
            setTimes("0:00", "0:00")
            npArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
            npArt.setImageResource(R.drawable.ic_album)
            btnPlay.setImageResource(R.drawable.ic_play)
            btnPlay.alpha = 0.35f; btnPrev.alpha = 0.35f; btnNext.alpha = 0.35f
            sourceLabel.setText(R.string.media_no_session)
            return
        }
        val pkgLabel = AppLauncher.appLabel(requireContext(), snap.sourcePackage) ?: snap.sourcePackage ?: ""
        npTrack.text = snap.title ?: getString(R.string.media_track_unknown)
        npArtist.text = snap.artist ?: pkgLabel
        npArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val bmp = snap.artwork
        if (bmp != null) {
            npArt.imageTintList = null
            npArt.scaleType = ImageView.ScaleType.FIT_CENTER
            npArt.setImageBitmap(bmp)
        } else {
            npArt.setImageResource(R.drawable.ic_album)
        }
        if (!seeking) {
            npSeek.isEnabled = snap.canSeek() && snap.durationMs > 0
            npSeek.max = (snap.durationMs / 1000).toInt().coerceAtLeast(1)
            npSeek.progress = (snap.positionMs / 1000).toInt().coerceIn(0, npSeek.max)
        }
        setTimes(formatTime(if (snap.positionMs > 0) snap.positionMs else 0),
                 formatTime(if (snap.durationMs > 0) snap.durationMs else 0))
        btnPlay.setImageResource(if (snap.playing) R.drawable.ic_pause else R.drawable.ic_play)
        // Кнопки активны только при реально объявленных источником действиях.
        btnPlay.alpha = if (snap.canPlay() || snap.canPause()) 1f else 0.35f
        btnPrev.alpha = if (snap.canSkipPrev()) 1f else 0.35f
        btnNext.alpha = if (snap.canSkipNext()) 1f else 0.35f
        // Внешний источник: имя пакета показываем в верхней панели источника (строка статуса снизу убрана)
        sourceLabel.text = getString(R.string.media_source_of, pkgLabel)
    }

    // ---- PlayerSource.Listener: общий источник изменился (плитка BT или кнопка экрана) ----

    override fun onPlayerSourceChanged(kind: PlayerSource.Kind) {
        if (!isAdded || !::npTrack.isInitialized) return
        if (kind == PlayerSource.Kind.LOCAL) {
            // При возврате к локальному — показываем актуальное состояние движка сразу.
            engine?.let { e ->
                if (e.hasPlaylist) {
                    val item = e.currentItem
                    npTrack.text = item?.displayTitle ?: ""
                    npArtist.text = item?.displayArtist ?: ""
                    btnPlay.setImageResource(if (e.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                }
            }
        }
        render()
    }

    private fun toastNoSession() =
        Toast.makeText(requireContext(), R.string.media_no_session, Toast.LENGTH_SHORT).show()

    private fun toastNoLocalPlaylist() =
        Toast.makeText(requireContext(), R.string.media_local_empty, Toast.LENGTH_SHORT).show()

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

    /** Время трека: синхронно в компактной строке и в центрированной группе. */
    private fun setTimes(pos: String, dur: String) {
        npPos.text = pos; npDur.text = dur
        npPosCenter.text = pos; npDurCenter.text = dur
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val total = ms / 1000
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }
}
