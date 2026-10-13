package com.vd.app

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var status: TextView
    @Volatile private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        urlBox = EditText(this).apply {
            hint = "Search or enter website"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val go = Button(this).apply { text = "Go" }
        bar.addView(urlBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(go)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (url != null) urlBox.setText(url)
            }
        }

        status = TextView(this).apply { text = "Starting engine..."; setPadding(16, 8, 16, 8) }
        val dl = Button(this).apply { text = "DOWNLOAD THIS VIDEO" }

        root.addView(bar)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(status)
        root.addView(dl)
        setContentView(root)

        go.setOnClickListener { openUrl() }
        urlBox.setOnEditorActionListener { _, _, _ -> openUrl(); true }
        dl.setOnClickListener { onDownloadClick() }

        web.loadUrl("https://www.google.com")

        Thread {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                ready = true
                ui("Ready. Open a video page, then press DOWNLOAD.")
            } catch (e: Exception) {
                ui("Engine error: ${e.message}")
            }
        }.start()
    }

    private fun ui(s: String) = runOnUiThread { status.text = s }

    private fun openUrl() {
        val t = urlBox.text.toString().trim()
        if (t.isEmpty()) return
        val u = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(" ") || !t.contains(".") -> "https://www.google.com/search?q=" + Uri.encode(t)
            else -> "https://$t"
        }
        web.loadUrl(u)
    }

    private fun onDownloadClick() {
        val url = web.url
        if (url.isNullOrEmpty()) return
        if (!ready) { ui("Engine not ready yet, wait a moment."); return }
        ui("Checking video...")
        Thread {
            try {
                val r = YoutubeDLRequest(url)
                r.addOption("--dump-single-json")
                r.addOption("--no-playlist")
                r.addOption("--skip-download")
                r.addOption("--no-warnings")
                val out = YoutubeDL.getInstance().execute(r).out
                val j = JSONObject(out)
                val title = j.optString("title", "Video")
                val subs = j.optJSONObject("subtitles")
                val langs = ArrayList<String>()
                if (subs != null) {
                    val it = subs.keys()
                    while (it.hasNext()) langs.add(it.next())
                }
                ui("Found: $title")
                runOnUiThread { askQuality(url, title, langs) }
            } catch (e: Exception) {
                ui("No video found on this page. ${e.message?.take(200)}")
            }
        }.start()
    }

    private fun askQuality(url: String, title: String, langs: List<String>) {
        val q = arrayOf("Best quality (MP4)", "1080p", "720p", "480p", "360p", "Audio only (MP3)")
        var qi = 0
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(q, 0) { _, w -> qi = w }
            .setPositiveButton(if (langs.isEmpty()) "Download" else "Next") { _, _ ->
                if (langs.isEmpty()) start(url, qi, emptyList()) else askSubs(url, qi, langs)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askSubs(url: String, qi: Int, langs: List<String>) {
        val checked = BooleanArray(langs.size)
        AlertDialog.Builder(this)
            .setTitle("Subtitles (optional)")
            .setMultiChoiceItems(langs.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Download") { _, _ ->
                start(url, qi, langs.filterIndexed { i, _ -> checked[i] })
            }
            .setNeutralButton("Download all subs") { _, _ -> start(url, qi, langs) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun start(url: String, qi: Int, subs: List<String>) {
        val tmp = File(getExternalFilesDir(null), "tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()

        val r = YoutubeDLRequest(url)
        r.addOption("--no-playlist")
        r.addOption("-o", tmp.absolutePath + "/%(title).80s.%(ext)s")
        if (qi == 5) {
            r.addOption("-x")
            r.addOption("--audio-format", "mp3")
        } else {
            val h = intArrayOf(0, 1080, 720, 480, 360)[qi]
            r.addOption("-f", if (h == 0) "bv*+ba/b" else "bv*[height<=$h]+ba/b[height<=$h]/b")
            r.addOption("-S", "res,ext:mp4:m4a")
            r.addOption("--merge-output-format", "mp4")
        }
        if (subs.isNotEmpty()) {
            r.addOption("--write-subs")
            r.addOption("--sub-langs", subs.joinToString(","))
            r.addOption("--convert-subs", "srt")
        }

        ui("Downloading...")
        Thread {
            try {
                YoutubeDL.getInstance().execute(r, null) { p, _, line ->
                    ui("Downloading ${p.toInt()}%\n$line")
                }
                tmp.listFiles()?.forEach { save(it) }
                ui("Done! Saved in Downloads/VideoDownloader")
            } catch (e: Exception) {
                ui("Error: ${e.message?.take(300)}")
            }
        }.start()
    }

    private fun save(f: File) {
        val ext = f.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/VideoDownloader")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return
        contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        f.delete()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
