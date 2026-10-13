package com.vd.app

import android.app.Dialog
import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.Locale
import java.util.TreeMap

class MainActivity : AppCompatActivity() {

    private val BG = Color.parseColor("#070B14")
    private val SURF = Color.parseColor("#0E1626")
    private val LINE = Color.parseColor("#1C2A44")
    private val ACC = Color.parseColor("#00B4FF")
    private val ACC2 = Color.parseColor("#0057FF")
    private val MUTED = Color.parseColor("#8DA0C0")

    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var pageBar: ProgressBar
    private lateinit var panel: LinearLayout
    private lateinit var panelText: TextView
    private lateinit var panelBar: ProgressBar
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ready = false
    @Volatile private var busy = false

    private class Sub(val code: String, val label: String, val auto: Boolean)

    // ---------- helpers ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun box(color: Int, r: Int, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        if (stroke != 0) g.setStroke(dp(1), stroke)
        return g
    }

    private fun grad(r: Int): GradientDrawable {
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ACC, ACC2))
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight)

    private fun clean(s: String): String = if (s == "null") "" else s

    private fun mb(b: Long): String =
        if (b >= 1000000000L) String.format(Locale.US, "%.1f GB", b / 1e9)
        else String.format(Locale.US, "%.0f MB", b / 1e6)

    private fun fmtDur(s: Double): String {
        val t = s.toInt()
        val h = t / 3600
        val m = (t % 3600) / 60
        val sec = t % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    private fun tagOf(h: Int): String = when {
        h >= 2160 -> "4K"
        h >= 1440 -> "2K"
        h >= 1080 -> "Full HD"
        h >= 720 -> "HD"
        else -> "SD"
    }

    private fun msg(e: Exception): String {
        val m = e.message ?: return "unknown error"
        val line = m.lines().lastOrNull { it.isNotBlank() } ?: m
        return line.trim().take(140)
    }

    // ---------- bottom progress panel ----------
    private fun panelShow(text: String, indeterminate: Boolean, progress: Int = 0, hideAfter: Long = 0L) {
        runOnUiThread {
            panel.visibility = View.VISIBLE
            panelText.text = text
            panelBar.isIndeterminate = indeterminate
            if (!indeterminate) panelBar.progress = progress
            main.removeCallbacksAndMessages(null)
            if (hideAfter > 0L) main.postDelayed({ panel.visibility = View.GONE }, hideAfter)
        }
    }

    private fun panelHide() {
        runOnUiThread { panel.visibility = View.GONE }
    }

    // ---------- UI ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(10), dp(8), dp(10), dp(8))

        val logo = ImageView(this)
        logo.setImageResource(R.drawable.hdm_logo)
        top.addView(logo, lp(dp(38), dp(38)))

        urlBox = EditText(this)
        urlBox.hint = "Search or enter website"
        urlBox.setHintTextColor(MUTED)
        urlBox.setTextColor(Color.WHITE)
        urlBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        urlBox.setSingleLine(true)
        urlBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        urlBox.imeOptions = EditorInfo.IME_ACTION_GO
        urlBox.background = box(SURF, 22, LINE)
        urlBox.setPadding(dp(16), dp(10), dp(16), dp(10))
        urlBox.setSelectAllOnFocus(true)
        val lpUrl = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lpUrl.marginStart = dp(10)
        lpUrl.marginEnd = dp(8)
        top.addView(urlBox, lpUrl)

        val go = tv("→", 20f, Color.WHITE, true)
        go.gravity = Gravity.CENTER
        go.background = grad(21)
        top.addView(go, lp(dp(42), dp(42)))

        pageBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        pageBar.max = 100
        pageBar.progressTintList = ColorStateList.valueOf(ACC)
        pageBar.progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        pageBar.visibility = View.GONE

        web = WebView(this)
        web.setBackgroundColor(BG)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "HDM")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                return !(u.startsWith("http://") || u.startsWith("https://"))
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (url != null && !urlBox.hasFocus()) urlBox.setText(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(JS, null)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                pageBar.progress = newProgress
                pageBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = box(SURF, 16, ACC)
        panel.setPadding(dp(16), dp(12), dp(16), dp(12))
        panel.visibility = View.GONE
        panelText = tv("", 13f, Color.WHITE)
        panelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        panelBar.max = 100
        panelBar.progressTintList = ColorStateList.valueOf(ACC)
        panel.addView(panelText)
        val lpBar = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
        lpBar.topMargin = dp(8)
        panel.addView(panelBar, lpBar)
        val lpPanel = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpPanel.setMargins(dp(12), dp(4), dp(12), dp(8))

        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setBackgroundColor(SURF)
        fun navBtn(label: String, action: () -> Unit) {
            val t = tv(label, 26f, ACC, true)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(10), 0, dp(10))
            t.setOnClickListener { action() }
            nav.addView(t, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        navBtn("‹") { if (web.canGoBack()) web.goBack() }
        navBtn("›") { if (web.canGoForward()) web.goForward() }
        navBtn("↻") { web.reload() }
        navBtn("⌂") { web.loadUrl("https://www.google.com") }

        root.addView(top)
        root.addView(pageBar, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(web, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(panel, lpPanel)
        root.addView(nav)
        setContentView(root)

        go.setOnClickListener { openUrl() }
        urlBox.setOnEditorActionListener { _, _, _ -> openUrl(); true }

        web.loadUrl("https://www.google.com")

        panelShow("Starting engine...", true)
        Thread {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                panelShow("Updating download engine...", true)
                val st = updateEngine()
                ready = true
                panelShow("HDM ready  (" + st + ")", false, 100, 3500L)
            } catch (e: Exception) {
                panelShow("Engine error: " + msg(e), false, 0, 10000L)
            }
        }.start()
    }

    private fun openUrl() {
        val t = urlBox.text.toString().trim()
        if (t.isEmpty()) return
        val u = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(" ") || !t.contains(".") -> "https://www.google.com/search?q=" + Uri.encode(t)
            else -> "https://" + t
        }
        urlBox.clearFocus()
        web.loadUrl(u)
    }

    // ---------- yt-dlp auto update (reflection, so it never breaks the build) ----------
    private fun findChannel(cls: Class<*>): Any? {
        for (n in listOf("NIGHTLY", "STABLE")) {
            try {
                val v = cls.getField("_" + n).get(null)
                if (v != null) return v
            } catch (e: Exception) {
            }
            try {
                val c = Class.forName(cls.name + "\$" + n)
                val v = c.getField("INSTANCE").get(null)
                if (v != null) return v
            } catch (e: Exception) {
            }
            try {
                val v = cls.getField(n).get(null)
                if (v != null) return v
            } catch (e: Exception) {
            }
        }
        return null
    }

    private fun updateEngine(): String {
        try {
            val yd = YoutubeDL.getInstance()
            for (m in yd.javaClass.methods) {
                if (m.name != "updateYoutubeDL") continue
                val pt = m.parameterTypes
                if (pt.isEmpty() || pt.size > 2) continue
                val args = arrayOfNulls<Any>(pt.size)
                args[0] = applicationContext
                if (pt.size == 2) {
                    val ch = findChannel(pt[1]) ?: continue
                    args[1] = ch
                }
                val res = m.invoke(yd, *args)
                val s = (res?.toString() ?: "ok").substringAfterLast('$').substringBefore('@')
                return "yt-dlp " + s
            }
            return "update unavailable"
        } catch (e: Exception) {
            return "update failed"
        }
    }

    // ---------- JS bridge ----------
    inner class Bridge {
        @JavascriptInterface
        fun download(page: String, src: String, title: String) {
            runOnUiThread { analyze(page, src) }
        }
    }

    private fun fetchInfo(url: String): JSONObject {
        val r = YoutubeDLRequest(url)
        r.addOption("--dump-single-json")
        r.addOption("--no-playlist")
        r.addOption("--skip-download")
        r.addOption("--no-warnings")
        r.addOption("--socket-timeout", "20")
        val out = YoutubeDL.getInstance().execute(r).out
        return JSONObject(out)
    }

    private fun loadThumb(u: String): Bitmap? {
        if (u.isEmpty()) return null
        return try {
            val c = URL(u).openConnection()
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.getInputStream().use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun analyze(page: String, src: String) {
        if (!ready) {
            Toast.makeText(this, "Engine is still starting, please wait...", Toast.LENGTH_SHORT).show()
            return
        }
        if (busy) {
            Toast.makeText(this, "Please wait, another task is running", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        panelShow("Reading video info...", true)
        Thread {
            try {
                var used = page
                val info = try {
                    fetchInfo(page)
                } catch (e: Exception) {
                    if (src.isNotEmpty()) {
                        used = src
                        fetchInfo(src)
                    } else throw e
                }
                val u = used
                val th = loadThumb(clean(info.optString("thumbnail", "")))
                panelHide()
                runOnUiThread { showSheet(u, info, th) }
            } catch (e: Exception) {
                panelShow("Couldn't read this video: " + msg(e), false, 0, 8000L)
            } finally {
                busy = false
            }
        }.start()
    }

    // ---------- beautiful quality popup ----------
    private fun showSheet(url: String, info: JSONObject, thumb: Bitmap?) {
        val title = clean(info.optString("title", "Video"))
        val who = clean(info.optString("uploader", ""))
        val dur = info.optDouble("duration", 0.0)

        val heights = TreeMap<Int, LongArray>()
        var audioSize = 0L
        val fm = info.optJSONArray("formats")
        if (fm != null) {
            for (i in 0 until fm.length()) {
                val f = fm.optJSONObject(i) ?: continue
                val vc = f.optString("vcodec", "none")
                val ac = f.optString("acodec", "none")
                val hasV = vc.isNotEmpty() && vc != "none" && vc != "null"
                val hasA = ac.isNotEmpty() && ac != "none" && ac != "null"
                var sz = f.optLong("filesize", 0L)
                if (sz <= 0L) sz = f.optLong("filesize_approx", 0L)
                if (sz <= 0L) {
                    val tbr = f.optDouble("tbr", 0.0)
                    if (tbr > 0 && dur > 0) sz = (tbr * 1000 / 8 * dur).toLong()
                }
                val h = f.optInt("height", 0)
                if (hasV && h > 0) {
                    val fps = f.optInt("fps", 0).toLong()
                    val cur = heights[h]
                    if (cur == null) {
                        heights[h] = longArrayOf(sz, fps)
                    } else {
                        if (sz > cur[0]) cur[0] = sz
                        if (fps > cur[1]) cur[1] = fps
                    }
                } else if (hasA && !hasV) {
                    if (sz > audioSize) audioSize = sz
                }
            }
        }

        val subs = ArrayList<Sub>()
        fun addSubs(key: String, auto: Boolean) {
            val o = info.optJSONObject(key) ?: return
            val ks = o.keys()
            while (ks.hasNext()) {
                val c = ks.next()
                if (c == "live_chat") continue
                val nm = try {
                    Locale.forLanguageTag(c).displayName
                } catch (e: Exception) {
                    c
                }
                val base = if (nm.isBlank()) c else nm
                subs.add(Sub(c, base + " [" + c + "]" + (if (auto) "  (auto)" else ""), auto))
            }
        }
        addSubs("subtitles", false)
        addSubs("automatic_captions", true)
        val chosen = BooleanArray(subs.size)

        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        sheet.setPadding(dp(18), dp(10), dp(18), dp(22))
        val bgSheet = GradientDrawable()
        bgSheet.setColor(BG)
        val rr = dp(24).toFloat()
        bgSheet.cornerRadii = floatArrayOf(rr, rr, rr, rr, 0f, 0f, 0f, 0f)
        bgSheet.setStroke(dp(1), LINE)
        sheet.background = bgSheet

        val handle = View(this)
        handle.background = box(LINE, 3)
        val lpHandle = lp(dp(44), dp(5))
        lpHandle.gravity = Gravity.CENTER_HORIZONTAL
        lpHandle.bottomMargin = dp(14)
        sheet.addView(handle, lpHandle)

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.background = box(SURF, 12)
        img.clipToOutline = true
        if (thumb != null) img.setImageBitmap(thumb)
        head.addView(img, lp(dp(128), dp(72)))
        val meta = LinearLayout(this)
        meta.orientation = LinearLayout.VERTICAL
        meta.setPadding(dp(12), 0, 0, 0)
        val tt = tv(title, 15f, Color.WHITE, true)
        tt.maxLines = 3
        tt.ellipsize = TextUtils.TruncateAt.END
        meta.addView(tt)
        val parts = ArrayList<String>()
        if (who.isNotEmpty()) parts.add(who)
        if (dur > 0) parts.add(fmtDur(dur))
        if (parts.isNotEmpty()) {
            val ml = tv(parts.joinToString("  •  "), 12f, MUTED)
            ml.maxLines = 1
            ml.ellipsize = TextUtils.TruncateAt.END
            meta.addView(ml)
        }
        head.addView(meta, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sheet.addView(head)

        fun label(text: String): TextView {
            val t = tv(text, 11f, MUTED, true)
            t.letterSpacing = 0.12f
            t.setPadding(dp(2), dp(16), 0, dp(8))
            return t
        }

        fun rowLp(): LinearLayout.LayoutParams {
            val p = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            p.bottomMargin = dp(8)
            return p
        }

        fun row(left: String, tag: String, right: String, onClick: () -> Unit): View {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.background = box(SURF, 14, LINE)
            r.setPadding(dp(16), dp(12), dp(12), dp(12))
            r.addView(tv(left, 16f, Color.WHITE, true))
            if (tag.isNotEmpty()) {
                val tg = tv(tag, 11f, ACC, true)
                tg.setPadding(dp(8), dp(2), dp(8), dp(2))
                tg.background = box(Color.parseColor("#0A2540"), 8)
                val p = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                p.marginStart = dp(10)
                r.addView(tg, p)
            }
            r.addView(View(this), lp(0, 1, 1f))
            if (right.isNotEmpty()) r.addView(tv(right, 13f, MUTED))
            val arrow = tv("↓", 18f, Color.WHITE, true)
            arrow.gravity = Gravity.CENTER
            arrow.background = grad(17)
            val pa = lp(dp(34), dp(34))
            pa.marginStart = dp(12)
            r.addView(arrow, pa)
            r.setOnClickListener { onClick() }
            return r
        }

        val begin = { h: Int, audio: Boolean ->
            d.dismiss()
            startDownload(url, h, audio, subs.filterIndexed { i, _ -> chosen[i] })
        }

        if (subs.isNotEmpty()) {
            fun subText(n: Int): String = if (n == 0) "Subtitles: none  ▾" else "Subtitles: " + n + " selected  ▾"
            val subBtn = tv(subText(0), 14f, ACC, true)
            subBtn.background = box(SURF, 14, ACC)
            subBtn.setPadding(dp(16), dp(12), dp(16), dp(12))
            subBtn.setOnClickListener {
                val labels = subs.map { s -> s.label }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Subtitles")
                    .setMultiChoiceItems(labels, chosen) { _, i, c -> chosen[i] = c }
                    .setPositiveButton("Done") { _, _ -> subBtn.text = subText(chosen.count { c -> c }) }
                    .setNeutralButton("Select all") { _, _ ->
                        for (i in chosen.indices) chosen[i] = true
                        subBtn.text = subText(chosen.size)
                    }
                    .setNegativeButton("Clear") { _, _ ->
                        for (i in chosen.indices) chosen[i] = false
                        subBtn.text = subText(0)
                    }
                    .show()
            }
            val lpSub = rowLp()
            lpSub.topMargin = dp(14)
            sheet.addView(subBtn, lpSub)
        }

        sheet.addView(label("VIDEO"))
        if (heights.isEmpty()) {
            sheet.addView(row("Best available", "", "") { begin(0, false) }, rowLp())
        } else {
            for ((h, v) in heights.descendingMap()) {
                val total = if (v[0] > 0) v[0] + audioSize else 0L
                val fpsTxt = if (v[1] > 30) v[1].toString() else ""
                val name = h.toString() + "p" + fpsTxt
                val sizeTxt = if (total > 0) "~" + mb(total) else ""
                sheet.addView(row(name, tagOf(h), sizeTxt) { begin(h, false) }, rowLp())
            }
        }
        sheet.addView(label("AUDIO"))
        sheet.addView(
            row("MP3", "Audio only", if (audioSize > 0) "~" + mb(audioSize) else "") { begin(0, true) },
            rowLp()
        )

        val scroll = ScrollView(this)
        scroll.addView(sheet)
        d.setContentView(scroll)
        val w = d.window
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        d.show()
    }

    // ---------- download ----------
    private fun startDownload(url: String, h: Int, audio: Boolean, subs: List<Sub>) {
        if (busy) return
        busy = true
        val tmp = File(getExternalFilesDir(null), "tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()

        val r = YoutubeDLRequest(url)
        r.addOption("--no-playlist")
        r.addOption("--no-mtime")
        r.addOption("-o", tmp.absolutePath + "/%(title).80s.%(ext)s")
        if (audio) {
            r.addOption("-x")
            r.addOption("--audio-format", "mp3")
            r.addOption("--audio-quality", "0")
        } else {
            r.addOption("-f", if (h == 0) "bv*+ba/b" else "bv*[height<=" + h + "]+ba/b[height<=" + h + "]/b")
            r.addOption("-S", "res,ext:mp4:m4a")
            r.addOption("--merge-output-format", "mp4")
        }
        if (subs.isNotEmpty()) {
            if (subs.any { s -> !s.auto }) r.addOption("--write-subs")
            if (subs.any { s -> s.auto }) r.addOption("--write-auto-subs")
            r.addOption("--sub-langs", subs.map { s -> s.code }.distinct().joinToString(","))
            r.addOption("--convert-subs", "srt")
        }

        panelShow("Starting download...", true)
        Thread {
            try {
                YoutubeDL.getInstance().execute(r, null) { p, eta, _ ->
                    val pr = p.toInt()
                    val txt = "Downloading " + (if (pr >= 0) pr.toString() + "%" else "") +
                            (if (eta > 0) "   •   ETA " + eta + "s" else "")
                    panelShow(txt, pr < 0, if (pr < 0) 0 else pr)
                }
                val files = tmp.listFiles()
                if (files == null || files.isEmpty()) throw Exception("No file was produced")
                for (f in files) save(f)
                panelShow("Saved to Downloads/HDM  ✓", false, 100, 6000L)
            } catch (e: Exception) {
                panelShow("Download failed: " + msg(e), false, 0, 9000L)
            } finally {
                busy = false
            }
        }.start()
    }

    private fun save(f: File) {
        val ext = f.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val v = ContentValues()
        v.put(MediaStore.Downloads.DISPLAY_NAME, f.name)
        v.put(MediaStore.Downloads.MIME_TYPE, mime)
        v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/HDM")
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return
        contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { i -> i.copyTo(o) } }
        f.delete()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    // ---------- floating download button injected into every video ----------
    private val JS = """
(function(){
  if (window.__hdmOn) return; window.__hdmOn = 1;
  var map = new Map();
  var ICON = '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="#fff" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4v11"></path><path d="M7 11l5 5 5-5"></path><path d="M5 20h14"></path></svg>';
  function pick(v){
    var u = location.href;
    var a = v.closest ? v.closest('a[href]') : null;
    if (a && a.href && a.href.indexOf('http') === 0) u = a.href;
    var s = v.currentSrc || v.src || '';
    if (s.indexOf('http') !== 0) s = '';
    try { HDM.download(u, s, document.title || ''); } catch(e) {}
  }
  function make(v){
    var b = document.createElement('div');
    b.innerHTML = ICON;
    b.style.cssText = 'position:fixed;z-index:2147483647;width:44px;height:44px;border-radius:22px;background:linear-gradient(135deg,#00c6ff,#0057ff);box-shadow:0 0 14px #00b4ff,0 2px 8px rgba(0,0,0,.6);display:none;align-items:center;justify-content:center;border:1.5px solid rgba(255,255,255,.55);cursor:pointer;';
    b.addEventListener('click', function(e){ e.stopPropagation(); e.preventDefault(); pick(v); }, true);
    document.body.appendChild(b);
    return b;
  }
  function scan(){
    var vs = document.getElementsByTagName('video');
    var seen = new Set();
    for (var i = 0; i < vs.length; i++){
      var v = vs[i];
      var r = v.getBoundingClientRect();
      var ok = r.width > 120 && r.height > 70 && r.bottom > 0 && r.top < window.innerHeight && r.right > 0 && r.left < window.innerWidth;
      if (!ok) continue;
      seen.add(v);
      var b = map.get(v);
      if (!b){ b = make(v); map.set(v, b); }
      b.style.top = (Math.max(r.top, 0) + 12) + 'px';
      b.style.left = (Math.min(r.right, window.innerWidth) - 56) + 'px';
      b.style.display = 'flex';
    }
    map.forEach(function(b, v){
      if (!v.isConnected){ b.remove(); map.delete(v); }
      else if (!seen.has(v)) { b.style.display = 'none'; }
    });
  }
  setInterval(scan, 400);
  scan();
})();
"""
}
