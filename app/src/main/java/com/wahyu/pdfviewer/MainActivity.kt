package com.wahyu.pdfviewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: PdfPageAdapter
    private var renderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val openPdfRequest = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private fun buildScreen() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(242, 245, 249))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(Color.rgb(16, 54, 92))
        }
        val title = TextView(this).apply {
            text = "PDF Viewer"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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

        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setBackgroundColor(Color.rgb(225, 230, 237))
            visibility = View.GONE
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
        }
        startActivityForResult(intent, openPdfRequest)
    }

    @Deprecated("Use the Activity Result API in a future update")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == openPdfRequest && resultCode == RESULT_OK) {
            data?.data?.let { openPdf(it) }
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
            adapter = PdfPageAdapter(newRenderer, executor)
            recycler.adapter = adapter
            recycler.visibility = View.VISIBLE
            status.text = "${newRenderer.pageCount} halaman • ${displayName(uri)}"
        } catch (e: Exception) {
            closePdf()
            Toast.makeText(this, "Gagal membuka PDF: ${e.message ?: "format tidak didukung"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun displayName(uri: Uri): String = uri.lastPathSegment?.substringAfterLast('/') ?: "Dokumen PDF"

    private fun closePdf() {
        recycler.adapter = null
        renderer?.close()
        renderer = null
        fileDescriptor?.close()
        fileDescriptor = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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

    class PageHolder(val image: ImageView) : RecyclerView.ViewHolder(image)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val image = ImageView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (10 * resources.displayMetrics.density).toInt() }
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(8, 8, 8, 8)
        }
        return PageHolder(image)
    }

    override fun getItemCount(): Int = pdfRenderer.pageCount

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        holder.image.setImageDrawable(null)
        holder.image.tag = position
        val targetWidth = holder.image.resources.displayMetrics.widthPixels -
            (16 * holder.image.resources.displayMetrics.density).toInt()
        executor.execute {
            var bitmap: Bitmap? = null
            try {
                synchronized(pdfRenderer) {
                    pdfRenderer.openPage(position).use { page ->
                        val width = targetWidth.coerceAtLeast(400)
                        val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap?.eraseColor(Color.WHITE)
                        page.render(bitmap!!, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            } catch (_: Exception) { }
            val rendered = bitmap
            holder.image.post {
                if (holder.image.tag == position && rendered != null) {
                    holder.image.setImageBitmap(rendered)
                } else {
                    rendered?.recycle()
                }
            }
        }
    }
}
