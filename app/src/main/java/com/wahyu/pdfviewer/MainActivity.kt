package com.wahyu.pdfviewer

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PointF
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
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
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
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt
import com.google.android.material.R as MaterialR
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Satu hasil pencarian. [rects] sudah dinormalisasi (0..1) terhadap ukuran halaman. */
data class SearchHit(val id: Int, val pageIndex: Int, val rects: List<RectF>)

/** Satu kata di halaman PDF beserta kotaknya (dinormalisasi 0..1). */
class WordBox(val text: String, val rect: RectF)

/** Pilihan teks pada satu halaman: kata ke-[start] sampai kata ke-[end]. */
class Selection(val pageIndex: Int, val words: List<WordBox>, val anchor: Int) {
    var start: Int = anchor
    var end: Int = anchor
}

/** Callback dari tampilan halaman ke Activity. Koordinat sudah dinormalisasi (0..1). */
interface PageListener {
    fun onLongPress(pageIndex: Int, nx: Float, ny: Float)
    fun onExtend(pageIndex: Int, nx: Float, ny: Float)
    fun onDragHandle(pageIndex: Int, handle: Int, nx: Float, ny: Float)
    fun onTap()
}

private const val MAX_ZOOM = 4f
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
    const val MENU_GOTO = 3
    const val MENU_SHARE = 4
}

    private lateinit var root: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var content: FrameLayout
    private lateinit var zoomLayout: ZoomLayout
    private lateinit var recycler: RecyclerView
    private lateinit var historyScroll: ScrollView
    private lateinit var historyList: LinearLayout
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var pageSlider: Slider
    private lateinit var searchBar: MaterialCardView
    private lateinit var searchInput: EditText
    private lateinit var searchCount: TextView
    private lateinit var selectionBar: MaterialCardView

    private var renderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var currentPdfLocalFile: File? = null
    private var nightMode = false
    private var sliderDragging = false
    private var pageKey: String? = null
    private var lastPage = 0
    // --- state pencarian ---
    private var hits: List<SearchHit> = emptyList()
    private var hitsByPage: Map<Int, List<SearchHit>> = emptyMap()
    private var currentHit = -1
    private var lastQuery = ""
    private var pendingHit: SearchHit? = null
    private val searchToken = AtomicInteger(0)
    private val searchRunnable = Runnable { runSearch() }

    // --- state pilih teks ---
    private var selection: Selection? = null
    private var extractor: WordExtractor? = null

    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val searchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val wordExecutor: ExecutorService = Executors.newSingleThreadExecutor()
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

    private val pageListener: PageListener = object : PageListener {
        override fun onLongPress(pageIndex: Int, nx: Float, ny: Float) {
            startSelectionAt(pageIndex, nx, ny)
        }

        override fun onExtend(pageIndex: Int, nx: Float, ny: Float) {
            val s = selection ?: return
            if (s.pageIndex != pageIndex) return
            val i = nearestWord(s.words, nx, ny, Float.MAX_VALUE)
            if (i < 0) return
            val newStart = minOf(s.anchor, i)
            val newEnd = maxOf(s.anchor, i)
            if (newStart == s.start && newEnd == s.end) return
            s.start = newStart
            s.end = newEnd
            refreshSelection()
        }

        override fun onDragHandle(pageIndex: Int, handle: Int, nx: Float, ny: Float) {
            val s = selection ?: return
            if (s.pageIndex != pageIndex) return
            val i = nearestWord(s.words, nx, ny, Float.MAX_VALUE)
            if (i < 0) return
            val oldStart = s.start
            val oldEnd = s.end
            if (handle == 0) s.start = minOf(i, s.end) else s.end = maxOf(i, s.start)
            if (s.start != oldStart || s.end != oldEnd) refreshSelection()
        }

        override fun onTap() {
            if (selection != null) clearSelection()
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
                selection != null -> clearSelection()
                searchBar.visibility == View.VISIBLE -> hideSearchBar()
                zoomLayout.visibility == View.VISIBLE -> showHistory()
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
        wordExecutor.shutdown()
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
            setTitleTextAppearance(context, R.style.ToolbarTitle)
            setSubtitleTextAppearance(context, R.style.ToolbarSubtitle)
            menu.add(Menu.NONE, MENU_SEARCH, 0, "Cari").apply {
                setIcon(R.drawable.ic_search)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            menu.add(Menu.NONE, MENU_NIGHT, 1, "Mode malam").apply {
                setIcon(R.drawable.ic_dark_mode)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            menu.add(Menu.NONE, MENU_GOTO, 2, "Ke halaman…").apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
            menu.add(Menu.NONE, MENU_SHARE, 3, "Bagikan PDF").apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
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
                    MENU_GOTO -> { showGoToPageDialog(); true }
                    MENU_SHARE -> { sharePdf(); true }
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

        // Pembaca: RecyclerView di dalam ZoomLayout (zoom berlaku untuk seluruh dokumen)
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(88))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    updatePageIndicator()
                }
            })
        }
        zoomLayout = ZoomLayout(this).apply {
            baseBottomPadding = dp(88)
            visibility = View.GONE
            onZoomSettled = { refreshHighlights() }
            addView(recycler, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        applyReadingBackground()
        content.addView(zoomLayout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Bar pencarian gaya Chrome
        searchBar = buildSearchBar()
        content.addView(
            searchBar,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
                setMargins(dp(12), 0, dp(12), dp(12))
            }
        )

        // Bar aksi pilih teks (Salin / Pilih semua)
        selectionBar = buildSelectionBar()
        content.addView(
            selectionBar,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
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

    private fun buildSelectionBar(): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = dp(6).toFloat()
            setCardBackgroundColor(themeColor(MaterialR.attr.colorSurfaceContainerHigh, Color.WHITE))
            visibility = View.GONE
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(4), dp(2))
        }
        row.addView(textAction("Salin") { copySelection() })
        row.addView(textAction("Pilih semua") { selectAllOnPage() })
        row.addView(iconButton(R.drawable.ic_close, "Batal pilih") { clearSelection() })
        card.addView(row)
        return card
    }

    private fun textAction(label: String, onClick: () -> Unit): TextView {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return TextView(this).apply {
            text = label
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(themeColor(MaterialR.attr.colorPrimary, Color.BLUE))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
        }
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
        zoomLayout.visibility = if (reading) View.VISIBLE else View.GONE
        historyScroll.visibility = if (reading) View.GONE else View.VISIBLE
        fab.visibility = if (reading) View.GONE else View.VISIBLE
        pageSlider.visibility =
            if (reading && (renderer?.pageCount ?: 0) > 1) View.VISIBLE else View.GONE
        toolbar.menu.findItem(MENU_SEARCH)?.isVisible = reading
        toolbar.menu.findItem(MENU_NIGHT)?.isVisible = reading
        toolbar.menu.findItem(MENU_GOTO)?.isVisible = reading
        toolbar.menu.findItem(MENU_SHARE)?.isVisible = reading
        toolbar.navigationIcon =
            if (reading) AppCompatResources.getDrawable(this, R.drawable.ic_back) else null
        if (!reading) {
            toolbar.title = "PDF Viewer"
            toolbar.subtitle = null
            hideSearchBar()
        }
    }

    private fun applyReadingBackground() {
        val color = if (nightMode) Color.rgb(18, 18, 18)
        else themeColor(MaterialR.attr.colorSurfaceContainerHigh, Color.rgb(225, 230, 237))
        recycler.setBackgroundColor(color)
        zoomLayout.setBackgroundColor(color)
    }

    private fun toggleNightMode() {
        nightMode = !nightMode
        prefs.edit().putBoolean("night_mode", nightMode).apply()
        applyReadingBackground()
        (recycler.adapter as? PdfPageAdapter)?.nightMode = nightMode
        for (i in 0 until recycler.childCount) {
            (recycler.getChildAt(i) as? PageView)?.setNight(nightMode)
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
        lastPage = page
        toolbar.subtitle = "${page + 1}/$count"
        if (!sliderDragging && pageSlider.visibility == View.VISIBLE) {
            pageSlider.value = (page + 1).toFloat().coerceIn(pageSlider.valueFrom, pageSlider.valueTo)
        }
    }

    private fun currentPageIndex(): Int {
        // Saat di-zoom, bagian yang terlihat hanya 1/zoom dari tinggi RecyclerView.
        val visibleHeight = recycler.height / zoomLayout.zoom
        val child = recycler.findChildViewUnder(recycler.width / 2f, visibleHeight / 2f)
        val pos = child?.let { recycler.getChildAdapterPosition(it) } ?: RecyclerView.NO_POSITION
        if (pos != RecyclerView.NO_POSITION) return pos
        return (recycler.layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
    }
    private fun saveLastPage() {
    val key = pageKey ?: return
    prefs.edit().putInt(key, lastPage).apply()
    }
    override fun onPause() {
    saveLastPage()
    super.onPause()
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
        extractor = file?.let { WordExtractor(it) }

        hideSearchBar()
        recycler.adapter = PdfPageAdapter(
            newRenderer, executor, nightMode, pageListener
        ) { page -> onPageReady(page) }
        val count = newRenderer.pageCount
pageKey = "page_" + (file?.absolutePath ?: name)
val saved = prefs.getInt(pageKey!!, 0).coerceIn(0, count - 1)
lastPage = saved
(recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(saved, 0)

pageSlider.valueFrom = 1f
pageSlider.valueTo = maxOf(2f, count.toFloat())
pageSlider.value = (saved + 1).toFloat()

setMode(true)
applyReadingBackground()
toolbar.title = name
toolbar.subtitle = "${saved + 1}/$count"
    }

    private fun showHistory() {
        closePdf()
        setMode(false)
        renderHistory()
    }

    private fun closePdf() {
        if (!::recycler.isInitialized) return
        saveLastPage()
        pageKey = null
        resetSearchState()
        selection = null
        selectionBar.visibility = View.GONE
        zoomLayout.resetZoom()
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

        val ex = extractor
        extractor = null
        if (ex != null) {
            try {
                wordExecutor.execute { ex.close() }
            } catch (_: Exception) {
            }
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
    private fun showGoToPageDialog() {
    val count = renderer?.pageCount ?: return
    val input = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        hint = "1 - $count"
        setSingleLine(true)
    }
    val container = FrameLayout(this).apply {
        setPadding(dp(24), dp(8), dp(24), 0)
        addView(input)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle("Ke halaman")
        .setView(container)
        .setPositiveButton("Buka") { _, _ ->
            val n = input.text.toString().toIntOrNull()
            if (n == null || n !in 1..count) {
                Toast.makeText(this, "Masukkan angka 1 sampai $count", Toast.LENGTH_SHORT).show()
            } else {
                (recycler.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(n - 1, 0)
            }
        }
        .setNegativeButton("Batal", null)
        .show()
    input.requestFocus()
}

private fun sharePdf() {
    val file = currentPdfLocalFile
    if (file == null || !file.exists()) {
        Toast.makeText(this, "File tidak tersedia untuk dibagikan.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Bagikan PDF"))
    } catch (e: Exception) {
        Toast.makeText(this, "Gagal membagikan: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
    // ------------------------------------------------------------------ pilih teks

    private fun nearestWord(words: List<WordBox>, nx: Float, ny: Float, maxDist: Float): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in words.indices) {
            val r = words[i].rect
            val dx = maxOf(r.left - nx, 0f, nx - r.right)
            val dy = maxOf(r.top - ny, 0f, ny - r.bottom) * 1.4f
            val d = sqrt(dx * dx + dy * dy)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return if (best >= 0 && bestDist <= maxDist) best else -1
    }

    private fun startSelectionAt(pageIndex: Int, nx: Float, ny: Float) {
        val ex = extractor ?: return
        wordExecutor.execute {
            var words: List<WordBox> = emptyList()
            try {
                words = ex.wordsOf(pageIndex)
            } catch (_: Exception) {
            } catch (_: OutOfMemoryError) {
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || extractor !== ex) return@runOnUiThread
                val index = nearestWord(words, nx, ny, 0.03f)
                if (index < 0) {
                    Toast.makeText(
                        this,
                        "Tidak ada teks di sini (PDF hasil scan tidak bisa dipilih).",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@runOnUiThread
                }
                selection = Selection(pageIndex, words, index)
                window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                refreshSelection()
            }
        }
    }

    private fun refreshSelection() {
        val s = selection
        val adapter = recycler.adapter as? PdfPageAdapter
        if (s == null || adapter == null || s.words.isEmpty()) {
            clearSelection()
            return
        }
        val first = s.start.coerceIn(0, s.words.size - 1)
        val last = s.end.coerceIn(first, s.words.size - 1)

        // Gabungkan kata-kata sebaris menjadi satu kotak per baris.
        val rects = ArrayList<RectF>()
        var current: RectF? = null
        for (i in first..last) {
            val r = s.words[i].rect
            val c = current
            if (c != null && r.centerY() > c.top && r.centerY() < c.bottom) {
                c.union(r)
            } else {
                val copy = RectF(r)
                rects.add(copy)
                current = copy
            }
        }
        val startPoint = PointF(rects.first().left, rects.first().bottom)
        val endPoint = PointF(rects.last().right, rects.last().bottom)

        adapter.setSelection(s.pageIndex, rects, startPoint, endPoint)
        selectionBar.visibility = View.VISIBLE
        applyToVisiblePages()
    }

    private fun clearSelection() {
        selection = null
        selectionBar.visibility = View.GONE
        (recycler.adapter as? PdfPageAdapter)?.clearSelection()
        applyToVisiblePages()
    }

    private fun selectAllOnPage() {
        val s = selection ?: return
        if (s.words.isEmpty()) return
        s.start = 0
        s.end = s.words.size - 1
        refreshSelection()
    }

    private fun selectedText(s: Selection): String {
        val sb = StringBuilder()
        val first = s.start.coerceIn(0, s.words.size - 1)
        val last = s.end.coerceIn(first, s.words.size - 1)
        for (i in first..last) {
            val word = s.words[i]
            if (i > first) {
                val prev = s.words[i - 1].rect
                val newLine = word.rect.centerY() > prev.bottom || word.rect.centerY() < prev.top
                sb.append(if (newLine) '\n' else ' ')
            }
            sb.append(word.text)
        }
        return sb.toString()
    }

    private fun copySelection() {
        val s = selection ?: return
        if (s.words.isEmpty()) return
        val text = selectedText(s)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PDF", text))
        Toast.makeText(this, "Teks disalin", Toast.LENGTH_SHORT).show()
        clearSelection()
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
        applyToVisiblePages()
    }

    private fun applyToVisiblePages() {
        val adapter = recycler.adapter as? PdfPageAdapter ?: return
        for (i in 0 until recycler.childCount) {
            val view = recycler.getChildAt(i) as? PageView ?: continue
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
        val visibleHeight = recycler.height / zoomLayout.zoom
        recycler.scrollBy(0, (targetY - visibleHeight / 3f).toInt())
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

// ====================================================================== teks PDF

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

/** Mengumpulkan semua kata satu halaman beserta kotak posisinya. */
private class WordCollector : PDFTextStripper() {
    val words = ArrayList<WordBox>()
    private var pageW = 1f
    private var pageH = 1f

    init {
        sortByPosition = true
    }

    override fun startPage(page: PDPage) {
        val box = page.cropBox
        val rotation = ((page.rotation % 360) + 360) % 360
        val swap = rotation == 90 || rotation == 270
        pageW = if (swap) box.height else box.width
        pageH = if (swap) box.width else box.height
        super.startPage(page)
    }

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        if (pageW <= 0f || pageH <= 0f) return
        val sb = StringBuilder()
        var rect: RectF? = null

        fun flush() {
            val r = rect
            if (r != null && sb.isNotEmpty()) words.add(WordBox(sb.toString(), r))
            sb.setLength(0)
            rect = null
        }

        for (tp in textPositions) {
            val unicode = tp.unicode ?: continue
            if (unicode.isBlank()) {
                flush()
                continue
            }
            sb.append(unicode)
            val h = tp.heightDir.coerceAtLeast(1f)
            val r = RectF(
                (tp.xDirAdj / pageW).coerceIn(0f, 1f),
                ((tp.yDirAdj - h) / pageH).coerceIn(0f, 1f),
                ((tp.xDirAdj + tp.widthDirAdj) / pageW).coerceIn(0f, 1f),
                ((tp.yDirAdj + h * 0.25f) / pageH).coerceIn(0f, 1f)
            )
            val current = rect
            if (current == null) rect = r else current.union(r)
        }
        flush()
    }
}

/** Menyimpan dokumen PDFBox tetap terbuka dan menyimpan hasil kata per halaman. */
class WordExtractor(private val file: File) {
    private var document: PDDocument? = null
    private val cache = HashMap<Int, List<WordBox>>()

    @Synchronized
    fun wordsOf(pageIndex: Int): List<WordBox> {
        cache[pageIndex]?.let { return it }
        val doc = document ?: PDDocument.load(file).also { document = it }
        val collector = WordCollector()
        collector.startPage = pageIndex + 1
        collector.endPage = pageIndex + 1
        collector.writeText(doc, DiscardWriter())
        val result: List<WordBox> = collector.words
        cache[pageIndex] = result
        return result
    }

    @Synchronized
    fun close() {
        try {
            document?.close()
        } catch (_: Exception) {
        }
        document = null
        cache.clear()
    }
}

// ====================================================================== adapter

private class PdfPageAdapter(
    private val pdfRenderer: PdfRenderer,
    private val executor: ExecutorService,
    var nightMode: Boolean,
    private val listener: PageListener,
    private val onPageReady: (Int) -> Unit
) : RecyclerView.Adapter<PdfPageAdapter.PageHolder>() {

    class PageHolder(val image: PageView) : RecyclerView.ViewHolder(image)

    private var hitsByPage: Map<Int, List<SearchHit>> = emptyMap()
    private var currentHitId = -1

    private var selPage = -1
    private var selRects: List<RectF> = emptyList()
    private var selStart: PointF? = null
    private var selEnd: PointF? = null

    private val ratios = HashMap<Int, Float>() // tinggi/lebar tiap halaman (hanya di main thread)

    private val cache = object : LruCache<Int, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 4).toInt()
    ) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
    }

    fun updateHighlights(map: Map<Int, List<SearchHit>>, currentId: Int) {
        hitsByPage = map
        currentHitId = currentId
    }

    fun setSelection(page: Int, rects: List<RectF>, start: PointF?, end: PointF?) {
        selPage = page
        selRects = rects
        selStart = start
        selEnd = end
    }

    fun clearSelection() {
        selPage = -1
        selRects = emptyList()
        selStart = null
        selEnd = null
    }

    fun applyHighlights(view: PageView) {
        val position = view.tag as? Int ?: return
        view.setHighlights(hitsByPage[position].orEmpty(), currentHitId)
        if (position == selPage) {
            view.setSelection(selRects, selStart, selEnd)
        } else {
            view.setSelection(emptyList(), null, null)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val density = parent.resources.displayMetrics.density
        val margin = (8 * density).toInt()
        val image = PageView(parent.context).apply {
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
        view.listener = listener
        view.setNight(nightMode)
        applyHighlights(view)

        val cached = cache.get(position)
        if (cached != null) {
            view.setPageBitmap(cached)
            onPageReady(position)
            return
        }

        // Tinggi sementara supaya RecyclerView tidak mengira semua halaman muat di layar.
        val displayWidth = view.resources.displayMetrics.widthPixels
        val pageWidth = (displayWidth - 16 * view.resources.displayMetrics.density).toInt()
        view.clearPage()
        view.minimumHeight = (pageWidth * (ratios[position] ?: 1.414f)).toInt()

        val targetWidth = (displayWidth * 2f).toInt().coerceIn(1000, 2400)
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
                    view.setPageBitmap(bitmap)
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

// ====================================================================== zoom seluruh dokumen

/**
 * Membungkus RecyclerView dan men-zoom seluruh daftar halaman:
 * cubit dua jari, ketuk dua kali, dan geser horizontal saat ter-zoom.
 * Scroll vertikal tetap ditangani RecyclerView.
 */
class ZoomLayout(context: Context) : FrameLayout(context) {

    var zoom = 1f
        private set
    var baseBottomPadding = 0
    var onZoomSettled: (() -> Unit)? = null

    private var posX = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
    private var decided = false
    private var panning = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchFocusX = detector.focusX
                pinchFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Geser mengikuti pergerakan titik tengah dua jari.
                posX += detector.focusX - pinchFocusX
                recyclerChild()?.scrollBy(0, ((pinchFocusY - detector.focusY) / zoom).roundToInt())
                pinchFocusX = detector.focusX
                pinchFocusY = detector.focusY
                applyZoom(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                onZoomSettled?.invoke()
            }
        }
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoom > 1.05f) applyZoom(1f, e.x, e.y) else applyZoom(2.5f, e.x, e.y)
                onZoomSettled?.invoke()
                return true
            }
        }
    )

    private fun recyclerChild(): RecyclerView? = getChildAt(0) as? RecyclerView

    fun resetZoom() {
        zoom = 1f
        posX = 0f
        updateTransform()
    }

    private fun applyZoom(target: Float, fx: Float, fy: Float) {
        val old = zoom
        val next = target.coerceIn(1f, MAX_ZOOM)
        if (next == old) {
            updateTransform()
            return
        }
        // Pertahankan titik di bawah jari agar tetap di tempat yang sama.
        posX = fx - (fx - posX) * next / old
        zoom = next
        updateTransform()
        recyclerChild()?.scrollBy(0, (fy / old - fy / next).roundToInt())
    }

    private fun updateTransform() {
        val rv = recyclerChild() ?: return
        posX = posX.coerceIn(width * (1f - zoom), 0f)
        rv.pivotX = 0f
        rv.pivotY = 0f
        rv.scaleX = zoom
        rv.scaleY = zoom
        rv.translationX = posX

        // Saat ter-zoom hanya 1/zoom tinggi yang terlihat, jadi tambahkan ruang kosong
        // di bawah supaya halaman terakhir tetap bisa di-scroll sampai terlihat penuh.
        val extra = (height * (1f - 1f / zoom)).toInt()
        val bottom = baseBottomPadding + extra
        if (rv.paddingBottom != bottom) {
            rv.setPadding(rv.paddingLeft, rv.paddingTop, rv.paddingRight, bottom)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTransform()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        tapDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                lastY = ev.y
                decided = false
                panning = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> return true

            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress || ev.pointerCount >= 2) return true
                if (zoom > 1.01f && !decided) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > slop || abs(dy) > slop) {
                        decided = true
                        if (abs(dx) > abs(dy)) {
                            panning = true
                            lastX = ev.x
                            lastY = ev.y
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && ev.pointerCount == 1 && panning) {
                    val dx = ev.x - lastX
                    val dy = ev.y - lastY
                    lastX = ev.x
                    lastY = ev.y
                    posX += dx
                    updateTransform()
                    recyclerChild()?.scrollBy(0, (-dy / zoom).roundToInt())
                } else if (ev.pointerCount == 1) {
                    lastX = ev.x
                    lastY = ev.y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (ev.actionIndex == 0) 1 else 0
                if (remaining < ev.pointerCount) {
                    lastX = ev.getX(remaining)
                    lastY = ev.getY(remaining)
                }
                // Setelah cubit selesai, satu jari yang tersisa boleh menggeser halaman.
                panning = zoom > 1.01f
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                panning = false
            }
        }
        return true
    }
}

// ====================================================================== view halaman

private class PageView(context: Context) : AppCompatImageView(context) {

    var listener: PageListener? = null

    private val density = resources.displayMetrics.density
    private val tmpRect = RectF()

    private var hits: List<SearchHit> = emptyList()
    private var currentHitId = -1

    private var selRects: List<RectF> = emptyList()
    private var selStart: PointF? = null
    private var selEnd: PointF? = null
    private var dragHandle = -1
    private var extending = false

    // Kuning untuk semua hasil cari, oranye untuk hasil aktif (seperti Chrome).
    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 235, 0)
        style = Paint.Style.FILL
    }
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(175, 255, 140, 0)
        style = Paint.Style.FILL
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 33, 150, 243)
        style = Paint.Style.FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(33, 150, 243)
        style = Paint.Style.FILL
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onLongPress(e: MotionEvent) {
                val page = tag as? Int ?: return
                if (dragHandle >= 0 || width <= 0 || height <= 0) return
                extending = true
                parent?.requestDisallowInterceptTouchEvent(true)
                listener?.onLongPress(page, e.x / width, e.y / height)
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (dragHandle < 0) listener?.onTap()
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.FIT_XY
        adjustViewBounds = true
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.WHITE)
    }

    private fun zoomLevel(): Float =
        ((parent as? View)?.parent as? ZoomLayout)?.zoom ?: 1f

    private fun handleRadius(): Float = 9f * density / zoomLevel()

    fun setNight(enabled: Boolean) {
        if (enabled) setColorFilter(NIGHT_FILTER) else clearColorFilter()
        setBackgroundColor(if (enabled) Color.BLACK else Color.WHITE)
    }

    fun setHighlights(newHits: List<SearchHit>, currentId: Int) {
        hits = newHits
        currentHitId = currentId
        invalidate()
    }

    fun setSelection(rects: List<RectF>, start: PointF?, end: PointF?) {
        selRects = rects
        selStart = start
        selEnd = end
        invalidate()
    }

    fun setPageBitmap(bitmap: Bitmap) {
        minimumHeight = 0
        setImageBitmap(bitmap)
        requestLayout()
    }

    fun clearPage() {
        setImageDrawable(null)
        hits = emptyList()
        selRects = emptyList()
        selStart = null
        selEnd = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (drawable != null && hits.isNotEmpty()) {
            // Hasil biasa digambar dulu, hasil aktif terakhir supaya berada di atas.
            for (pass in 0..1) {
                for (hit in hits) {
                    val isCurrent = hit.id == currentHitId
                    if (isCurrent != (pass == 1)) continue
                    val paint = if (isCurrent) currentPaint else normalPaint
                    for (r in hit.rects) {
                        tmpRect.set(r.left * w, r.top * h, r.right * w, r.bottom * h)
                        canvas.drawRoundRect(tmpRect, 3f, 3f, paint)
                    }
                }
            }
        }

        if (selRects.isNotEmpty()) {
            for (r in selRects) {
                tmpRect.set(r.left * w, r.top * h, r.right * w, r.bottom * h)
                canvas.drawRoundRect(tmpRect, 3f, 3f, selectionPaint)
            }
            val radius = handleRadius()
            selStart?.let { canvas.drawCircle(it.x * w, it.y * h + radius, radius, handlePaint) }
            selEnd?.let { canvas.drawCircle(it.x * w, it.y * h + radius, radius, handlePaint) }
        }
    }

    /** Mengembalikan 0 (pegangan awal), 1 (pegangan akhir), atau -1 bila tidak kena. */
    private fun hitHandle(x: Float, y: Float): Int {
        val radius = handleRadius()
        val reach = maxOf(radius * 2.2f, 24f * density / zoomLevel())
        val s = selStart
        val e = selEnd
        val ds = if (s != null) hypot(x - s.x * width, y - (s.y * height + radius)) else Float.MAX_VALUE
        val de = if (e != null) hypot(x - e.x * width, y - (e.y * height + radius)) else Float.MAX_VALUE
        return when {
            ds <= reach && ds <= de -> 0
            de <= reach -> 1
            else -> -1
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            dragHandle = hitHandle(event.x, event.y)
            extending = false
            if (dragHandle >= 0) parent?.requestDisallowInterceptTouchEvent(true)
        }

        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val page = tag as? Int
                if (page != null && width > 0 && height > 0) {
                    if (dragHandle >= 0) {
                        // Jari berada di bawah baris teks, jadi titik cari digeser ke atas.
                        val ty = event.y - handleRadius() * 1.6f
                        listener?.onDragHandle(page, dragHandle, event.x / width, ty / height)
                    } else if (extending) {
                        listener?.onExtend(page, event.x / width, event.y / height)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragHandle = -1
                extending = false
            }
        }
        return true
    }
}
