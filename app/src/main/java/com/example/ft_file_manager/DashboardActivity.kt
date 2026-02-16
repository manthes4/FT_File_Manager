package com.example.ft_file_manager

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

class DashboardActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private val storageItems = mutableListOf<DashboardItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        setupItems()

        recyclerView = findViewById(R.id.dashboardRecyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, 2)
        recyclerView.adapter = DashboardAdapter(storageItems) { item ->
            onItemClick(item)
        }
    }

    private fun setupItems() {
        // 1. Internal Storage
        val internalPath = Environment.getExternalStorageDirectory().absolutePath
        storageItems.add(DashboardItem(1, "Internal", internalPath, R.drawable.ic_phone))

        // 2. SD Card (Αν υπάρχει)
        val sdPath = getExternalSDPath()
        if (sdPath != null) {
            storageItems.add(DashboardItem(2, "SD Card", sdPath, R.drawable.ic_sd))
        }

        // 3. Root
        storageItems.add(DashboardItem(3, "Root", "/", R.drawable.ic_root))

        // 4. FTP
        storageItems.add(DashboardItem(4, "FTP Server", null, R.drawable.ic_ftp))

        // Υπολογισμός χώρου για όλα
        storageItems.forEach { updateSpaceInfo(it) }
    }

    private fun updateSpaceInfo(item: DashboardItem) {
        if (item.path == null) return
        try {
            val stat = StatFs(item.path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val available = stat.availableBlocksLong * stat.blockSizeLong
            val used = total - available

            item.totalSpace = formatSize(total)
            item.usedSpace = formatSize(used)
            item.percentage = if (total > 0) ((used * 100) / total).toInt() else 0
        } catch (e: Exception) {
            item.totalSpace = "N/A"
        }
    }

    private fun getExternalSDPath(): String? {
        val dirs = getExternalFilesDirs(null)
        for (dir in dirs) {
            if (dir != null && Environment.isExternalStorageRemovable(dir)) {
                return dir.absolutePath.split("/Android")[0]
            }
        }
        return null
    }

    private fun onItemClick(item: DashboardItem) {
        if (item.id == 4) {
            // ΤΩΡΑ ανοίγει η οθόνη του FTP
            val intent = Intent(this, FtpActivity::class.java)
            startActivity(intent)
        } else {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("START_PATH", item.path)
            startActivity(intent)
        }
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
    }
}