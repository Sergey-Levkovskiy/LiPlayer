package com.shiftplayer

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
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
import androidx.media3.ui.DefaultTimeBar
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
 * Двигается PlayerView внутри чёрного контейнера с clipChildren=true —
 * пиксели не пересчитываются, кадр рисуется в другом месте экрана.
 *
 * Управление: четыре действия внизу справа (запись, качество, сдвиг,
 * настройки), список записей слева сверху. Навигация — штатным фокусом
 * Android, а не ручным разбором кнопок: так пульт ведёт себя предсказуемо.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private companion object {
        /** Кнопки «назад/вперёд» рядом с паузой. */
        const val SEEK_BUTTON_MS = 15_000L

        /** Стрелки, когда фокус стоит на полосе времени. */
        const val SEEK_BAR_MS = 120_000L

        /**
         * Насколько не доводим кадр до края чёрной полосы: ровно на краю
         * панель телевизора начинает «плыть» масштабом.
         */
        const val EDGE_GUARD_PX = 4f

        const val UI_TIMEOUT_MS = 6_000L

        /**
         * Диагональ панели, для которой «Полный» размер = 100 %.
         *
         * Меняется одной цифрой, если приложение поедет на другой телевизор.
         */
        const val BASE_DIAGONAL_IN = 98

        /** Варианты уменьшения картинки, дюймы по диагонали. */
        val SCREEN_IN = intArrayOf(0, 85, 75, 65, 55)

        val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

        val ASPECTS = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )

        val CLOCK_SP = floatArrayOf(0f, 14f, 18f, 24f)
        val CLOCK_ALPHA = floatArrayOf(0.25f, 0.45f, 0.65f, 0.9f)

        /** Отсрочка старта записи, минуты. */
        val REC_DELAYS = intArrayOf(0, 5, 10, 15, 30, 45, 60, 90, 120)

        /** Длительность записи, минуты. 0 — без лимита. */
        val REC_DURATIONS = intArrayOf(0, 15, 30, 45, 60, 90, 120, 180)

        /** Пауза «на рекламу», секунды. */
        val SNOOZE_SEC = intArrayOf(120, 180, 270, 300, 420, 600)

        // Ряды в панели «ещё настройки». Запись, качество и сдвиг живут
        // отдельными кнопками, поэтому здесь их нет.
        const val ROW_QUALITY = 0
        const val ROW_AUDIO = 1
        const val ROW_SUBS = 2
        const val ROW_SPEED = 3
        const val ROW_ASPECT = 4
        const val ROW_SCREEN = 5
        const val ROW_SHIFT_MANUAL = 6
        const val ROW_CLOCK = 7
        const val ROW_CLOCK_DIM = 8
        const val ROW_REC_SHOW = 9
        const val ROW_REC_DELAY = 10
        const val ROW_REC_DUR = 11
        const val ROW_SNOOZE = 12
        const val ROW_REC_DIR = 13
        const val ROW_COUNT = 14
    }

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var leftTop: LinearLayout
    private lateinit var btnLibrary: ImageButton
    private lateinit var clock: TextView
    private lateinit var hud: TextView
    private lateinit var drawer: LinearLayout
    private lateinit var drawerList: ListView
    private lateinit var drawerEmpty: TextView
    private lateinit var popup: LinearLayout
    private lateinit var actionBar: LinearLayout
    private lateinit var sideScroll: ScrollView
    private lateinit var sideBar: LinearLayout
    private lateinit var auxPanel: LinearLayout
    private lateinit var recBadge: LinearLayout
    private lateinit var recDot: ImageView
    private lateinit var recSize: TextView

    private lateinit var btnRecord: ImageButton
    private lateinit var btnRecPause: ImageButton
    private lateinit var btnRecSnooze: ImageButton
    private lateinit var btnRecStop: ImageButton
    private lateinit var btnShift: ImageButton
    private lateinit var btnSettings: ImageButton

    private val rowViews = ArrayList<View>(ROW_COUNT)
    private val rowLabels = ArrayList<TextView>(ROW_COUNT)

    private var player: ExoPlayer? = null
    private var prefs: SharedPreferences? = null
    private var currentUri: Uri? = null

    private var shiftPx = 0f
    private var safeMarginPx = 0f
    private var aspectKey = "shift_default"

    /** 0 центр, 1 верхний край, 2 нижний край, 3 вручную. */
    private var shiftMode = 0

    private var speedIndex = 2
    private var aspectIndex = 0
    private var qualityIndex = 0
    private var audioIndex = 0
    private var subsIndex = 0
    private var screenIndex = 0
    private var clockSize = 0
    private var clockDim = 2
    private var recDelayIndex = 0
    private var recDurIndex = 0
    private var recDirIndex = 0
    private var snoozeIndex = 2          // 4,5 минуты

    /** 0 — кнопка записи в панели показана, 1 — скрыта. */
    private var recShowIndex = 0

    /**
     * Ручное смещение «захватило» стрелки вверх/вниз.
     *
     * Без этого режима вверх/вниз означали бы переход между рядами, и кадр
     * пришлось бы двигать влево/вправо — неинтуитивно для вертикали.
     */
    private var shiftCapture = false

    private var recStartAt = 0L
    private var recStopAt = 0L
    private var recResumeAt = 0L

    private val ui = Handler(Looper.getMainLooper())
    private val hideHud = Runnable { hud.visibility = View.GONE }

    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val stampFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

    private val recordings = ArrayList<File>()
    private val thumbs = HashMap<String, Bitmap?>()

    private val recorder by lazy { StreamRecorder(recDirs().first()) }

    /** Одна секунда: часы, счётчик записи, отсрочки и автостопы. */
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (clockSize > 0) clock.text = clockFmt.format(Date(now))

            if (recStartAt in 1..now) {
                recStartAt = 0L
                startRecording()
            }
            if (recorder.isRecording && recResumeAt in 1..now) {
                recResumeAt = 0L
                recorder.isPaused = false
                syncRecordButtons()
            }
            if (recorder.isRecording && recStopAt in 1..now) stopRecording()
            if (recorder.isRecording || recStartAt > 0L) refreshRecBadge()

            ui.postDelayed(this, 1_000L)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        bindViews()

        prefs = getSharedPreferences("shift_player", MODE_PRIVATE)
        restoreSettings()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()

        playerView.resizeMode = ASPECTS[aspectIndex]
        playerView.controllerShowTimeoutMs = UI_TIMEOUT_MS.toInt()

        // Штатные настройки и субтитры Media3 не нужны — их заменяет наше
        // меню, которое стоит на их месте в правом нижнем углу панели.
        playerView.setShowSubtitleButton(false)
        playerView.setControllerVisibilityListener(
            object : PlayerView.ControllerVisibilityListener {
                override fun onVisibilityChanged(visibility: Int) {
                    onControllerVisibility(visibility == View.VISIBLE)
                }
            }
        )

        buildSideBar()
        wireActions()
        styleControls()
        applyClockStyle()
        applyScreenSize()
        ui.post(tick)

        val uri = intent?.data
        if (uri != null) {
            startPlayback(uri)
        } else {
            startActivity(Intent(this, LibraryActivity::class.java))
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { startPlayback(it) }
    }

    /** Плеер освобождается в onStop, поэтому при возврате поднимаем заново. */
    override fun onStart() {
        super.onStart()
        if (player == null) currentUri?.let { startPlayback(it) }
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

    private fun bindViews() {
        root = findViewById(R.id.root)
        playerView = findViewById(R.id.player_view)
        leftTop = findViewById(R.id.left_top)
        btnLibrary = findViewById(R.id.btn_library)
        clock = findViewById(R.id.clock)
        hud = findViewById(R.id.hud)
        drawer = findViewById(R.id.drawer)
        drawerList = findViewById(R.id.drawer_list)
        drawerList.itemsCanFocus = true
        drawerEmpty = findViewById(R.id.drawer_empty)
        popup = findViewById(R.id.popup)
        actionBar = findViewById(R.id.action_bar)
        sideScroll = findViewById(R.id.side_scroll)
        sideBar = findViewById(R.id.side_bar)
        auxPanel = findViewById(R.id.aux_panel)
        recBadge = findViewById(R.id.rec_badge)
        recDot = findViewById(R.id.rec_dot)
        recSize = findViewById(R.id.rec_size)

        btnRecord = findViewById(R.id.btn_record)
        btnRecPause = findViewById(R.id.btn_rec_pause)
        btnRecSnooze = findViewById(R.id.btn_rec_snooze)
        btnRecStop = findViewById(R.id.btn_rec_stop)
        btnShift = findViewById(R.id.btn_shift)
        btnSettings = findViewById(R.id.btn_settings)
    }

    // ---------------------------------------------------------------- настройки

    private fun restoreSettings() {
        val p = prefs ?: return
        shiftMode = p.getInt("shift_mode", 0)
        speedIndex = p.getInt("speed", 2).coerceIn(0, SPEEDS.size - 1)
        aspectIndex = p.getInt("aspect", 0).coerceIn(0, ASPECTS.size - 1)
        screenIndex = p.getInt("screen", 0).coerceIn(0, SCREEN_IN.size - 1)
        clockSize = p.getInt("clock_size", 0).coerceIn(0, CLOCK_SP.size - 1)
        clockDim = p.getInt("clock_dim", 2).coerceIn(0, CLOCK_ALPHA.size - 1)
        recDelayIndex = p.getInt("rec_delay", 0).coerceIn(0, REC_DELAYS.size - 1)
        recDurIndex = p.getInt("rec_dur", 0).coerceIn(0, REC_DURATIONS.size - 1)
        snoozeIndex = p.getInt("snooze", 2).coerceIn(0, SNOOZE_SEC.size - 1)
        recShowIndex = p.getInt("rec_show", 0).coerceIn(0, 1)
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
            .setSeekBackIncrementMs(SEEK_BUTTON_MS)
            .setSeekForwardIncrementMs(SEEK_BUTTON_MS)
            .build()

        playerView.player = exo

        exo.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) =
                onVideoGeometryChanged(videoSize)

            override fun onTracksChanged(tracks: Tracks) {
                if (sideScroll.visibility == View.VISIBLE) refreshAllRows()
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
        if (shiftMode == 3) prefs?.edit()?.putFloat(aspectKey, shiftPx)?.apply()
        saveInt("shift_mode", shiftMode)
        if (showHud) showHud()
    }

    private fun edgeShift() = (safeMarginPx - EDGE_GUARD_PX).coerceAtLeast(0f)

    private fun setShiftMode(mode: Int) {
        shiftMode = mode
        shiftPx = when (mode) {
            1 -> -edgeShift()
            2 -> edgeShift()
            else -> 0f
        }
        applyShift(showHud = true)
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
        ui.removeCallbacks(hideHud)
        ui.postDelayed(hideHud, 2_000L)
    }

    /** Уменьшение картинки под меньшую диагональ. */
    private fun applyScreenSize() {
        val inches = SCREEN_IN[screenIndex]
        val scale = if (inches == 0) 1f else inches.toFloat() / BASE_DIAGONAL_IN
        playerView.scaleX = scale
        playerView.scaleY = scale
    }

    // ------------------------------------------------------------- панель действий

    private fun wireActions() {
        btnLibrary.setOnClickListener { toggleDrawer() }
        btnRecord.setOnClickListener { onRecordPressed() }
        btnRecPause.setOnClickListener { toggleRecPause() }
        btnRecSnooze.setOnClickListener { snoozeRecording() }
        btnRecStop.setOnClickListener { stopRecording() }
        btnShift.setOnClickListener { openShiftPopup() }
        btnSettings.setOnClickListener { toggleSidePanel() }
        syncRecordButtons()
    }

    /**
     * Наши иконки живут и умирают вместе с панелью Media3 — иначе они
     * висели бы над видео, когда панель уже скрылась.
     */
    private fun onControllerVisibility(shown: Boolean) {
        actionBar.visibility = if (shown) View.VISIBLE else View.GONE
        // INVISIBLE, а не GONE: иначе плашка записи под кнопкой прыгает.
        btnLibrary.visibility = if (shown) View.VISIBLE else View.INVISIBLE
        hideStockButtons()
        if (!shown) closePanels()
    }

    /**
     * Шестерёнка Media3 скрывается по id: публичного сеттера у неё нет.
     * Повторяем при каждом показе панели — библиотека её пересобирает.
     */
    private fun hideStockButtons() {
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)
            ?.visibility = View.GONE
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)
            ?.visibility = View.GONE
    }

    /** Любое нажатие поднимает панель и продлевает ей жизнь. */
    private fun showUi(focus: Boolean) {
        playerView.showController()
        hideStockButtons()
        if (focus && !hasPanelFocus()) firstActionButton().requestFocus()
    }

    /** Первая доступная кнопка панели действий — для наведения фокуса. */
    private fun firstActionButton(): View = when {
        recorder.isRecording -> btnRecPause
        recShowIndex == 0 -> btnRecord
        else -> btnShift
    }

    /** Пока открыт список или всплывашка, панель не должна уезжать. */
    private fun pinController(pinned: Boolean) {
        playerView.controllerShowTimeoutMs =
            if (pinned) 0 else UI_TIMEOUT_MS.toInt()
        playerView.showController()
    }

    private fun keepUiAlive() {
        if (playerView.controllerShowTimeoutMs != 0) playerView.showController()
    }

    private fun closePanels() {
        popup.visibility = View.GONE
        popup.removeAllViews()
        drawer.visibility = View.GONE
        sideScroll.visibility = View.GONE
        auxPanel.visibility = View.GONE
        shiftCapture = false
    }

    private fun hasPanelFocus(): Boolean {
        val f = currentFocus ?: return false
        return f.isDescendantOf(actionBar) || f.isDescendantOf(popup) ||
            f.isDescendantOf(sideBar) || f.isDescendantOf(drawer) ||
            f === btnLibrary
    }

    private fun View.isDescendantOf(group: ViewGroup): Boolean {
        var p: View? = this
        while (p != null) {
            if (p === group) return true
            p = p.parent as? View
        }
        return false
    }

    // ------------------------------------------------------------- всплывашки

    /** Меню НАД кнопкой: три иконки для сдвига, три пункта для качества. */
    private fun openPopup(views: List<View>) {
        popup.removeAllViews()
        views.forEach { popup.addView(it) }
        popup.visibility = View.VISIBLE
        pinController(true)
        views.firstOrNull()?.requestFocus()
    }

    private fun popupIcon(iconRes: Int, label: String, action: () -> Unit): View {
        val row = LayoutInflater.from(this).inflate(R.layout.row_control, popup, false)
        row.findViewById<ImageView>(R.id.row_icon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.row_label).text = label
        row.setOnClickListener {
            action()
            popup.visibility = View.GONE
            popup.removeAllViews()
            pinController(false)
            actionBar.requestFocus()
        }
        return row
    }

    private fun openShiftPopup() {
        openPopup(
            listOf(
                popupIcon(R.drawable.ic_arrow_up, getString(R.string.shift_top)) {
                    setShiftMode(1)
                },
                popupIcon(R.drawable.ic_arrow_center, getString(R.string.shift_center)) {
                    setShiftMode(0)
                },
                popupIcon(R.drawable.ic_arrow_down, getString(R.string.shift_bottom)) {
                    setShiftMode(2)
                }
            )
        )
    }

    // ------------------------------------------------------------ панель настроек

    private fun toggleSidePanel() {
        if (sideScroll.visibility == View.VISIBLE) {
            sideScroll.visibility = View.GONE
            auxPanel.visibility = View.GONE
            shiftCapture = false
            pinController(false)
            btnSettings.requestFocus()
        } else {
            drawer.visibility = View.GONE
            refreshAllRows()
            sideScroll.visibility = View.VISIBLE
            pinController(true)
            rowViews.firstOrNull()?.requestFocus()
            updateAux(0)
        }
    }

    private fun buildSideBar() {
        val inflater = LayoutInflater.from(this)
        val icons = intArrayOf(
            R.drawable.ic_quality,
            R.drawable.ic_audio,
            R.drawable.ic_subs,
            R.drawable.ic_speed,
            R.drawable.ic_aspect,
            R.drawable.ic_screen,
            R.drawable.ic_shift,
            R.drawable.ic_clock,
            R.drawable.ic_clock_dim,
            R.drawable.ic_record,
            R.drawable.ic_timer,
            R.drawable.ic_duration,
            R.drawable.ic_pause_timed,
            R.drawable.ic_folder
        )
        for (i in 0 until ROW_COUNT) {
            val row = inflater.inflate(R.layout.row_control, sideBar, false)
            row.findViewById<ImageView>(R.id.row_icon).setImageResource(icons[i])
            row.setOnKeyListener { _, code, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val fast = event.repeatCount > 4

                // В режиме захвата вертикальные стрелки двигают кадр,
                // а не переводят фокус на соседний ряд.
                if (i == ROW_SHIFT_MANUAL && shiftCapture) {
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_UP -> { nudgeManual(-1, fast); true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { nudgeManual(1, fast); true }
                        KeyEvent.KEYCODE_DPAD_LEFT -> { nudgeManual(-1, fast); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { nudgeManual(1, fast); true }
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER -> { setShiftCapture(false); true }
                        else -> false
                    }
                } else when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { stepRow(i, -1, fast); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { stepRow(i, 1, fast); true }
                    else -> false
                }
            }
            row.setOnClickListener {
                if (i == ROW_SHIFT_MANUAL) setShiftCapture(!shiftCapture)
                else stepRow(i, 1, false)
            }
            row.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    if (shiftCapture && i != ROW_SHIFT_MANUAL) setShiftCapture(false)
                    updateAux(i)
                }
            }
            rowViews.add(row)
            rowLabels.add(row.findViewById(R.id.row_label))
            sideBar.addView(row)
        }
    }

    private fun setShiftCapture(on: Boolean) {
        shiftCapture = on
        if (on) shiftMode = 3
        updateAux(ROW_SHIFT_MANUAL)
        refreshRow(ROW_SHIFT_MANUAL)
    }

    private fun nudgeManual(dir: Int, fast: Boolean) {
        shiftMode = 3
        shiftPx += dir * (if (fast) 24f else 4f)
        applyShift(showHud = false)
        refreshRow(ROW_SHIFT_MANUAL)
        updateAux(ROW_SHIFT_MANUAL)
        keepUiAlive()
    }

    /**
     * Подсказка слева от выбранного ряда.
     *
     * У дорожек и субтитров — весь список сразу, чтобы не перебирать
     * вслепую. У ручного смещения — текущее значение и что нажимать.
     */
    private fun updateAux(row: Int) {
        when (row) {
            ROW_AUDIO -> {
                val list = flatTracks(C.TRACK_TYPE_AUDIO)
                showAux(
                    list.mapIndexed { i, (g, t) -> trackLabel(g.getTrackFormat(t), i) },
                    audioIndex
                )
            }
            ROW_SUBS -> {
                val labels = ArrayList<String>()
                labels.add(getString(R.string.val_off))
                flatTracks(C.TRACK_TYPE_TEXT).forEachIndexed { i, (g, t) ->
                    labels.add(trackLabel(g.getTrackFormat(t), i))
                }
                showAux(labels, subsIndex)
            }
            ROW_QUALITY -> {
                val labels = arrayListOf(
                    getString(R.string.val_max), getString(R.string.val_auto)
                )
                flatTracks(C.TRACK_TYPE_VIDEO).forEach { (g, t) ->
                    labels.add(videoLabel(g.getTrackFormat(t)))
                }
                showAux(labels, qualityIndex)
            }
            ROW_SHIFT_MANUAL -> showAux(
                listOf(
                    getString(R.string.shift_now, shiftPx.roundToInt()),
                    getString(R.string.shift_limit, safeMarginPx.roundToInt()),
                    getString(
                        if (shiftCapture) R.string.shift_capture_on
                        else R.string.shift_capture_off
                    )
                ),
                if (shiftCapture) 2 else -1
            )
            else -> auxPanel.visibility = View.GONE
        }
    }

    private fun showAux(labels: List<String>, current: Int) {
        auxPanel.removeAllViews()
        if (labels.isEmpty()) {
            auxPanel.visibility = View.GONE
            return
        }
        val inflater = LayoutInflater.from(this)
        labels.forEachIndexed { i, s ->
            val tv = inflater.inflate(R.layout.row_aux, auxPanel, false) as TextView
            tv.text = s
            if (i == current) tv.setBackgroundResource(R.drawable.row_selected)
            auxPanel.addView(tv)
        }
        auxPanel.visibility = View.VISIBLE
    }

    private fun refreshAllRows() {
        for (i in 0 until ROW_COUNT) refreshRow(i)
    }

    private fun refreshRow(i: Int) {
        rowLabels[i].text = getString(R.string.row_label, rowTitle(i), rowValue(i))
    }

    private fun rowTitle(i: Int): String = getString(
        when (i) {
            ROW_QUALITY -> R.string.ctl_quality
            ROW_AUDIO -> R.string.ctl_audio
            ROW_SUBS -> R.string.ctl_subs
            ROW_SPEED -> R.string.ctl_speed
            ROW_ASPECT -> R.string.ctl_aspect
            ROW_SCREEN -> R.string.ctl_screen
            ROW_SHIFT_MANUAL -> R.string.shift_manual
            ROW_CLOCK -> R.string.ctl_clock
            ROW_CLOCK_DIM -> R.string.ctl_clock_dim
            ROW_REC_SHOW -> R.string.ctl_rec_show
            ROW_REC_DELAY -> R.string.ctl_rec_delay
            ROW_REC_DUR -> R.string.ctl_rec_dur
            ROW_SNOOZE -> R.string.ctl_snooze
            else -> R.string.ctl_rec_dir
        }
    )

    private fun rowValue(i: Int): String = when (i) {
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
        ROW_SCREEN -> if (SCREEN_IN[screenIndex] == 0) getString(R.string.screen_full)
        else getString(R.string.screen_inches, SCREEN_IN[screenIndex])
        ROW_SHIFT_MANUAL -> getString(R.string.val_px, shiftPx.roundToInt())
        ROW_CLOCK -> when (clockSize) {
            0 -> getString(R.string.val_off)
            1 -> getString(R.string.clock_small)
            2 -> getString(R.string.clock_medium)
            else -> getString(R.string.clock_large)
        }
        ROW_CLOCK_DIM -> getString(
            R.string.val_percent, (CLOCK_ALPHA[clockDim] * 100).roundToInt()
        )
        ROW_REC_SHOW -> getString(
            if (recShowIndex == 0) R.string.val_shown else R.string.val_hidden
        )
        ROW_REC_DELAY -> delayValue()
        ROW_REC_DUR -> if (REC_DURATIONS[recDurIndex] == 0)
            getString(R.string.rec_unlimited)
        else getString(R.string.val_minutes, REC_DURATIONS[recDurIndex])
        ROW_SNOOZE -> fmtMinSec(SNOOZE_SEC[snoozeIndex])
        else -> dirLabel(recDirIndex)
    }

    private fun stepRow(i: Int, dir: Int, fast: Boolean) {
        when (i) {
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
            ROW_SCREEN -> {
                screenIndex = (screenIndex + dir + SCREEN_IN.size) % SCREEN_IN.size
                saveInt("screen", screenIndex)
                applyScreenSize()
            }
            ROW_SHIFT_MANUAL -> nudgeManual(dir, fast)
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
            ROW_REC_SHOW -> {
                recShowIndex = if (recShowIndex == 0) 1 else 0
                saveInt("rec_show", recShowIndex)
                syncRecordButtons()
            }
            ROW_REC_DELAY -> {
                recDelayIndex = (recDelayIndex + dir + REC_DELAYS.size) % REC_DELAYS.size
                saveInt("rec_delay", recDelayIndex)
            }
            ROW_REC_DUR -> {
                recDurIndex = (recDurIndex + dir + REC_DURATIONS.size) % REC_DURATIONS.size
                saveInt("rec_dur", recDurIndex)
            }
            ROW_SNOOZE -> {
                snoozeIndex = (snoozeIndex + dir + SNOOZE_SEC.size) % SNOOZE_SEC.size
                saveInt("snooze", snoozeIndex)
            }
            ROW_REC_DIR -> {
                val n = recDirs().size
                recDirIndex = (recDirIndex + dir + n) % n
                saveInt("rec_dir", recDirIndex)
            }
        }
        refreshRow(i)
        updateAux(i)
        keepUiAlive()
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

    // -------------------------------------------------------------- оформление

    /**
     * Приводит панель Media3 к стилю проекта.
     *
     * Цвета полосы заданы атрибутами внутри библиотечной вёрстки, поэтому
     * меняем их сеттерами DefaultTimeBar — это надёжнее, чем подменять
     * exo_player_control_view.xml своей копией.
     */
    private fun styleControls() {
        playerView.setShowPreviousButton(false)
        playerView.setShowNextButton(false)
        playerView.setShowRewindButton(true)
        playerView.setShowFastForwardButton(true)

        val accent = ContextCompat.getColor(this, R.color.brand_accent)
        val text = ContextCompat.getColor(this, R.color.brand_text)

        playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            // Шаг стрелок по самой полосе — крупный, кнопки остаются мелкими.
            setKeyTimeIncrement(SEEK_BAR_MS)
            setPlayedColor(accent)
            setScrubberColor(accent)
            setBufferedColor(ContextCompat.getColor(this@PlayerActivity, R.color.brand_buffered))
            setUnplayedColor(ContextCompat.getColor(this@PlayerActivity, R.color.brand_unplayed))
        }

        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
            ?.let { tintTree(it, text) }

        playerView.findViewById<ImageView>(androidx.media3.ui.R.id.exo_play_pause)
            ?.setColorFilter(accent)
    }

    private fun tintTree(v: View, color: Int) {
        when (v) {
            is ViewGroup -> for (i in 0 until v.childCount) tintTree(v.getChildAt(i), color)
            is ImageView -> v.setColorFilter(color)
            is TextView -> v.setTextColor(color)
        }
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
        val size = 2 + flatTracks(C.TRACK_TYPE_VIDEO).size
        setQuality((qualityIndex + dir + size) % size)
    }

    private fun setQuality(index: Int) {
        val p = player ?: return
        qualityIndex = index
        val params = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        when (index) {
            0 -> params.setForceHighestSupportedBitrate(true)
            1 -> params.setForceHighestSupportedBitrate(false)
            else -> {
                val list = flatTracks(C.TRACK_TYPE_VIDEO)
                val at = index - 2
                if (at in list.indices) {
                    val (g, i) = list[at]
                    params.setForceHighestSupportedBitrate(false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                }
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

    private fun delayValue(): String {
        val min = REC_DELAYS[recDelayIndex]
        if (min == 0) return getString(R.string.rec_now)
        val at = clockFmt.format(Date(System.currentTimeMillis() + min * 60_000L))
        return getString(R.string.rec_at, at, min)
    }

    private fun onRecordPressed() {
        when {
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
        val at = System.currentTimeMillis() + REC_DELAYS[recDelayIndex] * 60_000L
        recStartAt = at
        recDot.alpha = 0.45f
        recBadge.visibility = View.VISIBLE
        refreshRecBadge()
        Toast.makeText(
            this, getString(R.string.rec_scheduled, clockFmt.format(Date(at))),
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
        recStopAt = if (dur > 0) System.currentTimeMillis() + dur * 60_000L else 0L
        recResumeAt = 0L
        recDot.alpha = 1f
        recBadge.visibility = View.VISIBLE
        leftTop.visibility = View.VISIBLE
        refreshRecBadge()
        syncRecordButtons()
        Toast.makeText(
            this, getString(R.string.rec_started, target.name), Toast.LENGTH_LONG
        ).show()
    }

    private fun toggleRecPause() {
        recorder.isPaused = !recorder.isPaused
        recResumeAt = 0L
        syncRecordButtons()
        refreshRecBadge()
        keepUiAlive()
    }

    /** Пауза на заданное время — чтобы вырезать рекламный блок. */
    private fun snoozeRecording() {
        recorder.isPaused = true
        recResumeAt = System.currentTimeMillis() + SNOOZE_SEC[snoozeIndex] * 1_000L
        syncRecordButtons()
        refreshRecBadge()
        keepUiAlive()
    }

    private fun stopRecording() {
        val written = recorder.bytesWritten
        val target = recorder.stop()
        recStopAt = 0L
        recResumeAt = 0L
        recBadge.visibility = View.GONE
        syncRecordButtons()
        if (target == null) return
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

    /**
     * Одна кнопка «запись» превращается в три: пауза, пауза на время, стоп.
     *
     * Если кнопка скрыта настройкой, но запись уже идёт, органы управления
     * всё равно показываем — иначе её нечем остановить.
     */
    private fun syncRecordButtons() {
        val on = recorder.isRecording
        val allowed = recShowIndex == 0
        btnRecord.visibility = if (!on && allowed) View.VISIBLE else View.GONE
        btnRecPause.visibility = if (on) View.VISIBLE else View.GONE
        btnRecSnooze.visibility = if (on) View.VISIBLE else View.GONE
        btnRecStop.visibility = if (on) View.VISIBLE else View.GONE
        btnRecPause.setImageResource(
            if (recorder.isPaused) R.drawable.ic_record else R.drawable.ic_pause
        )
        btnRecPause.contentDescription = getString(
            if (recorder.isPaused) R.string.rec_resume else R.string.rec_pause
        )
        btnRecSnooze.contentDescription =
            getString(R.string.rec_snooze, fmtMinSec(SNOOZE_SEC[snoozeIndex]))

        // Фокус не должен провалиться на скрытую кнопку.
        if (currentFocus?.visibility == View.GONE) firstActionButton().requestFocus()
    }

    private fun refreshRecBadge() {
        recSize.text = when {
            recStartAt > 0L ->
                getString(R.string.rec_armed, fmtClock(recStartAt - System.currentTimeMillis()))
            recResumeAt > 0L -> getString(
                R.string.rec_snoozed, fmtClock(recResumeAt - System.currentTimeMillis())
            )
            recorder.isPaused -> getString(R.string.rec_paused)
            recStopAt > 0L -> getString(
                R.string.rec_left, fmtSize(recorder.bytesWritten),
                fmtClock(recStopAt - System.currentTimeMillis())
            )
            else -> fmtSize(recorder.bytesWritten)
        }
    }

    // ------------------------------------------------------------ ящик записей

    private val drawerAdapter = object : BaseAdapter() {
        override fun getCount() = recordings.size
        override fun getItem(position: Int) = recordings[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val f = recordings[position]
            val v = convertView
                ?: LayoutInflater.from(this@PlayerActivity)
                    .inflate(R.layout.row_recording, parent, false)

            v.findViewById<TextView>(R.id.rec_title).text = f.name
            v.findViewById<TextView>(R.id.rec_meta).text = getString(
                R.string.lib_rec_meta, fmtSize(f.length()), stampFmt.format(Date(f.lastModified()))
            )

            val thumb = v.findViewById<ImageView>(R.id.rec_thumb)
            val cached = thumbs[f.absolutePath]
            if (cached != null) thumb.setImageBitmap(cached)
            else {
                thumb.setImageResource(R.drawable.ic_video)
                loadThumb(f, thumb)
            }

            v.findViewById<View>(R.id.rec_row).setOnClickListener { playFile(f) }
            v.findViewById<View>(R.id.rec_delete).setOnClickListener { confirmDelete(f) }
            return v
        }
    }

    /** Кадр из записи в фоне: MediaMetadataRetriever блокирует поток. */
    private fun loadThumb(f: File, target: ImageView) {
        val path = f.absolutePath
        if (thumbs.containsKey(path)) return
        thumbs[path] = null
        Thread {
            var bmp: Bitmap? = null
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(path)
                bmp = mmr.getFrameAtTime(3_000_000L)
            } catch (e: Exception) {
                // Битый или зашифрованный файл — оставим иконку.
            } finally {
                try {
                    mmr.release()
                } catch (ignored: Exception) {
                }
            }
            val result = bmp
            ui.post {
                thumbs[path] = result
                if (result != null && target.isAttachedToWindow) {
                    target.setImageBitmap(result)
                }
            }
        }.start()
    }

    private fun toggleDrawer() {
        if (drawer.visibility == View.VISIBLE) {
            drawer.visibility = View.GONE
            pinController(false)
            btnLibrary.requestFocus()
        } else {
            sideScroll.visibility = View.GONE
            reloadRecordings()
            drawer.visibility = View.VISIBLE
            pinController(true)
            focusFirstRecording()
        }
    }

    /**
     * Наводит фокус на первую запись.
     *
     * Сразу после показа ящика рядов ещё нет — ListView их не разложил,
     * поэтому ждём кадр отрисовки и при необходимости пробуем ещё раз.
     */
    private fun focusFirstRecording(attempt: Int = 0) {
        if (recordings.isEmpty()) {
            btnLibrary.requestFocus()
            return
        }
        drawerList.post {
            if (drawer.visibility != View.VISIBLE) return@post
            drawerList.setSelection(0)
            val row = drawerList.getChildAt(0)?.findViewById<View>(R.id.rec_row)
            when {
                row != null -> row.requestFocus()
                attempt < 3 -> focusFirstRecording(attempt + 1)
                else -> drawerList.requestFocus()
            }
        }
    }

    private fun reloadRecordings() {
        recordings.clear()
        // Новые сверху — так просил владелец.
        recordings.addAll(
            recDirs()
                .flatMap { it.listFiles()?.toList() ?: emptyList() }
                .filter { it.isFile && it.name.endsWith(".ts", ignoreCase = true) }
                .sortedByDescending { it.lastModified() }
        )
        if (drawerList.adapter == null) drawerList.adapter = drawerAdapter
        drawerAdapter.notifyDataSetChanged()
        drawerEmpty.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun playFile(f: File) {
        drawer.visibility = View.GONE
        startPlayback(Uri.fromFile(f))
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.del_title)
            .setMessage(getString(R.string.del_message, f.name, fmtSize(f.length())))
            .setNegativeButton(R.string.del_cancel, null)
            .setPositiveButton(R.string.del_ok) { _, _ ->
                if (f.delete()) {
                    thumbs.remove(f.absolutePath)
                    Toast.makeText(this, R.string.del_done, Toast.LENGTH_SHORT).show()
                    reloadRecordings()
                    focusFirstRecording()
                } else {
                    Toast.makeText(this, R.string.del_failed, Toast.LENGTH_LONG).show()
                }
            }
            .setOnDismissListener { goFullscreen() }
            .show()
    }

    // ----------------------------------------------------------------- формат

    private fun fmtClock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    private fun fmtMinSec(sec: Int): String =
        if (sec % 60 == 0) getString(R.string.val_minutes, sec / 60)
        else String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)

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

        // BACK закрывает открытое, а не выходит из плеера.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            when {
                shiftCapture -> { setShiftCapture(false); return true }
                popup.visibility == View.VISIBLE -> {
                    popup.visibility = View.GONE
                    popup.removeAllViews()
                    pinController(false)
                    btnShift.requestFocus()
                    return true
                }
                drawer.visibility == View.VISIBLE -> { toggleDrawer(); return true }
                sideScroll.visibility == View.VISIBLE -> { toggleSidePanel(); return true }
                playerView.isControllerFullyVisible -> {
                    playerView.hideController(); return true
                }
            }
        }

        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_SETTINGS) {
            showUi(focus = true)
            return true
        }

        // Панель скрыта — первое нажатие ТОЛЬКО поднимает её и никуда не
        // проваливается. Иначе тот же OK долетал до кнопки записи, на
        // которую фокус встал секунду назад, и запись стартовала сразу.
        if (!playerView.isControllerFullyVisible) {
            showUi(focus = true)
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> return true
            }
        } else {
            keepUiAlive()
        }
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
