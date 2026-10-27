package com.vwshell.auto.ui

import android.content.Intent
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
import com.vwshell.auto.R
import com.vwshell.auto.core.AppLauncher
import com.vwshell.auto.core.MediaMonitor
import com.vwshell.auto.core.Prefs

/**
 * Медиа: управление реальным воспроизведением через Android MediaSession/MediaController.
 * Источник определяется среди активных сессий системы (USB-плеер, Bluetooth Audio и т.п.).
 * Если источника нет — показывается честное сообщение, никаких выдуманных треков.
 */
class MediaFragment : BaseSectionFragment(), MediaMonitor.Listener {

    override val titleRes = R.string.media_title

    private var controller: MediaController? = null
    private lateinit var trackText: TextView
    private lateinit var artistText: TextView
    private lateinit var statusText: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var pickBtn: View
    private lateinit var sourceIcon: ImageView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_media, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        trackText = view.findViewById(R.id.mediaTrack)
        artistText = view.findViewById(R.id.mediaArtist)
        statusText = view.findViewById(R.id.mediaStatus)
        playBtn = view.findViewById(R.id.btnMediaPlay)
        sourceIcon = view.findViewById(R.id.mediaSourceIcon)
        pickBtn = view.findViewById(R.id.btnPickMediaApp)

        view.findViewById<View>(R.id.btnMediaPrev).setOnClickListener { transport(PlaybackState.ACTION_SKIP_TO_PREVIOUS) }
        view.findViewById<View>(R.id.btnMediaNext).setOnClickListener { transport(PlaybackState.ACTION_SKIP_TO_NEXT) }
        playBtn.setOnClickListener { togglePlay() }
        pickBtn.setOnClickListener { showAppPicker() }
    }

    override fun onResume() {
        super.onResume()
        MediaMonitor.get(requireContext()).addListener(this)
        MediaMonitor.get(requireContext()).refresh()
    }

    override fun onPause() {
        MediaMonitor.get(requireContext()).removeListener(this)
        super.onPause()
    }

    override fun onActiveSource(c: MediaController?, sourcePackage: String?) {
        if (!isAdded) return
        controller = c
        val monitor = MediaMonitor.get(requireContext())
        if (c == null) {
            sourceIcon.visibility = View.GONE
            trackText.text = ""
            artistText.text = ""
            playBtn.setImageResource(R.drawable.ic_play)
            if (!monitor.hasNotificationAccess) {
                statusText.text = getString(R.string.media_no_session) +
                    "\n\n" + "Доступ к медиа-сессиям предоставляется в системных настройках доступа к уведомлениям."
                pickBtn.visibility = View.VISIBLE
            } else {
                statusText.setText(R.string.media_no_session)
                pickBtn.visibility = View.VISIBLE
            }
            return
        }
        pickBtn.visibility = View.GONE
        val md: MediaMetadata? = c.metadata
        val ps: PlaybackState? = c.playbackState
        statusText.text = sourcePackage ?: ""
        sourceIcon.visibility = View.VISIBLE
        sourceIcon.setImageResource(
            if (sourcePackage?.contains("bluetooth", true) == true || sourcePackage?.contains("btaudio", true) == true)
                R.drawable.ic_bluetooth else R.drawable.ic_media
        )
        trackText.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: getString(R.string.media_track_unknown)
        artistText.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: getString(R.string.media_unknown_artist)
        val playing = ps?.state == PlaybackState.STATE_PLAYING || ps?.state == PlaybackState.STATE_BUFFERING
        playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun transport(action: Long) {
        val c = controller ?: return toastUnavailable()
        val allowed = c.playbackState?.actions ?: 0L
        if (allowed and action == 0L) return toastUnavailable()
        when (action) {
            PlaybackState.ACTION_SKIP_TO_PREVIOUS -> c.transportControls.skipToPrevious()
            PlaybackState.ACTION_SKIP_TO_NEXT -> c.transportControls.skipToNext()
            PlaybackState.ACTION_PLAY -> c.transportControls.play()
            PlaybackState.ACTION_PAUSE -> c.transportControls.pause()
            PlaybackState.ACTION_PLAY_PAUSE -> {
                if (allowed and PlaybackState.ACTION_PLAY != 0L) c.transportControls.play() else c.transportControls.pause()
            }
        }
    }

    private fun togglePlay() {
        val c = controller ?: return toastUnavailable()
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing) c.transportControls.pause() else c.transportControls.play()
    }

    private fun toastUnavailable() =
        Toast.makeText(requireContext(), R.string.media_no_session, Toast.LENGTH_SHORT).show()

    /** Выбор пользовательского медиаприложения для быстрого запуска. */
    private fun showAppPicker() {
        val apps = AppLauncher.installedLaunchableApps(requireContext())
        if (apps.isEmpty()) return toastUnavailable()
        val labels = apps.map { it.label }.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.media_pick_app)
            .setItems(labels) { _, which ->
                Prefs.get(requireContext()).mediaAppPackage = apps[which].packageName
                AppLauncher.launchPackage(requireContext(), apps[which].packageName)
            }
            .setNeutralButton(R.string.settings_behavior) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }
            .show()
    }
}
