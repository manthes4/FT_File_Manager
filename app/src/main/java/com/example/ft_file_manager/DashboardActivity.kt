package com.example.ft_file_manager

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.View
import android.widget.Toast
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

        // 1. Αρχικοποίηση των Views για το Drawer και την Toolbar
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.dashboardToolbar)
        val drawerLayout = findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawerLayout)
        val navigationView = findViewById<com.google.android.material.navigation.NavigationView>(R.id.navigationView)

        // 2. Ρύθμιση Toolbar Navigation (Άνοιγμα Συρταριού)
        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
        }

        // 3. Διαχείριση κλικ στο μενού του Drawer
        navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_internal -> {
                    // Είσαι ήδη στο Dashboard, απλά κλείσε το συρτάρι
                }
                R.id.nav_root -> {
                    val intent = Intent(this, MainActivity::class.java)
                    intent.putExtra("START_PATH", "/")
                    startActivity(intent)
                }
                R.id.nav_ftp -> {
                    startActivity(Intent(this, FtpActivity::class.java))
                }
                R.id.nav_external -> {
                    val sdPath = getExternalSDPath()
                    if (sdPath != null) {
                        val intent = Intent(this, MainActivity::class.java)
                        intent.putExtra("START_PATH", sdPath)
                        startActivity(intent)
                    } else {
                        Toast.makeText(this, "Η SD Card δεν βρέθηκε", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            drawerLayout.closeDrawers()
            true
        }

        // 4. Ρύθμιση RecyclerView και LayoutManager
        recyclerView = findViewById(R.id.dashboardRecyclerView)

        // Βάση 6: 2 μεγάλες κάρτες (3+3) ή 3 μικρές (2+2+2) ανά σειρά
        val layoutManager = GridLayoutManager(this, 6)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                // Αν είναι ο τίτλος (Header ID 10) πιάνει 6 στήλες, αλλιώς 3 ή 2
                return when (adapter.getItemViewType(position)) {
                    1 -> 3  // Storage
                    10 -> 6 // Header
                    else -> 2 // Pinned Favorites
                }
            }
        }
        recyclerView.layoutManager = layoutManager

        // 5. Αρχικοποίηση Adapter
        adapter = DashboardAdapter(storageItems,
            { item -> onItemClick(item) },
            { item -> onLongClick(item) }
        )
        recyclerView.adapter = adapter
    }

    private fun updateDrawerMenu() {
        val navigationView = findViewById<com.google.android.material.navigation.NavigationView>(R.id.navigationView)
        val menu = navigationView.menu
        menu.removeGroup(R.id.group_favorites) // Καθαρισμός

        val prefs = getSharedPreferences("favorites", MODE_PRIVATE)
        val savedPaths = prefs.getString("paths_ordered", "") ?: ""

        if (savedPaths.isNotEmpty()) {
            savedPaths.split("|").forEach { entry ->
                val parts = entry.split("*")
                if (parts.size == 2) {
                    val displayName = parts[0]
                    val realPath = parts[1]

                    val menuItem = menu.add(R.id.group_favorites, android.view.View.generateViewId(), 100, displayName)

                    // ΕΛΕΓΧΟΣ: Αν είναι FTP βάλε το εικονίδιο share/ftp, αλλιώς folder
                    if (realPath.startsWith("ftp://")) {
                        menuItem.setIcon(android.R.drawable.ic_menu_share) // Ή το δικό σου ic_ftp
                    } else {
                        menuItem.setIcon(R.drawable.ic_folder_yellow)
                    }

                    menuItem.setOnMenuItemClickListener {
                        if (realPath.startsWith("ftp://")) {
                            // Αν είναι FTP, άνοιξε την FtpActivity
                            val host = realPath.replace("ftp://", "")
                            val intent = Intent(this, FtpActivity::class.java)
                            intent.putExtra("TARGET_HOST", host)
                            startActivity(intent)
                        } else {
                            // Αν είναι φάκελος, κάλεσε την openPath που ήδη έχεις
                            openPath(realPath)
                        }
                        true
                    }
                }
            }
        }
    }

    // Μην ξεχάσεις να την καλέσεις στην onResume!
    override fun onResume() {
        super.onResume()
        setupItems()
        updateDrawerMenu() // <--- Προσθήκη εδώ
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
        // 2. Προσθήκη Pinned Φακέλων
        val prefsDash = getSharedPreferences("dashboard_pins", MODE_PRIVATE)
        var savedPins = prefsDash.getString("paths", "") ?: ""

// ΑΝ ΕΙΝΑΙ ΑΔΕΙΟ: Διάβασε από τα favorites του Drawer για να μην είναι κενό την πρώτη φορά
        if (savedPins.isEmpty()) {
            val prefsFav = getSharedPreferences("favorites", MODE_PRIVATE)
            savedPins = prefsFav.getString("paths_ordered", "") ?: ""
            // Προαιρετικά: Σώσε τα αμέσως στα pins για να γίνει ο συγχρονισμός
            prefsDash.edit().putString("paths", savedPins).apply()
        }

        if (savedPins.isNotEmpty()) {
            savedPins.split("|").forEach { entry ->
                val parts = entry.split("*")
                if (parts.size == 2 && File(parts[1]).exists()) {
                    storageItems.add(DashboardItem(5, parts[0], parts[1], R.drawable.ic_folder_yellow))
                }
            }
        }

        // Υπολογισμός χώρου για όλα τα items
        storageItems.forEach { updateSpaceInfo(it) }

        adapter.notifyDataSetChanged()
    }

    private fun openPath(path: String) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("START_PATH", path)
        startActivity(intent)
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