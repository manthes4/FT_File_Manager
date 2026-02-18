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
                R.id.nav_network -> {
                    startActivity(Intent(this, NetworkClientActivity::class.java))
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
        val navView = findViewById<com.google.android.material.navigation.NavigationView>(R.id.navigationView)
        val menu = navView.menu

        // 1. ΚΑΘΑΡΙΣΜΟΣ HEADER (Εικόνα)
        // Σβήνουμε παλιά headers για να μην γεμίσει η οθόνη εικόνες σε κάθε refresh
        while (navView.headerCount > 0) {
            navView.removeHeaderView(navView.getHeaderView(0))
        }
        // Εισαγωγή της εικόνας από το XML που φτιάξαμε
        navView.inflateHeaderView(R.layout.nav_header_favorites)

        // 2. ΚΑΘΑΛΙΚΟΣ ΚΑΘΑΡΙΣΜΟΣ GROUP & ITEM
        // Αφαιρούμε το item και το group για να "ξεπλύνουμε" το SubMenu
        menu.removeItem(R.id.group_favorites)
        menu.removeGroup(R.id.group_favorites)

        val prefs = getSharedPreferences("favorites", MODE_PRIVATE)
        val savedPaths = prefs.getString("paths_ordered", "") ?: ""

        if (savedPaths.isNotEmpty()) {
            // 3. ΔΗΜΙΟΥΡΓΙΑ SUBMENU (Όπως στη Main)
            val favoriteSubMenu = menu.addSubMenu(0, R.id.group_favorites, 100, "ΑΓΑΠΗΜΕΝΑ")

            val entries = savedPaths.split("|").filter { it.isNotEmpty() }
            entries.forEachIndexed { index, entry ->
                val parts = entry.split("*")
                if (parts.size == 2) {
                    val displayName = parts[0]
                    val realPath = parts[1]

                    val menuItem = menu.add(R.id.group_favorites, index, index, displayName)

                    // Ορισμός εικονιδίου
                    when {
                        realPath.startsWith("ftp://") -> menuItem.setIcon(android.R.drawable.ic_menu_share)
                        realPath.startsWith("smb://") -> menuItem.setIcon(R.drawable.ic_root)
                        else -> menuItem.setIcon(R.drawable.ic_folder_yellow)
                    }

                    // Σύνδεση με το Custom Layout που έχει τα κουμπιά Rename/Delete
                    menuItem.setActionView(R.layout.menu_item_favorite)
                    val actionView = menuItem.actionView

                    actionView?.findViewById<android.widget.ImageButton>(R.id.btnRenameFavorite)?.setOnClickListener {
                        showRenameFavoriteDialog(entry, index)
                    }

                    actionView?.findViewById<android.widget.ImageButton>(R.id.btnRemoveFavorite)?.setOnClickListener {
                        showRemoveFavoriteDialog(entry)
                    }

                    menuItem.setOnMenuItemClickListener {
                        when {
                            realPath.startsWith("ftp://") -> {
                                val host = realPath.replace("ftp://", "")
                                val intent = Intent(this, FtpActivity::class.java).apply {
                                    putExtra("TARGET_HOST", host)
                                }
                                startActivity(intent)
                            }
                            realPath.startsWith("smb://") -> {
                                // 1. Διαβάζουμε τα αποθηκευμένα στοιχεία για αυτό το path
                                val netPrefs = getSharedPreferences("network_settings", MODE_PRIVATE)
                                // Χρησιμοποιούμε το realPath ως κλειδί για να βρούμε το σωστό User/Pass
                                val savedUser = netPrefs.getString("user_$realPath", "")
                                val savedPass = netPrefs.getString("pass_$realPath", "")

                                val intent = Intent(this, NetworkClientActivity::class.java).apply {
                                    putExtra("TARGET_SMB_PATH", realPath)
                                    putExtra("SMB_USER", savedUser)
                                    putExtra("SMB_PASS", savedPass)
                                }
                                startActivity(intent)
                            }
                            else -> openPath(realPath)
                        }
                        findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawerLayout).closeDrawers()
                        true
                    }
                }
            }
        }
    }

    private fun showRenameFavoriteDialog(oldEntry: String, index: Int) {
        val parts = oldEntry.split("*")
        val currentName = parts[0]
        val path = parts[1]

        val input = android.widget.EditText(this)
        input.setText(currentName)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Μετονομασία Αγαπημένου")
            .setView(input)
            .setPositiveButton("ΟΚ") { _, _ ->
                val newName = input.text.toString()
                if (newName.isNotEmpty()) {
                    val prefs = getSharedPreferences("favorites", MODE_PRIVATE)
                    val savedPaths = prefs.getString("paths_ordered", "") ?: ""
                    val entries = savedPaths.split("|").toMutableList()

                    // Ενημέρωση της συγκεκριμένης εγγραφής
                    entries[index] = "$newName*$path"

                    prefs.edit().putString("paths_ordered", entries.joinToString("|")).apply()
                    updateDrawerMenu() // Refresh το μενού
                    setupItems()       // Refresh τις κάρτες αν το έχεις κάνει Pin
                }
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }

    private fun showRemoveFavoriteDialog(entryToRemove: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Διαγραφή Αγαπημένου")
            .setMessage("Θέλετε να αφαιρέσετε το '${entryToRemove.split("*")[0]}' από τα αγαπημένα;")
            .setPositiveButton("Διαγραφή") { _, _ ->
                val prefs = getSharedPreferences("favorites", MODE_PRIVATE)
                val savedPaths = prefs.getString("paths_ordered", "") ?: ""

                val newPaths = savedPaths.split("|")
                    .filter { it != entryToRemove && it.isNotEmpty() }
                    .joinToString("|")

                prefs.edit().putString("paths_ordered", newPaths).apply()
                updateDrawerMenu()
                setupItems()
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }

    // Μην ξεχάσεις να την καλέσεις στην onResume!
    override fun onResume() {
        super.onResume()
        setupItems()
        updateDrawerMenu() // <--- Προσθήκη εδώ
    }

    private fun setupItems() {
        storageItems.clear()

        // --- ΣΕΙΡΑ: Internal, SD Card, Downloads, Root, FTP Server, SMB/SFTP ---

        // 1. Internal (ID 1)
        val internalPath = Environment.getExternalStorageDirectory().absolutePath
        storageItems.add(DashboardItem(1, "Internal Storage", internalPath, R.drawable.ic_phone))

        // 2. SD Card (ID 2)
        val sdPath = getExternalSDPath()
        if (sdPath != null) {
            storageItems.add(DashboardItem(2, "SD Card", sdPath, R.drawable.ic_sd))
        }

        // 3. Downloads (ID 6)
        val downloadsPath = File(Environment.getExternalStorageDirectory(), "Download").absolutePath
        storageItems.add(DashboardItem(6, "Download", downloadsPath, R.drawable.ic_folder_yellow))

        // 4. Root (ID 3)
        storageItems.add(DashboardItem(3, "Root", "/", R.drawable.ic_root))

        // 5. FTP Server (ID 4) - Η εφαρμογή σου ως Server
        storageItems.add(DashboardItem(4, "FTP Server", null, R.drawable.ic_ftp))

        // 6. SMB/SFTP Network (ID 7) - Σύνδεση σε άλλες συσκευές
        storageItems.add(DashboardItem(7, "Network SMB/SFTP", "network_global", R.drawable.ic_ftp)) // Χρησιμοποίησε ένα εικονίδιο δικτύου αν έχεις

        // --- 2. Pinned Φακέλων (ID 5) ---
        val prefsDash = getSharedPreferences("dashboard_pins", MODE_PRIVATE)
        var savedPins = prefsDash.getString("paths", "") ?: ""

        if (savedPins.isEmpty()) {
            val prefsFav = getSharedPreferences("favorites", MODE_PRIVATE)
            savedPins = prefsFav.getString("paths_ordered", "") ?: ""
            prefsDash.edit().putString("paths", savedPins).apply()
        }

        if (savedPins.isNotEmpty()) {
            savedPins.split("|").forEach { entry ->
                val parts = entry.split("*")
                if (parts.size == 2) {
                    val path = parts[1]
                    // Προσθήκη αν είναι τοπικό αρχείο που υπάρχει ή αν είναι δικτυακό (smb/ftp)
                    if (path.startsWith("smb://") || path.startsWith("ftp://") || File(path).exists()) {
                        storageItems.add(DashboardItem(5, parts[0], path, R.drawable.ic_folder_yellow))
                    }
                }
            }
        }

        // Υπολογισμός χώρου ΜΟΝΟ για όσα είναι τοπικά paths
        storageItems.forEach { updateSpaceInfo(it) }

        adapter.notifyDataSetChanged()
    }

    private fun openPath(path: String) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("START_PATH", path)
        startActivity(intent)
    }

    private fun updateSpaceInfo(item: DashboardItem) {
        // Αν δεν υπάρχει path ή αν είναι εικονικό/δικτυακό, μην προχωράς
        if (item.path == null || item.id == 4 || item.id == 7 || item.path!!.startsWith("smb://")) {
            item.totalSpace = "" // Προαιρετικά άδειασέ το
            return
        }

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
        when (item.id) {
            4 -> { // FTP Server (Local Server)
                startActivity(Intent(this, FtpActivity::class.java))
            }
            7 -> { // Network SMB/SFTP (Client)
                startActivity(Intent(this, NetworkClientActivity::class.java))
            }
            else -> {
                // Για Internal, SD, Downloads, Root και Pinned
                val path = item.path ?: ""

                if (path.startsWith("smb://")) {
                    // Αν είναι καρφιτσωμένο SMB, άνοιξε τον Client
                    val intent = Intent(this, NetworkClientActivity::class.java)
                    intent.putExtra("TARGET_SMB_PATH", path)
                    startActivity(intent)
                } else {
                    // Για όλα τα τοπικά αρχεία
                    val intent = Intent(this, MainActivity::class.java)
                    intent.putExtra("START_PATH", path)
                    startActivity(intent)
                }
            }
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