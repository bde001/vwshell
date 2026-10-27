package com.vwshell.auto.ui

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.vwshell.auto.R
import com.vwshell.auto.core.AppLauncher
import com.vwshell.auto.core.MediaMonitor
import com.vwshell.auto.core.Prefs

/**
 * Медиа: управление реальным воспроизведением через Android MediaSession/MediaController.
 * Источник определяется среди активных сессий системы (USB-плеер, Bluetooth Audio и т.п.).
 * Отображаются только реальные метаданные и обложка, предоставленные сессией.
 * Транспортные действия выполняются только если текущая сессия их поддерживает
 * (проверка PlaybackState.ACTION_* — см. MediaSessionLogic).
 */
class MediaFragment : BaseSectionFragment(), MediaMonitor.Listener {

    override val titleRes = R.string.media_title

    private var controller: MediaController? = null
    private lateinit var trackText: TextView
    private lateinit var artistText: TextView
    private lateinit var statusText: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var prevBtn: View
    private lateinit var nextBtn: View
    private lateinit var pickBtn: MaterialButton
    private lateinit var grantBtn: MaterialButton
    private lateinit var sourceIcon: ImageView
    private lateinit var albumArt: ImageView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_media, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        trackText = view.findViewById(R.id.mediaTrack)
        artistText = view.findViewById(R.id.mediaArtist)
        statusText = view.findViewById(R.id.mediaStatus)
        playBtn = view.findViewById(R.id.btnMediaPlay)
        prevBtn = view.findViewById(R.id.btnMediaPrev)
        nextBtn = view.findViewById(R.id.btnMediaNext)
        sourceIcon = view.findViewById(R.id.mediaSourceIcon)
        albumArt = view.findViewById(R.id.mediaAlbumArt)
        pickBtn = view.findViewById(R.id.btnPickMediaApp)
        grantBtn = view.findViewById(R.id.btnGrantMediaAccess)

