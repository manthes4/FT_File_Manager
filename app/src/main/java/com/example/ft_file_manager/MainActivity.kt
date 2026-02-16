package com.example.ft_file_manager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ft_file_manager.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import kotlinx.coroutines.*
import java.util.Collections
import kotlin.jvm.java

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var currentPath: File = File("/storage/emulated/0")
    private var fullFileList = mutableListOf<FileModel>()
    private var fileToMove: FileModel? = null
    private var isCutOperation: Boolean = false
    private var isSelectionMode = false // Αυτή η γραμμή έλειπε!
    private var bulkFilesToMove = listOf<FileModel>() // Νέα μεταβλητή στην κορυφή της κλάσης!

    // Αντί για MutableSet, χρησιμοποιούμε MutableList για να έχουμε σειρά (index)
    private var favoritePaths = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Μέσα στο onCreate
        val prefs = getSharedPreferences("favorites", MODE_PRIVATE)
        val savedPaths = prefs.getString("paths_ordered", "")
        if (!savedPaths.isNullOrEmpty()) {
            // Καθαρίζουμε τη λίστα και προσθέτουμε τα στοιχεία από το String με τη σωστή σειρά
            favoritePaths.clear()
            favoritePaths.addAll(savedPaths.split("|"))
        } else {
            // Fallback: Αν υπάρχουν παλιά δεδομένα από το Set (πριν τη μετατροπή)
            val oldSet = prefs.getStringSet("paths", emptySet()) ?: emptySet()
            if (oldSet.isNotEmpty()) {
                favoritePaths.addAll(oldSet)
                saveFavorites() // Τα σώζουμε αμέσως στη νέα μορφή String
            }
        }
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
                    selectedFiles.forEach { model ->
                        if (model.isDirectory) {
                            // Παίρνουμε μόνο το όνομα του φακέλου για ετικέτα (π.χ. "Documents")
                            val folderName = File(model.path).name
                            val entryToSave = "$folderName*${model.path}"

                            // Έλεγχος αν υπάρχει ήδη (συγκρίνοντας το path μέρος)
                            if (!favoritePaths.any { it.endsWith("*${model.path}") }) {
                                favoritePaths.add(entryToSave)
                            }
                        }
                    }
                    saveFavorites()
                    updateDrawerMenu()
                    exitSelectionMode()
                    true
                }

                R.id.action_delete -> {
                    confirmBulkDelete(selectedFiles); true
                }

                R.id.action_copy -> {
                    startBulkMove(selectedFiles, isCut = false); true
                }

                R.id.action_cut -> {
                    startBulkMove(selectedFiles, isCut = true); true
                }

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
        loadFavoritesFromPrefs() // Φόρτωση
        updateDrawerMenu()       // Σχεδιασμός μενού
        setupDrawerDragAndDrop() // Ενεργοποίηση Drag & Drop
        loadFiles(currentPath)
    }

    override fun onResume() {
        super.onResume()
        // Αυτό εκτελείται κάθε φορά που επιστρέφεις στην εφαρμογή ή κλείνεις την FtpActivity
        loadFavoritesFromPrefs()
        updateDrawerMenu()
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

        // Χρησιμοποιούμε lifecycleScope ή MainScope για να ελέγχουμε το UI
        MainScope().launch {
            val fileList = mutableListOf<FileModel>()
            val files = withContext(Dispatchers.IO) { directory.listFiles() }

            if (files != null) {
                files.forEach {
                    fileList.add(
                        FileModel(
                            it.name, it.absolutePath, it.isDirectory,
                            // Αν είναι φάκελος, βάζουμε προσωρινό κείμενο
                            if (it.isDirectory) "Υπολογισμός..." else formatFileSize(it.length()),
                            false
                        )
                    )
                }
            } else {
                // ROOT MODE (ls -F)
                val cmd = "export LANG=en_US.UTF-8; ls -F -N --color=never '${directory.absolutePath}'"
                val output = RootTools.getOutput(cmd)

                if (output.isNotEmpty() && !output.contains("Error:")) {
                    output.split("\n").forEach { line ->
                        val name = line.trim()
                        if (name.isNotEmpty()) {
                            val isDir = name.endsWith("/")
                            val cleanName = if (isDir) name.dropLast(1) else name
                            fileList.add(
                                FileModel(
                                    cleanName,
                                    "${directory.absolutePath}/$cleanName".replace("//", "/"),
                                    isDir,
                                    if (isDir) "Υπολογισμός..." else "System File",
                                    false
                                )
                            )
                        }
                    }
                }
            }

            // Ταξινόμηση
            fileList.sortWith(compareByDescending<FileModel> { it.isDirectory }
                .thenBy { it.name.lowercase() })

            fullFileList = fileList
            updateAdapter(fileList)

            // --- ΕΔΩ ΞΕΚΙΝΑΕΙ Ο BACKGROUND ΥΠΟΛΟΓΙΣΜΟΣ ---
            launch(Dispatchers.Default) {
                fileList.forEachIndexed { index, model ->
                    if (model.isDirectory) {
                        // Υπολογισμός μεγέθους φακέλου στο IO Thread
                        val sizeValue = withContext(Dispatchers.IO) {
                            try {
                                getFolderSize(File(model.path))
                            } catch (e: Exception) {
                                0L
                            }
                        }

                        // Ενημέρωση του μοντέλου και του UI αμέσως μόλις βρεθεί το μέγεθος
                        withContext(Dispatchers.Main) {
                            model.size = formatFileSize(sizeValue)
                            // Ενημερώνουμε μόνο τη συγκεκριμένη σειρά στο RecyclerView για μέγιστη ταχύτητα
                            binding.recyclerView.adapter?.notifyItemChanged(index)
                        }
                    }
                }
            }
        }
    }

    // Πρόσθεσε και αυτές τις δύο βοηθητικές συναρτήσεις στην MainActivity
    private fun getFolderSize(folder: File): Long {
        var size: Long = 0
        val files = folder.listFiles()
        if (files != null) {
            for (file in files) {
                if (file.isFile) size += file.length()
                // Προαιρετικά για βάθος: else size += getFolderSize(file)
                // Σημείωση: Το scan μόνο των αρχείων πρώτου επιπέδου είναι ΠΟΛΥ πιο γρήγορο
            }
        }
        return size
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
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

            // Μεταφορά στον αρχικό κατάλογο της εσωτερικής μνήμης

            loadFiles(Environment.getExternalStorageDirectory())
        }

        view.findViewById<TextView>(R.id.btnCut).setOnClickListener {
            fileToMove = file
            isCutOperation = true
            binding.fabPaste.show()
            dialog.dismiss()

            // Μεταφορά στον αρχικό κατάλογο της εσωτερικής μνήμης

            loadFiles(Environment.getExternalStorageDirectory())
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

        MainScope().launch {
            binding.progressBar.visibility = View.VISIBLE
            var allSuccess = true

            // 1. Μετατροπή του currentPath στην πραγματική Root διαδρομή
            val rawDestDir = currentPath.absolutePath
            val realDestDir = if (rawDestDir.startsWith("/storage/emulated/0")) {
                rawDestDir.replace("/storage/emulated/0", "/data/media/0")
            } else {
                rawDestDir
            }

            // 2. Έλεγχος αν γράφουμε σε σύστημα
            val isSystemTarget = realDestDir.startsWith("/system") ||
                    realDestDir.startsWith("/vendor") ||
                    realDestDir == "/"

            if (isSystemTarget) {
                RootTools.unlockSystem()
            }

            withContext(Dispatchers.IO) {
                filesToProcess.forEach { model ->
                    // Μετατροπή και της πηγής (source) σε real path αν χρειάζεται
                    val rawSourcePath = model.path
                    val realSourcePath = if (rawSourcePath.startsWith("/storage/emulated/0")) {
                        rawSourcePath.replace("/storage/emulated/0", "/data/media/0")
                    } else {
                        rawSourcePath
                    }

                    val fileName = File(rawSourcePath).name
                    val finalDestPath = "$realDestDir/$fileName".replace("//", "/")

                    try {
                        val command = if (isCutOperation) {
                            "mv '$realSourcePath' '$finalDestPath'"
                        } else {
                            "cp -r '$realSourcePath' '$finalDestPath'"
                        }

                        // Εκτέλεση μέσω RootTools
                        val result = RootTools.executeSilent(command)
                        if (!result) allSuccess = false

                    } catch (e: Exception) {
                        allSuccess = false
                    }
                }
            }

            if (isSystemTarget) {
                RootTools.lockSystem()
            }

            binding.progressBar.visibility = View.GONE
            loadFiles(currentPath)
            binding.fabPaste.hide()
            fileToMove = null
            bulkFilesToMove = emptyList()

            Toast.makeText(
                this@MainActivity,
                if (allSuccess) "Η επιχείρηση ολοκληρώθηκε!" else "Αποτυχία σε κάποια αρχεία",
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
                    // 1. Μετατροπή path για παράκαμψη του Scoped Storage
                    val realPath = if (file.path.startsWith("/storage/emulated/0")) {
                        file.path.replace("/storage/emulated/0", "/data/media/0")
                    } else {
                        file.path
                    }

                    val isSystem = realPath.startsWith("/system") ||
                            realPath.startsWith("/vendor") ||
                            realPath == "/"

                    if (isSystem) RootTools.unlockSystem()

                    val success = withContext(Dispatchers.IO) {
                        // Χρησιμοποιούμε ΠΑΝΤΑ RootTools για εγγυημένη διαγραφή
                        // σε Android 14 και φακέλους όπως το Android/data
                        RootTools.executeSilent("rm -rf '$realPath'")
                    }

                    if (isSystem) RootTools.lockSystem()

                    if (success) {
                        loadFiles(currentPath)
                        Toast.makeText(this@MainActivity, "Διαγράφηκε", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Αποτυχία διαγραφής", Toast.LENGTH_SHORT).show()
                    }
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
                        // 1. Μετατροπή της παλιάς διαδρομής σε Real Root Path
                        val oldPathReal = if (fileModel.path.startsWith("/storage/emulated/0")) {
                            fileModel.path.replace("/storage/emulated/0", "/data/media/0")
                        } else {
                            fileModel.path
                        }

                        // 2. Υπολογισμός της νέας διαδρομής (στο ίδιο parent folder)
                        val parentFile = File(oldPathReal).parent ?: ""
                        val newPathReal = "$parentFile/$newName".replace("//", "/")

                        // 3. Έλεγχος για System Unlock
                        val isSystem = oldPathReal.startsWith("/system") ||
                                oldPathReal.startsWith("/vendor") ||
                                oldPathReal == "/"

                        if (isSystem) RootTools.unlockSystem()

                        val success = withContext(Dispatchers.IO) {
                            // Χρησιμοποιούμε ΠΑΝΤΑ mv μέσω Root για να είμαστε σίγουροι
                            RootTools.executeSilent("mv '$oldPathReal' '$newPathReal'")
                        }

                        if (isSystem) RootTools.lockSystem()

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
                            // Μετατροπή path για Root
                            val realPath = if (file.path.startsWith("/storage/emulated/0")) {
                                file.path.replace("/storage/emulated/0", "/data/media/0")
                            } else {
                                file.path
                            }

                            // Εκτέλεση rm -rf μέσω Root
                            val result = RootTools.executeSilent("rm -rf '$realPath'")
                            if (!result) success = false
                        }
                        success
                    }

                    if (isSystem) RootTools.lockSystem()

                    exitSelectionMode()
                    loadFiles(currentPath)
                    Toast.makeText(this@MainActivity,
                        if (allSuccess) "Διαγράφηκαν επιτυχώς" else "Κάποιες διαγραφές απέτυχαν",
                        Toast.LENGTH_SHORT).show()
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

        // Μεταφορά στον αρχικό κατάλογο της εσωτερικής μνήμης
        loadFiles(Environment.getExternalStorageDirectory())
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
                        // 1. Μετατροπή της εικονικής διαδρομής στην πραγματική διαδρομή Root
                        val rawPath = "${currentPath.absolutePath}/$name".replace("//", "/")
                        val realRootPath = if (rawPath.startsWith("/storage/emulated/0")) {
                            rawPath.replace("/storage/emulated/0", "/data/media/0")
                        } else {
                            rawPath
                        }

                        val success = withContext(Dispatchers.IO) {
                            try {
                                // 2. Πάντα ξεκλείδωμα (για σιγουριά)
                                RootTools.unlockSystem()

                                // 3. Χρήση Root εντολών παντού για να παρακάμψουμε το Scoped Storage
                                val command = if (isDirectory) "mkdir -p '$realRootPath'" else "touch '$realRootPath'"

                                // Εκτέλεση μέσω της νέας RootTools που φτιάξαμε
                                RootTools.executeSilent(command)
                            } catch (e: Exception) {
                                Log.e("FILE_MANAGER", "Error: ${e.message}")
                                false
                            }
                        }

                        if (success) {
                            loadFiles(currentPath)
                            Toast.makeText(this@MainActivity, "Επιτυχία!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Αποτυχία δημιουργίας", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }

    private fun updateDrawerMenu() {
        val menu = binding.navigationView.menu
        val favoriteItem = menu.findItem(R.id.nav_favorites_group) ?:
        menu.addSubMenu(0, R.id.nav_favorites_group, 100, "Αγαπημένα").item

        val favoriteSubMenu = favoriteItem.subMenu!!
        favoriteSubMenu.clear()

        favoritePaths.forEachIndexed { index, entry ->
            // Χωρίζουμε το Alias από το Path (μορφή: "Το Όνομά Μου*ftp://host")
            val parts = entry.split("*")
            val (displayName, realPath) = if (parts.size > 1) {
                parts[0] to parts[1]
            } else {
                // Αν είναι παλιό αγαπημένο χωρίς αστερίσκο
                val name = if (entry.startsWith("ftp://")) entry.replace("ftp://", "") else File(entry).name
                name to entry
            }

            val menuItem = favoriteSubMenu.add(0, index, index, displayName)
            menuItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)

            // Εικονίδιο ανάλογα με το REAL path
            if (realPath.startsWith("ftp://")) {
                menuItem.setIcon(android.R.drawable.ic_menu_share)
            } else {
                menuItem.setIcon(android.R.drawable.ic_dialog_map)
            }

            menuItem.setActionView(R.layout.menu_item_favorite)
            val actionView = menuItem.actionView

            actionView?.findViewById<ImageButton>(R.id.btnRenameFavorite)?.setOnClickListener {
                showRenameFavoriteDialog(entry, index)
            }

            actionView?.findViewById<ImageButton>(R.id.btnRemoveFavorite)?.setOnClickListener {
                showRemoveFavoriteDialog(entry)
            }

            menuItem.setOnMenuItemClickListener {
                if (realPath.startsWith("ftp://")) {
                    val host = realPath.replace("ftp://", "")
                    val intent = Intent(this, FtpActivity::class.java)
                    intent.putExtra("TARGET_HOST", host)
                    startActivity(intent)
                } else {
                    loadFiles(File(realPath))
                }
                binding.drawerLayout.closeDrawers()
                true
            }
        }
    }

    private fun showRenameFavoriteDialog(oldEntry: String, index: Int) {
        val parts = oldEntry.split("*")
        val currentAlias = parts[0]
        val realPath = if (parts.size > 1) parts[1] else parts[0]

        val input = android.widget.EditText(this)
        input.setText(currentAlias)

        AlertDialog.Builder(this)
            .setTitle("Μετονομασία Ετικέτας")
            .setMessage("Δώστε ένα όνομα για αυτό το αγαπημένο:")
            .setView(input)
            .setPositiveButton("Αποθήκευση") { _, _ ->
                val newAlias = input.text.toString()
                if (newAlias.isNotEmpty()) {
                    // Αποθηκεύουμε ως "ΝέοΌνομα*ΠραγματικόPath"
                    favoritePaths[index] = "$newAlias*$realPath"
                    saveFavorites()
                    updateDrawerMenu()
                }
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }

    private fun saveFavorites() {
        val prefs = getSharedPreferences("favorites", MODE_PRIVATE)
        val orderedString = favoritePaths.joinToString("|")
        prefs.edit()
            .putString("paths_ordered", orderedString)
            // Προαιρετικά σβήνουμε το παλιό set για να μην πιάνει χώρο
            .remove("paths")
            .apply()
    }

    private fun showRemoveFavoriteDialog(entry: String) {
        val displayName = entry.split("*")[0] // Παίρνουμε μόνο το πρώτο μέρος
        AlertDialog.Builder(this)
            .setTitle("Αφαίρεση από τα Αγαπημένα;")
            .setMessage("Θέλετε να αφαιρέσετε το '$displayName' από τη λίστα;")
            .setPositiveButton("Αφαίρεση") { _, _ ->
                favoritePaths.remove(entry)
                saveFavorites()
                updateDrawerMenu()
                Toast.makeText(this, "Αφαιρέθηκε", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }

    private fun setupDrawerDragAndDrop() {
        val navRecycler = binding.navigationView.getChildAt(0) as? RecyclerView ?: return

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition

                // ΒΑΣΙΚΗ ΑΛΛΑΓΗ: Το offset σου είναι 7 βάσει των logs
                val offset = 7

                if (fromPos < offset || toPos < offset) return false

                val fromIdx = fromPos - offset
                val toIdx = toPos - offset

                // Έλεγχος αν οι δείκτες είναι μέσα στα όρια της λίστας (0 έως 2)
                if (fromIdx in favoritePaths.indices && toIdx in favoritePaths.indices) {
                    // 1. Swap στη λίστα
                    java.util.Collections.swap(favoritePaths, fromIdx, toIdx)

                    // 2. Ενημέρωση του Adapter
                    recyclerView.adapter?.notifyItemMoved(fromPos, toPos)

                    // 3. ΕΞΑΝΑΓΚΑΣΜΟΣ ανανέωσης για να παραμερίσουν οι άλλοι
                    recyclerView.adapter?.notifyItemChanged(fromPos)
                    recyclerView.adapter?.notifyItemChanged(toPos)

                    return true
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Σώζουμε και φρεσκάρουμε τα κουμπιά "X"
                saveFavorites()
                updateDrawerMenu()
            }
        })

        itemTouchHelper.attachToRecyclerView(navRecycler)
    }
    private fun loadFavoritesFromPrefs() {
        val prefs = getSharedPreferences("favorites", MODE_PRIVATE)
        val savedPaths = prefs.getString("paths_ordered", "")
        if (!savedPaths.isNullOrEmpty()) {
            favoritePaths.clear()
            favoritePaths.addAll(savedPaths.split("|"))
        }
    }

    private fun openFile(file: File) {
        val extension = file.extension.lowercase()

        when (extension) {
            "txt", "log", "java", "py", "xml", "json", "html" -> {
                val intent = Intent(this, TextEditorActivity::class.java)
                intent.putExtra("PATH", file.absolutePath)
                startActivity(intent)
            }
            "jpg", "jpeg", "png", "gif", "webp" -> {
                // Εδώ θα φτιάξεις μια ImageViewActivity αντίστοιχα
                val intent = Intent(this, ImageViewActivity::class.java)
                intent.putExtra("PATH", file.absolutePath)
                startActivity(intent)
            }
            "pdf" -> {
                // Για PDF, αν δεν θες βιβλιοθήκη, η καλύτερη λύση εσωτερικά
                // είναι το PdfRenderer του Android (θέλει λίγο κώδικα παραπάνω)
                openPdfInternal(file)
            }
            else -> {
                // Για όλα τα άλλα (mp3, mp4, docx), άνοιγμα με εξωτερική εφαρμογή
                openFileExternally(file)
            }
        }
    }

    private fun openFileExternally(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.provider", file
            )
            val extension = file.extension.lowercase()
            val mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extension) ?: "*/*"

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Άνοιγμα με:"))
        } catch (e: Exception) {
            Toast.makeText(this, "Αποτυχία ανοίγματος: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPdfInternal(file: File) {
        // Προσωρινά τα στέλνουμε έξω μέχρι να φτιάξουμε τον δικό μας Viewer
        openFileExternally(file)
    }
}