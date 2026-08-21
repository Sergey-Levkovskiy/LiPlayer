package com.shiftplayer

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Пишет байты потока на диск по пути к плееру — без перекодирования.
 *
 * Вставляется в цепочку через TeeDataSource: всё, что ExoPlayer прочитал из
 * сети, дублируется в файл один-к-одному. Процессор не задействован, качество
 * равно исходному.
 *
 * Плейлисты и ключи шифрования отфильтровываются: попади .m3u8 в середину
 * файла — запись станет неиграбельной.
 *
 * Методы open/write/close вызываются с загрузочного потока ExoPlayer,
 * start/stop — с главного, поэтому доступ к потоку записи под замком.
 */
@UnstableApi
class StreamRecorder(dir: File) : DataSink {

    /** Куда писать. Меняется из настроек между записями. */
    @Volatile
    var dir: File = dir

    private val lock = Any()

    private var out: FileOutputStream? = null

    /** Текущий сегмент пропускаем: запись выключена или это не медиаданные. */
    @Volatile
    private var skipCurrent = true

    @Volatile
    var isRecording = false
        private set

    var file: File? = null
        private set

    @Volatile
    var bytesWritten = 0L
        private set

    /** @return файл записи, либо null если открыть не удалось. */
    fun start(): File? = synchronized(lock) {
        if (isRecording) return file
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val target = File(dir, "LiPlayer_$stamp.ts")
        return try {
            dir.mkdirs()
            out = FileOutputStream(target)
            file = target
            bytesWritten = 0L
            isRecording = true
            target
        } catch (e: Exception) {
            out = null
            file = null
            null
        }
    }

    fun stop(): File? = synchronized(lock) {
        if (!isRecording) return null
        isRecording = false
        skipCurrent = true
        try {
            out?.flush()
            out?.close()
        } catch (ignored: Exception) {
        }
        out = null
        return file
    }

    // ------------------------------------------------------------- DataSink

    override fun open(dataSpec: DataSpec) {
        skipCurrent = !isRecording || !isMediaSegment(dataSpec)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (skipCurrent) return
        synchronized(lock) {
            val stream = out ?: return
            try {
                stream.write(buffer, offset, length)
                bytesWritten += length
            } catch (e: Exception) {
                // Диск кончился или флешку выдернули — тихо прекращаем запись.
                isRecording = false
                skipCurrent = true
                try {
                    stream.close()
                } catch (ignored: Exception) {
                }
                out = null
            }
        }
    }

    /** Сегмент закончился, но файл держим открытым до stop(). */
    override fun close() = Unit

    private fun isMediaSegment(dataSpec: DataSpec): Boolean {
        val path = dataSpec.uri.path?.lowercase() ?: return true
        return !(path.endsWith(".m3u8") ||
            path.endsWith(".m3u") ||
            path.endsWith(".mpd") ||
            path.endsWith(".key"))
    }
}
