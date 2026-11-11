package com.wahyu.pdfviewer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.InputType
import android.util.LruCache
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.slider.Slider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.Writer
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import com.google.android.material.R as MaterialR

/** Satu hasil pencarian. [rects] sudah dinormalisasi (0..1) terhadap ukuran halaman. */
data class SearchHit(val id: Int, val pageIndex: Int, val rects: List<RectF>)

private const val MAX_ZOOM = 6f
private val NIGHT_FILTER = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

class MainActivity : AppCompatActivity() {

    private companion object {
        const val MENU_SEARCH = 1
        const val MENU_NIGHT = 2
    }

    private lateinit var root: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var content: FrameLayout
    private lateinit var recycler: RecyclerView
    private lateinit var historyScroll: ScrollView
    private lateinit var historyList: LinearLayout
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var pageSlider: Slider
    private lateinit var searchBar: MaterialCardView
    private lateinit var searchInput: EditText
    private lateinit var searchCount: TextView

    private var renderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var currentPdfLocalFile: File? = null
    private var nightMode = false
    private var sliderDragging = false

    // --- state pencarian ---
    private var hits: List<SearchHit> = emptyList()
    private var hitsByPage: Map<Int, List<SearchHit>> = emptyMap()
    private var currentHit = -1
    private var lastQuery = ""
    private var pendingHit: SearchHit? = null
    private val searchToken = AtomicInteger(0)
    private val searchRunnable = Runnable { runSearch() }

    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val searchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("pdf_viewer_preferences", MODE_PRIVATE) }

    private data class RecentPdf(
        val uri: String,
        val name: String,
        val openedAt: Long,
        val localPath: String = ""
    )

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // Tidak semua penyedia dokumen mendukung izin persisten.
            }
            openPdf(uri)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        nightMode = prefs.getBoolean("night_mode", false)
        buildScreen()
        setMode(false)
        renderHistory()

        onBackPressedDispatcher.addCallback(this) {
            when {
                searchBar.visibility == View.VISIBLE -> hideSearchBar()
                recycler.visibility == View.VISIBLE -> showHistory()
                else -> finish()
            }
        }
        handleIncomingPdf(intent)
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        handleIncomingPdf(newIntent)
    }

    override fun onDestroy() {
        searchToken.incrementAndGet()
        closePdf()
        executor.shutdown()
        searchExecutor.shutdown()
        super.onDestroy()
    }

    // Menerima PDF yang dibuka dari File Manager atau aplikasi lain.
    private fun handleIncomingPdf(incomingIntent: Intent?) {
        if (incomingIntent == null || incomingIntent.action != Intent.ACTION_VIEW) return
        val uri = incomingIntent.data ?: return
        val readFlag = incomingIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (readFlag != 0) {
            try {
                contentResolver.takePersistableUriPermission(uri, readFlag)
            } catch (_: Exception) {
                // Izin sementara tetap cukup untuk membuka PDF saat ini.
            }
        }
        openPdf(uri)
    }

    // ------------------------------------------------------------------ UI

    private fun themeColor(attr: Int, fallback: Int): Int =
        MaterialColors.getColor(this, attr, fallback)

    private fun buildScreen() {
        val surface = themeColor(MaterialR.attr.colorSurface, Color.WHITE)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        toolbar = MaterialToolbar(this).apply {
            title = "PDF Viewer"
            menu.add(Menu.NONE, MENU_SEARCH, 0, "Cari").apply {
                setIcon(R.drawable.ic_search)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            menu.add(Menu.NONE, MENU_NIGHT, 1, "Mode malam").apply {
                setIcon(R.drawable.ic_dark_mode)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            setNavigationOnClickListener { showHistory() }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SEARCH -> {
                        if (searchBar.visibility == View.VISIBLE) hideSearchBar() else showSearchBar()
                        true
                    }
                    MENU_NIGHT -> {
                        toggleNightMode()
                        true
                    }
                    else -> false
                }
            }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)))

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Riwayat
        historyScroll = ScrollView(this)
        historyList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(96))
        }
        historyScroll.addView(historyList)
        content.addView(historyScroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Pembaca
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(88))
            visibility = View.GONE
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    updatePageIndicator()
                }
            })
        }
        applyReadingBackground()
        content.addView(recycler, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Bar pencarian gaya Chrome
        searchBar = buildSearchBar()
        content.addView(
            searchBar,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
                setMargins(dp(12), 0, dp(12), dp(12))
            }
        )

        // FAB buka PDF
        fab = ExtendedFloatingActionButton(this).apply {
            text = "Buka PDF"
            setIconResource(R.drawable.ic_add)
            setOnClickListener { choosePdf() }
        }
        content.addView(
            fab,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(dp(16), dp(16), dp(16), dp(16))
            }
        )

        // Slider halaman
        pageSlider = Slider(this).apply {
            stepSize = 1f
            visibility = View.GONE
            setLabelFormatter { value -> value.toInt().toString() }
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    (recycler.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(value.toInt() - 1, 0)
                }
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) { sliderDragging = true }
                override fun onStopTrackingTouch(slider: Slider) { sliderDragging = false }
            })
        }
        root.addView(
            pageSlider,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                setMargins(dp(16), 0, dp(16), 0)
            }
        )

        setContentView(root)
    }

    private fun buildSearchBar(): MaterialCardView {
        val onSurface = themeColor(MaterialR.attr.colorOnSurface, Color.BLACK)
        val onSurfaceVariant = themeColor(MaterialR.attr.colorOnSurfaceVariant, Color.DKGRAY)

        val card = MaterialCardView(this).apply {
            radius = dp(28).toFloat()
            cardElevation = dp(6).toFloat()
            setCardBackgroundColor(themeColor(MaterialR.attr.colorSurfaceContainerHigh, Color.WHITE))
            visibility = View.GONE
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(4), dp(6), dp(4))
        }

        searchInput = EditText(this).apply {
            hint = "Cari di PDF"
            textSize = 16f
            setTextColor(onSurface)
            setHintTextColor(onSurfaceVariant)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    onSearchSubmit()
                    true
                } else false
            }
            doAfterTextChanged {
                removeCallbacks(searchRunnable)
                postDelayed(searchRunnable, 450)
            }
        }
        row.addView(searchInput, LinearLayout.LayoutParams(0, dp(48), 1f))

        searchCount = TextView(this).apply {
            textSize = 14f
            setTextColor(onSurfaceVariant)
            setPadding(dp(8), 0, dp(8), 0)
        }
        row.addView(searchCount)

        row.addView(iconButton(R.drawable.ic_arrow_up, "Hasil sebelumnya") { moveHit(-1) })
        row.addView(iconButton(R.drawable.ic_arrow_down, "Hasil berikutnya") { moveHit(1) })
        row.addView(iconButton(R.drawable.ic_close, "Tutup pencarian") { hideSearchBar() })

        card.addView(row)
        return card
    }

    private fun iconButton(iconRes: Int, description: String, onClick: () -> Unit): ImageButton {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        return ImageButton(this).apply {
            setImageResource(iconRes)
            contentDescription = description
            setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
    }

    private fun setMode(reading: Boolean) {
        recycler.visibility = if (reading) View.VISIBLE else View.GONE
        historyScroll.visibility = if (reading) View.GONE else View.VISIBLE
        fab.visibility = if (reading) View.GONE else View.VISIBLE
        pageSlider.visibility =
            if (reading && (renderer?.pageCount ?: 0) > 1) View.VISIBLE else View.GONE
        toolbar.menu.findItem(MENU_SEARCH)?.isVisible = reading
        toolbar.menu.findItem(MENU_NIGHT)?.isVisible = reading
        toolbar.navigationIcon =
            if (reading) AppCompatResources.getDrawable(this, R.drawable.ic_back) else null
        if (!reading) {
            toolbar.title = "PDF Viewer"
            toolbar.subtitle = null
            hideSearchBar()
        }
    }

    private fun applyReadingBackground() {
        recycler.setBackgroundColor(
            if (nightMode) Color.rgb(18, 18, 18)
            else themeColor(MaterialR.attr.colorSurfaceContainerHigh, Color.rgb(225, 230, 237))
        )
    }

    private fun toggleNightMode() {
        nightMode = !nightMode
        prefs.edit().putBoolean("night_mode", nightMode).apply()
        applyReadingBackground()
        (recycler.adapter as? PdfPageAdapter)?.nightMode = nightMode
        for (i in 0 until recycler.childCount) {
            (recycler.getChildAt(i) as? ZoomableImageView)?.setNight(nightMode)
        }
        Toast.makeText(
            this,
            if (nightMode) "Mode malam aktif" else "Mode malam nonaktif",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updatePageIndicator() {
        val layout = recycler.layoutManager as? LinearLayoutManager ?: return
        val count = renderer?.pageCount ?: 0
        if (count <= 0) return
        var page = currentPageIndex()
        if (page == RecyclerView.NO_POSITION) page = layout.findFirstVisibleItemPosition()
        if (page == RecyclerView.NO_POSITION) return
        toolbar.subtitle = "${page + 1}/$count"
        if (!sliderDragging && pageSlider.visibility == View.VISIBLE) {
            pageSlider.value = (page + 1).toFloat().coerceIn(pageSlider.valueFrom, pageSlider.valueTo)
        }
    }

    private fun currentPageIndex(): Int {
        val child = recycler.findChildViewUnder(recycler.width / 2f, recycler.height / 2f)
        val pos = child?.let { recycler.getChildAdapterPosition(it) } ?: RecyclerView.NO_POSITION
        if (pos != RecyclerView.NO_POSITION) return pos
        return (recycler.layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
    }

    // ------------------------------------------------------------------ buka PDF

    private fun choosePdf() = pickPdf.launch(arrayOf("application/pdf"))

    private fun openPdf(uri: Uri) {
        try {
            closePdf()
            val name = displayName(uri)
            val localFile = copyPdfToPrivateStorage(uri, name)
            val descriptor = if (localFile != null) {
                ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IOException("Dokumen tidak dapat dibuka.")
            }
            showDocument(descriptor, localFile, name)
            saveHistory(uri, name, localFile?.absolutePath ?: "")
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
                val descriptor = ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY)
                showDocument(descriptor, local, item.name)
                updateHistoryOpenedAt(item.uri)
            } else {
                openPdf(Uri.parse(item.uri))
            }
        } catch (e: Exception) {
            closePdf()
            Toast.makeText(this, "Dokumen riwayat tidak dapat dibuka: ${e.message}", Toast.LENGTH_LONG).show()
            removeHistory(item.uri)
        }
    }

    private fun showDocument(descriptor: ParcelFileDescriptor, file: File?, name: String) {
        val newRenderer = try {
            PdfRenderer(descriptor)
        } catch (e: Exception) {
            try { descriptor.close() } catch (_: IOException) {}
            throw e
        }
        fileDescriptor = descriptor
        renderer = newRenderer
        currentPdfLocalFile = file

        hideSearchBar()
        recycler.adapter = PdfPageAdapter(newRenderer, executor, nightMode) { page -> onPageReady(page) }
        recycler.scrollToPosition(0)

        val count = newRenderer.pageCount
        pageSlider.valueFrom = 1f
        pageSlider.valueTo = maxOf(2f, count.toFloat())
        pageSlider.value = 1f

        setMode(true)
        applyReadingBackground()
        toolbar.title = name
        toolbar.subtitle = "1/$count"
    }

    private fun showHistory() {
        closePdf()
        setMode(false)
        renderHistory()
    }

    private fun closePdf() {
        if (!::recycler.isInitialized) return
        resetSearchState()
        recycler.adapter = null
        val r = renderer
        renderer = null
        if (r != null) {
            synchronized(r) {
                try { r.close() } catch (_: Exception) {}
            }
        }
        try { fileDescriptor?.close() } catch (_: IOException) {}
        fileDescriptor = null
        currentPdfLocalFile = null
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

    private fun displayName(uri: Uri): String {
        var name: String? = null
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val candidateColumns = listOf(
                    OpenableColumns.DISPLAY_NAME, "_display_name", "display_name", "title", "name"
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

    // ------------------------------------------------------------------ pencarian

    private fun showSearchBar() {
        searchBar.visibility = View.VISIBLE
        searchInput.requestFocus()
        searchInput.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun hideSearchBar() {
        searchBar.visibility = View.GONE
        hideKeyboard()
        searchInput.setText("")
        searchInput.removeCallbacks(searchRunnable)
        resetSearchState()
        searchCount.text = ""
        refreshHighlights()
    }

    private fun resetSearchState() {
        searchToken.incrementAndGet()
        hits = emptyList()
        hitsByPage = emptyMap()
        currentHit = -1
        lastQuery = ""
        pendingHit = null
    }

    private fun onSearchSubmit() {
        searchInput.removeCallbacks(searchRunnable)
        val query = searchInput.text.toString().trim()
        if (query.isNotEmpty() && query == lastQuery && hits.isNotEmpty()) moveHit(1) else runSearch()
        hideKeyboard()
    }

    private fun runSearch() {
        val query = searchInput.text.toString().trim()
        val file = currentPdfLocalFile
        val token = searchToken.incrementAndGet()
        val normalizedQuery = query.filterNot { it.isWhitespace() }.lowercase()

        hits = emptyList()
        hitsByPage = emptyMap()
        currentHit = -1
        pendingHit = null
        refreshHighlights()

        if (normalizedQuery.isEmpty()) {
            lastQuery = ""
            searchCount.text = ""
            return
        }
        if (file == null || !file.exists()) {
            searchCount.text = "0/0"
            return
        }
        lastQuery = query
        searchCount.text = "…"

        searchExecutor.execute {
            val found = ArrayList<SearchHit>()
            var failure: String? = null
            try {
                PDDocument.load(file).use { document ->
                    HitCollector(normalizedQuery, found, token, searchToken)
                        .writeText(document, DiscardWriter())
                }
            } catch (_: SearchCancelled) {
                return@execute
            } catch (e: OutOfMemoryError) {
                failure = "Memori tidak cukup"
            } catch (e: Exception) {
                failure = e.message ?: "PDF tidak dapat dibaca"
            }

            runOnUiThread {
                if (token != searchToken.get() || isFinishing || isDestroyed) return@runOnUiThread
                if (failure != null) {
                    searchCount.text = "Gagal"
                    Toast.makeText(this, "Pencarian teks gagal: $failure", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                hits = found
                hitsByPage = found.groupBy { it.pageIndex }
                currentHit = if (found.isEmpty()) -1 else {
                    val page = currentPageIndex()
                    found.indexOfFirst { it.pageIndex >= page }.let { if (it < 0) 0 else it }
                }
                updateSearchCount()
                refreshHighlights()
                if (currentHit >= 0) goToCurrentHit()
            }
        }
    }

    private fun updateSearchCount() {
        searchCount.text = when {
            searchInput.text.isNullOrBlank() -> ""
            hits.isEmpty() -> "0/0"
            else -> "${currentHit + 1}/${hits.size}"
        }
    }

    private fun moveHit(direction: Int) {
        if (hits.isEmpty()) return
        currentHit = (currentHit + direction + hits.size) % hits.size
        updateSearchCount()
        refreshHighlights()
        goToCurrentHit()
    }

    private fun refreshHighlights() {
        val adapter = recycler.adapter as? PdfPageAdapter ?: return
        adapter.updateHighlights(hitsByPage, hits.getOrNull(currentHit)?.id ?: -1)
        for (i in 0 until recycler.childCount) {
            val view = recycler.getChildAt(i) as? ZoomableImageView ?: continue
            adapter.applyHighlights(view)
        }
    }

    private fun goToCurrentHit() {
        val hit = hits.getOrNull(currentHit) ?: return
        val layout = recycler.layoutManager as? LinearLayoutManager ?: return
        pendingHit = hit
        layout.scrollToPositionWithOffset(hit.pageIndex, 0)
        recycler.doOnNextLayout { refineScrollToHit() }
    }

    /** Setelah halaman termuat, geser sedikit supaya hasil berada di sepertiga atas layar. */
    private fun refineScrollToHit() {
        val hit = pendingHit ?: return
        val layout = recycler.layoutManager as? LinearLayoutManager ?: return
        val view = layout.findViewByPosition(hit.pageIndex) ?: return
        if (view.height < dp(120)) return // halaman belum termuat, tunggu onPageReady
        val top = hit.rects.minOfOrNull { it.top } ?: 0f
        val targetY = view.top + top * view.height
        recycler.scrollBy(0, (targetY - recycler.height / 3f).toInt())
        pendingHit = null
    }

    private fun onPageReady(position: Int) {
        if (pendingHit?.pageIndex == position) {
            recycler.doOnNextLayout { refineScrollToHit() }
        }
    }

    // ------------------------------------------------------------------ riwayat

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

    private fun writeHistory(items: List<RecentPdf>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("uri", item.uri)
                    .put("name", item.name)
                    .put("openedAt", item.openedAt)
                    .put("localPath", item.localPath)
            )
        }
        prefs.edit().putString("recent_pdfs", array.toString()).apply()
    }

    private fun saveHistory(uri: Uri, name: String, localPath: String) {
        val items = loadHistory()
        items.removeAll { it.uri == uri.toString() }
        items.add(0, RecentPdf(uri.toString(), name, System.currentTimeMillis(), localPath))
        val kept = items.take(20)
        writeHistory(kept)
        val retainedPaths = kept.map { it.localPath }.filter { it.isNotBlank() }.toSet()
        File(filesDir, "recent_pdfs").listFiles()?.forEach { file ->
            if (file.absolutePath !in retainedPaths) file.delete()
        }
        renderHistory()
    }

    private fun updateHistoryOpenedAt(uri: String) {
        val item = loadHistory().firstOrNull { it.uri == uri } ?: return
        saveHistory(Uri.parse(item.uri), item.name, item.localPath)
    }

    private fun removeHistory(uri: String) {
        val all = loadHistory()
        all.firstOrNull { it.uri == uri }?.localPath
            ?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
        writeHistory(all.filterNot { it.uri == uri })
        renderHistory()
    }

    private fun renderHistory() {
        if (!::historyList.isInitialized) return
        historyList.removeAllViews()
        val items = loadHistory()
        val onSurface = themeColor(MaterialR.attr.colorOnSurface, Color.BLACK)
        val onSurfaceVariant = themeColor(MaterialR.attr.colorOnSurfaceVariant, Color.DKGRAY)

        historyList.addView(TextView(this).apply {
            text = "Riwayat dokumen"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(onSurface)
            setPadding(dp(4), dp(8), dp(4), dp(12))
        })

        if (items.isEmpty()) {
            historyList.addView(TextView(this).apply {
                text = "Belum ada dokumen PDF yang dibuka.\nKetuk “Buka PDF” untuk mulai membaca."
                textSize = 14f
                setTextColor(onSurfaceVariant)
                setPadding(dp(4), dp(8), dp(4), dp(8))
            })
            return
        }

        items.forEach { item ->
            val card = MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = themeColor(MaterialR.attr.colorOutlineVariant, Color.LTGRAY)
                setCardBackgroundColor(themeColor(MaterialR.attr.colorSurfaceContainerLow, Color.WHITE))
                isClickable = true
                isFocusable = true
                setOnClickListener { openPdfFromHistory(item) }
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(4), dp(12))
            }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(TextView(this).apply {
                text = item.name
                textSize = 15f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(onSurface)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            details.addView(TextView(this).apply {
                text = "Terakhir dibuka: " + DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM, DateFormat.SHORT
                ).format(Date(item.openedAt))
                textSize = 12f
                setTextColor(onSurfaceVariant)
                setPadding(0, dp(4), 0, 0)
            })
            row.addView(details, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(iconButton(R.drawable.ic_delete, "Hapus riwayat") { removeHistory(item.uri) })
            card.addView(row)
            historyList.addView(card)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

// ====================================================================== pencarian teks

private class SearchCancelled : RuntimeException()

private class DiscardWriter : Writer() {
    override fun write(cbuf: CharArray, off: Int, len: Int) {}
    override fun flush() {}
    override fun close() {}
}

/**
 * Membaca teks PDF halaman demi halaman (urutan baca), mencari [query] (tanpa spasi,
 * huruf kecil) dan menyimpan kotak posisi tiap huruf yang cocok sebagai [SearchHit].
 */
private class HitCollector(
    private val query: String,
    private val out: MutableList<SearchHit>,
    private val token: Int,
    private val currentToken: AtomicInteger
) : PDFTextStripper() {

    private val buffer = StringBuilder()
    private val positions = ArrayList<TextPosition>()

    init {
        sortByPosition = true
    }

    override fun startPage(page: PDPage) {
        if (currentToken.get() != token) throw SearchCancelled()
        buffer.setLength(0)
        positions.clear()
        super.startPage(page)
    }

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        for (tp in textPositions) {
            val unicode = tp.unicode ?: continue
            for (ch in unicode) {
                if (!ch.isWhitespace()) {
                    buffer.append(ch.lowercaseChar())
                    positions.add(tp)
                }
            }
        }
    }

    override fun endPage(page: PDPage) {
        findMatches(page)
        super.endPage(page)
    }

    private fun findMatches(page: PDPage) {
        if (buffer.length < query.length) return
        val box = page.cropBox
        val rotation = ((page.rotation % 360) + 360) % 360
        val swap = rotation == 90 || rotation == 270
        val pageWidth = if (swap) box.height else box.width
        val pageHeight = if (swap) box.width else box.height
        if (pageWidth <= 0f || pageHeight <= 0f) return

        val haystack = buffer.toString()
        var from = 0
        while (true) {
            val at = haystack.indexOf(query, from)
            if (at < 0) break
            out.add(SearchHit(out.size, currentPageNo - 1, buildRects(at, at + query.length, pageWidth, pageHeight)))
            from = at + query.length
        }
    }

    /** Gabungkan huruf-huruf yang sebaris menjadi satu kotak per baris. */
    private fun buildRects(from: Int, to: Int, pageWidth: Float, pageHeight: Float): List<RectF> {
        val rects = ArrayList<RectF>()
        var current: RectF? = null
        var baseline = 0f
        for (i in from until minOf(to, positions.size)) {
            val tp = positions[i]
            val h = tp.heightDir.coerceAtLeast(1f)
            val r = RectF(
                (tp.xDirAdj / pageWidth).coerceIn(0f, 1f),
                ((tp.yDirAdj - h) / pageHeight).coerceIn(0f, 1f),
                ((tp.xDirAdj + tp.widthDirAdj) / pageWidth).coerceIn(0f, 1f),
                ((tp.yDirAdj + h * 0.25f) / pageHeight).coerceIn(0f, 1f)
            )
            val c = current
            if (c != null && abs(tp.yDirAdj - baseline) < h * 0.6f && r.left - c.right < 0.05f) {
                c.union(r)
            } else {
                rects.add(r)
                current = r
                baseline = tp.yDirAdj
            }
        }
        return rects
    }
}

// ====================================================================== adapter

private class PdfPageAdapter(
    private val pdfRenderer: PdfRenderer,
    private val executor: ExecutorService,
    var nightMode: Boolean,
    private val onPageReady: (Int) -> Unit
) : RecyclerView.Adapter<PdfPageAdapter.PageHolder>() {

    class PageHolder(val image: ZoomableImageView) : RecyclerView.ViewHolder(image)

    private var hitsByPage: Map<Int, List<SearchHit>> = emptyMap()
    private var currentHitId = -1
    private val ratios = HashMap<Int, Float>() // tinggi/lebar tiap halaman (hanya di main thread)

    private val cache = object : LruCache<Int, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 5).toInt()
    ) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
    }

    fun updateHighlights(map: Map<Int, List<SearchHit>>, currentId: Int) {
        hitsByPage = map
        currentHitId = currentId
    }

    fun applyHighlights(view: ZoomableImageView) {
        val position = view.tag as? Int ?: return
        view.setHighlights(hitsByPage[position].orEmpty(), currentHitId)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val density = parent.resources.displayMetrics.density
        val margin = (8 * density).toInt()
        val image = ZoomableImageView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(margin, 0, margin, margin) }
        }
        return PageHolder(image)
    }

    override fun getItemCount(): Int = pdfRenderer.pageCount

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val view = holder.image
        view.tag = position
        view.setNight(nightMode)
        view.setHighlights(hitsByPage[position].orEmpty(), currentHitId)

        val cached = cache.get(position)
        if (cached != null) {
            view.setZoomBitmap(cached)
            onPageReady(position)
            return
        }

        // Tinggi sementara supaya RecyclerView tidak mengira semua halaman muat di layar.
        val displayWidth = view.resources.displayMetrics.widthPixels
        val pageWidth = (displayWidth - 16 * view.resources.displayMetrics.density).toInt()
        view.clearPage()
        view.minimumHeight = (pageWidth * (ratios[position] ?: 1.414f)).toInt()

        val targetWidth = (displayWidth * 1.5f).toInt().coerceIn(900, 2400)
        executor.execute {
            if (view.tag != position) return@execute // sudah di-scroll jauh, lewati
            var rendered: Bitmap? = null
            try {
                synchronized(pdfRenderer) {
                    pdfRenderer.openPage(position).use { page ->
                        val height = (targetWidth.toFloat() * page.height / page.width)
                            .toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        rendered = bitmap
                    }
                }
            } catch (_: Exception) {
            }
            val bitmap = rendered ?: return@execute
            cache.put(position, bitmap)
            view.post {
                if (view.tag == position) {
                    ratios[position] = bitmap.height.toFloat() / bitmap.width
                    view.setZoomBitmap(bitmap)
                    onPageReady(position)
                }
            }
        }
    }

    override fun onViewRecycled(holder: PageHolder) {
        holder.image.tag = null
        holder.image.clearPage()
        super.onViewRecycled(holder)
    }
}

// ====================================================================== view halaman

private class ZoomableImageView(context: Context) : AppCompatImageView(context) {

    private val drawMatrix = Matrix()
    private val tmpRect = RectF()
    private var zoom = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private var hits: List<SearchHit> = emptyList()
    private var currentHitId = -1

    // Kuning untuk semua hasil, oranye untuk hasil yang sedang aktif (seperti Chrome).
    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 235, 0)
        style = Paint.Style.FILL
    }
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(175, 255, 140, 0)
        style = Paint.Style.FILL
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoom > 1.05f) resetZoom() else zoomTo(2.5f, e.x, e.y)
                return true
            }
        }
    )

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomTo(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
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
        adjustViewBounds = true
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.WHITE)
    }

    fun setNight(enabled: Boolean) {
        if (enabled) setColorFilter(NIGHT_FILTER) else clearColorFilter()
        setBackgroundColor(if (enabled) Color.BLACK else Color.WHITE)
    }

    fun setHighlights(newHits: List<SearchHit>, currentId: Int) {
        hits = newHits
        currentHitId = currentId
        invalidate()
    }

    fun setZoomBitmap(bitmap: Bitmap) {
        minimumHeight = 0
        setImageBitmap(bitmap)
        requestLayout()
        resetZoom()
        post { resetZoom() }
    }

    fun clearPage() {
        setImageDrawable(null)
        hits = emptyList()
        zoom = 1f
        drawMatrix.reset()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = drawable ?: return
        if (hits.isEmpty()) return
        val bw = d.intrinsicWidth.toFloat()
        val bh = d.intrinsicHeight.toFloat()
        if (bw <= 0f || bh <= 0f) return

        // Gambar hasil biasa dulu, hasil aktif terakhir supaya berada di atas.
        for (pass in 0..1) {
            for (hit in hits) {
                val isCurrent = hit.id == currentHitId
                if (isCurrent != (pass == 1)) continue
                val paint = if (isCurrent) currentPaint else normalPaint
                for (r in hit.rects) {
                    tmpRect.set(r.left * bw, r.top * bh, r.right * bw, r.bottom * bh)
                    drawMatrix.mapRect(tmpRect)
                    canvas.drawRoundRect(tmpRect, 3f, 3f, paint)
                }
            }
        }
    }

    private fun zoomTo(target: Float, px: Float, py: Float) {
        val next = target.coerceIn(1f, MAX_ZOOM)
        val factor = next / zoom
        if (factor == 1f) return
        drawMatrix.postScale(factor, factor, px, py)
        zoom = next
        clampMatrix()
        imageMatrix = drawMatrix
        parent?.requestDisallowInterceptTouchEvent(zoom > 1.01f)
    }

    fun resetZoom() {
        val d = drawable ?: return
        if (width <= 0 || d.intrinsicWidth <= 0) return
        val fit = width.toFloat() / d.intrinsicWidth.toFloat()
        drawMatrix.reset()
        drawMatrix.setScale(fit, fit)
        imageMatrix = drawMatrix
        zoom = 1f
        invalidate()
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    /** Menjaga halaman tetap menutupi view. Mengembalikan koreksi vertikal yang diterapkan. */
    private fun clampMatrix(): Float {
        val d = drawable ?: return 0f
        tmpRect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(tmpRect)
        var dx = 0f
        var dy = 0f
        if (tmpRect.width() <= width) {
            dx = (width - tmpRect.width()) / 2f - tmpRect.left
        } else if (tmpRect.left > 0) {
            dx = -tmpRect.left
        } else if (tmpRect.right < width) {
            dx = width - tmpRect.right
        }
        if (tmpRect.height() <= height) {
            dy = (height - tmpRect.height()) / 2f - tmpRect.top
        } else if (tmpRect.top > 0) {
            dy = -tmpRect.top
        } else if (tmpRect.bottom < height) {
            dy = height - tmpRect.bottom
        }
        drawMatrix.postTranslate(dx, dy)
        return dy
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (drawable != null) post { resetZoom() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(zoom > 1.01f)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && zoom > 1.01f && event.pointerCount == 1) {
                    val index = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: 0
                    val x = event.getX(index)
                    val y = event.getY(index)
                    drawMatrix.postTranslate(x - lastX, y - lastY)
                    val verticalCorrection = clampMatrix()
                    imageMatrix = drawMatrix
                    lastX = x
                    lastY = y
                    // Saat sudah mentok di tepi atas/bawah halaman, serahkan ke RecyclerView
                    // supaya dokumen tetap bisa di-scroll walau sedang di-zoom.
                    parent?.requestDisallowInterceptTouchEvent(abs(verticalCorrection) < 0.5f)
                } else if (event.pointerCount >= 2) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val liftedIndex = event.actionIndex
                if (event.getPointerId(liftedIndex) == activePointerId) {
                    val replacement = if (liftedIndex == 0) 1 else 0
                    if (replacement < event.pointerCount) {
                        activePointerId = event.getPointerId(replacement)
                        lastX = event.getX(replacement)
                        lastY = event.getY(replacement)
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
