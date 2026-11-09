package com.wahyu.pdfviewer

import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var pageIndicator: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var historyScroll: ScrollView
    private lateinit var historyList: LinearLayout
    private lateinit var historyButton: Button
    private lateinit var zoomControls: LinearLayout
    private lateinit var searchPanel: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchStatus: TextView
    private var searchMatches: List<Int> = emptyList()
    private var searchMatchIndex: Int = -1
    private var currentPdfLocalFile: File? = null

    private var renderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val openPdfRequest = 1001
    private val prefs by lazy { getSharedPreferences("pdf_viewer_preferences", MODE_PRIVATE) }

    private data class RecentPdf(
        val uri: String,
        val name: String,
        val openedAt: Long,
        val localPath: String = ""
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        buildScreen()
        renderHistory()
        handleIncomingPdf(intent)
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        handleIncomingPdf(newIntent)
    }

    // Menerima PDF yang dibuka dari File Manager atau aplikasi lain.
    private fun handleIncomingPdf(incomingIntent: Intent?) {
        if (incomingIntent == null || incomingIntent.action != Intent.ACTION_VIEW) return
        val uri = incomingIntent.data ?: return

        // Simpan izin akses bila penyedia dokumen mengizinkannya.
        val readFlag = incomingIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (readFlag != 0) {
            try {
                contentResolver.takePersistableUriPermission(uri, readFlag)
            } catch (_: SecurityException) {
                // Izin sementara tetap dapat digunakan untuk membuka PDF saat ini.
            } catch (_: IllegalArgumentException) {
                // Tidak semua aplikasi sumber memberikan izin persisten.
            }
        }
        openPdf(uri)
    }

    private fun buildScreen() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(242, 245, 249))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.rgb(52, 52, 52))
        }

        val title = TextView(this).apply {
            text = "PDF Viewer"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        historyButton = Button(this).apply {
            text = "☰"
            textSize = 20f
            isAllCaps = false
            visibility = View.VISIBLE
            setOnClickListener { showHistory() }
        }
        header.addView(historyButton)

        val searchToggle = Button(this).apply {
            text = "⌕"
            textSize = 20f
            isAllCaps = false
            setOnClickListener {
                if (recycler.visibility == View.VISIBLE) {
                    searchPanel.visibility = if (searchPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    if (searchPanel.visibility == View.VISIBLE) searchInput.requestFocus()
                } else {
                    Toast.makeText(this@MainActivity, "Buka PDF terlebih dahulu.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        header.addView(searchToggle)

        val openButton = Button(this).apply {
            text = "Buka"
            isAllCaps = false
            setOnClickListener { choosePdf() }
        }
        header.addView(openButton)
        root.addView(header)

        status = TextView(this).apply {
            text = "Pilih dokumen PDF untuk mulai membaca."
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(8), dp(4), dp(8))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        pageIndicator = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(10), dp(8))
        }
        val documentInfo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(52, 52, 52))
            addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pageIndicator, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(documentInfo)

        zoomControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setBackgroundColor(Color.rgb(52, 52, 52))
            setPadding(dp(4), dp(0), dp(4), dp(4))
        }
        val zoomOutButton = Button(this).apply {
            text = "−"
            textSize = 22f
            contentDescription = "Perkecil tampilan PDF"
            setOnClickListener { zoomVisiblePages(0.8f) }
        }
        val zoomResetButton = Button(this).apply {
            text = "Reset"
            isAllCaps = false
            setOnClickListener { resetVisiblePagesZoom() }
        }
        val zoomInButton = Button(this).apply {
            text = "+"
            textSize = 22f
            contentDescription = "Perbesar tampilan PDF"
            setOnClickListener { zoomVisiblePages(1.25f) }
        }
        zoomControls.addView(zoomOutButton)
        zoomControls.addView(zoomResetButton)
        zoomControls.addView(zoomInButton)
        root.addView(zoomControls)

        searchPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.rgb(52, 52, 52))
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        searchInput = EditText(this).apply {
            hint = "Cari dalam PDF"
            singleLine = true
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundColor(Color.rgb(75, 75, 75))
        }
        searchPanel.addView(searchInput, LinearLayout.LayoutParams(0, dp(42), 1f))
        val findButton = Button(this).apply {
            text = "Cari"
            isAllCaps = false
            setOnClickListener { searchInPdf() }
        }
        searchPanel.addView(findButton)
        val previousButton = Button(this).apply {
            text = "↑"
            contentDescription = "Hasil sebelumnya"
            setOnClickListener { moveSearchResult(-1) }
        }
        searchPanel.addView(previousButton)
        val nextButton = Button(this).apply {
            text = "↓"
            contentDescription = "Hasil berikutnya"
            setOnClickListener { moveSearchResult(1) }
        }
        searchPanel.addView(nextButton)
        searchStatus = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(6), 0, dp(4), 0)
        }
        searchPanel.addView(searchStatus)
        root.addView(searchPanel)

        historyScroll = ScrollView(this)
        historyList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(18))
        }
        historyScroll.addView(historyList)
        root.addView(historyScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setBackgroundColor(Color.rgb(225, 230, 237))
            visibility = View.GONE
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(12))
        }
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updatePageIndicator()
            }
        })
        root.addView(recycler, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
    }

    private fun choosePdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, openPdfRequest)
    }

    @Deprecated("Use the Activity Result API in a future update")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == openPdfRequest && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                val flags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                try {
                    contentResolver.takePersistableUriPermission(uri, flags)
                } catch (_: SecurityException) {
                    // Beberapa penyedia dokumen tidak memberikan izin persisten.
                }
                openPdf(uri)
            }
        }
    }

    private fun openPdf(uri: Uri) {
        try {
            closePdf()
            val name = displayName(uri)
            val localFile = copyPdfToPrivateStorage(uri, name)
            currentPdfLocalFile = localFile
            searchPanel.visibility = View.GONE
            searchMatches = emptyList()
            searchMatchIndex = -1
            searchStatus.text = ""
            val descriptor = if (localFile != null) {
                ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IOException("Dokumen tidak dapat dibuka.")
            }

            val newRenderer = PdfRenderer(descriptor)
            fileDescriptor = descriptor
            renderer = newRenderer
            recycler.adapter = PdfPageAdapter(newRenderer, executor)
            recycler.scrollToPosition(0)
            recycler.visibility = View.VISIBLE
            historyScroll.visibility = View.GONE
            historyButton.visibility = View.VISIBLE
            zoomControls.visibility = View.VISIBLE

            status.text = name
            pageIndicator.text = "1/${newRenderer.pageCount}"
            saveHistory(uri, name, localFile?.absolutePath ?: "")
            recycler.post { updatePageIndicator() }
        } catch (e: Exception) {
            closePdf()
            Toast.makeText(
                this,
                "Gagal membuka PDF: ${e.message ?: "format tidak didukung"}",
                Toast.LENGTH_LONG
            ).show()
            showHistory()
        }
    }

    private fun openPdfFromHistory(item: RecentPdf) {
        try {
            val local = item.localPath.takeIf { it.isNotBlank() }?.let { File(it) }
            if (local != null && local.exists()) {
                closePdf()
                currentPdfLocalFile = local
                searchPanel.visibility = View.GONE
                searchMatches = emptyList()
                searchMatchIndex = -1
                val descriptor = ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY)
                val newRenderer = PdfRenderer(descriptor)
                fileDescriptor = descriptor
                renderer = newRenderer
                recycler.adapter = PdfPageAdapter(newRenderer, executor)
                recycler.scrollToPosition(0)
                recycler.visibility = View.VISIBLE
                historyScroll.visibility = View.GONE
                historyButton.visibility = View.VISIBLE
                zoomControls.visibility = View.VISIBLE
                status.text = item.name
                pageIndicator.text = "1/${newRenderer.pageCount}"
                recycler.post { updatePageIndicator() }
                updateHistoryOpenedAt(item.uri)
            } else {
                openPdf(Uri.parse(item.uri))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Dokumen riwayat tidak dapat dibuka: ${e.message}", Toast.LENGTH_LONG).show()
            removeHistory(item.uri)
        }
    }

    private fun copyPdfToPrivateStorage(uri: Uri, name: String): File? {
        return try {
            val directory = File(filesDir, "recent_pdfs").apply { mkdirs() }
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(70)
                .ifBlank { "document.pdf" }
            val target = File(directory, "${uri.toString().hashCode().toUInt().toString(16)}_$safeName")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: return null
            target
        } catch (_: Exception) {
            null
        }
    }

    private fun updateHistoryOpenedAt(uri: String) {
        val item = loadHistory().firstOrNull { it.uri == uri } ?: return
        saveHistory(Uri.parse(item.uri), item.name, item.localPath)
    }

    private fun updatePageIndicator() {
        val layout = recycler.layoutManager as? LinearLayoutManager ?: return
        val first = layout.findFirstVisibleItemPosition()
        val count = renderer?.pageCount ?: 0
        if (first != RecyclerView.NO_POSITION && count > 0) {
            pageIndicator.text = "${first + 1}/$count"
        }
    }

    private fun searchInPdf() {
        val query = searchInput.text.toString().trim()
        val pdfFile = currentPdfLocalFile
        if (query.isEmpty()) {
            searchStatus.text = "Masukkan kata"
            return
        }
        if (pdfFile == null || !pdfFile.exists()) {
            searchStatus.text = "File tidak tersedia"
            return
        }

        searchStatus.text = "Mencari..."
        searchMatches = emptyList()
        searchMatchIndex = -1

        executor.execute {
            val pages = mutableListOf<Int>()
            var failure: String? = null
            try {
                PDDocument.load(pdfFile).use { document ->
                    val stripper = PDFTextStripper()
                    for (pageNumber in 1..document.numberOfPages) {
                        stripper.startPage = pageNumber
                        stripper.endPage = pageNumber
                        val pageText = stripper.getText(document)
                        if (pageText.contains(query, ignoreCase = true)) {
                            pages.add(pageNumber - 1)
                        }
                    }
                }
            } catch (e: Exception) {
                failure = e.message ?: "PDF tidak dapat dibaca"
            }

            runOnUiThread {
                if (failure != null) {
                    searchStatus.text = "Pencarian gagal"
                    Toast.makeText(this, "Pencarian teks gagal: $failure", Toast.LENGTH_LONG).show()
                } else if (pages.isEmpty()) {
                    searchMatches = emptyList()
                    searchStatus.text = "Tidak ditemukan"
                } else {
                    searchMatches = pages
                    searchMatchIndex = 0
                    searchStatus.text = "1/${pages.size}"
                    goToSearchMatch()
                }
            }
        }
    }

    private fun moveSearchResult(direction: Int) {
        if (searchMatches.isEmpty()) return
        searchMatchIndex = (searchMatchIndex + direction + searchMatches.size) % searchMatches.size
        searchStatus.text = "${searchMatchIndex + 1}/${searchMatches.size}"
        goToSearchMatch()
    }

    private fun goToSearchMatch() {
        val page = searchMatches.getOrNull(searchMatchIndex) ?: return
        (recycler.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(page, dp(8))
        pageIndicator.text = "${page + 1}/${renderer?.pageCount ?: 0}"
    }

    private fun showHistory() {
        closePdf()
        recycler.visibility = View.GONE
        historyScroll.visibility = View.VISIBLE
        historyButton.visibility = View.VISIBLE
        zoomControls.visibility = View.GONE
        searchPanel.visibility = View.GONE
        status.text = "Riwayat dokumen"
        pageIndicator.text = ""
        renderHistory()
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        var cursor: Cursor? = null
        try {
            // Query semua kolom karena beberapa File Manager tidak mendukung
            // projection OpenableColumns.DISPLAY_NAME secara langsung.
            cursor = contentResolver.query(uri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val candidateColumns = listOf(
                    OpenableColumns.DISPLAY_NAME,
                    "_display_name",
                    "display_name",
                    "title",
                    "name"
                )
                for (column in candidateColumns) {
                    val index = cursor.getColumnIndex(column)
                    if (index >= 0) {
                        val value = cursor.getString(index)
                        if (!value.isNullOrBlank()) {
                            name = value
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }

        if (name.isNullOrBlank()) name = intent.getStringExtra(Intent.EXTRA_TITLE)
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.takeIf { it.contains('.') && !it.startsWith("document:") }
        }
        return name?.takeIf { it.isNotBlank() } ?: "Dokumen PDF"
    }

    private fun loadHistory(): MutableList<RecentPdf> {
        val result = mutableListOf<RecentPdf>()
        val raw = prefs.getString("recent_pdfs", "[]") ?: "[]"
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                result.add(
                    RecentPdf(
                        item.optString("uri"),
                        item.optString("name", "Dokumen PDF"),
                        item.optLong("openedAt"),
                        item.optString("localPath", "")
                    )
                )
            }
        } catch (_: Exception) {
            prefs.edit().remove("recent_pdfs").apply()
        }
        return result.sortedByDescending { it.openedAt }.toMutableList()
    }

    private fun saveHistory(uri: Uri, name: String, localPath: String) {
        val items = loadHistory()
        items.removeAll { it.uri == uri.toString() }
        items.add(0, RecentPdf(uri.toString(), name, System.currentTimeMillis(), localPath))
        val array = JSONArray()
        items.take(20).forEach { item ->
            array.put(
                JSONObject()
                    .put("uri", item.uri)
                    .put("name", item.name)
                    .put("openedAt", item.openedAt)
                    .put("localPath", item.localPath)
            )
        }
        val retainedPaths = items.take(20).map { it.localPath }.filter { it.isNotBlank() }.toSet()
        File(filesDir, "recent_pdfs").listFiles()?.forEach { file ->
            if (file.absolutePath !in retainedPaths) file.delete()
        }
        prefs.edit().putString("recent_pdfs", array.toString()).apply()
        renderHistory()
    }

    private fun removeHistory(uri: String) {
        val array = JSONArray()
        val removed = loadHistory().firstOrNull { it.uri == uri }
        loadHistory().filterNot { it.uri == uri }.forEach { item ->
            array.put(
                JSONObject()
                    .put("uri", item.uri)
                    .put("name", item.name)
                    .put("openedAt", item.openedAt)
                    .put("localPath", item.localPath)
            )
        }
        removed?.localPath?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
        prefs.edit().putString("recent_pdfs", array.toString()).apply()
        renderHistory()
    }

    private fun renderHistory() {
        if (!::historyList.isInitialized) return
        historyList.removeAllViews()
        val items = loadHistory()

        val heading = TextView(this).apply {
            text = "Riwayat dokumen"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(25, 42, 62))
            setPadding(dp(4), dp(8), dp(4), dp(12))
        }
        historyList.addView(heading)

        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Belum ada dokumen PDF yang dibuka."
                textSize = 14f
                setTextColor(Color.rgb(90, 100, 112))
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            historyList.addView(empty)
            return
        }

        items.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(8), dp(10))
                setBackgroundColor(Color.WHITE)
            }

            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val name = TextView(this).apply {
                text = item.name
                textSize = 15f
                maxLines = 2
                setTextColor(Color.rgb(28, 43, 60))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val date = TextView(this).apply {
                text = "Terakhir dibuka: " + DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM, DateFormat.SHORT
                ).format(Date(item.openedAt))
                textSize = 11f
                setTextColor(Color.rgb(105, 115, 126))
                setPadding(0, dp(4), 0, 0)
            }
            details.addView(name)
            details.addView(date)
            details.setOnClickListener { openPdfFromHistory(item) }
            card.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val open = Button(this).apply {
                text = "Buka"
                isAllCaps = false
                setOnClickListener { openPdfFromHistory(item) }
            }
            card.addView(open)

            val delete = Button(this).apply {
                text = "×"
                contentDescription = "Hapus riwayat"
                isAllCaps = false
                setOnClickListener { removeHistory(item.uri) }
            }
            card.addView(delete)
            historyList.addView(card)

            val divider = View(this).apply {
                setBackgroundColor(Color.rgb(225, 230, 237))
            }
            historyList.addView(divider, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ))
            val space = View(this)
            historyList.addView(space, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8)
            ))
        }
    }

    private fun zoomVisiblePages(factor: Float) {
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i)
            if (child is ZoomableImageView) child.zoomBy(factor)
        }
    }

    private fun resetVisiblePagesZoom() {
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i)
            if (child is ZoomableImageView) child.resetZoom()
        }
    }

    private fun closePdf() {
        recycler.adapter = null
        renderer?.close()
        renderer = null
        fileDescriptor?.close()
        fileDescriptor = null
        currentPdfLocalFile = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        closePdf()
        executor.shutdown()
        super.onDestroy()
    }
}

