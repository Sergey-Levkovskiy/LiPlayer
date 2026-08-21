package com.shiftplayer

import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TeeDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.core.content.ContextCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Плеер с вертикальным сдвигом изображения БЕЗ масштабирования.
 *
 * Двигается только PlayerView (видео и субтитры) внутри чёрного контейнера
 * с clipChildren=true — пиксели не пересчитываются, кадр рисуется в другом
 * месте экрана. Панель управления вынесена отдельным PlayerControlView и
 * прибита к низу, поэтому сдвиг её не задевает.
 *
 * Настройки — панель иконок слева: вверх/вниз выбирают параметр,
 * влево/вправо меняют значение не закрывая панель.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private companion object {
        const val SEEK_STEP_MS = 120_000L

        /**
         * Насколько не доводим кадр до края чёрной полосы.
         *
         * Ровно на краю панель телевизора начинает «плыть» масштабом —
         * край кадра попадает в зону её собственного скейлера.
         */
        const val EDGE_GUARD_PX = 4f

        const val UI_TIMEOUT_MS = 5_000L

        val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

        val ASPECTS = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )

        /** Выкл, мелкие, средние, крупные. */
        val CLOCK_SP = floatArrayOf(0f, 14f, 18f, 24f)
        val CLOCK_ALPHA = floatArrayOf(0.25f, 0.45f, 0.65f, 0.9f)

        /** Отсрочка старта записи, минуты. */
        val REC_DELAYS = intArrayOf(0, 5, 10, 15, 30, 45, 60, 90, 120)

        /** Длительность записи, минуты. 0 — без лимита. */
        val REC_DURATIONS = intArrayOf(0, 15, 30, 45, 60, 90, 120, 180)

        // Порядок рядов в панели слева.
        const val ROW_SHIFT = 0
        const val ROW_QUALITY = 1
        const val ROW_AUDIO = 2
        const val ROW_SUBS = 3
        const val ROW_SPEED = 4
        const val ROW_ASPECT = 5
        const val ROW_CLOCK = 6
        const val ROW_CLOCK_DIM = 7
        const val ROW_RECORD = 8
        const val ROW_REC_DELAY = 9
        const val ROW_REC_DUR = 10
        const val ROW_REC_DIR = 11
        const val ROW_COUNT = 12
    }

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var controls: PlayerControlView
    private lateinit var sideScroll: ScrollView
    private lateinit var sideBar: LinearLayout
    private lateinit var clock: TextView
    private lateinit var recBadge: LinearLayout
    private lateinit var recDot: ImageView
    private lateinit var recSize: TextView
    private lateinit var hud: TextView

    private val rowViews = ArrayList<View>(ROW_COUNT)
    private val rowLabels = ArrayList<TextView>(ROW_COUNT)

    private var player: ExoPlayer? = null
    private var prefs: SharedPreferences? = null
    private var currentUri: Uri? = null

    /** Сдвиг в пикселях: плюс — вниз, минус — вверх. */
    private var shiftPx = 0f

    /** Половина чёрной полосы — предел сдвига без обрезки кадра. */
    private var safeMarginPx = 0f

    /** У 2.35:1 и 16:9 свой сохранённый ручной сдвиг. */
    private var aspectKey = "shift_default"

    /**
     * Режим сдвига: 0 центр, 1 верхний край, 2 нижний край, 3 вручную.
     *
     * Хранится именно режим, а не пиксели: у каждого фильма своя толщина
     * полос, поэтому «вверх до края» пересчитывается под новую геометрию.
     */
    private var shiftMode = 0

    private var manualShift = false

    private var speedIndex = 2
    private var aspectIndex = 0
    private var qualityIndex = 0
    private var audioIndex = 0
    private var subsIndex = 0

    private var clockSize = 0
    private var clockDim = 2

    private var recDelayIndex = 0
    private var recDurIndex = 0
    private var recDirIndex = 0

    /** Момент автостарта и автостопа записи, 0 — не задан. */
    private var recStartAt = 0L
    private var recStopAt = 0L

    private var barActive = false
    private var barRow = ROW_SHIFT

    private val ui = Handler(Looper.getMainLooper())
    private val hideHud = Runnable { hud.visibility = View.GONE }
    private val hideBar = Runnable { setBarVisible(false) }

    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** Одна секунда: часы, счётчик записи, отсрочка, автостоп. */
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()

            if (clockSize > 0) clock.text = clockFmt.format(Date(now))

            if (recStartAt in 1..now) {
                recStartAt = 0L
                startRecording()
            }
            if (recorder.isRecording && recStopAt in 1..now) {
                stopRecording()
            }
            if (recorder.isRecording || recStartAt > 0L) refreshRecBadge()
            if (barActive) {
                refreshRow(ROW_RECORD)
                if (manualShift) refreshRow(ROW_SHIFT)
            }
            ui.postDelayed(this, 1_000L)
        }
    }

    /** Пишет сетевые байты в файл параллельно воспроизведению. */
    private val recorder by lazy { StreamRecorder(recDirs().first()) }

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri == null) finish() else startPlayback(uri) }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        root = findViewById(R.id.root)
        playerView = findViewById(R.id.player_view)
        controls = findViewById(R.id.controls)
        sideScroll = findViewById(R.id.side_scroll)
        sideBar = findViewById(R.id.side_bar)
        clock = findViewById(R.id.clock)
        recBadge = findViewById(R.id.rec_badge)
        recDot = findViewById(R.id.rec_dot)
        recSize = findViewById(R.id.rec_size)
        hud = findViewById(R.id.hud)

        prefs = getSharedPreferences("shift_player", MODE_PRIVATE)
        restoreSettings()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()

        playerView.resizeMode = ASPECTS[aspectIndex]
        controls.showTimeoutMs = UI_TIMEOUT_MS.toInt()

        buildSideBar()
        styleControls()
        applyClockStyle()
        ui.post(tick)

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
        ui.removeCallbacksAndMessages(null)
        releasePlayer()
    }

    // ---------------------------------------------------------------- настройки

    private fun restoreSettings() {
        val p = prefs ?: return
        shiftMode = p.getInt("shift_mode", 0)
        speedIndex = p.getInt("speed", 2).coerceIn(0, SPEEDS.size - 1)
        aspectIndex = p.getInt("aspect", 0).coerceIn(0, ASPECTS.size - 1)
        clockSize = p.getInt("clock_size", 0).coerceIn(0, CLOCK_SP.size - 1)
        clockDim = p.getInt("clock_dim", 2).coerceIn(0, CLOCK_ALPHA.size - 1)
        recDelayIndex = p.getInt("rec_delay", 0).coerceIn(0, REC_DELAYS.size - 1)
        recDurIndex = p.getInt("rec_dur", 0).coerceIn(0, REC_DURATIONS.size - 1)
        recDirIndex = p.getInt("rec_dir", 0).coerceIn(0, recDirs().size - 1)
    }

    private fun saveInt(key: String, value: Int) {
        prefs?.edit()?.putInt(key, value)?.apply()
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

        // Tee дублирует прочитанные байты в рекордер. Пока запись выключена,
        // рекордер их просто выбрасывает, накладных расходов нет.
        val base = DefaultDataSource.Factory(this, http)
        val tee = DataSource.Factory { TeeDataSource(base.createDataSource(), recorder) }
        val sources = DefaultMediaSourceFactory(tee)

        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        // Максимальное качество по умолчанию: адаптивный алгоритм иначе
        // застревает на низком варианте после первой просадки сети.
        val selector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredTextLanguage("ru")
                    .setForceHighestSupportedBitrate(true)
            )
        }

        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(sources)
            .setTrackSelector(selector)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()

        playerView.player = exo
        controls.player = exo

        exo.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) =
                onVideoGeometryChanged(videoSize)

            override fun onTracksChanged(tracks: Tracks) {
                if (barActive) refreshAllRows()
            }

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

        val saved = prefs?.getLong(posKey(uri), 0L) ?: 0L
        if (saved > 10_000L) exo.seekTo(saved)

        exo.setPlaybackSpeed(SPEEDS[speedIndex])
        exo.playWhenReady = true
        player = exo
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
        if (recorder.isRecording) stopRecording()
        recStartAt = 0L
        playerView.player = null
        controls.player = null
        player?.release()
        player = null
    }

    /**
     * Новая геометрия кадра: пересчитываем полосу и применяем режим сдвига.
     *
     * Именно здесь «вверх до края» переносится на новый фильм — предел у
     * каждого свой, поэтому пиксели считаются заново.
     */
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

            shiftPx = when (shiftMode) {
                1 -> -edgeShift()
                2 -> edgeShift()
                3 -> prefs?.getFloat(aspectKey, 0f) ?: 0f
                else -> 0f
            }
            applyShift(showHud = false)
        }
    }

    // ------------------------------------------------------------------- shift

    private fun applyShift(showHud: Boolean) {
        val limit = root.height / 2f
        shiftPx = shiftPx.coerceIn(-limit, limit)
        playerView.translationY = shiftPx
        // Пиксели нужны только ручному режиму, режим — всем остальным.
        if (shiftMode == 3) prefs?.edit()?.putFloat(aspectKey, shiftPx)?.apply()
        saveInt("shift_mode", shiftMode)
        if (showHud) showHud()
    }

    private fun edgeShift() = (safeMarginPx - EDGE_GUARD_PX).coerceAtLeast(0f)

    private fun showHud() {
        val cropping = abs(shiftPx) > safeMarginPx + 0.5f
        val sb = StringBuilder()
            .append(getString(R.string.hud_shift, shiftPx.roundToInt()))
            .append("   ")
            .append(getString(R.string.hud_safe, safeMarginPx.roundToInt()))
        if (cropping) sb.append("   ").append(getString(R.string.hud_crop))
        hud.text = sb.toString()
        hud.visibility = View.VISIBLE
        ui.removeCallbacks(hideHud)
        ui.postDelayed(hideHud, 2_000L)
    }

    // ---------------------------------------------------------------- side bar

    private fun buildSideBar() {
        val inflater = LayoutInflater.from(this)
        val icons = intArrayOf(
            R.drawable.ic_shift,
            R.drawable.ic_quality,
            R.drawable.ic_audio,
            R.drawable.ic_subs,
            R.drawable.ic_speed,
            R.drawable.ic_aspect,
            R.drawable.ic_clock,
            R.drawable.ic_clock_dim,
            R.drawable.ic_record,
            R.drawable.ic_timer,
            R.drawable.ic_duration,
            R.drawable.ic_folder
        )
        for (i in 0 until ROW_COUNT) {
            val row = inflater.inflate(R.layout.row_control, sideBar, false)
            row.findViewById<ImageView>(R.id.row_icon).setImageResource(icons[i])
            rowViews.add(row)
            rowLabels.add(row.findViewById(R.id.row_label))
            sideBar.addView(row)
        }
    }

    private fun setBarVisible(visible: Boolean) {
        barActive = visible
        if (!visible) manualShift = false
        sideScroll.visibility = if (visible) View.VISIBLE else View.GONE
        ui.removeCallbacks(hideBar)
        if (visible) {
            refreshAllRows()
            scrollToRow()
            ui.postDelayed(hideBar, UI_TIMEOUT_MS)
        }
    }

    private fun keepBarAlive() {
        ui.removeCallbacks(hideBar)
        ui.postDelayed(hideBar, UI_TIMEOUT_MS)
    }

    private fun scrollToRow() {
        val v = rowViews[barRow]
        sideScroll.post {
            sideScroll.smoothScrollTo(0, v.top - (sideScroll.height - v.height) / 2)
        }
    }

    private fun refreshAllRows() {
        for (i in 0 until ROW_COUNT) refreshRow(i)
    }

    private fun refreshRow(i: Int) {
        val selected = i == barRow
        rowViews[i].setBackgroundResource(if (selected) R.drawable.row_selected else 0)
        rowLabels[i].visibility = if (selected) View.VISIBLE else View.GONE
        if (selected) {
            rowLabels[i].text = getString(R.string.row_label, rowTitle(i), rowValue(i))
        }
    }

    private fun moveRow(delta: Int) {
        manualShift = false
        val prev = barRow
        barRow = (barRow + delta + ROW_COUNT) % ROW_COUNT
        refreshRow(prev)
        refreshRow(barRow)
        scrollToRow()
        keepBarAlive()
    }

    private fun rowTitle(i: Int): String = getString(
        when (i) {
            ROW_SHIFT -> R.string.ctl_shift
            ROW_QUALITY -> R.string.ctl_quality
            ROW_AUDIO -> R.string.ctl_audio
            ROW_SUBS -> R.string.ctl_subs
            ROW_SPEED -> R.string.ctl_speed
            ROW_ASPECT -> R.string.ctl_aspect
            ROW_CLOCK -> R.string.ctl_clock
            ROW_CLOCK_DIM -> R.string.ctl_clock_dim
            ROW_RECORD -> R.string.ctl_record
            ROW_REC_DELAY -> R.string.ctl_rec_delay
            ROW_REC_DUR -> R.string.ctl_rec_dur
            else -> R.string.ctl_rec_dir
        }
    )

    private fun rowValue(i: Int): String = when (i) {
        ROW_SHIFT -> shiftValue()
        ROW_QUALITY -> qualityValue()
        ROW_AUDIO -> trackValue(C.TRACK_TYPE_AUDIO, audioIndex)
        ROW_SUBS -> if (subsIndex == 0) getString(R.string.val_off)
        else trackValue(C.TRACK_TYPE_TEXT, subsIndex - 1)
        ROW_SPEED -> getString(R.string.val_speed, fmtSpeed(SPEEDS[speedIndex]))
        ROW_ASPECT -> getString(
            when (aspectIndex) {
                0 -> R.string.aspect_fit
                1 -> R.string.aspect_fill
                else -> R.string.aspect_zoom
            }
        )
        ROW_CLOCK -> when (clockSize) {
            0 -> getString(R.string.val_off)
            1 -> getString(R.string.clock_small)
            2 -> getString(R.string.clock_medium)
            else -> getString(R.string.clock_large)
        }
        ROW_CLOCK_DIM -> getString(
            R.string.val_percent, (CLOCK_ALPHA[clockDim] * 100).roundToInt()
        )
        ROW_RECORD -> recordValue()
        ROW_REC_DELAY -> delayValue()
        ROW_REC_DUR -> if (REC_DURATIONS[recDurIndex] == 0)
            getString(R.string.rec_unlimited)
        else getString(R.string.val_minutes, REC_DURATIONS[recDurIndex])
        else -> dirLabel(recDirIndex)
    }

    /** Влево/вправо на выбранном ряду. Панель остаётся открытой. */
    private fun stepRow(dir: Int, fast: Boolean) {
        when (barRow) {
            ROW_SHIFT -> {
                // Влево/вправо перебирают режимы, сам кадр тут не двигается.
                manualShift = false
                shiftMode = (shiftMode + dir + 4) % 4
                when (shiftMode) {
                    1 -> shiftPx = -edgeShift()
                    2 -> shiftPx = edgeShift()
                    3 -> manualShift = true
                    else -> shiftPx = 0f
                }
                applyShift(showHud = false)
            }
            ROW_QUALITY -> stepQuality(dir)
            ROW_AUDIO -> stepTrack(C.TRACK_TYPE_AUDIO, dir)
            ROW_SUBS -> stepTrack(C.TRACK_TYPE_TEXT, dir)
            ROW_SPEED -> {
                speedIndex = (speedIndex + dir + SPEEDS.size) % SPEEDS.size
                player?.setPlaybackSpeed(SPEEDS[speedIndex])
                saveInt("speed", speedIndex)
            }
            ROW_ASPECT -> {
                aspectIndex = (aspectIndex + dir + ASPECTS.size) % ASPECTS.size
                playerView.resizeMode = ASPECTS[aspectIndex]
                saveInt("aspect", aspectIndex)
            }
            ROW_CLOCK -> {
                clockSize = (clockSize + dir + CLOCK_SP.size) % CLOCK_SP.size
                saveInt("clock_size", clockSize)
                applyClockStyle()
            }
            ROW_CLOCK_DIM -> {
                clockDim = (clockDim + dir + CLOCK_ALPHA.size) % CLOCK_ALPHA.size
                saveInt("clock_dim", clockDim)
                applyClockStyle()
            }
            ROW_RECORD -> toggleRecording()
            ROW_REC_DELAY -> {
                recDelayIndex = (recDelayIndex + dir + REC_DELAYS.size) % REC_DELAYS.size
                saveInt("rec_delay", recDelayIndex)
            }
            ROW_REC_DUR -> {
                recDurIndex = (recDurIndex + dir + REC_DURATIONS.size) % REC_DURATIONS.size
                saveInt("rec_dur", recDurIndex)
            }
            ROW_REC_DIR -> {
                val n = recDirs().size
                recDirIndex = (recDirIndex + dir + n) % n
                saveInt("rec_dir", recDirIndex)
            }
        }
        refreshRow(barRow)
        keepBarAlive()
    }

    /** OK на выбранном ряду. */
    private fun activateRow() {
        when (barRow) {
            ROW_SHIFT -> {
                manualShift = !manualShift
                if (manualShift) {
                    shiftMode = 3
                    applyShift(showHud = false)
                }
            }
            ROW_RECORD -> toggleRecording()
            else -> stepRow(1, fast = false)
        }
        refreshRow(barRow)
        keepBarAlive()
    }

    private fun shiftValue(): String {
        if (manualShift) return getString(R.string.shift_manual_on, shiftPx.roundToInt())
        if (shiftMode == 3) return getString(R.string.shift_manual)
        val edge = edgeShift()
        return when {
            abs(shiftPx) < 0.5f -> getString(R.string.shift_center)
            edge > 0.5f && abs(shiftPx + edge) < 0.5f -> getString(R.string.shift_top)
            edge > 0.5f && abs(shiftPx - edge) < 0.5f -> getString(R.string.shift_bottom)
            else -> getString(R.string.val_px, shiftPx.roundToInt())
        }
    }

    private fun nudgeShift(dir: Int, fast: Boolean) {
        shiftPx += dir * (if (fast) 24f else 4f)
        applyShift(showHud = false)
        refreshRow(ROW_SHIFT)
        keepBarAlive()
    }

    // -------------------------------------------------------------- оформление

    /**
     * Приводит панель Media3 к стилю проекта.
     *
     * Цвета полосы прокрутки заданы атрибутами внутри библиотечной вёрстки,
     * поэтому меняем их в рантайме через сеттеры DefaultTimeBar — это
     * надёжнее, чем подменять весь exo_player_control_view.xml своим.
     */
    private fun styleControls() {
        // Плейлиста нет — кнопки «предыдущий/следующий» только мешают.
        controls.setShowPreviousButton(false)
        controls.setShowNextButton(false)
        controls.setShowRewindButton(true)
        controls.setShowFastForwardButton(true)

        val accent = ContextCompat.getColor(this, R.color.brand_accent)
        val text = ContextCompat.getColor(this, R.color.brand_text)

        controls.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setPlayedColor(accent)
            setScrubberColor(accent)
            setBufferedColor(ContextCompat.getColor(this@PlayerActivity, R.color.brand_buffered))
            setUnplayedColor(ContextCompat.getColor(this@PlayerActivity, R.color.brand_unplayed))
        }

        tintTree(controls, text)

        // Кнопка воспроизведения — акцентная, чтобы глаз цеплялся за неё.
        controls.findViewById<ImageView>(androidx.media3.ui.R.id.exo_play_pause)
            ?.setColorFilter(accent)
    }

    private fun tintTree(v: View, color: Int) {
        when (v) {
            is ViewGroup -> for (i in 0 until v.childCount) tintTree(v.getChildAt(i), color)
            is ImageView -> v.setColorFilter(color)
            is TextView -> v.setTextColor(color)
        }
    }

    // ------------------------------------------------------------------- часы

    private fun applyClockStyle() {
        if (clockSize == 0) {
            clock.visibility = View.GONE
            return
        }
        clock.visibility = View.VISIBLE
        clock.textSize = CLOCK_SP[clockSize]
        clock.alpha = CLOCK_ALPHA[clockDim]
        clock.text = clockFmt.format(Date())
    }

    // ------------------------------------------------------------------ tracks

    private fun flatTracks(type: Int): List<Pair<Tracks.Group, Int>> {
        val out = ArrayList<Pair<Tracks.Group, Int>>()
        player?.currentTracks?.groups
            ?.filter { it.type == type && it.isSupported }
            ?.forEach { g ->
                for (i in 0 until g.length) if (g.isTrackSupported(i)) out.add(g to i)
            }
        return out
    }

    private fun trackValue(type: Int, index: Int): String {
        val list = flatTracks(type)
        if (list.isEmpty()) return getString(R.string.val_none)
        val (g, i) = list[index.coerceIn(0, list.size - 1)]
        return trackLabel(g.getTrackFormat(i), index)
    }

    private fun stepTrack(type: Int, dir: Int) {
        val p = player ?: return
        val list = flatTracks(type)
        if (list.isEmpty()) return

        // У субтитров нулевая позиция — «выключено».
        val size = if (type == C.TRACK_TYPE_TEXT) list.size + 1 else list.size
        val cur = if (type == C.TRACK_TYPE_TEXT) subsIndex else audioIndex
        val next = (cur + dir + size) % size
        if (type == C.TRACK_TYPE_TEXT) subsIndex = next else audioIndex = next

        val params = p.trackSelectionParameters.buildUpon()
        if (type == C.TRACK_TYPE_TEXT && next == 0) {
            params.setTrackTypeDisabled(type, true)
        } else {
            val pick = if (type == C.TRACK_TYPE_TEXT) list[next - 1] else list[next]
            params.setTrackTypeDisabled(type, false)
                .setOverrideForType(
                    TrackSelectionOverride(pick.first.mediaTrackGroup, pick.second)
                )
        }
        p.trackSelectionParameters = params.build()
    }

    /**
     * Качество: «Максимум», «Авто» и конкретные варианты потока.
     *
     * Апскейла тут нет и быть не может — панель телевизора всё равно растянет
     * кадр своим скейлером. Смысл в том, чтобы взять лучший из вариантов,
     * которые отдаёт провайдер.
     */
    private fun qualityValue(): String {
        if (qualityIndex == 0) return getString(R.string.val_max)
        if (qualityIndex == 1) return getString(R.string.val_auto)
        val list = flatTracks(C.TRACK_TYPE_VIDEO)
        val at = qualityIndex - 2
        if (at !in list.indices) return getString(R.string.val_max)
        val (g, i) = list[at]
        return videoLabel(g.getTrackFormat(i))
    }

    private fun stepQuality(dir: Int) {
        val p = player ?: return
        val size = 2 + flatTracks(C.TRACK_TYPE_VIDEO).size
        qualityIndex = (qualityIndex + dir + size) % size

        val params = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        when (qualityIndex) {
            0 -> params.setForceHighestSupportedBitrate(true)
            1 -> params.setForceHighestSupportedBitrate(false)
            else -> {
                val (g, i) = flatTracks(C.TRACK_TYPE_VIDEO)[qualityIndex - 2]
                params.setForceHighestSupportedBitrate(false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
            }
        }
        p.trackSelectionParameters = params.build()
    }

    private fun trackLabel(f: Format, ordinal: Int): String {
        val parts = ArrayList<String>()
        f.label?.let { parts.add(it) }
        f.language?.takeIf { it != "und" }?.let { parts.add(it) }
        if (f.channelCount > 0) parts.add("${f.channelCount} ch")
        return if (parts.isEmpty()) getString(R.string.val_track_n, ordinal + 1)
        else parts.joinToString(" · ")
    }

    private fun videoLabel(f: Format): String =
        if (f.bitrate > 0) getString(
            R.string.val_res_rate, f.width, f.height, f.bitrate / 1_000_000f
        ) else getString(R.string.val_res, f.width, f.height)

    // --------------------------------------------------------------- recording

    /**
     * Каталоги для записи: нулевой — внутренняя память, дальше съёмные.
     *
     * getExternalFilesDirs отдаёт каталоги приложения на всех томах, включая
     * USB-флешку. Разрешения для них не нужны — это своя песочница.
     */
    private fun recDirs(): List<File> {
        val dirs = getExternalFilesDirs(Environment.DIRECTORY_MOVIES)
            .filterNotNull()
            .toMutableList()
        if (dirs.isEmpty()) dirs.add(filesDir)
        return dirs
    }

    private fun dirLabel(index: Int): String =
        if (index == 0) getString(R.string.dir_internal)
        else getString(R.string.dir_removable, index)

    private fun recordValue(): String {
        if (recorder.isRecording) {
            val size = fmtSize(recorder.bytesWritten)
            if (recStopAt == 0L) return size
            return getString(R.string.rec_left, size, fmtClock(recStopAt - now()))
        }
        if (recStartAt > 0L) {
            return getString(R.string.rec_armed, fmtClock(recStartAt - now()))
        }
        return getString(R.string.val_off)
    }

    private fun delayValue(): String {
        val min = REC_DELAYS[recDelayIndex]
        if (min == 0) return getString(R.string.rec_now)
        val at = clockFmt.format(Date(now() + min * 60_000L))
        return getString(R.string.rec_at, at, min)
    }

    /** OK на ряду «Запись»: пуск, отмена отсрочки или остановка. */
    private fun toggleRecording() {
        when {
            recorder.isRecording -> stopRecording()
            recStartAt > 0L -> {
                recStartAt = 0L
                recBadge.visibility = View.GONE
                Toast.makeText(this, R.string.rec_cancelled, Toast.LENGTH_SHORT).show()
            }
            REC_DELAYS[recDelayIndex] > 0 -> armRecording()
            else -> startRecording()
        }
    }

    private fun armRecording() {
        val at = now() + REC_DELAYS[recDelayIndex] * 60_000L
        recStartAt = at
        recDot.alpha = 0.45f
        recBadge.visibility = View.VISIBLE
        refreshRecBadge()
        Toast.makeText(
            this,
            getString(R.string.rec_scheduled, clockFmt.format(Date(at))),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun startRecording() {
        recorder.dir = recDirs()[recDirIndex.coerceIn(0, recDirs().size - 1)]
        val target = recorder.start()
        if (target == null) {
            Toast.makeText(this, R.string.rec_failed, Toast.LENGTH_LONG).show()
            return
        }
        val dur = REC_DURATIONS[recDurIndex]
        recStopAt = if (dur > 0) now() + dur * 60_000L else 0L
        recDot.alpha = 1f
        recBadge.visibility = View.VISIBLE
        refreshRecBadge()
        Toast.makeText(
            this, getString(R.string.rec_started, target.name), Toast.LENGTH_LONG
        ).show()
    }

    private fun stopRecording() {
        val written = recorder.bytesWritten
        val target = recorder.stop() ?: return
        recStopAt = 0L
        recBadge.visibility = View.GONE
        if (written == 0L) {
            target.delete()
            Toast.makeText(this, R.string.rec_empty, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(
            this,
            getString(R.string.rec_stopped, fmtSize(written), target.parent ?: ""),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun refreshRecBadge() {
        recSize.text = recordValue()
    }

    private fun now() = System.currentTimeMillis()

    private fun fmtClock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1L shl 30 ->
            String.format(Locale.US, "%.1f ГБ", bytes / (1L shl 30).toFloat())
        bytes >= 1L shl 20 ->
            String.format(Locale.US, "%.0f МБ", bytes / (1L shl 20).toFloat())
        else -> String.format(Locale.US, "%.0f КБ", bytes / 1024f)
    }

    private fun fmtSpeed(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    // ----------------------------------------------------------------- controls

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        val code = event.keyCode

        // Ручной сдвиг забирает вертикальные стрелки себе.
        if (barActive && manualShift) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    nudgeShift(-1, event.repeatCount > 4); return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    nudgeShift(1, event.repeatCount > 4); return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> { stepRow(-1, false); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { stepRow(1, false); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BACK -> {
                    manualShift = false
                    refreshRow(ROW_SHIFT)
                    keepBarAlive()
                    return true
                }
            }
        }

        // Панель настроек открыта — стрелки её и обслуживают.
        if (barActive) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> { moveRow(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { moveRow(1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    stepRow(-1, event.repeatCount > 4); return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    stepRow(1, event.repeatCount > 4); return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    activateRow(); return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> {
                    setBarVisible(false); return true
                }
            }
            keepBarAlive()
            return super.dispatchKeyEvent(event)
        }

        // Закрыта: MENU и «влево» открывают её, остальное уходит панели снизу.
        when (code) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                setBarVisible(true); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!controls.isFullyVisible) { setBarVisible(true); return true }
            }
        }
        if (!controls.isFullyVisible) controls.show()
        return super.dispatchKeyEvent(event)
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
