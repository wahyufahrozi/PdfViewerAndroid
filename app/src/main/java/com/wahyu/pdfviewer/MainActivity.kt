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
import android.widget.ImageView
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
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var historyScroll: ScrollView
    private lateinit var historyList: LinearLayout
    private lateinit var historyButton: Button

    private var renderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val openPdfRequest = 1001
    private val prefs by lazy { getSharedPreferences("pdf_viewer_preferences", MODE_PRIVATE) }

    private data class RecentPdf(
        val uri: String,
        val name: String,
        val openedAt: Long
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(Color.rgb(16, 54, 92))
        }

        val title = TextView(this).apply {
            text = "PDF Viewer"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        historyButton = Button(this).apply {
            text = "Riwayat"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { showHistory() }
        }
        header.addView(historyButton)

        val openButton = Button(this).apply {
            text = "Buka PDF"
            isAllCaps = false
            setOnClickListener { choosePdf() }
        }
        header.addView(openButton)
        root.addView(header)

        status = TextView(this).apply {
            text = "Pilih dokumen PDF untuk mulai membaca."
            textSize = 14f
            setTextColor(Color.rgb(65, 75, 88))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(status)

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
            val descriptor = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Dokumen tidak dapat dibuka.")
            val newRenderer = PdfRenderer(descriptor)
            fileDescriptor = descriptor
            renderer = newRenderer
            recycler.adapter = PdfPageAdapter(newRenderer, executor)
            recycler.scrollToPosition(0)
            recycler.visibility = View.VISIBLE
            historyScroll.visibility = View.GONE
            historyButton.visibility = View.VISIBLE

            val name = displayName(uri)
            status.text = "${newRenderer.pageCount} halaman • $name"
            saveHistory(uri, name)
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

    private fun showHistory() {
        closePdf()
        recycler.visibility = View.GONE
        historyScroll.visibility = View.VISIBLE
        historyButton.visibility = View.GONE
        status.text = "Dokumen yang terakhir dibuka."
        renderHistory()
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index)
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Dokumen PDF"
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
                        item.optLong("openedAt")
                    )
                )
            }
        } catch (_: Exception) {
            prefs.edit().remove("recent_pdfs").apply()
        }
        return result.sortedByDescending { it.openedAt }.toMutableList()
    }

    private fun saveHistory(uri: Uri, name: String) {
        val items = loadHistory()
        items.removeAll { it.uri == uri.toString() }
        items.add(0, RecentPdf(uri.toString(), name, System.currentTimeMillis()))
        val array = JSONArray()
        items.take(20).forEach { item ->
            array.put(
                JSONObject()
                    .put("uri", item.uri)
                    .put("name", item.name)
                    .put("openedAt", item.openedAt)
            )
        }
        prefs.edit().putString("recent_pdfs", array.toString()).apply()
        renderHistory()
    }

    private fun removeHistory(uri: String) {
        val array = JSONArray()
        loadHistory().filterNot { it.uri == uri }.forEach { item ->
            array.put(
                JSONObject()
                    .put("uri", item.uri)
                    .put("name", item.name)
                    .put("openedAt", item.openedAt)
            )
        }
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
            details.setOnClickListener { openPdf(Uri.parse(item.uri)) }
            card.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val open = Button(this).apply {
                text = "Buka"
                isAllCaps = false
                setOnClickListener { openPdf(Uri.parse(item.uri)) }
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

    private fun closePdf() {
        recycler.adapter = null
        renderer?.close()
        renderer = null
        fileDescriptor?.close()
        fileDescriptor = null
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
    private val baseMatrix = Matrix()
    private var zoom = 1f
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val nextZoom = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
                val factor = nextZoom / zoom
                if (factor != 1f) {
                    drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                    zoom = nextZoom
                    if (zoom <= 1.02f) resetZoom()
                    else imageMatrix = drawMatrix
                }
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    fun setZoomBitmap(bitmap: Bitmap) {
        setImageBitmap(bitmap)
        post { resetZoom() }
    }

    private fun resetZoom() {
        val d = drawable ?: return
        if (width <= 0 || height <= 0) return
        val imageWidth = d.intrinsicWidth.toFloat()
        val imageHeight = d.intrinsicHeight.toFloat()
        if (imageWidth <= 0f || imageHeight <= 0f) return

        val fit = min(width / imageWidth, height / imageHeight)
        baseMatrix.setScale(fit, fit)
        baseMatrix.postTranslate(
            (width - imageWidth * fit) / 2f,
            (height - imageHeight * fit) / 2f
        )
        drawMatrix.set(baseMatrix)
        imageMatrix = drawMatrix
        zoom = 1f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && zoom > 1.02f && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    drawMatrix.postTranslate(dx, dy)
                    imageMatrix = drawMatrix
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
