package com.shiftplayer

import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Плеер с вертикальным сдвигом изображения БЕЗ масштабирования.
 *
 * Двигается только PlayerView (видео и субтитры) внутри чёрного контейнера
 * с clipChildren=true — пиксели не пересчитываются, кадр рисуется в другом
 * месте экрана. Панель управления вынесена из PlayerView отдельным
 * PlayerControlView и прибита к низу, поэтому сдвиг её не задевает.
 *
 * Управление настройками — панель иконок в левом углу: вверх/вниз выбирают
 * параметр, влево/вправо меняют значение не закрывая панель.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private companion object {
        /** Перемотка кнопками панели. */
        const val SEEK_STEP_MS = 120_000L

        /**
         * Насколько не доводим кадр до края чёрной полосы.
         *
         * Ровно на краю панель телевизора начинает «плыть» масштабом —
         * видимо, край кадра попадает в зону её собственного скейлера.
         * Отход на 4 px убирает эффект полностью.
         */
        const val EDGE_GUARD_PX = 4f

        const val UI_TIMEOUT_MS = 5_000L

        val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

        val ASPECTS = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )

        // Порядок рядов в панели слева.
        const val ROW_SHIFT = 0
        const val ROW_QUALITY = 1
        const val ROW_AUDIO = 2
        const val ROW_SUBS = 3
        const val ROW_SPEED = 4
        const val ROW_ASPECT = 5
        const val ROW_RECORD = 6
        const val ROW_COUNT = 7
    }

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var controls: PlayerControlView
    private lateinit var sideBar: LinearLayout
    private lateinit var recBadge: LinearLayout
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

    /** У 2.35:1 и 16:9 свой сохранённый сдвиг. */
    private var aspectKey = "shift_default"

    private var speedIndex = 2          // 1.0×
    private var aspectIndex = 0         // По размеру
    private var qualityIndex = 0        // Максимум — как просили, по умолчанию
    private var audioIndex = 0
    private var subsIndex = 0           // 0 — выключены

    private var barActive = false
    private var barRow = ROW_SHIFT

    /** Курсор режима на ряду «Сдвиг»: центр, верх, низ, вручную. */
    private var shiftMode = 0

    /** Ручной режим: стрелки вверх/вниз двигают кадр, а не ходят по рядам. */
    private var manualShift = false

    private val ui = Handler(Looper.getMainLooper())
    private val hideHud = Runnable { hud.visibility = View.GONE }
    private val hideBar = Runnable { setBarVisible(false) }

    private val recTick = object : Runnable {
        override fun run() {
            if (!recorder.isRecording) return
            refreshRecBadge()
            if (barActive) refreshRow(ROW_RECORD)
            ui.postDelayed(this, 1_000L)
        }
    }

    /** Пишет сетевые байты в файл параллельно воспроизведению. */
    private val recorder by lazy {
        StreamRecorder(getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir)
    }

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
        sideBar = findViewById(R.id.side_bar)
        recBadge = findViewById(R.id.rec_badge)
        recSize = findViewById(R.id.rec_size)
        hud = findViewById(R.id.hud)

        prefs = getSharedPreferences("shift_player", MODE_PRIVATE)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()

        playerView.resizeMode = ASPECTS[aspectIndex]
        controls.showTimeoutMs = UI_TIMEOUT_MS.toInt()

        buildSideBar()

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
                // Дорожки приехали позже — обновляем подписи в панели.
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

        // Возобновление с последней позиции для файлов (не для прямых эфиров).
        val saved = prefs?.getLong(posKey(uri), 0L) ?: 0L
        if (saved > 10_000L) exo.seekTo(saved)

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
        playerView.player = null
        controls.player = null
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

    /**
     * Предел сдвига минус запас: ровно на краю панель телевизора оставляет
     * тонкую кромку от собственного скейлера, поэтому не доводим до конца.
     */
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
            R.drawable.ic_record
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
        sideBar.visibility = if (visible) View.VISIBLE else View.GONE
        ui.removeCallbacks(hideBar)
        if (visible) {
            refreshAllRows()
            ui.postDelayed(hideBar, UI_TIMEOUT_MS)
        }
    }

    private fun keepBarAlive() {
        ui.removeCallbacks(hideBar)
        ui.postDelayed(hideBar, UI_TIMEOUT_MS)
    }

    private fun refreshAllRows() {
        for (i in 0 until ROW_COUNT) refreshRow(i)
    }

    private fun refreshRow(i: Int) {
        val selected = i == barRow
        rowViews[i].setBackgroundResource(
            if (selected) R.drawable.row_selected else 0
        )
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
            else -> R.string.ctl_record
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
        else -> if (recorder.isRecording) fmtSize(recorder.bytesWritten)
        else getString(R.string.val_off)
    }

    /**
     * Подпись ряда «Сдвиг».
     *
     * Режим не хранится, а выводится из фактического сдвига — тогда подпись
     * не расходится с картинкой после загрузки сохранённого значения.
     */
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

    /** Влево/вправо на выбранном ряду. Панель остаётся открытой. */
    private fun stepRow(dir: Int, fast: Boolean) {
        when (barRow) {
            ROW_SHIFT -> {
                // Влево/вправо перебирают режимы, сам сдвиг тут не двигается.
                manualShift = false
                shiftMode = (shiftMode + dir + 4) % 4
                when (shiftMode) {
                    0 -> { shiftPx = 0f; applyShift(showHud = false) }
                    1 -> { shiftPx = -edgeShift(); applyShift(showHud = false) }
                    2 -> { shiftPx = edgeShift(); applyShift(showHud = false) }
                    else -> manualShift = true
                }
            }
            ROW_QUALITY -> stepQuality(dir)
            ROW_AUDIO -> stepTrack(C.TRACK_TYPE_AUDIO, dir)
            ROW_SUBS -> stepTrack(C.TRACK_TYPE_TEXT, dir)
            ROW_SPEED -> {
                speedIndex = (speedIndex + dir + SPEEDS.size) % SPEEDS.size
                player?.setPlaybackSpeed(SPEEDS[speedIndex])
            }
            ROW_ASPECT -> {
                aspectIndex = (aspectIndex + dir + ASPECTS.size) % ASPECTS.size
                playerView.resizeMode = ASPECTS[aspectIndex]
            }
            ROW_RECORD -> toggleRecording()
        }
        refreshRow(barRow)
        keepBarAlive()
    }

    /** OK на выбранном ряду. */
    private fun activateRow() {
        when (barRow) {
            // OK входит в ручной режим и выходит из него.
            ROW_SHIFT -> {
                manualShift = !manualShift
                if (manualShift) shiftMode = 3
            }
            ROW_RECORD -> toggleRecording()
            else -> stepRow(1, fast = false)
        }
        refreshRow(barRow)
        keepBarAlive()
    }

    // ------------------------------------------------------------------ tracks

    private fun videoGroups(): List<Tracks.Group> =
        player?.currentTracks?.groups?.filter {
            it.type == C.TRACK_TYPE_VIDEO && it.isSupported
        } ?: emptyList()

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

    private fun toggleRecording() {
        if (recorder.isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val target = recorder.start()
        if (target == null) {
            Toast.makeText(this, R.string.rec_failed, Toast.LENGTH_LONG).show()
            return
        }
        recBadge.visibility = View.VISIBLE
        refreshRecBadge()
        ui.removeCallbacks(recTick)
        ui.postDelayed(recTick, 1_000L)
        Toast.makeText(
            this, getString(R.string.rec_started, target.name), Toast.LENGTH_LONG
        ).show()
    }

    private fun stopRecording() {
        val written = recorder.bytesWritten
        val target = recorder.stop() ?: return
        ui.removeCallbacks(recTick)
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
        recSize.text = fmtSize(recorder.bytesWritten)
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.1f ГБ", bytes / (1L shl 30).toFloat())
        bytes >= 1L shl 20 -> String.format("%.0f МБ", bytes / (1L shl 20).toFloat())
        else -> String.format("%.0f КБ", bytes / 1024f)
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
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    stepRow(-1, fast = false); return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    stepRow(1, fast = false); return true
                }
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
