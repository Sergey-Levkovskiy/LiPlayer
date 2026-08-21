package com.shiftplayer

import android.app.AlertDialog
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Плеер с вертикальным сдвигом изображения БЕЗ масштабирования.
 *
 * Картинка двигается через translationY на PlayerView внутри чёрного
 * контейнера с clipChildren=true. Пиксели не пересчитываются — кадр просто
 * рисуется в другом месте экрана.
 *
 * Остальное — стандартный набор: субтитры, выбор дорожек, скорость,
 * перемотка, память позиции.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var hud: TextView

    private var player: ExoPlayer? = null
    private var prefs: SharedPreferences? = null

    private var currentUri: Uri? = null

    /** Сдвиг в пикселях: плюс — вниз, минус — вверх. */
    private var shiftPx = 0f

    /** Половина чёрной полосы — предел сдвига без обрезки кадра. */
    private var safeMarginPx = 0f

    /** У 2.35:1 и 16:9 свой сохранённый сдвиг. */
    private var aspectKey = "shift_default"

    private var resizeIndex = 0

    private val hudHandler = Handler(Looper.getMainLooper())
    private val hideHud = Runnable { hud.visibility = View.GONE }

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri == null) finish() else startPlayback(uri) }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        root = findViewById(R.id.root)
        playerView = findViewById(R.id.player_view)
        hud = findViewById(R.id.hud)

        prefs = getSharedPreferences("shift_player", MODE_PRIVATE)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()

        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        playerView.controllerShowTimeoutMs = 4000
        playerView.setShowSubtitleButton(true)
        playerView.setShowNextButton(false)
        playerView.setShowPreviousButton(false)
        playerView.setShowFastForwardButton(true)
        playerView.setShowRewindButton(true)
        playerView.requestFocus()

        // Шестерёнка Media3 открывает наше меню: там и дорожки, и сдвиг.
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)
            ?.setOnClickListener { openSettings() }

        val uri = intent?.data
        if (uri != null) startPlayback(uri) else pickVideo.launch(arrayOf("video/*"))
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        hudHandler.removeCallbacksAndMessages(null)
        releasePlayer()
    }

    // ------------------------------------------------------------ playback init

    private fun startPlayback(uri: Uri) {
        releasePlayer()
        currentUri = uri

        // Многие IPTV-порталы отдают http и любят редиректы между протоколами.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("LiPlayer")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)

        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))

        // EXTENSION_RENDERER_MODE_PREFER — задействовать доп. декодеры, если есть.
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val selector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setPreferredTextLanguage("ru"))
        }

        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(sources)
            .setTrackSelector(selector)
            .build()

        playerView.player = exo

        exo.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) =
                onVideoGeometryChanged(videoSize)

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.err_playback, error.errorCodeName),
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        exo.setMediaItem(buildMediaItem(uri))
        exo.prepare()

        // Возобновление с последней позиции для файлов (не для прямых эфиров).
        val saved = prefs?.getLong(posKey(uri), 0L) ?: 0L
        if (saved > 10_000L) exo.seekTo(saved)

        exo.playWhenReady = true
        player = exo

        // D-pad должен приходить в PlayerView, иначе панель не откроется.
        playerView.requestFocus()
    }

    /** Поддержка внешних субтитров, которые присылают торрент-клиенты. */
    private fun buildMediaItem(uri: Uri): MediaItem {
        val builder = MediaItem.Builder().setUri(uri)

        val subs = intent?.getParcelableArrayExtra("subs")
        val names = intent?.getStringArrayExtra("subs.name")
        if (subs != null && subs.isNotEmpty()) {
            val configs = subs.mapIndexedNotNull { i, p ->
                (p as? Uri)?.let {
                    MediaItem.SubtitleConfiguration.Builder(it)
                        .setMimeType(guessSubtitleMime(it))
                        .setLabel(names?.getOrNull(i) ?: "Субтитры ${i + 1}")
                        .setSelectionFlags(0)
                        .build()
                }
            }
            if (configs.isNotEmpty()) builder.setSubtitleConfigurations(configs)
        }
        return builder.build()
    }

    private fun guessSubtitleMime(uri: Uri): String {
        val p = uri.toString().lowercase()
        return when {
            p.endsWith(".ass") || p.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            p.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            p.endsWith(".ttml") || p.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun posKey(uri: Uri) = "pos_" + uri.toString().hashCode()

    private fun savePosition() {
        val p = player ?: return
        val uri = currentUri ?: return
        if (p.duration > 0 && p.currentPosition < p.duration - 15_000L) {
            prefs?.edit()?.putLong(posKey(uri), p.currentPosition)?.apply()
        } else {
            prefs?.edit()?.remove(posKey(uri))?.apply()
        }
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    /** Считаем высоту кадра на экране и размер чёрной полосы. */
    private fun onVideoGeometryChanged(videoSize: VideoSize) {
        root.post {
            val viewW = root.width.toFloat()
            val viewH = root.height.toFloat()
            if (viewW <= 0f || viewH <= 0f || videoSize.height == 0) return@post

            val aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            if (aspect <= 0f) return@post

            val shownH = if (viewW / viewH > aspect) viewH else viewW / aspect
            safeMarginPx = ((viewH - shownH) / 2f).coerceAtLeast(0f)

            aspectKey = "shift_" + (aspect * 100).roundToInt()
            shiftPx = prefs?.getFloat(aspectKey, 0f) ?: 0f
            applyShift(showHud = false)
        }
    }

    // ------------------------------------------------------------------- shift

    private fun applyShift(showHud: Boolean) {
        val limit = root.height / 2f
        shiftPx = shiftPx.coerceIn(-limit, limit)
        playerView.translationY = shiftPx
        prefs?.edit()?.putFloat(aspectKey, shiftPx)?.apply()
        if (showHud) showHud()
    }

    private fun nudge(delta: Float) { shiftPx += delta; applyShift(true) }
    private fun resetShift() { shiftPx = 0f; applyShift(true) }
    private fun snapToBottom() { shiftPx = safeMarginPx; applyShift(true) }
    private fun snapToTop() { shiftPx = -safeMarginPx; applyShift(true) }

    private fun cycleResize() {
        val modes = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )
        val labels = arrayOf("По размеру", "Растянуть", "Обрезать")
        resizeIndex = (resizeIndex + 1) % modes.size
        playerView.resizeMode = modes[resizeIndex]
        hud.text = labels[resizeIndex]
        hud.visibility = View.VISIBLE
        hudHandler.removeCallbacks(hideHud)
        hudHandler.postDelayed(hideHud, 1500L)
    }

    private fun showHud() {
        val cropping = abs(shiftPx) > safeMarginPx + 0.5f
        val sb = StringBuilder()
            .append(getString(R.string.hud_shift, shiftPx.roundToInt()))
            .append("   ")
            .append(getString(R.string.hud_safe, safeMarginPx.roundToInt()))
        if (cropping) sb.append("   ").append(getString(R.string.hud_crop))
        hud.text = sb.toString()
        hud.visibility = View.VISIBLE
        hudHandler.removeCallbacks(hideHud)
        hudHandler.postDelayed(hideHud, 2000L)
    }

    // ----------------------------------------------------------------- controls

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        val step = if (event.repeatCount > 6) 24f else 4f

        // Каналы двигают картинку всегда, даже поверх панели управления.
        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { openSettings(); return true }
            KeyEvent.KEYCODE_CHANNEL_UP -> { nudge(-step); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { nudge(step); return true }
        }

        // Пока панель скрыта — стрелки управляют сдвигом.
        // Когда открыта — работают как обычная навигация по кнопкам.
        if (!playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { nudge(-step); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { nudge(step); return true }
                KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> { snapToTop(); return true }
                KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> { snapToBottom(); return true }
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> { resetShift(); return true }
                KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> { cycleResize(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- settings

    /** Единое меню: звуковая дорожка, субтитры, скорость, сдвиг кадра. */
    private fun openSettings() {
        val items = arrayOf(
            getString(R.string.menu_shift),
            getString(R.string.menu_audio),
            getString(R.string.menu_subs),
            getString(R.string.menu_speed),
            getString(R.string.menu_resize)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openShiftMenu()
                    1 -> openTrackMenu(C.TRACK_TYPE_AUDIO)
                    2 -> openTrackMenu(C.TRACK_TYPE_TEXT)
                    3 -> openSpeedMenu()
                    4 -> cycleResize()
                }
            }
            .setOnDismissListener { goFullscreen() }
            .show()
    }

    private fun openShiftMenu() {
        val items = arrayOf(
            getString(R.string.shift_up_edge),
            getString(R.string.shift_down_edge),
            getString(R.string.shift_reset),
            getString(R.string.shift_step_up),
            getString(R.string.shift_step_down),
            getString(R.string.shift_hint)
        )
        AlertDialog.Builder(this)
            .setTitle(
                getString(
                    R.string.shift_title,
                    shiftPx.roundToInt(),
                    safeMarginPx.roundToInt()
                )
            )
            .setItems(items) { _, which ->
                when (which) {
                    0 -> snapToTop()
                    1 -> snapToBottom()
                    2 -> resetShift()
                    // Шаг оставляет меню открытым: подряд жать удобнее.
                    3 -> { nudge(-16f); openShiftMenu() }
                    4 -> { nudge(16f); openShiftMenu() }
                    5 -> showHud()
                }
            }
            .setOnDismissListener { goFullscreen() }
            .show()
    }

    private fun openTrackMenu(type: Int) {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == type && it.isSupported }
        if (groups.isEmpty()) {
            Toast.makeText(this, R.string.no_tracks, Toast.LENGTH_SHORT).show()
            return
        }

        val labels = ArrayList<String>()
        val targets = ArrayList<Pair<Tracks.Group, Int>?>()

        // Субтитры можно выключить, звук — нет.
        if (type == C.TRACK_TYPE_TEXT) {
            labels.add(getString(R.string.track_off))
            targets.add(null)
        }
        groups.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                labels.add(trackLabel(g.getTrackFormat(i), labels.size))
                targets.add(g to i)
            }
        }

        val titleRes = if (type == C.TRACK_TYPE_AUDIO) R.string.menu_audio else R.string.menu_subs
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setItems(labels.toTypedArray()) { _, which ->
                val target = targets[which]
                val params = p.trackSelectionParameters.buildUpon()
                if (target == null) {
                    params.setTrackTypeDisabled(type, true)
                } else {
                    params.setTrackTypeDisabled(type, false)
                    params.setOverrideForType(
                        TrackSelectionOverride(target.first.mediaTrackGroup, target.second)
                    )
                }
                p.trackSelectionParameters = params.build()
            }
            .setOnDismissListener { goFullscreen() }
            .show()
    }

    private fun openSpeedMenu() {
        val p = player ?: return
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_speed)
            .setItems(speeds.map { "${it}x" }.toTypedArray()) { _, which ->
                p.setPlaybackSpeed(speeds[which])
            }
            .setOnDismissListener { goFullscreen() }
            .show()
    }

    /** Человекочитаемое имя дорожки: язык, каналы, кодек. */
    private fun trackLabel(f: Format, ordinal: Int): String {
        val parts = ArrayList<String>()
        f.label?.let { parts.add(it) }
        f.language?.takeIf { it != "und" }?.let { parts.add(it) }
        if (f.channelCount > 0) parts.add("${f.channelCount} ch")
        f.codecs?.substringBefore('.')?.let { parts.add(it) }
        return if (parts.isEmpty()) getString(R.string.track_n, ordinal + 1)
        else parts.joinToString(" · ")
    }

    // --------------------------------------------------------------- fullscreen

    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }
}