        prevBtn.setOnClickListener { transport(PlaybackState.ACTION_SKIP_TO_PREVIOUS) }
        nextBtn.setOnClickListener { transport(PlaybackState.ACTION_SKIP_TO_NEXT) }
        playBtn.setOnClickListener { togglePlay() }
        pickBtn.setOnClickListener { showAppPicker() }
        grantBtn.setOnClickListener { openNotificationListenerSettings() }
    }

    override fun onResume() {
        super.onResume()
        MediaMonitor.get(requireContext()).addListener(this)
        // При возврате из системных настроек доступа повторно проверяем разрешение и обновляем UI.
        MediaMonitor.get(requireContext()).refresh()
    }

    override fun onPause() {
        MediaMonitor.get(requireContext()).removeListener(this)
        super.onPause()
    }

    override fun onActiveSource(c: MediaController?, sourcePackage: String?) {
        if (!isAdded || !::trackText.isInitialized) return
        controller = c
        val monitor = MediaMonitor.get(requireContext())
        if (c == null) {
            sourceIcon.visibility = View.GONE
            albumArt.visibility = View.GONE
            albumArt.setImageDrawable(null)
            trackText.text = ""
            artistText.text = ""
            playBtn.setImageResource(R.drawable.ic_play)
            playBtn.alpha = 0.35f
            prevBtn.alpha = 0.35f
            nextBtn.alpha = 0.35f
            grantBtn.visibility = if (monitor.hasNotificationAccess) View.GONE else View.VISIBLE
            pickBtn.visibility = View.VISIBLE
            statusText.setText(if (monitor.hasNotificationAccess) R.string.media_no_session else R.string.media_need_access)
            return
        }
        grantBtn.visibility = View.GONE
        val md: MediaMetadata? = c.metadata
        val ps: PlaybackState? = c.playbackState
        val pkgLabel = AppLauncher.appLabel(requireContext(), sourcePackage) ?: sourcePackage ?: ""
        statusText.text = getString(R.string.media_source_of, pkgLabel)
        sourceIcon.visibility = View.VISIBLE
        sourceIcon.setImageResource(
            if (sourcePackage?.contains("bluetooth", true) == true || sourcePackage?.contains("btaudio", true) == true)
                R.drawable.ic_bluetooth else R.drawable.ic_usb)
        trackText.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: getString(R.string.media_track_unknown)
        artistText.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: getString(R.string.media_unknown_artist)
        // Обложка: только реально предоставленная сессией (BITMAP_ARTWORK или BITMAP_ALBUM_ART),
        // иначе — приглушённая векторная заглушка, без фальшивых картинок.
        val bmp: Bitmap? = md?.getBitmap(MediaMetadata.METADATA_KEY_ARTWORK)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        albumArt.visibility = View.VISIBLE
        if (bmp != null) {
            albumArt.setImageDrawable(null)
            albumArt.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            albumArt.scaleType = ImageView.ScaleType.FIT_CENTER
            albumArt.setImageBitmap(bmp)
        } else {
            albumArt.setBackgroundColor(resources.getColor(R.color.vw_tile_bg, null))
            albumArt.scaleType = ImageView.ScaleType.CENTER_INSIDE
            albumArt.setImageResource(R.drawable.ic_album)
            albumArt.imageTintList = android.content.res.ColorStateList.valueOf(
                resources.getColor(R.color.vw_text_disabled, null))
        }
        val playing = MediaSessionLogic.isPlaying(ps)
        playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        // Неактивные кнопки подсвечиваются прозрачностью: действие доступно только если сессия его объявила.
        playBtn.alpha = if (MediaSessionLogic.playOrPause(ps) != null) 1f else 0.35f
        prevBtn.alpha = if (MediaSessionLogic.canSkipPrevious(ps)) 1f else 0.35f
        nextBtn.alpha = if (MediaSessionLogic.canSkipNext(ps)) 1f else 0.35f
        pickBtn.visibility = View.GONE
    }

    private fun transport(action: Long) {
        val c = controller ?: return toastUnavailable()
        val allowed = c.playbackState?.actions ?: 0L
        // Вызываем транспортное действие ТОЛЬКО если сессия его поддерживает.
        if (allowed and action == 0L) return toastActionUnsupported()
        when (action) {
            PlaybackState.ACTION_SKIP_TO_PREVIOUS -> c.transportControls.skipToPrevious()
            PlaybackState.ACTION_SKIP_TO_NEXT -> c.transportControls.skipToNext()
        }
    }

    private fun togglePlay() {
        val c = controller ?: return toastUnavailable()
        val ps = c.playbackState
        when (MediaSessionLogic.playOrPause(ps)) {
            true -> c.transportControls.play()
            false -> c.transportControls.pause()
            null -> toastActionUnsupported()
        }
    }

    private fun toastUnavailable() =
        Toast.makeText(requireContext(), R.string.media_no_session, Toast.LENGTH_SHORT).show()

    private fun toastActionUnsupported() =
        Toast.makeText(requireContext(), R.string.media_action_unsupported, Toast.LENGTH_SHORT).show()

    /** Выбор музыкального приложения для запуска; выбор сохраняется. */
    private fun showAppPicker() {
        val apps = AppLauncher.installedLaunchableApps(requireContext())
        if (apps.isEmpty()) {
            Toast.makeText(requireContext(), R.string.nav_none, Toast.LENGTH_LONG).show()
            return
        }
        AppPickerDialog.show(requireContext(), R.string.media_pick_app, apps, onPick = { entry ->
            Prefs.get(requireContext()).mediaAppPackage = entry.packageName
            if (!AppLauncher.launchPackage(requireContext(), entry.packageName))
                Toast.makeText(requireContext(), R.string.launch_failed, Toast.LENGTH_SHORT).show()
        })
    }

    /** Notification Listener Access — специальный доступ Android, runtime-диалог неприменим. */
    private fun openNotificationListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // На нестандартных прошивках экран настроек может отсутствовать.
            Toast.makeText(requireContext(), R.string.media_settings_missing, Toast.LENGTH_LONG).show()
        }
    }
}