private class PdfPageAdapter(
    private val pdfRenderer: PdfRenderer,
    private val executor: ExecutorService
) : RecyclerView.Adapter<PdfPageAdapter.PageHolder>() {

    class PageHolder(val image: ZoomableImageView) : RecyclerView.ViewHolder(image)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val image = ZoomableImageView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also {
                it.bottomMargin = (10 * resources.displayMetrics.density).toInt()
            }
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.MATRIX
            setBackgroundColor(Color.WHITE)
            setPadding(8, 8, 8, 8)
        }
        return PageHolder(image)
    }

    override fun getItemCount(): Int = pdfRenderer.pageCount

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        holder.image.tag = position
        holder.image.setImageDrawable(null)
        val displayWidth = holder.image.resources.displayMetrics.widthPixels
        val targetWidth = (displayWidth * 2).coerceIn(1200, 3000)

        executor.execute {
            var bitmap: Bitmap? = null
            try {
                synchronized(pdfRenderer) {
                    pdfRenderer.openPage(position).use { page ->
                        val height = (targetWidth.toFloat() * page.height / page.width)
                            .toInt().coerceAtLeast(1)
                        bitmap = Bitmap.createBitmap(
                            targetWidth, height, Bitmap.Config.ARGB_8888
                        )
                        bitmap?.eraseColor(Color.WHITE)
                        page.render(
                            bitmap!!, null, null,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        )
                    }
                }
            } catch (_: Exception) {
            }

            val rendered = bitmap
            holder.image.post {
                if (holder.image.tag == position && rendered != null) {
                    holder.image.setZoomBitmap(rendered)
                } else {
                    rendered?.recycle()
                }
            }
        }
    }
}

