package com.example.ft_file_manager

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class PdfViewerActivity : AppCompatActivity() {

    private var pdfRenderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)

        val filePath = intent.getStringExtra("PATH") ?: return
        val file = File(filePath)

        // Toolbar Setup
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.pdfToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = file.name
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        setupRenderer(file)

        val recyclerView = findViewById<RecyclerView>(R.id.pdfRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = PdfAdapter(pdfRenderer!!)
    }

    private fun setupRenderer(file: File) {
        fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pdfRenderer = PdfRenderer(fileDescriptor!!)
    }

    override fun onDestroy() {
        pdfRenderer?.close()
        fileDescriptor?.close()
        super.onDestroy()
    }

    inner class PdfAdapter(private val renderer: PdfRenderer) : RecyclerView.Adapter<PdfAdapter.PdfViewHolder>() {

        inner class PdfViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            // Τώρα το R.id.imgPage θα αναγνωρίζεται κανονικά!
            val pageImage: ImageView = view.findViewById(R.id.imgPage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfViewHolder {
            // Κάνουμε inflate το νέο layout που φτιάξαμε
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page, parent, false)
            return PdfViewHolder(view)
        }

        override fun onBindViewHolder(holder: PdfViewHolder, position: Int) {
            val page = renderer.openPage(position)

            // Αυξάνουμε λίγο την ανάλυση για να φαίνεται καθαρά στο zoom
            val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            holder.pageImage.setImageBitmap(bitmap)
            page.close()
        }

        override fun getItemCount() = renderer.pageCount
    }
}