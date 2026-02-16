package com.example.ft_file_manager

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

class DashboardActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private val storageItems = mutableListOf<DashboardItem>()
    private lateinit var adapter: DashboardAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        recyclerView = findViewById(R.id.dashboardRecyclerView)

        // Ρύθμιση Grid: Οι μεγάλες κάρτες (Storage) πιάνουν 2 στήλες, οι μικρές (Pins) πιάνουν 1.
        // Ορίζουμε 4 στήλες ως βάση
        // Ορίζουμε 6 στήλες ως βάση (το ελάχιστο κοινό πολλαπλάσιο για 2 και 3)
        val layoutManager = GridLayoutManager(this, 6)

        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == 1) {
                    // TYPE_STORAGE: Κάθε κάρτα πιάνει 3 στήλες (6 / 3 = 2 κάρτες ανά σειρά)
                    3
                } else {
                    // TYPE_PINNED: Κάθε κάρτα πιάνει 2 στήλες (6 / 2 = 3 κάρτες ανά σειρά)
                    2
                }
            }
        }

        recyclerView.layoutManager = layoutManager

        // Αρχικοποίηση Adapter με Click και Long Click
        adapter = DashboardAdapter(storageItems,
            { item -> onItemClick(item) },
            { item -> onLongClick(item) }
        )
        recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        setupItems() // Ανανέωση δεδομένων κάθε φορά που επιστρέφουμε
    }

    private fun setupItems() {
        storageItems.clear()

        // 1. Βασικές μονάδες (ID 1-4)
        val internalPath = Environment.getExternalStorageDirectory().absolutePath
        storageItems.add(DashboardItem(1, "Internal", internalPath, R.drawable.ic_phone))

        val sdPath = getExternalSDPath()
        if (sdPath != null) {
            storageItems.add(DashboardItem(2, "SD Card", sdPath, R.drawable.ic_sd))
        }

        storageItems.add(DashboardItem(3, "Root", "/", R.drawable.ic_root))
        storageItems.add(DashboardItem(4, "FTP Server", null, R.drawable.ic_ftp))

        // 2. Προσθήκη Pinned Φακέλων (ID 5) - Μόνο για το Dashboard
        val prefs = getSharedPreferences("dashboard_pins", MODE_PRIVATE)
        val savedPins = prefs.getString("paths", "") ?: ""

        if (savedPins.isNotEmpty()) {
            savedPins.split("|").forEach { entry ->
                val parts = entry.split("*")
                if (parts.size == 2) {
                    val folderName = parts[0]
                    val path = parts[1]

                    // Εμφάνιση μόνο αν ο φάκελος υπάρχει ακόμα στη μνήμη
                    if (File(path).exists()) {
                        storageItems.add(DashboardItem(5, folderName, path, R.drawable.ic_folder_yellow))
                    }
                }
            }
        }

        // Υπολογισμός χώρου για όλα τα items
        storageItems.forEach { updateSpaceInfo(it) }

        adapter.notifyDataSetChanged()
    }

    private fun updateSpaceInfo(item: DashboardItem) {
        if (item.path == null) return
        try {
            val file = File(item.path!!)
            if (!file.exists()) return

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
                val path = dir.absolutePath.split("/Android")[0]
                return if (File(path).exists()) path else null
            }
        }
        return null
    }

    private fun onItemClick(item: DashboardItem) {
        if (item.id == 4) {
            startActivity(Intent(this, FtpActivity::class.java))
        } else {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("START_PATH", item.path)
            startActivity(intent)
        }
    }

    private fun onLongClick(item: DashboardItem) {
        // Επιτρέπουμε τη διαγραφή μόνο για τα Pinned Items (ID 5)
        if (item.id == 5) {
            AlertDialog.Builder(this)
                .setTitle("Αφαίρεση")
                .setMessage("Θέλετε να αφαιρέσετε τη συντόμευση '${item.title}' από το Dashboard;")
                .setPositiveButton("Αφαίρεση") { _, _ ->
                    removePin(item)
                }
                .setNegativeButton("Άκυρο", null)
                .show()
        }
    }

    private fun removePin(item: DashboardItem) {
        val prefs = getSharedPreferences("dashboard_pins", MODE_PRIVATE)
        val savedPins = prefs.getString("paths", "") ?: ""
        val entryToRemove = "${item.title}*${item.path}"

        val newPins = savedPins.split("|")
            .filter { it != entryToRemove }
            .joinToString("|")

        prefs.edit().putString("paths", newPins).apply()
        setupItems() // Refresh
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
    }
}