private class ZoomableImageView(context: android.content.Context) :
    AppCompatImageView(context) {

    private val drawMatrix = Matrix()
    private var zoom = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val nextZoom = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
                val factor = nextZoom / zoom
                if (factor != 1f) {
                    drawMatrix.postScale(
                        factor, factor,
                        detector.focusX, detector.focusY
                    )
                    zoom = nextZoom
                    imageMatrix = drawMatrix
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (zoom <= 1.01f) {
                    resetZoom()
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
        isFocusable = true
        adjustViewBounds = true
    }

    fun setZoomBitmap(bitmap: Bitmap) {
        setImageBitmap(bitmap)
        requestLayout()
        post { resetZoom() }
    }

    fun zoomBy(factor: Float) {
        val nextZoom = (zoom * factor).coerceIn(1f, 5f)
        val actualFactor = nextZoom / zoom
        if (actualFactor == 1f) return

        val pivotX = (width - paddingLeft - paddingRight) / 2f + paddingLeft
        val pivotY = (height - paddingTop - paddingBottom) / 2f + paddingTop
        drawMatrix.postScale(actualFactor, actualFactor, pivotX, pivotY)
        zoom = nextZoom
        imageMatrix = drawMatrix
        parent?.requestDisallowInterceptTouchEvent(zoom > 1.01f)
    }

    fun resetZoom() {
        val d = drawable ?: return
        val contentWidth = width - paddingLeft - paddingRight
        if (contentWidth <= 0 || d.intrinsicWidth <= 0) return

        // Fit-to-width: halaman memenuhi lebar layar pada skala normal.
        val fit = contentWidth.toFloat() / d.intrinsicWidth.toFloat()
        drawMatrix.reset()
        drawMatrix.setScale(fit, fit)
        drawMatrix.postTranslate(paddingLeft.toFloat(), paddingTop.toFloat())
        imageMatrix = drawMatrix
        zoom = 1f
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (drawable != null) post { resetZoom() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                // Biarkan RecyclerView melakukan scroll vertikal pada zoom normal.
                parent?.requestDisallowInterceptTouchEvent(zoom > 1.01f)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Kunci gesture dua jari agar tidak diambil alih RecyclerView.
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress &&
                    zoom > 1.01f &&
                    event.pointerCount == 1
                ) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                        .takeIf { it >= 0 } ?: 0
                    val x = event.getX(pointerIndex)
                    val y = event.getY(pointerIndex)
                    drawMatrix.postTranslate(x - lastX, y - lastY)
                    imageMatrix = drawMatrix
                    lastX = x
                    lastY = y
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else if (event.pointerCount >= 2) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val liftedIndex = event.actionIndex
                val liftedId = event.getPointerId(liftedIndex)
                if (liftedId == activePointerId) {
                    val replacementIndex = if (liftedIndex == 0) 1 else 0
                    if (replacementIndex < event.pointerCount) {
                        activePointerId = event.getPointerId(replacementIndex)
                        lastX = event.getX(replacementIndex)
                        lastY = event.getY(replacementIndex)
                    }
                }
                parent?.requestDisallowInterceptTouchEvent(zoom > 1.01f)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(zoom > 1.01f)
            }
        }
        return true
    }
}
