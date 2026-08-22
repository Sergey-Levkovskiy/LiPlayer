package com.shiftplayer

import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Стартовый экран: что открыть.
 *
 * Сверху записи эфира — их можно смотреть и удалять. Ниже видео с устройства
 * из MediaStore. В конце — системный выбор файла на случай, когда нужного
 * файла в медиатеке нет (сетевые папки, свежая флешка).
 *
 * Удалять разрешаем только свои записи: чужие файлы MediaStore требуют
 * отдельного согласия пользователя на каждый файл, и это не наша забота.
 */
class LibraryActivity : ComponentActivity() {

    private companion object {
        const val KIND_SECTION = 0
        const val KIND_RECORDING = 1
        const val KIND_MEDIA = 2
        const val KIND_PICK = 3
    }

    private class Item(
        val kind: Int,
        val title: String,
        val meta: String = "",
        val uri: Uri? = null,
        val file: File? = null
    )

    private lateinit var list: ListView
    private lateinit var empty: TextView

    private val items = ArrayList<Item>()

    private val dateFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) play(uri) }

    private val askRead = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { reload() }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        // Заголовки секций пропускаются при навигации пультом.
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = items[position].kind != KIND_SECTION

        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) =
            if (items[position].kind == KIND_SECTION) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[position]
            val inflater = LayoutInflater.from(this@LibraryActivity)

            if (item.kind == KIND_SECTION) {
                val v = convertView as? TextView
                    ?: inflater.inflate(R.layout.row_section, parent, false) as TextView
                v.text = item.title
                return v
            }

            val v = convertView ?: inflater.inflate(R.layout.row_media, parent, false)
            v.findViewById<ImageView>(R.id.media_icon).setImageResource(
                if (item.kind == KIND_PICK) R.drawable.ic_open else R.drawable.ic_video
            )
            v.findViewById<TextView>(R.id.media_title).text = item.title
            v.findViewById<TextView>(R.id.media_meta).apply {
                text = item.meta
                visibility = if (item.meta.isEmpty()) View.GONE else View.VISIBLE
            }
            return v
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        findViewById<TextView>(R.id.title).text = getString(
            R.string.lib_title, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA
        )

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        list.adapter = adapter

        list.setOnItemClickListener { _, _, position, _ ->
            val item = items[position]
            if (item.kind == KIND_PICK) pickVideo.launch(arrayOf("video/*"))
            else item.uri?.let { play(it) }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(position)
        }

        requestReadIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        // Вернулись из плеера — могла появиться новая запись.
        reload()
    }

    /** MENU или «удалить» на пульте — то же, что долгое нажатие. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_DEL) {
            if (confirmDelete(list.selectedItemPosition)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ данные

    private fun readPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasRead(): Boolean =
        ContextCompat.checkSelfPermission(this, readPermission()) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestReadIfNeeded() {
        if (hasRead()) reload() else askRead.launch(readPermission())
    }

    private fun recDirs(): List<File> {
        val dirs = getExternalFilesDirs(Environment.DIRECTORY_MOVIES)
            .filterNotNull()
            .toMutableList()
        if (dirs.isEmpty()) dirs.add(filesDir)
        return dirs
    }

    private fun reload() {
        val keep = list.selectedItemPosition
        items.clear()

        val recordings = recDirs()
            .flatMap { it.listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile && it.name.endsWith(".ts", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }

        if (recordings.isNotEmpty()) {
            items.add(Item(KIND_SECTION, getString(R.string.lib_recordings)))
            recordings.forEach { f ->
                items.add(
                    Item(
                        kind = KIND_RECORDING,
                        title = f.name,
                        meta = getString(
                            R.string.lib_rec_meta,
                            fmtSize(f.length()),
                            dateFmt.format(Date(f.lastModified()))
                        ),
                        uri = Uri.fromFile(f),
                        file = f
                    )
                )
            }
        }

        val device = if (hasRead()) queryVideos() else emptyList()
        if (device.isNotEmpty()) {
            items.add(Item(KIND_SECTION, getString(R.string.lib_device)))
            items.addAll(device)
        }

        items.add(Item(KIND_SECTION, getString(R.string.lib_other)))
        items.add(Item(KIND_PICK, getString(R.string.lib_pick)))

        adapter.notifyDataSetChanged()

        val onlyService = recordings.isEmpty() && device.isEmpty()
        empty.visibility = if (onlyService) View.VISIBLE else View.GONE

        // Держим выделение на месте после обновления списка.
        if (keep in items.indices && adapter.isEnabled(keep)) {
            list.setSelection(keep)
        } else {
            list.setSelection(firstSelectable())
        }
    }

    private fun firstSelectable(): Int =
        items.indexOfFirst { it.kind != KIND_SECTION }.coerceAtLeast(0)

    private fun queryVideos(): List<Item> {
        val out = ArrayList<Item>()
        val cols = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE
        )
        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                cols,
                null,
                null,
                MediaStore.Video.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    out.add(
                        Item(
                            kind = KIND_MEDIA,
                            title = c.getString(iName) ?: getString(R.string.lib_unnamed),
                            meta = getString(
                                R.string.lib_media_meta,
                                fmtDuration(c.getLong(iDur)),
                                fmtSize(c.getLong(iSize))
                            ),
                            uri = ContentUris.withAppendedId(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                            )
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Нет провайдера или отозвали доступ — просто показываем записи.
        }
        return out
    }

    // ------------------------------------------------------------------ действия

    private fun play(uri: Uri) {
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    /** @return true, если диалог показан. */
    private fun confirmDelete(position: Int): Boolean {
        val item = items.getOrNull(position) ?: return false
        val file = item.file ?: return false

        AlertDialog.Builder(this)
            .setTitle(R.string.del_title)
            .setMessage(getString(R.string.del_message, item.title, fmtSize(file.length())))
            .setNegativeButton(R.string.del_cancel, null)
            .setPositiveButton(R.string.del_ok) { _, _ ->
                if (file.delete()) {
                    Toast.makeText(this, R.string.del_done, Toast.LENGTH_SHORT).show()
                    reload()
                } else {
                    Toast.makeText(this, R.string.del_failed, Toast.LENGTH_LONG).show()
                }
            }
            .show()
        return true
    }

    // ----------------------------------------------------------------- формат

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1L shl 30 ->
            String.format(Locale.US, "%.1f ГБ", bytes / (1L shl 30).toFloat())
        bytes >= 1L shl 20 ->
            String.format(Locale.US, "%.0f МБ", bytes / (1L shl 20).toFloat())
        else -> String.format(Locale.US, "%.0f КБ", bytes / 1024f)
    }

    private fun fmtDuration(ms: Long): String {
        if (ms <= 0L) return "—"
        val total = ms / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}
