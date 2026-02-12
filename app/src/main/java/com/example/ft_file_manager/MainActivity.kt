package com.example.ft_file_manager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ft_file_manager.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var currentPath: File = File("/storage/emulated/0")
    private var fullFileList = mutableListOf<FileModel>()
    private var fileToMove: FileModel? = null
    private var isCutOperation: Boolean = false
    private var isSelectionMode = false // Αυτή η γραμμή έλειπε!
    private var bulkFilesToMove = listOf<FileModel>() // Νέα μεταβλητή στην κορυφή της κλάσης!

    private val favoritePaths = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("favorites", MODE_PRIVATE)
        favoritePaths.addAll(prefs.getStringSet("paths", emptySet()) ?: emptySet())
        updateDrawerMenu()

        binding.recyclerView.layoutManager = LinearLayoutManager(this)

        // 1. Toolbar Navigation (Drawer)
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            val selectedFiles = fullFileList.filter { it.isSelected }

            when (item.itemId) {
                R.id.action_favorite -> {
                    // Δουλεύει μόνο για φακέλους
                    val directories = selectedFiles.filter { it.isDirectory }
                    if (directories.isEmpty()) {
                        Toast.makeText(this, "Μόνο οι φάκελοι μπαίνουν στα Αγαπημένα", Toast.LENGTH_SHORT).show()
                    } else {
                        directories.forEach { favoritePaths.add(it.path) }

                        // Αποθήκευση
                        getSharedPreferences("favorites", MODE_PRIVATE)
                            .edit()
                            .putStringSet("paths", favoritePaths)
                            .apply()

                        updateDrawerMenu()
                        exitSelectionMode()
                        Toast.makeText(this, "Προστέθηκε στα Αγαπημένα", Toast.LENGTH_SHORT).show()
                    }
                    true
                }

                R.id.action_delete -> { confirmBulkDelete(selectedFiles); true }
                R.id.action_copy   -> { startBulkMove(selectedFiles, isCut = false); true }
                R.id.action_cut    -> { startBulkMove(selectedFiles, isCut = true); true }
                R.id.action_rename -> {
                    if (selectedFiles.size == 1) showRenameDialog(selectedFiles[0])
                    true
                }
                else -> false
            }
        }

        // 3. Bottom Bar Buttons
        binding.btnSelectAll.setOnClickListener {
            if (!isSelectionMode) toggleSelectionMode(true)
            val allSelected = fullFileList.all { it.isSelected }
            fullFileList.forEach { it.isSelected = !allSelected }
            binding.recyclerView.adapter?.notifyDataSetChanged()

            val count = fullFileList.count { it.isSelected }
            if (count == 0) exitSelectionMode()
            else {
                binding.toolbar.title = "$count επιλεγμένα"
                updateMenuVisibility()
            }
        }

        binding.fabPaste.setOnClickListener { pasteFile() }
        binding.btnNewFolder.setOnClickListener { view ->
            val popup = androidx.appcompat.widget.PopupMenu(this, view)
            popup.menu.add("Νέος Φάκελος")
            popup.menu.add("Νέο Αρχείο (.txt)")

            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Νέος Φάκελος" -> showCreateItemDialog(isDirectory = true)
                    "Νέο Αρχείο (.txt)" -> showCreateItemDialog(isDirectory = false)
                }
                true
            }
            popup.show()
        }

        binding.btnSort.setOnClickListener { showSortDialog() }

        setupNavigationDrawer()
        setupBackNavigation()
        checkPermissions()
        loadFiles(currentPath)
    }

    private fun checkPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }

    private fun setupNavigationDrawer() {
        binding.navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_internal -> loadFiles(Environment.getExternalStorageDirectory())
                R.id.nav_root -> loadFiles(File("/"))
                R.id.nav_ftp -> startActivity(Intent(this, FtpActivity::class.java))
                R.id.nav_external -> {
                    val externalDirs = getExternalFilesDirs(null)
                    // Το dirs[0] είναι η εσωτερική μνήμη, το dirs[1] (αν υπάρχει) είναι η SD Card
                    if (externalDirs.size > 1 && externalDirs[1] != null) {
                        // Παίρνουμε το root της SD Card (πριν το /Android/data/...)
                        val sdCardPath = externalDirs[1].absolutePath.split("/Android")[0]
                        loadFiles(File(sdCardPath))
                    } else {
                        Toast.makeText(this, "Η SD Card δεν βρέθηκε", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            binding.drawerLayout.closeDrawers()
            true
        }
    }

    // Μέσα στην MainActivity
    private fun loadFiles(directory: File) {
        currentPath = directory
        binding.toolbar.title = directory.name.ifEmpty { "Internal Storage" }

        // Χρησιμοποιούμε Coroutine για να τρέξουμε το RootTools
        MainScope().launch {
            val fileList = mutableListOf<FileModel>()

            // Δοκιμάζουμε την κανονική ανάγνωση (Java File API)
            val files = withContext(Dispatchers.IO) { directory.listFiles() }

            if (files != null) {
                files.forEach {
                    fileList.add(
                        FileModel(
                            it.name, it.absolutePath, it.isDirectory,
                            if (it.isDirectory) "Φάκελος" else "${it.length() / 1024} KB", false
                        )
                    )
                }
            } else {
                // ΕΔΩ χρησιμοποιούμε το ΝΕΟ RootTools
                val output = RootTools.getOutput("ls -F '${directory.absolutePath}'")
                if (output.isNotEmpty() && !output.contains("Error:")) {
                    output.split("\n").forEach { line ->
                        val name = line.trim()
                        if (name.isNotEmpty()) {
                            val isDir = name.endsWith("/")
                            val cleanName = if (isDir) name.dropLast(1) else name
                            fileList.add(
                                FileModel(
                                    cleanName, "${directory.absolutePath}/$cleanName", isDir,
                                    if (isDir) "Φάκελος" else "System File", true
                                )
                            )
                        }
                    }
                }
            }

            fileList.sortWith(compareByDescending<FileModel> { it.isDirectory }.thenBy { it.name.lowercase() })
            fullFileList = fileList
            updateAdapter(fileList)
        }
    }

    private fun updateAdapter(list: List<FileModel>) {
        val adapter = FileAdapter(
            list,
            isInSelectionMode = isSelectionMode,
            onItemClick = { selectedFile ->
                val file = File(selectedFile.path)
                if (selectedFile.isDirectory) loadFiles(file) else openFile(file)
            },
            onItemLongClick = { selectedFile ->
                if (!isSelectionMode) {
                    selectedFile.isSelected = true
                    toggleSelectionMode(true)
                    // Αφαιρέθηκε το showOptionsDialog(selectedFile)
                }
            },
            onSelectionChanged = {
                val count = fullFileList.count { it.isSelected }
                if (count == 0) {
                    exitSelectionMode()
                } else {
                    binding.toolbar.title = "$count επιλεγμένα"
                    updateMenuVisibility()
                }
            }
        )
        binding.recyclerView.adapter = adapter
    }

    private fun showOptionsDialog(file: FileModel) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_file_options, null)

        view.findViewById<TextView>(R.id.btnCopy).setOnClickListener {
            fileToMove = file
            isCutOperation = false
            binding.fabPaste.show()
            dialog.dismiss()
        }

        view.findViewById<TextView>(R.id.btnCut).setOnClickListener {
            fileToMove = file
            isCutOperation = true
            binding.fabPaste.show()
            dialog.dismiss()
        }

        view.findViewById<TextView>(R.id.btnDelete).setOnClickListener {
            confirmDelete(file)
            dialog.dismiss()
        }

        view.findViewById<TextView>(R.id.btnRename).setOnClickListener {
            showRenameDialog(file)
            dialog.dismiss()
        }

        view.findViewById<TextView>(R.id.btnShare).setOnClickListener {
            shareFile(file)
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun pasteFile() {
        val filesToProcess = if (fileToMove != null) listOf(fileToMove!!) else bulkFilesToMove
        if (filesToProcess.isEmpty()) return

        // Χρησιμοποιούμε Coroutine γιατί το RootTools είναι suspend
        MainScope().launch {
            binding.progressBar.visibility = View.VISIBLE // Εμφάνιση
            var allSuccess = true

            // Έλεγχος αν ο προορισμός είναι στο σύστημα
            val isSystemTarget = currentPath.absolutePath.startsWith("/system") ||
                    currentPath.absolutePath.startsWith("/vendor")

            if (isSystemTarget) {
                val unlocked = RootTools.unlockSystem()
                if (!unlocked) {
                    Toast.makeText(
                        this@MainActivity,
                        "Αποτυχία ξεκλειδώματος συστήματος (Read-Only)",
                        Toast.LENGTH_LONG
                    ).show()
                    // Συνεχίζουμε παρ' όλα αυτά, μήπως και πέτυχε αθόρυβα
                }
            }

            withContext(Dispatchers.IO) {
                filesToProcess.forEach { model ->
                    val source = File(model.path)
                    val dest = File(currentPath, source.name)

                    try {
                        if (isCutOperation) {
                            // Χρήση RootTools για μετακίνηση στο σύστημα
                            val moved = if (isSystemTarget || model.isRoot) {
                                RootTools.executeSilent("mv '${source.absolutePath}' '${dest.absolutePath}'")
                            } else {
                                source.renameTo(dest)
                            }
                            if (!moved) allSuccess = false
                        } else {
                            // Χρήση RootTools για αντιγραφή στο σύστημα
                            val copied = if (isSystemTarget || model.isRoot) {
                                RootTools.executeSilent("cp -r '${source.absolutePath}' '${dest.absolutePath}'")
                            } else {
                                source.copyRecursively(dest, true)
                            }
                            if (!copied) allSuccess = false
                        }
                    } catch (e: Exception) {
                        allSuccess = false
                    }
                }
            }

            // ΕΔΩ ΠΡΟΣΘΕΤΟΥΜΕ ΤΟ LOCK
            if (isSystemTarget) {
                RootTools.lockSystem()
            }

            binding.progressBar.visibility = View.GONE // Εξαφάνιση
            loadFiles(currentPath)

            // Επιστροφή στο UI
            loadFiles(currentPath)
            binding.fabPaste.hide()
            fileToMove = null
            bulkFilesToMove = emptyList()
            Toast.makeText(
                this@MainActivity,
                if (allSuccess) "Η αντιγραφή ολοκληρώθηκε!" else "Αποτυχία σε κάποια αρχεία",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showCreateFolderDialog() {
        val input = android.widget.EditText(this)
        input.hint = "Όνομα φακέλου"
        AlertDialog.Builder(this)
            .setTitle("Νέος Φάκελος")
            .setView(input)
            .setPositiveButton("Δημιουργία") { _, _ ->
                val newDir = File(currentPath, input.text.toString())
                if (newDir.mkdir()) loadFiles(currentPath)
                else Toast.makeText(this, "Αποτυχία", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }

    private fun showSortDialog() {
        val options = arrayOf("Όνομα (Α-Ω)", "Όνομα (Ω-Α)", "Μέγεθος (Μεγάλα)")
        AlertDialog.Builder(this)
            .setTitle("Ταξινόμηση")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> fullFileList.sortWith(compareByDescending<FileModel> { it.isDirectory }.thenBy { it.name.lowercase() })
                    1 -> fullFileList.sortWith(compareByDescending<FileModel> { it.isDirectory }.thenByDescending { it.name.lowercase() })
                    2 -> { /* Add size logic */
                    }
                }
                updateAdapter(fullFileList)
            }
            .show()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val storageRoot = Environment.getExternalStorageDirectory().absolutePath
                if (currentPath.absolutePath != storageRoot && currentPath.absolutePath != "/" && currentPath.parentFile != null) {
                    loadFiles(currentPath.parentFile!!)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun confirmDelete(file: FileModel) {
        AlertDialog.Builder(this)
            .setTitle("Διαγραφή")
            .setMessage("Θέλετε να διαγράψετε το ${file.name};")
            .setPositiveButton("Ναι") { _, _ ->
                MainScope().launch {
                    val isSystem =
                        file.path.startsWith("/system") || file.path.startsWith("/vendor")
                    if (isSystem) RootTools.unlockSystem()

                    val success = withContext(Dispatchers.IO) {
                        if (file.isRoot || isSystem) {
                            RootTools.executeSilent("rm -rf '${file.path}'")
                        } else {
                            File(file.path).deleteRecursively()
                        }
                    }

                    if (success) loadFiles(currentPath)
                    else Toast.makeText(this@MainActivity, "Αποτυχία διαγραφής", Toast.LENGTH_SHORT)
                        .show()
                }
            }
            .setNegativeButton("Όχι", null)
            .show()
    }

    private fun shareFile(fileModel: FileModel) {
        val file = File(fileModel.path)
        if (file.isDirectory) return
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share via"))
    }

    private fun openFile(file: File) {
        Toast.makeText(this, "Άνοιγμα: ${file.name}", Toast.LENGTH_SHORT).show()
    }

    private fun showRenameDialog(fileModel: FileModel) {
        val input = android.widget.EditText(this)
        input.setText(fileModel.name)
        AlertDialog.Builder(this)
            .setTitle("Μετονομασία")
            .setView(input)
            .setPositiveButton("ΟΚ") { _, _ ->
                val newName = input.text.toString()
                if (newName.isNotEmpty()) {
                    MainScope().launch {
                        val isSystem =
                            fileModel.path.startsWith("/system") || fileModel.path.startsWith("/vendor")
                        if (isSystem) RootTools.unlockSystem()

                        val parentPath = File(fileModel.path).parent
                        val newPath = "$parentPath/$newName"

                        val success = withContext(Dispatchers.IO) {
                            if (fileModel.isRoot || isSystem) {
                                RootTools.executeSilent("mv '${fileModel.path}' '$newPath'")
                            } else {
                                File(fileModel.path).renameTo(File(newPath))
                            }
                        }

                        if (success) {
                            loadFiles(currentPath)
                            exitSelectionMode()
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Αποτυχία μετονομασίας",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }

    private fun toggleSelectionMode(enabled: Boolean) {
        isSelectionMode = enabled
        binding.toolbar.menu.clear()

        if (enabled) {
            binding.toolbar.inflateMenu(R.menu.contextual_menu)
            binding.toolbar.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel)
            binding.toolbar.setNavigationOnClickListener { exitSelectionMode() }
            updateMenuVisibility()
        } else {
            binding.toolbar.setNavigationIcon(android.R.drawable.ic_menu_sort_by_size)
            binding.toolbar.title = currentPath.name.ifEmpty { "Internal Storage" }
            binding.toolbar.setNavigationOnClickListener {
                binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
            }
        }

        (binding.recyclerView.adapter as? FileAdapter)?.let {
            it.isInSelectionMode = enabled
            it.notifyDataSetChanged()
        }
    }

    // Έλεγχος αν θα φαίνεται το Rename
    private fun updateMenuVisibility() {
        val selectedCount = fullFileList.count { it.isSelected }
        val renameItem = binding.toolbar.menu.findItem(R.id.action_rename)
        renameItem?.isVisible = (selectedCount == 1) // Μόνο αν είναι 1 επιλεγμένο
    }

    private fun exitSelectionMode() {
        fullFileList.forEach { it.isSelected = false }
        isSelectionMode = false
        toggleSelectionMode(false)
    }

    // Για Μαζική Διαγραφή
    private fun confirmBulkDelete(selectedFiles: List<FileModel>) {
        AlertDialog.Builder(this)
            .setTitle("Μαζική Διαγραφή")
            .setMessage("Είσαι σίγουρος ότι θέλεις να διαγράψεις ${selectedFiles.size} στοιχεία;")
            .setPositiveButton("Ναι") { _, _ ->
                MainScope().launch {
                    val isSystem = currentPath.absolutePath.startsWith("/system") ||
                            currentPath.absolutePath.startsWith("/vendor")

                    if (isSystem) RootTools.unlockSystem()

                    val allSuccess = withContext(Dispatchers.IO) {
                        var success = true
                        selectedFiles.forEach { file ->
                            val result = if (file.isRoot || isSystem) {
                                RootTools.executeSilent("rm -rf '${file.path}'")
                            } else {
                                File(file.path).deleteRecursively()
                            }
                            if (!result) success = false
                        }
                        success
                    }

                    exitSelectionMode()
                    loadFiles(currentPath)
                    Toast.makeText(
                        this@MainActivity,
                        if (allSuccess) "Διαγράφηκαν επιτυχώς" else "Κάποιες διαγραφές απέτυχαν",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Όχι", null)
            .show()
    }

    private fun startBulkMove(selectedFiles: List<FileModel>, isCut: Boolean) {
        bulkFilesToMove = selectedFiles
        isCutOperation = isCut
        fileToMove = null // Ακυρώνουμε την μεμονωμένη μεταφορά αν υπήρχε

        Toast.makeText(
            this,
            "${selectedFiles.size} αρχεία έτοιμα για επικόλληση",
            Toast.LENGTH_SHORT
        ).show()
        exitSelectionMode()
        binding.fabPaste.show()
    }

    private fun showCreateItemDialog(isDirectory: Boolean) {
        val input = android.widget.EditText(this)
        input.hint = if (isDirectory) "Όνομα φακέλου" else "Όνομα αρχείου"

        AlertDialog.Builder(this)
            .setTitle(if (isDirectory) "Δημιουργία Φακέλου" else "Δημιουργία Αρχείου")
            .setView(input)
            .setPositiveButton("Δημιουργία") { _, _ ->
                val name = input.text.toString()
                if (name.isNotEmpty()) {
                    MainScope().launch {
                        val isSystemPath = currentPath.absolutePath.startsWith("/system") ||
                                currentPath.absolutePath.startsWith("/vendor") ||
                                currentPath.absolutePath == "/"

                        val success = withContext(Dispatchers.IO) {
                            try {
                                if (isSystemPath) {
                                    // Ξεκλείδωμα αν είναι σύστημα
                                    RootTools.unlockSystem()

                                    // Δημιουργία μέσω Root εντολών Linux
                                    val fullPath =
                                        "${currentPath.absolutePath}/$name".replace("//", "/")
                                    val command =
                                        if (isDirectory) "mkdir '$fullPath'" else "touch '$fullPath'"
                                    RootTools.executeSilent(command)
                                } else {
                                    // Κανονική δημιουργία για εσωτερική μνήμη
                                    val newItem = File(currentPath, name)
                                    if (isDirectory) newItem.mkdir() else newItem.createNewFile()
                                }
                            } catch (e: Exception) {
                                false
                            }
                        }

                        if (success) {
                            loadFiles(currentPath)
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Αποτυχία δημιουργίας (Root;)",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }

    private fun updateDrawerMenu() {
        val menu = binding.navigationView.menu

        // Αναζήτηση του item που περιέχει το SubMenu
        val favoriteItem = menu.findItem(R.id.nav_favorites_group)
        val favoriteSubMenu = favoriteItem?.subMenu ?:
        menu.addSubMenu(0, R.id.nav_favorites_group, 100, "Αγαπημένα")

        favoriteSubMenu.clear()

        favoritePaths.forEach { path ->
            val file = File(path)
            val menuItem = favoriteSubMenu.add(file.name)
            menuItem.setIcon(android.R.drawable.btn_star_big_on)

            menuItem.setOnMenuItemClickListener {
                // Αν ο χρήστης είναι ήδη στον φάκελο, ρωτάμε για αφαίρεση
                if (currentPath.absolutePath == path) {
                    showRemoveFavoriteDialog(path)
                } else {
                    loadFiles(file)
                    binding.drawerLayout.closeDrawers()
                }
                true
            }
        }
    }

    private fun showRemoveFavoriteDialog(path: String) {
        AlertDialog.Builder(this)
            .setTitle("Αφαίρεση από τα Αγαπημένα;")
            .setMessage("Θέλετε να αφαιρέσετε τον φάκελο ${File(path).name} από τη λίστα;")
            .setPositiveButton("Αφαίρεση") { _, _ ->
                favoritePaths.remove(path)
                getSharedPreferences("favorites", MODE_PRIVATE)
                    .edit()
                    .putStringSet("paths", favoritePaths)
                    .apply()
                updateDrawerMenu()
                Toast.makeText(this, "Αφαιρέθηκε", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }
}