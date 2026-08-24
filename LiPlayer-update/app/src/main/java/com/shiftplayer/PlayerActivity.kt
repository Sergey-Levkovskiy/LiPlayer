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
import android.view.Gravity
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
import androidx.media3.ui.TimeBar
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
 * PlayerView переставляется внутри чёрного контейнера с clipChildren=true:
 * меняется его положение в разметке, а не трансформация. Пиксели не
 * пересчитываются — кадр просто рисуется в другом месте экрана.
 *
 * Управление собрано в левом столбце: настройки, запись, список записей,
 * сдвиг, часы, транспорт. Меню раскрываются вправо от столбца и встают на
 * уровень своего пункта. Навигация — штатным фокусом Android, а не ручным
 * разбором кнопок: так пульт ведёт себя предсказуемо.
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
         * Пока открыт список или настройки, панель живёт дольше — но всё
         * равно уезжает сама, как в любом проигрывателе.
         */
        const val PANEL_TIMEOUT_MS = 14_000L

        /** Сколько раз пробуем поднять оборвавшийся поток. */
        const val MAX_RETRIES = 5

        /** Позиция пишется на диск каждые N тиков по секунде. */
        const val SAVE_EVERY_TICKS = 5

        /**
         * Диагональ панели, для которой «Полный» размер = 100 %.
         *
         * Меняется одной цифрой, если приложение поедет на другой телевизор.
         */
        const val BASE_DIAGONAL_IN = 98

        /** Варианты уменьшения картинки, дюймы по диагонали. */
        val SCREEN_IN = intArrayOf(0, 85, 75, 65, 55)

        /** Куда прижимать уменьшенную картинку. */
        val POSITIONS = intArrayOf(
            Gravity.CENTER,
            Gravity.TOP or Gravity.LEFT,
            Gravity.TOP or Gravity.RIGHT,
            Gravity.BOTTOM or Gravity.LEFT,
            Gravity.BOTTOM or Gravity.RIGHT
        )

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
        const val ROW_POSITION = 6
        const val ROW_SHIFT_MANUAL = 7
        const val ROW_CLOCK = 8
        const val ROW_CLOCK_DIM = 9
        const val ROW_REC_SHOW = 10
        const val ROW_REC_DELAY = 11
        const val ROW_REC_DUR = 12
        const val ROW_SNOOZE = 13
        const val ROW_REC_DIR = 14
        const val ROW_LIBRARY = 15
        const val ROW_COUNT = 16
    }

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var bottomBar: LinearLayout
    private lateinit var scrim: View
    private lateinit var timeBar: DefaultTimeBar
    private lateinit var timeText: TextView
    private lateinit var clock: TextView
    private lateinit var hud: TextView
    private lateinit var drawer: LinearLayout
    private lateinit var drawerList: ListView
    private lateinit var drawerEmpty: TextView
    private lateinit var popup: LinearLayout
    private lateinit var menuCats: LinearLayout
    private lateinit var menuDetail: LinearLayout
    private lateinit var recBadge: LinearLayout
    private lateinit var recDot: ImageView
    private lateinit var recSize: TextView

    private lateinit var btnSettings: ImageButton
    private lateinit var btnRecord: ImageButton
    private lateinit var btnRecPause: ImageButton
    private lateinit var btnRecSnooze: ImageButton
    private lateinit var btnRecStop: ImageButton
    private lateinit var btnShift: ImageButton
    private lateinit var btnRew: ImageButton
    private lateinit var btnPlay: ImageButton
    private lateinit var btnFfwd: ImageButton

    /** Подписи рядов открытой категории: id ряда -> TextView. */
    private val detailLabels = HashMap<Int, TextView>()

    /** Индекс открытой категории, -1 — второй уровень закрыт. */
    private var catIndex = -1

    private var barVisible = false

    /** Пока тянут полосу, позицию из плеера не подставляем. */
    private var scrubbing = false

    private var player: ExoPlayer? = null
    private var prefs: SharedPreferences? = null
    private var currentUri: Uri? = null

    private var shiftPx = 0f
    private var safeMarginPx = 0f

    /** Последняя известная геометрия кадра — для пересчёта после смены размера. */
    private var lastVideo: VideoSize? = null
    private var aspectKey = "shift_default"

    /** 0 центр, 1 верхний край, 2 нижний край, 3 вручную. */
    private var shiftMode = 0

    private var speedIndex = 2
    private var aspectIndex = 0
    private var qualityIndex = 0
    private var audioIndex = 0
    private var subsIndex = 0
    private var screenIndex = 0
    private var posIndex = 0
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

    private var retries = 0
    private var tickCount = 0

    private var recStartAt = 0L
    private var recStopAt = 0L
    private var recResumeAt = 0L

    private val ui = Handler(Looper.getMainLooper())
    private val hideHud = Runnable { hud.visibility = View.GONE }
    private val hideBar = Runnable { setBarVisible(false) }

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
            if (barVisible) updateProgress()

            // Пишем позицию на ходу: onStop не вызывается ни при обрыве
            // потока, ни когда систему убивает приложение.
            if (++tickCount % SAVE_EVERY_TICKS == 0 && player?.isPlaying == true) {
                savePosition()
            }

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


        wireActions()
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
        scrim = findViewById(R.id.scrim)
        bottomBar = findViewById(R.id.bottom_bar)
        timeBar = findViewById(R.id.time_bar)
        timeText = findViewById(R.id.time_text)
        clock = findViewById(R.id.clock)
        hud = findViewById(R.id.hud)
        drawer = findViewById(R.id.drawer)
        drawerList = findViewById(R.id.drawer_list)
        drawerList.itemsCanFocus = true
        drawerEmpty = findViewById(R.id.drawer_empty)
        popup = findViewById(R.id.popup)
        menuCats = findViewById(R.id.menu_cats)
        menuDetail = findViewById(R.id.menu_detail)
        recBadge = findViewById(R.id.rec_badge)
        recDot = findViewById(R.id.rec_dot)
        recSize = findViewById(R.id.rec_size)

        btnSettings = findViewById(R.id.btn_settings)
        btnRecord = findViewById(R.id.btn_record)
        btnRecPause = findViewById(R.id.btn_rec_pause)
        btnRecSnooze = findViewById(R.id.btn_rec_snooze)
        btnRecStop = findViewById(R.id.btn_rec_stop)
        btnShift = findViewById(R.id.btn_shift)
        btnRew = findViewById(R.id.btn_rew)
        btnPlay = findViewById(R.id.btn_play)
        btnFfwd = findViewById(R.id.btn_ffwd)
    }

    // ---------------------------------------------------------------- настройки

    private fun restoreSettings() {
        val p = prefs ?: return
        shiftMode = p.getInt("shift_mode", 0)
        speedIndex = p.getInt("speed", 2).coerceIn(0, SPEEDS.size - 1)
        aspectIndex = p.getInt("aspect", 0).coerceIn(0, ASPECTS.size - 1)
        screenIndex = p.getInt("screen", 0).coerceIn(0, SCREEN_IN.size - 1)
        posIndex = p.getInt("screen_pos", 0).coerceIn(0, POSITIONS.size - 1)
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
                if (menuDetail.visibility == View.VISIBLE) refreshAllRows()
            }

            override fun onPlaybackStateChanged(state: Int) {
                // Поток поднялся — счётчик попыток обнуляем.
                if (state == Player.STATE_READY) retries = 0
                syncPlayButton()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = syncPlayButton()

            override fun onPlayerError(error: PlaybackException) {
                // Сначала фиксируем позицию, потом пробуем поднять поток.
                savePosition()
                scheduleRetry(error)
            }
        })

        exo.setMediaItem(buildMediaItem(uri))
        exo.prepare()

        // Позиция от вызывающего важнее нашей: Лампа знает, где человек
        // остановился, даже если наш процесс успели убить.
        val fromCaller = intentStartPosition()
        val saved = prefs?.getLong(posKey(uri), 0L) ?: 0L
        val startAt = if (fromCaller > 0L) fromCaller else saved
        if (startAt > 10_000L) {
            exo.seekTo(startAt)
            Toast.makeText(
                this, getString(R.string.resumed_at, fmtPosition(startAt)), Toast.LENGTH_SHORT
            ).show()
        }

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

    /**
     * Устойчивый ключ позиции.
     *
     * Полный URL как ключ не годится: TorrServe и подобные меняют порт,
     * номер сессии и порядок параметров между запусками, поэтому позиция
     * терялась, хотя была сохранена. Берём то, что не меняется — хеш
     * раздачи с номером файла, иначе имя файла.
     */
    /**
     * Позиция, которую передал вызывающий — Лампа, TorrServe, файловый
     * менеджер. Контракт тот же, что у MX Player: extras «position» в
     * миллисекундах. Тип у разных клиентов плавает, поэтому берём как есть.
     */
    private fun intentStartPosition(): Long {
        val extras = intent?.extras ?: return -1L
        for (key in arrayOf("position", "start_position", "extra_position")) {
            val ms = when (val v = extras.get(key)) {
                is Int -> v.toLong()
                is Long -> v
                is Float -> v.toLong()
                is Double -> v.toLong()
                else -> null
            }
            if (ms != null && ms > 0L) return ms
        }
        return -1L
    }

    /**
     * Отдаём позицию обратно вызывающему, чтобы Лампа обновила свой
     * таймлайн. Без этого она помнит только то, что видела до запуска плеера.
     */
    private fun publishResult() {
        val p = player ?: return
        setResult(
            RESULT_OK,
            Intent()
                .putExtra("position", p.currentPosition.toInt())
                .putExtra("duration", if (p.duration > 0) p.duration.toInt() else 0)
                .putExtra("end_by", "user")
        )
    }

    private fun posKey(uri: Uri): String {
        val id = try {
            val hash = uri.getQueryParameter("link") ?: uri.getQueryParameter("hash")
            val index = uri.getQueryParameter("index") ?: ""
            val name = uri.lastPathSegment ?: ""
            when {
                !hash.isNullOrEmpty() -> "$hash#$index"
                name.isNotEmpty() -> name
                else -> uri.toString()
            }
        } catch (e: UnsupportedOperationException) {
            // Непрозрачный URI — параметров у него нет.
            uri.toString()
        }
        return "pos_" + id.hashCode()
    }

    private fun savePosition() {
        val p = player ?: return
        val uri = currentUri ?: return
        publishResult()
        if (p.duration > 0 && p.currentPosition < p.duration - 15_000L) {
            prefs?.edit()?.putLong(posKey(uri), p.currentPosition)?.apply()
        } else {
            prefs?.edit()?.remove(posKey(uri))?.apply()
        }
    }

    /**
     * Обрыв потока — не повод терять место. Переподключаемся с растущей
     * задержкой и возвращаемся туда же, где остановились.
     */
    private fun scheduleRetry(error: PlaybackException) {
        if (retries >= MAX_RETRIES) {
            Toast.makeText(
                this, getString(R.string.err_gave_up, error.errorCodeName),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (retries == 0) {
            Toast.makeText(this, R.string.err_retry, Toast.LENGTH_SHORT).show()
        }
        val delay = 2_000L shl retries
        retries++
        ui.removeCallbacks(retryPlayback)
        ui.postDelayed(retryPlayback, delay)
    }

    private val retryPlayback = Runnable {
        val p = player ?: return@Runnable
        val uri = currentUri ?: return@Runnable
        val saved = prefs?.getLong(posKey(uri), 0L) ?: 0L
        p.prepare()
        if (saved > 10_000L) p.seekTo(saved)
        p.playWhenReady = true
    }

    private fun releasePlayer() {
        if (recorder.isRecording) stopRecording()
        ui.removeCallbacks(retryPlayback)
        retries = 0
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
        lastVideo = videoSize
        root.post { recomputeGeometry() }
    }

    private fun recomputeGeometry() {
        val videoSize = lastVideo ?: return
        val viewW = playerView.width.toFloat()
        val viewH = playerView.height.toFloat()
        if (viewW <= 0f || viewH <= 0f || videoSize.height == 0) return

        val aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        if (aspect <= 0f) return

        val shownH = if (viewW / viewH > aspect) viewH else viewW / aspect
        safeMarginPx = ((viewH - shownH) / 2f).coerceAtLeast(0f)
        aspectKey = "shift_" + (aspect * 100).roundToInt()

        shiftPx = when (shiftMode) {
            1 -> -edgeShift()
            2 -> edgeShift()
            // Если под новым ключом соотношения записи ещё нет, держим
            // текущее значение: иначе ручной сдвиг обнулялся сам.
            3 -> prefs?.getFloat(aspectKey, shiftPx) ?: shiftPx
            else -> 0f
        }
        applyShift(showHud = false)
    }

    // ------------------------------------------------------------------- shift

    private fun applyShift(showHud: Boolean) {
        val viewH = (if (playerView.height > 0) playerView.height else root.height).toFloat()
        // Ручному режиму даём весь ход: именно так выносят за экран полосы,
        // впечатанные в кадр. Авто-значения и так малы — они от полос.
        val limit = if (shiftMode == 3) viewH else viewH / 2f
        shiftPx = shiftPx.coerceIn(-limit, limit)
        applyGeometry()
        if (shiftMode == 3) prefs?.edit()?.putFloat(aspectKey, shiftPx)?.apply()
        saveInt("shift_mode", shiftMode)
        if (showHud) showHud()
    }

    /**
     * Размер, положение и сдвиг одной операцией над layoutParams.
     *
     * Сдвиг делается ОТСТУПОМ, а не translationY: внутри PlayerView лежит
     * SurfaceView, а он живёт на аппаратной поверхности и трансформации
     * вида не слушается — двигалось всё, кроме самого видео. Изменение
     * положения в разметке поверхность переносит по-настоящему.
     */
    private fun applyGeometry() {
        val w = root.width
        val h = root.height
        if (w <= 0 || h <= 0) return

        val inches = SCREEN_IN[screenIndex]
        val scale = if (inches == 0) 1f else inches.toFloat() / BASE_DIAGONAL_IN

        val lp = playerView.layoutParams as FrameLayout.LayoutParams
        lp.width = (w * scale).roundToInt()
        lp.height = (h * scale).roundToInt()
        lp.gravity = if (inches == 0) Gravity.CENTER else POSITIONS[posIndex]

        // У нижней гравитации отступ сверху не действует — считаем от низа.
        val shift = shiftPx.roundToInt()
        if (lp.gravity and Gravity.BOTTOM == Gravity.BOTTOM) {
            lp.topMargin = 0
            lp.bottomMargin = -shift
        } else {
            lp.topMargin = shift
            lp.bottomMargin = 0
        }
        playerView.layoutParams = lp

        // Остатки прежнего способа сдвига, иначе сложились бы дважды.
        playerView.translationY = 0f
    }

    /**
     * Предел авто-сдвига: только по аппаратным полосам.
     *
     * Если полосы впечатаны в кадр, для плеера это обычное 16:9 видео и
     * считать нечего — авто честно вернёт ноль. Такие полосы убираются
     * ручным сдвигом, который двигает кадр целиком.
     */
    private fun edgeShift() = (safeMarginPx - EDGE_GUARD_PX).coerceAtLeast(0f)

    /** Есть ли что прятать авто-сдвигом. */
    private fun hasHardwareBars() = safeMarginPx > EDGE_GUARD_PX + 1f

    private fun setShiftMode(mode: Int) {
        if (mode == 1 || mode == 2) {
            if (!hasHardwareBars()) {
                Toast.makeText(this, R.string.shift_no_bars, Toast.LENGTH_LONG).show()
            }
        }
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

    /**
     * Уменьшение картинки под меньшую диагональ.
     *
     * Меняем РАЗМЕР вьюхи, а не scaleX/scaleY. При масштабировании кадр
     * сначала растянулся бы до полного экрана, а потом сжался — двойная
     * переоцифровка. С меньшей вьюхой поверхность создаётся сразу нужного
     * размера и декодированный кадр приводится к нему одним проходом.
     *
     * Побочный выигрыш: на 65″ поток 1280×720 ложится почти пиксель в
     * пиксель (1920 × 65/98 = 1273), то есть без домысливания вовсе.
     */
    private fun applyScreenSize() {
        root.post {
            applyGeometry()
            // Полоса и предел сдвига считаются от вьюхи, а не от экрана,
            // поэтому пересчёт — только после того, как вьюху разложили.
            playerView.post { recomputeGeometry() }
        }
    }

    // ------------------------------------------------------------ нижняя панель

    private fun wireActions() {
        btnSettings.setOnClickListener { toggleMenu() }
        btnRecord.setOnClickListener { onRecordPressed() }
        btnRecPause.setOnClickListener { toggleRecPause() }
        btnRecSnooze.setOnClickListener { snoozeRecording() }
        btnRecStop.setOnClickListener { stopRecording() }
        btnShift.setOnClickListener { openShiftPopup() }

        btnRew.setOnClickListener { player?.seekBack(); keepUiAlive() }
        btnFfwd.setOnClickListener { player?.seekForward(); keepUiAlive() }
        btnPlay.setOnClickListener {
            val p = player ?: return@setOnClickListener
            p.playWhenReady = !p.isPlaying
            syncPlayButton()
            keepUiAlive()
        }

        // Своя панель — значит и полосу прокрутки ведём сами.
        timeBar.setKeyTimeIncrement(SEEK_BAR_MS)
        timeBar.setPlayedColor(ContextCompat.getColor(this, R.color.brand_accent))
        timeBar.setScrubberColor(ContextCompat.getColor(this, R.color.brand_accent))
        timeBar.setBufferedColor(ContextCompat.getColor(this, R.color.brand_buffered))
        timeBar.setUnplayedColor(ContextCompat.getColor(this, R.color.brand_unplayed))
        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(bar: TimeBar, position: Long) {
                scrubbing = true
                keepUiAlive()
            }

            override fun onScrubMove(bar: TimeBar, position: Long) {
                updateTimeText(position)
                keepUiAlive()
            }

            override fun onScrubStop(bar: TimeBar, position: Long, canceled: Boolean) {
                scrubbing = false
                if (!canceled) player?.seekTo(position)
                keepUiAlive()
            }
        })

        syncRecordButtons()
        syncPlayButton()
    }

    private fun syncPlayButton() {
        btnPlay.setImageResource(
            if (player?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play
        )
        updateScrim()
    }

    /** Полоса и время: вызывается раз в секунду. */
    private fun updateProgress() {
        val p = player ?: return
        val duration = if (p.duration > 0) p.duration else 0L
        timeBar.setDuration(duration)
        timeBar.setBufferedPosition(p.bufferedPosition)
        if (!scrubbing) {
            timeBar.setPosition(p.currentPosition)
            updateTimeText(p.currentPosition)
        }
    }

    /** Прошло и осталось: у прямого эфира длительности нет, покажем только прошло. */
    private fun updateTimeText(position: Long) {
        val p = player
        val duration = if (p != null && p.duration > 0) p.duration else 0L
        timeText.text = if (duration > 0) {
            getString(
                R.string.time_pair, fmtPosition(position), fmtPosition(duration - position)
            )
        } else {
            fmtPosition(position)
        }
    }

    // ------------------------------------------------------- показ и скрытие

    private fun setBarVisible(visible: Boolean) {
        barVisible = visible
        bottomBar.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) closePanels()
        updateScrim()
        ui.removeCallbacks(hideBar)
        if (visible) ui.postDelayed(hideBar, currentTimeout())
    }

    /** Пока открыто меню, панель живёт дольше — но всё равно уезжает сама. */
    private fun currentTimeout(): Long =
        if (menuCats.visibility == View.VISIBLE || drawer.visibility == View.VISIBLE ||
            popup.visibility == View.VISIBLE
        ) PANEL_TIMEOUT_MS else UI_TIMEOUT_MS

    private fun keepUiAlive() {
        if (!barVisible) {
            setBarVisible(true)
            return
        }
        ui.removeCallbacks(hideBar)
        ui.postDelayed(hideBar, currentTimeout())
    }

    /** Затемнение 5 %: на паузе и когда открыто меню. */
    private fun updateScrim() {
        val paused = player?.isPlaying == false
        scrim.visibility = if (barVisible || paused) View.VISIBLE else View.GONE
    }

    private fun closePanels() {
        popup.visibility = View.GONE
        popup.removeAllViews()
        drawer.visibility = View.GONE
        closeMenu()
    }

    /**
     * Куда встать фокусом, когда панель поднимают с пульта.
     *
     * OK — на пуск, «вниз» — на полосу прокрутки, «вверх» — на настройки.
     */
    private fun focusInitial(keyCode: Int) {
        bottomBar.post {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> timeBar.requestFocus()
                KeyEvent.KEYCODE_DPAD_UP -> btnSettings.requestFocus()
                else -> btnPlay.requestFocus()
            }
        }
    }

    // ------------------------------------------------------- расстановка панелей

    private val gapPx: Int
        get() = (8 * resources.displayMetrics.density).roundToInt()

    /** X вида относительно корня. */
    private fun leftInRoot(v: View): Int {
        var x = 0
        var cur: View? = v
        while (cur != null && cur !== root) {
            x += cur.left
            cur = cur.parent as? View
        }
        return x
    }

    /**
     * Меню растут вверх от панели: категории над ней, настройки над
     * категориями. По горизонтали каждое привязано к своей кнопке — панель
     * настроек встаёт над выбранной категорией, а не по центру экрана.
     */
    private fun placePanels() {
        root.post {
            val barH = bottomBar.height
            if (menuCats.visibility == View.VISIBLE) {
                menuCats.translationY = -(barH + gapPx).toFloat()
                menuCats.translationX = leftInRoot(btnSettings).toFloat()
            }
            if (menuDetail.visibility == View.VISIBLE) {
                menuDetail.translationY = -(barH + gapPx + menuCats.height + gapPx).toFloat()
                val anchor = menuCats.getChildAt(catIndex)
                val x = if (anchor != null) leftInRoot(anchor) else leftInRoot(btnSettings)
                // Не даём уехать за правый край экрана.
                val maxX = (root.width - menuDetail.width - gapPx).coerceAtLeast(0)
                menuDetail.translationX = x.coerceAtMost(maxX).toFloat()
            }
            if (popup.visibility == View.VISIBLE) {
                popup.translationY = -(barH + gapPx).toFloat()
                popup.translationX = leftInRoot(btnShift).toFloat()
            }
        }
    }

    // ------------------------------------------------------- всплывашка сдвига

    private fun openShiftPopup() {
        closeMenu()
        drawer.visibility = View.GONE
        popup.removeAllViews()
        listOf(
            popupRow(R.drawable.ic_arrow_up, getString(R.string.shift_top)) { setShiftMode(1) },
            popupRow(R.drawable.ic_arrow_center, getString(R.string.shift_center)) { setShiftMode(0) },
            popupRow(R.drawable.ic_arrow_down, getString(R.string.shift_bottom)) { setShiftMode(2) }
        ).forEach { popup.addView(it) }
        popup.visibility = View.VISIBLE
        keepUiAlive()
        placePanels()
        popup.getChildAt(0)?.requestFocus()
    }

    private fun popupRow(iconRes: Int, label: String, action: () -> Unit): View {
        val row = LayoutInflater.from(this).inflate(R.layout.row_control, popup, false)
        row.findViewById<ImageView>(R.id.row_icon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.row_label).text = label
        row.setOnClickListener {
            action()
            popup.visibility = View.GONE
            popup.removeAllViews()
            btnShift.requestFocus()
            keepUiAlive()
        }
        return row
    }

    // ----------------------------------------------------------- меню настроек

    private class Category(val titleRes: Int, val iconRes: Int, val rows: IntArray)

    private val categories = listOf(
        Category(
            R.string.cat_video, R.drawable.ic_film,
            intArrayOf(ROW_QUALITY, ROW_AUDIO, ROW_SUBS, ROW_SPEED)
        ),
        Category(
            R.string.cat_screen, R.drawable.ic_screen,
            intArrayOf(ROW_SCREEN, ROW_POSITION, ROW_ASPECT, ROW_SHIFT_MANUAL)
        ),
        Category(
            R.string.cat_record, R.drawable.ic_record,
            intArrayOf(
                ROW_LIBRARY, ROW_REC_SHOW, ROW_REC_DELAY,
                ROW_REC_DUR, ROW_SNOOZE, ROW_REC_DIR
            )
        ),
        Category(
            R.string.cat_clock, R.drawable.ic_clock,
            intArrayOf(ROW_CLOCK, ROW_CLOCK_DIM)
        )
    )

    private fun toggleMenu() {
        if (menuCats.visibility == View.VISIBLE) {
            closeMenu()
            btnSettings.requestFocus()
            keepUiAlive()
            return
        }
        popup.visibility = View.GONE
        drawer.visibility = View.GONE
        buildCategories()
        menuCats.visibility = View.VISIBLE
        keepUiAlive()
        placePanels()
        menuCats.getChildAt(0)?.requestFocus()
    }

    private fun closeMenu() {
        menuCats.visibility = View.GONE
        menuCats.removeAllViews()
        closeDetail()
    }

    private fun closeDetail() {
        menuDetail.visibility = View.GONE
        menuDetail.removeAllViews()
        detailLabels.clear()
        catIndex = -1
        shiftCapture = false
    }

    /** Категории — только иконки, выбранная подсвечена. */
    private fun buildCategories() {
        menuCats.removeAllViews()
        categories.forEachIndexed { index, cat ->
            val btn = ImageButton(this)
            val side = (46 * resources.displayMetrics.density).roundToInt()
            btn.layoutParams = LinearLayout.LayoutParams(side, side)
            btn.setBackgroundResource(R.drawable.btn_focus)
            btn.setPadding(gapPx, gapPx, gapPx, gapPx)
            btn.scaleType = ImageView.ScaleType.FIT_CENTER
            btn.setImageResource(cat.iconRes)
            btn.contentDescription = getString(cat.titleRes)
            btn.isFocusable = true
            btn.setOnClickListener { openCategory(index) }
            btn.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && catIndex >= 0 && catIndex != index) openCategory(index)
            }
            btn.setOnKeyListener { _, code, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                // Настройки категории лежат выше — туда и ведёт «вверх».
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    if (catIndex != index) openCategory(index)
                    else menuDetail.getChildAt(0)?.requestFocus()
                    true
                } else false
            }
            menuCats.addView(btn)
        }
        highlightCategory()
    }

    private fun highlightCategory() {
        for (i in 0 until menuCats.childCount) {
            val btn = menuCats.getChildAt(i) as? ImageButton ?: continue
            btn.setColorFilter(
                if (i == catIndex) ContextCompat.getColor(this, R.color.brand_accent)
                else ContextCompat.getColor(this, R.color.brand_text)
            )
        }
    }

    private fun openCategory(index: Int) {
        catIndex = index
        shiftCapture = false
        highlightCategory()
        menuDetail.removeAllViews()
        detailLabels.clear()

        val inflater = LayoutInflater.from(this)
        categories[index].rows.forEach { rowId ->
            val row = inflater.inflate(R.layout.row_control, menuDetail, false)
            row.findViewById<ImageView>(R.id.row_icon).setImageResource(rowIcon(rowId))
            detailLabels[rowId] = row.findViewById(R.id.row_label)

            row.setOnKeyListener { _, code, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val fast = event.repeatCount > 4

                if (rowId == ROW_SHIFT_MANUAL && shiftCapture) {
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
                    KeyEvent.KEYCODE_DPAD_LEFT -> { stepRow(rowId, -1, fast); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { stepRow(rowId, 1, fast); true }
                    else -> false
                }
            }
            row.setOnClickListener {
                when (rowId) {
                    ROW_LIBRARY -> toggleDrawer()
                    ROW_SHIFT_MANUAL -> setShiftCapture(!shiftCapture)
                    else -> stepRow(rowId, 1, false)
                }
            }
            menuDetail.addView(row)
            refreshRow(rowId)
        }

        menuDetail.visibility = View.VISIBLE
        placePanels()
        keepUiAlive()
    }

    private fun setShiftCapture(on: Boolean) {
        shiftCapture = on
        if (on) shiftMode = 3
        refreshRow(ROW_SHIFT_MANUAL)
    }

    private fun nudgeManual(dir: Int, fast: Boolean) {
        shiftMode = 3
        shiftPx += dir * (if (fast) 24f else 4f)
        applyShift(showHud = false)
        refreshRow(ROW_SHIFT_MANUAL)
        keepUiAlive()
    }

    private fun refreshAllRows() {
        detailLabels.keys.toList().forEach { refreshRow(it) }
    }

    private fun refreshRow(id: Int) {
        val label = detailLabels[id] ?: return
        label.text = getString(R.string.row_label, rowTitle(id), rowValue(id))
    }

    private fun rowIcon(id: Int): Int = when (id) {
        ROW_QUALITY -> R.drawable.ic_quality
        ROW_AUDIO -> R.drawable.ic_audio
        ROW_SUBS -> R.drawable.ic_subs
        ROW_SPEED -> R.drawable.ic_speed
        ROW_SCREEN -> R.drawable.ic_screen
        ROW_POSITION -> R.drawable.ic_position
        ROW_ASPECT -> R.drawable.ic_aspect
        ROW_SHIFT_MANUAL -> R.drawable.ic_shift
        ROW_CLOCK -> R.drawable.ic_clock
        ROW_CLOCK_DIM -> R.drawable.ic_clock_dim
        ROW_LIBRARY -> R.drawable.ic_list
        ROW_REC_SHOW -> R.drawable.ic_record
        ROW_REC_DELAY -> R.drawable.ic_timer
        ROW_REC_DUR -> R.drawable.ic_duration
        ROW_SNOOZE -> R.drawable.ic_pause_timed
        else -> R.drawable.ic_folder
    }

    private fun rowTitle(i: Int): String = getString(
        when (i) {
            ROW_QUALITY -> R.string.ctl_quality
            ROW_AUDIO -> R.string.ctl_audio
            ROW_SUBS -> R.string.ctl_subs
            ROW_SPEED -> R.string.ctl_speed
            ROW_ASPECT -> R.string.ctl_aspect
            ROW_SCREEN -> R.string.ctl_screen
            ROW_POSITION -> R.string.ctl_position
            ROW_SHIFT_MANUAL -> R.string.shift_manual
            ROW_CLOCK -> R.string.ctl_clock
            ROW_CLOCK_DIM -> R.string.ctl_clock_dim
            ROW_LIBRARY -> R.string.ctl_library
            ROW_REC_SHOW -> R.string.ctl_rec_show
            ROW_REC_DELAY -> R.string.ctl_rec_delay
            ROW_REC_DUR -> R.string.ctl_rec_dur
            ROW_SNOOZE -> R.string.ctl_snooze
            else -> R.string.ctl_rec_dir
        }
    )

    private fun positionLabel(index: Int): String = getString(
        when (index) {
            1 -> R.string.pos_top_left
            2 -> R.string.pos_top_right
            3 -> R.string.pos_bottom_left
            4 -> R.string.pos_bottom_right
            else -> R.string.pos_center
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
        ROW_POSITION -> if (SCREEN_IN[screenIndex] == 0) getString(R.string.pos_na)
        else positionLabel(posIndex)
        ROW_SHIFT_MANUAL -> if (shiftCapture)
            getString(R.string.shift_manual_on, shiftPx.roundToInt())
        else getString(R.string.val_px, shiftPx.roundToInt())
        ROW_CLOCK -> when (clockSize) {
            0 -> getString(R.string.val_off)
            1 -> getString(R.string.clock_small)
            2 -> getString(R.string.clock_medium)
            else -> getString(R.string.clock_large)
        }
        ROW_CLOCK_DIM -> getString(
            R.string.val_percent, (CLOCK_ALPHA[clockDim] * 100).roundToInt()
        )
        ROW_LIBRARY -> getString(R.string.lib_open)
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
                refreshRow(ROW_POSITION)
            }
            ROW_POSITION -> {
                posIndex = (posIndex + dir + POSITIONS.size) % POSITIONS.size
                saveInt("screen_pos", posIndex)
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
        if (currentFocus?.visibility == View.GONE) {
            (if (on) btnRecPause else btnPlay).requestFocus()
        }
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
            keepUiAlive()
            btnSettings.requestFocus()
        } else {
            closeMenu()
            popup.visibility = View.GONE
            reloadRecordings()
            drawer.visibility = View.VISIBLE
            keepUiAlive()
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
            btnSettings.requestFocus()
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

    private fun fmtPosition(ms: Long): String {
        val total = ms / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

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

        // BACK закрывает открытое по одному уровню, а не выходит из плеера.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            when {
                shiftCapture -> { setShiftCapture(false); return true }
                popup.visibility == View.VISIBLE -> {
                    popup.visibility = View.GONE
                    popup.removeAllViews()
                    btnShift.requestFocus()
                    return true
                }
                drawer.visibility == View.VISIBLE -> { toggleDrawer(); return true }
                menuDetail.visibility == View.VISIBLE -> {
                    closeDetail()
                    highlightCategory()
                    menuCats.getChildAt(0)?.requestFocus()
                    return true
                }
                menuCats.visibility == View.VISIBLE -> { toggleMenu(); return true }
                barVisible -> { setBarVisible(false); return true }
            }
        }

        if (event.keyCode == KeyEvent.KEYCODE_MENU ||
            event.keyCode == KeyEvent.KEYCODE_SETTINGS
        ) {
            keepUiAlive()
            toggleMenu()
            return true
        }

        // Панель скрыта — первое нажатие ТОЛЬКО поднимает её и никуда не
        // проваливается. Иначе тот же OK долетал до кнопки под фокусом.
        if (!barVisible) {
            setBarVisible(true)
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    focusInitial(event.keyCode)
                    return true
                }
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
