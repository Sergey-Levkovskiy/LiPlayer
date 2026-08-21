package com.shiftplayer

import android.content.Intent
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Плеер с вертикальным сдвигом изображения БЕЗ масштабирования.
 *
 * Принцип: PlayerView лежит внутри чёрного FrameLayout с clipChildren=true.
 * Мы двигаем сам PlayerView через translationY — пиксели видео не
 * пересчитываются, картинка просто рисуется в другом месте экрана.
 * Всё, что уехало за край, обрезается контейнером.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var hud: TextView

    private var player: ExoPlayer? = null
    private var prefs: SharedPreferences? = null

    /** Текущий сдвиг в пикселях. Положительный — вниз, отрицательный — вверх. */
    private var shiftPx = 0f

    /** Половина чёрной полосы: максимум сдвига без обрезки картинки. */
    private var safeMarginPx = 0f

    /** Ключ настроек для текущего соотношения сторон (у 2.35:1 и 16:9 свой сдвиг). */
    private var aspectKey = "shift_default"

    private val hudHandler = Handler(Looper.getMainLooper())
    private val hideHud = Runnable { hud.visibility = View.GONE }

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            finish()
        } else {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            startPlayback(uri)
        }
    }

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

        // Свой D-pad, штатный контроллер Media3 отключён — иначе стрелки
        // начали бы бегать по кнопкам перемотки вместо сдвига картинки.
        playerView.useController = false
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

        val uri = intent?.data
        if (uri != null) {
            startPlayback(uri)
        } else {
            // Запуск с лаунчера без файла — предлагаем выбрать видео.
            pickVideo.launch(arrayOf("video/*"))
        }
    }

    override fun onStop() {
        super.onStop()
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

        val exo = ExoPlayer.Builder(this).build()
        playerView.player = exo

        exo.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                onVideoGeometryChanged(videoSize)
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.err_playback, error.errorCodeName),
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        exo.playWhenReady = true
        player = exo
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    /**
     * Считаем реальную высоту картинки на экране и величину чёрной полосы.
     * Отсюда берётся «безопасный» предел сдвига.
     */
    private fun onVideoGeometryChanged(videoSize: VideoSize) {
        root.post {
            val viewW = root.width.toFloat()
            val viewH = root.height.toFloat()
            if (viewW <= 0f || viewH <= 0f || videoSize.height == 0) return@post

            val videoAspect =
                videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            if (videoAspect <= 0f) return@post

            val displayedH =
                if (viewW / viewH > videoAspect) viewH else viewW / videoAspect
            safeMarginPx = ((viewH - displayedH) / 2f).coerceAtLeast(0f)

            aspectKey = "shift_" + (videoAspect * 100).roundToInt()
            shiftPx = prefs?.getFloat(aspectKey, 0f) ?: 0f

            applyShift(showHud = safeMarginPx > 0f && shiftPx != 0f)
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

    private fun nudge(deltaPx: Float) {
        shiftPx += deltaPx
        applyShift(showHud = true)
    }

    private fun resetShift() {
        shiftPx = 0f
        applyShift(showHud = true)
    }

    /** Сдвинуть картинку вплотную к низу экрана (одна полоса сверху). */
    private fun snapToBottom() {
        shiftPx = safeMarginPx
        applyShift(showHud = true)
    }

    /** Сдвинуть картинку вплотную к верху экрана (одна полоса снизу). */
    private fun snapToTop() {
        shiftPx = -safeMarginPx
        applyShift(showHud = true)
    }

    private fun showHud() {
        val cropping = abs(shiftPx) > safeMarginPx + 0.5f
        val sb = StringBuilder()
        sb.append(getString(R.string.hud_shift, shiftPx.roundToInt()))
        sb.append("   ")
        sb.append(getString(R.string.hud_safe, safeMarginPx.roundToInt()))
        if (cropping) {
            sb.append("   ")
            sb.append(getString(R.string.hud_crop))
        }
        hud.text = sb.toString()
        hud.visibility = View.VISIBLE
        hudHandler.removeCallbacks(hideHud)
        hudHandler.postDelayed(hideHud, 2000L)
    }

    // ----------------------------------------------------------------- controls

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Долгое удержание — крупный шаг, чтобы не жать стрелку 30 раз.
        val step = if (event.repeatCount > 6) 24f else 4f

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                nudge(step); return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                nudge(-step); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlay(); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(10_000L); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-10_000L); return true
            }
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0,
            KeyEvent.KEYCODE_MENU -> {
                resetShift(); return true
            }
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> {
                snapToTop(); return true
            }
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> {
                snapToBottom(); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun togglePlay() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val target = (p.currentPosition + deltaMs).coerceAtLeast(0L)
        p.seekTo(target)
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
