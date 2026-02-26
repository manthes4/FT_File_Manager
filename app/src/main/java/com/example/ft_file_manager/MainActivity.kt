package com.example.ft_file_manager

import android.content.ClipData
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.view.Menu
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.EditText
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
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.ChannelSftp
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var currentPath: File = File("/storage/emulated/0")
    private var fullFileList = mutableListOf<FileModel>()
    private var fileToMove: FileModel? = null
    private var isCutOperation: Boolean = false
    private var isSelectionMode = false // Αυτή η γραμμή έλειπε!
    private var bulkFilesToMove = listOf<FileModel>() // Νέα μεταβλητή στην κορυφή της κλάσης!
    private val sizeCache = mutableMapOf<String, CharSequence>()
    private lateinit var adapter: FileAdapter

    private var protocol = "LOCAL" // Καλή πρακτική: ξεκινάμε πάντα ως Local
    private var host = ""
    private var user = ""
    private var pass = ""

    // Αντί για MutableSet, χρησιμοποιούμε MutableList για να έχουμε σειρά (index)
    private var favoritePaths = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        com.bumptech.glide.Glide.get(this).setMemoryCategory(com.bumptech.glide.MemoryCategory.HIGH)

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

        // 1. Ορίζουμε το μέγεθος των thumbnails για τον προ-φορτωτή
        val sizeProvider = com.bumptech.glide.util.FixedPreloadSizeProvider<FileModel>(150, 150)

// 2. Ορίζουμε ποια αρχεία θα προ-φορτώνονται (μόνο εικόνες/βίντεο)
        val modelProvider =
            object : com.bumptech.glide.ListPreloader.PreloadModelProvider<FileModel> {
                override fun getPreloadItems(position: Int): List<FileModel> {
                    val item = fullFileList.getOrNull(position)
                    // Προ-φορτώνουμε μόνο αν είναι αρχείο και όχι φάκελος
                    return if (item != null && !item.isDirectory && isImageOrVideo(item.path)) {
                        listOf(item)
                    } else {
                        emptyList()
                    }
                }

                override fun getPreloadRequestBuilder(item: FileModel): RequestBuilder<*> {
                    // ΕΔΩ ΠΡΕΠΕΙ ΝΑ ΕΙΝΑΙ ΑΚΡΙΒΩΣ ΟΙ ΙΔΙΕΣ ΡΥΘΜΙΣΕΙΣ ΜΕ ΤΟΝ ADAPTER
                    return Glide.with(this@MainActivity)
                        .asBitmap()
                        .load(item.path)
                        .signature(ObjectKey(item.path + item.lastModifiedCached))
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE) // Αποθηκεύει μόνο το τελικό thumbnail των 120x120
                        .override(120, 120)
                        .centerCrop()
                }
            }

// 3. Σύνδεση του Preloader με το RecyclerView (Προ-φορτώνουμε 30 στοιχεία μπροστά!)
        val preloader =
            com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader<FileModel>(
                Glide.with(this), modelProvider, sizeProvider, 50
            )
        // Στην onCreate της MainActivity.kt:
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setHasFixedSize(true) // Βοηθάει πολύ στην ταχύτητα
            setItemViewCacheSize(15) // Κρατάει 20 στοιχεία έτοιμα στη μνήμη
        }

        // 2. ΕΔΩ ΜΠΑΙΝΕΙ Ο SCROLL LISTENER
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)

                if (newState == RecyclerView.SCROLL_STATE_DRAGGING || newState == RecyclerView.SCROLL_STATE_SETTLING) {
                    // Σταματάει το Glide όσο σκρολάρεις για να μη λαγκάρει η SD card
                    Glide.with(this@MainActivity).pauseRequests()
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    // Ξεκινάει πάλι το Glide μόλις σταματήσει τελείως το scroll
                    Glide.with(this@MainActivity).resumeRequests()
                }
            }
        })

        // Δίνει προτεραιότητα στο UI thread για πιο ομαλή κίνηση
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        binding.toolbar.findViewById<ImageButton>(R.id.btnUp).setOnClickListener {
            val parent = currentPath.parentFile
            // Έλεγχος για να μην βγούμε έξω από το Root ή το Internal
            if (parent != null && currentPath.absolutePath != "/") {
                loadFiles(parent)
            } else {
                Toast.makeText(this, "Βρίσκεστε στον αρχικό φάκελο", Toast.LENGTH_SHORT).show()
            }
        }

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

                R.id.action_share -> {
                    if (selectedFiles.isNotEmpty()) {
                        // Αν θέλεις να μοιραστείς πολλά αρχεία
                        if (selectedFiles.size == 1) {
                            shareFile(selectedFiles[0])
                        } else {
                            // Για πολλά αρχεία, θα χρειαστείς ACTION_SEND_MULTIPLE
                            shareMultipleFiles(selectedFiles)
                        }
                    } else {
                        Toast.makeText(this, "Επιλέξτε αρχεία πρώτα", Toast.LENGTH_SHORT).show()
                    }
                    true
                }

                R.id.action_copy -> {
                    val firstFile = selectedFiles.firstOrNull()?.path ?: ""

                    // ΕΛΕΓΧΟΣ ΜΟΝΟ ΜΕ ΒΑΣΗ ΤΟ PATH
                    val isSmbSource = firstFile.startsWith("smb://")
                    val isSftpSource = firstFile.startsWith("sftp://")

                    TransferManager.filesToMove = selectedFiles
                    TransferManager.isCut = false
                    TransferManager.sourceIsSmb = isSmbSource
                    TransferManager.sourceIsSftp = isSftpSource

                    Log.d("TRANSFER_DEBUG", "Copy - SMB: $isSmbSource, SFTP: $isSftpSource, Path: $firstFile")
                    startBulkMove(selectedFiles, isCut = false)
                    goToDashboard()
                    true
                }

                R.id.action_cut -> {
                    val firstFile = selectedFiles.firstOrNull()?.path ?: ""

                    // Αφαιρούμε το "|| protocol == ..." για να μην μπερδεύεται με την παλιά τιμή
                    val isSmbSource = firstFile.startsWith("smb://")
                    val isSftpSource = firstFile.startsWith("sftp://")

                    TransferManager.filesToMove = selectedFiles
                    TransferManager.isCut = true
                    TransferManager.sourceIsSmb = isSmbSource
                    TransferManager.sourceIsSftp = isSftpSource

                    Log.d("TRANSFER_DEBUG", "Cut - SMB: $isSmbSource, SFTP: $isSftpSource, Path: $firstFile")

                    startBulkMove(selectedFiles, isCut = true)

                    // Εδώ ο κώδικας για το Intent (αφού δεν έχεις την helper goToDashboard)
                    val intent = Intent(this, DashboardActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    startActivity(intent)
                    finish()
                    true
                }

                R.id.action_rename -> {
                    if (selectedFiles.size == 1) showRenameDialog(selectedFiles[0])
                    true
                }

                R.id.action_delete -> {
                    confirmBulkDelete(selectedFiles); true
                }

                R.id.action_reset_app -> {
                    if (selectedFiles.isNotEmpty()) {
                        selectedFiles.forEach { model ->
                            // Παίρνουμε την κατάληξη από το path ή το name
                            val fileName = model.name ?: ""
                            val extension = fileName.substringAfterLast('.', "").lowercase()

                            if (extension.isNotEmpty()) {
                                val mimeType = android.webkit.MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(extension) ?: "*/*"

                                // Καθαρισμός προτίμησης
                                getSharedPreferences("AppPreferences", MODE_PRIVATE).edit()
                                    .remove("pref_$mimeType")
                                    .apply()

                                Toast.makeText(this, "Reset: .$extension", Toast.LENGTH_SHORT).show()
                            }
                        }
                        exitSelectionMode() // Κλείνουμε το μενού επιλογής αφού τελειώσουμε
                    } else {
                        Toast.makeText(this, "Επιλέξτε αρχεία πρώτα", Toast.LENGTH_SHORT).show()
                    }
                    true
                }

                R.id.action_zip -> {
                    if (selectedFiles.isNotEmpty()) {
                        val firstFile = File(selectedFiles[0].path)
                        val parentDir = firstFile.parentFile ?: File("/storage/emulated/0/Download")
                        val zipFile = File(parentDir, "${firstFile.nameWithoutExtension}.zip")

                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val firstFile = File(selectedFiles[0].path)
                                val finalZip = File(firstFile.parentFile, "${firstFile.nameWithoutExtension}.zip")

                                // Δημιουργούμε ένα προσωρινό αρχείο
                                val tempZip = File(firstFile.parentFile, "temp_${System.currentTimeMillis()}.zip")

                                FileOutputStream(tempZip).use { fos ->
                                    ZipOutputStream(BufferedOutputStream(fos)).use { zos ->
                                        selectedFiles.forEach { model ->
                                            addToZip(File(model.path), zos, "")
                                        }
                                    }
                                }

                                // Αν υπάρχει το παλιό, το σβήνουμε τώρα
                                if (finalZip.exists()) finalZip.delete()

                                // Μετονομασία του temp στο τελικό
                                val success = tempZip.renameTo(finalZip)
                                Log.d("ZIP_LOG", "Rename success: $success")

                                // Ενημέρωση συστήματος για να "δει" το νέο αρχείο
                                MediaScannerConnection.scanFile(this@MainActivity, arrayOf(finalZip.absolutePath), null, null)

                                withContext(Dispatchers.Main) {
                                    loadFiles(currentPath)
                                    exitSelectionMode()
                                }
                            } catch (e: Exception) {
                                Log.e("ZIP_LOG", "ΣΦΑΛΜΑ: ${e.message}")
                            }
                        }
                    }
                    true
                }

                R.id.action_unzip -> {
                    val selectedFile = File(selectedFiles[0].path)
                    if (selectedFile.extension.lowercase() == "zip") {
                        unzipDirectly(selectedFile)
                    } else {
                        Toast.makeText(this, "Επιλέξτε ένα αρχείο ZIP", Toast.LENGTH_SHORT).show()
                    }
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

        binding.btnDashboard.setOnClickListener {
            finish() // Κλείνει την MainActivity και επιστρέφει στην προηγούμενη (Dashboard)
        }

        // Κουμπί Επιβεβαίωσης Επικόλλησης
        binding.btnConfirmPaste.setOnClickListener {
            pasteFile()
        }

// Κουμπί Ακύρωσης
        binding.btnCancelPaste.setOnClickListener {
            cleanupAfterPaste() // Χρησιμοποιούμε τη συνάρτηση καθαρισμού που φτιάξαμε
            Toast.makeText(this, "Η επικόλληση ακυρώθηκε", Toast.LENGTH_SHORT).show()
        }

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

        // Μέσα στο onCreate, μετά το binding.btnSort.setOnClickListener
        binding.btnSearch.setOnClickListener {
            showSearchDialog()
        }

        // Λήψη του μονοπατιού από το Dashboard
        val startPathStr = intent.getStringExtra("START_PATH")
            ?: Environment.getExternalStorageDirectory().absolutePath
        val startPathFile = File(startPathStr)

        setupNavigationDrawer()
        setupBackNavigation()
        checkPermissions()
        loadFavoritesFromPrefs()
        updateDrawerMenu()
        setupDrawerDragAndDrop()

// ΠΡΟΣΟΧΗ: Φόρτωσε το αρχείο που ήρθε από το Intent
        loadFiles(startPathFile)
    }

    override fun onResume() {
        super.onResume()
        // Φόρτωση ρυθμίσεων και ενημέρωση μενού
        loadFavoritesFromPrefs()
        loadFiles(currentPath)
        updateDrawerMenu()

        // --- Η ΝΕΑ ΛΟΓΙΚΗ ΓΙΑ ΤΟ CONTAINER ---
        if (TransferManager.filesToMove.isNotEmpty()) {
            // Εμφανίζουμε όλο το ημιδιαφανές πλαίσιο με τα δύο κουμπιά
            binding.pasteContainer.visibility = View.VISIBLE
            Log.d("TRANSFER_DEBUG", "Files found in Manager, showing Paste UI")
        } else {
            // Αν δεν υπάρχει τίποτα για επικόλληση, το κρύβουμε
            binding.pasteContainer.visibility = View.GONE
        }
    }

    private fun cleanupAfterPaste() {
        TransferManager.filesToMove = emptyList()
        TransferManager.sourceIsSmb = false
        TransferManager.sourceIsSftp = false
        TransferManager.isCut = false
        fileToMove = null
        bulkFilesToMove = emptyList()

        // Κρύβουμε όλο το πλαίσιο
        binding.pasteContainer.visibility = View.GONE
    }

    private fun checkPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }

    private fun goToDashboard() {
        val intent = Intent(this, DashboardActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }

    private fun setupNavigationDrawer() {
        binding.navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_internal -> loadFiles(Environment.getExternalStorageDirectory())
                R.id.nav_root -> loadFiles(File("/"))
                R.id.nav_ftp -> startActivity(Intent(this, FtpActivity::class.java))

                // Η ΝΕΑ ΠΡΟΣΘΗΚΗ ΕΔΩ:
                R.id.nav_network -> {
                    val intent = Intent(this, NetworkClientActivity::class.java)
                    startActivity(intent)
                }

                R.id.nav_external -> {
                    val externalDirs = getExternalFilesDirs(null)
                    if (externalDirs.size > 1 && externalDirs[1] != null) {
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

    private fun isImageOrVideo(path: String): Boolean {
        val extension = path.substringAfterLast('.', "").lowercase()
        val imageExtensions = listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        val videoExtensions = listOf("mp4", "mkv", "avi", "mov", "3gp")
        return imageExtensions.contains(extension) || videoExtensions.contains(extension)
    }

    private fun loadFiles(directory: File) {
        currentPath = directory
        val displayTitle = getDisplayTitle(directory.absolutePath)
        supportActionBar?.title = displayTitle
        updateBreadcrumbs(directory.absolutePath) // Προσθήκη εδώ

        // Χρησιμοποιούμε lifecycleScope αντί για MainScope για καλύτερη διαχείριση μνήμης
        lifecycleScope.launch {
            val fileList = withContext(Dispatchers.IO) {
                val tempArrayList = mutableListOf<FileModel>()
                val files = try {
                    directory.listFiles()
                } catch (e: Exception) {
                    null
                }

                if (files != null) {
                    for (file in files) {
                        val cachedSize = sizeCache[file.absolutePath]
                        val lastModValue = file.lastModified()

                        tempArrayList.add(
                            FileModel(
                                name = file.name,
                                path = file.absolutePath,
                                isDirectory = file.isDirectory,
                                size = cachedSize
                                    ?: if (file.isDirectory) "--" else FolderCalculator.formatSize(
                                        file.length()
                                    ),
                                isSelected = false,
                                lastModifiedCached = lastModValue // <--- Πρόσεξε το κόμμα στην προηγούμενη γραμμή!
                            )
                        )
                    }
                } else {
                    // ROOT / BYPASS MODE
                    val cmd =
                        "export LANG=en_US.UTF-8; ls -p -N --color=never \"${directory.absolutePath}\""
                    val output = RootTools.getOutput(cmd)
                    if (output.isNotEmpty() && !output.contains("Error:")) {
                        output.split("\n").forEach { line ->
                            val name = line.trim()
                            if (name.isNotEmpty()) {
                                val isDir = name.endsWith("/")
                                val cleanName = if (isDir) name.dropLast(1) else name
                                val fullPath = File(directory, cleanName).absolutePath
                                val cachedSize = sizeCache[fullPath]

                                tempArrayList.add(
                                    FileModel(
                                        cleanName,
                                        fullPath,
                                        isDir,
                                        cachedSize ?: if (isDir) "--" else "System File",
                                        false
                                    )
                                )
                            }
                        }
                    }
                }

                // Η ΤΑΞΙΝΟΜΗΣΗ ΓΙΝΕΤΑΙ ΕΔΩ (Στο IO Thread)
                tempArrayList.sortWith(compareByDescending<FileModel> { it.isDirectory }.thenBy { it.name.lowercase() })
                tempArrayList // Επιστρέφει την έτοιμη λίστα
            }

            // Μόνο η ενημέρωση του Adapter γίνεται στο Main Thread
            fullFileList = fileList
            updateAdapter(fileList)
        }
    }

    private fun safeDelete(fileModel: FileModel) {
        MainScope().launch {
            val success = withContext(Dispatchers.IO) {
                val path = fileModel.path
                // Χρησιμοποιούμε rm -rf με διπλά εισαγωγικά για τα σύμβολα
                RootTools.executeSilent("rm -rf \"$path\"")
            }

            if (success) {
                sizeCache.remove(fileModel.path) // Αφαιρεί το αρχείο
                sizeCache.remove(currentPath.absolutePath) // Αφαιρεί τον φάκελο για να ξαναμετρηθεί!

                Toast.makeText(this@MainActivity, "Διαγράφηκε", Toast.LENGTH_SHORT).show()
                loadFiles(currentPath)
            } else {
                Toast.makeText(this@MainActivity, "Αποτυχία διαγραφής", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateAdapter(list: List<FileModel>) {
        // Δημιουργούμε τον adapter και τον αποθηκεύουμε στη lateinit μεταβλητή της κλάσης
        adapter = FileAdapter(
            list,
            isInSelectionMode = isSelectionMode,
            onItemClick = { selectedFile ->
                if (isSelectionMode) {
                    selectedFile.isSelected = !selectedFile.isSelected
                    adapter.notifyDataSetChanged() // Χρήση της μεταβλητής πλέον
                    val count = fullFileList.count { it.isSelected }
                    if (count == 0) exitSelectionMode()
                    else binding.toolbar.title = "$count επιλεγμένα"
                } else {
                    val file = File(selectedFile.path)
                    if (selectedFile.isDirectory) loadFiles(file) else openFile(file)
                }
            },
            onItemLongClick = { selectedFile ->
                if (!isSelectionMode) {
                    selectedFile.isSelected = true
                    toggleSelectionMode(true)
                }
            },
            onSelectionChanged = {
                val count = fullFileList.count { it.isSelected }
                if (count == 0) exitSelectionMode()
                else {
                    binding.toolbar.title = "$count επιλεγμένα"
                    updateMenuVisibility()
                }
            }
        )

        // Τώρα τον συνδέουμε με το UI
        binding.recyclerView.adapter = adapter
    }

    private fun showOptionsDialog(file: FileModel) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_file_options, null)

        view.findViewById<TextView>(R.id.btnCopy).setOnClickListener {
            // 1. Ενημέρωση τοπικών μεταβλητών
            fileToMove = file
            isCutOperation = false

            // 2. Ενημέρωση TransferManager με βάση το πρωτόκολλο
            TransferManager.filesToMove = listOf(file)
            TransferManager.isCut = false

            // Εδώ είναι το κρίσιμο σημείο:
            TransferManager.sourceIsSmb = (protocol == "SMB")
            TransferManager.sourceIsSftp = (protocol == "SFTP")

            // Αποθήκευση στοιχείων σύνδεσης στον TransferManager για να ξέρει από πού να τα τραβήξει
            if (protocol == "SFTP") {
                TransferManager.sftpHost = host
                TransferManager.sftpUser = user
                TransferManager.sftpPass = pass
            } else {
                TransferManager.smbHost = host
                TransferManager.smbUser = user
                TransferManager.smbPass = pass
            }

            dialog.dismiss()

            // ΜΕΤΑΒΑΣΗ ΣΤΟ DASHBOARD
            val intent = Intent(this, DashboardActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)

            finish()
        }

        view.findViewById<TextView>(R.id.btnCut).setOnClickListener {
            fileToMove = file
            isCutOperation = true

            // 1. Ενημέρωση TransferManager
            TransferManager.filesToMove = listOf(file)
            TransferManager.isCut = true

            // 2. Ενημέρωση Πρωτοκόλλου Πηγής
            TransferManager.sourceIsSmb = (protocol == "SMB")
            TransferManager.sourceIsSftp = (protocol == "SFTP")

            // 3. Μεταφορά στοιχείων σύνδεσης
            if (protocol == "SFTP") {
                TransferManager.sftpHost = host
                TransferManager.sftpUser = user
                TransferManager.sftpPass = pass
            } else {
                TransferManager.smbHost = host
                TransferManager.smbUser = user
                TransferManager.smbPass = pass
            }

            dialog.dismiss()

            // ΜΕΤΑΒΑΣΗ ΣΤΟ DASHBOARD
            val intent = Intent(this, DashboardActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)

            finish()
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

        view.findViewById<TextView>(R.id.btnResetAppPreference).setOnClickListener {
            val fileName = file.name ?: ""
            val extension = fileName.substringAfterLast('.', "").lowercase()

            if (extension.isNotEmpty()) {
                val mimeType = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(extension) ?: "*/*"

                getSharedPreferences("AppPreferences", MODE_PRIVATE).edit()
                    .remove("pref_$mimeType")
                    .apply()

                Toast.makeText(this, "Η προτίμηση για .$extension καθαρίστηκε", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun showSearchDialog() {
        val input = EditText(this)
        input.hint = "Αναζήτηση παντού..."

        AlertDialog.Builder(this)
            .setTitle("Βαθιά Αναζήτηση")
            .setView(input)
            .setPositiveButton("Αναζήτηση") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) {
                    performDeepSearch(query)
                }
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }

    private fun performDeepSearch(query: String) {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            // Η εντολή 'find' ψάχνει αναδρομικά σε όλους τους υποφακέλους
            // Το -iname κάνει την αναζήτηση case-insensitive (αγνοεί κεφαλαία/μικρά)
            val cmd = "find \"${currentPath.absolutePath}\" -iname \"*$query*\" -maxdepth 3"
            val output = RootTools.getOutput(cmd)

            val searchResults = mutableListOf<FileModel>()
            output.split("\n").forEach { path ->
                val file = File(path.trim())
                if (file.exists()) {
                    searchResults.add(
                        FileModel(
                            file.name,
                            file.absolutePath,
                            file.isDirectory,
                            if (file.isDirectory) "--" else FolderCalculator.formatSize(file.length()),
                            false
                        )
                    )
                }
            }

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                val adapter = binding.recyclerView.adapter as? FileAdapter
                adapter?.updateList(searchResults)
                binding.toolbar.title = "Αποτελέσματα: ${searchResults.size}"
            }
        }
    }

    private fun pasteFile() {
        Log.d("TRANSFER_DEBUG", "--- Paste Initiated ---")
        Log.d("TRANSFER_DEBUG", "Files to move count: ${TransferManager.filesToMove.size}")
        Log.d("TRANSFER_DEBUG", "Protocol Flags -> SMB: ${TransferManager.sourceIsSmb}, SFTP: ${TransferManager.sourceIsSftp}")

        // 1. Έλεγχος Δικτύου
        if (TransferManager.filesToMove.isNotEmpty() && (TransferManager.sourceIsSmb || TransferManager.sourceIsSftp)) {
            Log.d("TRANSFER_DEBUG", "Redirecting to Network Download Flow")
            executeNetworkDownload()
            return
        }

        // 2. Τοπική Επικόλληση
        val filesToProcess = TransferManager.filesToMove
        val isCut = TransferManager.isCut

        if (filesToProcess.isEmpty()) {
            Log.e("TRANSFER_DEBUG", "Abort: filesToProcess is EMPTY")
            Toast.makeText(this, "Δεν υπάρχουν αρχεία για επικόλληση", Toast.LENGTH_SHORT).show()
            return
        }

        MainScope().launch {
            binding.progressBar.visibility = View.VISIBLE
            var allSuccess = true

            val rawDestDir = currentPath.absolutePath
            val realDestDir = rawDestDir.replace("/storage/emulated/0", "/data/media/0")

            Log.d("TRANSFER_DEBUG", "Destination - Raw: $rawDestDir, Real: $realDestDir")

            val isSystemTarget = realDestDir.startsWith("/system") || realDestDir.startsWith("/vendor") || realDestDir == "/"
            if (isSystemTarget) {
                Log.d("TRANSFER_DEBUG", "System target detected, unlocking...")
                RootTools.unlockSystem()
            }

            withContext(Dispatchers.IO) {
                filesToProcess.forEachIndexed { index, fileModel ->
                    Log.d("TRANSFER_DEBUG", "Processing file [$index]: ${fileModel.name}")
                    Log.d("TRANSFER_DEBUG", "Source Path: ${fileModel.path}")

                    val realSourcePath = fileModel.path.replace("/storage/emulated/0", "/data/media/0")
                    val fileName = File(fileModel.path).name
                    val finalDestPath = "$realDestDir/$fileName".replace("//", "/")

                    try {
                        val command = if (isCut) {
                            "mv \"$realSourcePath\" \"$finalDestPath\""
                        } else {
                            "cp -r \"$realSourcePath\" \"$finalDestPath\""
                        }

                        Log.d("TRANSFER_DEBUG", "Executing Command: $command")

                        val result = RootTools.executeSilent(command)
                        Log.d("TRANSFER_DEBUG", "Command Result: $result")

                        if (!result) allSuccess = false
                    } catch (e: Exception) {
                        Log.e("TRANSFER_DEBUG", "Exception during local paste: ${e.message}")
                        allSuccess = false
                    }
                }
            }

            if (isSystemTarget) RootTools.lockSystem()

            Log.d("TRANSFER_DEBUG", "Cleaning up TransferManager...")
            cleanupTransfer()

            binding.progressBar.visibility = View.GONE
            loadFiles(currentPath)

            Toast.makeText(this@MainActivity, if (allSuccess) "Ολοκληρώθηκε!" else "Αποτυχία (Check Logs)", Toast.LENGTH_SHORT).show()
        }
    }

    // Βοηθητική συνάρτηση για καθαρισμό
    private fun cleanupTransfer() {
        Log.d("TRANSFER_DEBUG", "Cleaning up TransferManager and hiding UI")

        // 1. Καθαρισμός δεδομένων στον Singleton Manager
        TransferManager.filesToMove = emptyList()
        TransferManager.sourceIsSmb = false
        TransferManager.sourceIsSftp = false
        TransferManager.isCut = false

        // 2. Καθαρισμός τοπικών μεταβλητών της MainActivity
        fileToMove = null
        bulkFilesToMove = emptyList()

        // 3. ΕΝΗΜΕΡΩΣΗ UI: Κρύβουμε όλο το πλαίσιο (Container) αντί για το FAB
        binding.pasteContainer.visibility = View.GONE

        // Αν έχεις κρατήσει και το παλιό fabPaste για ασφάλεια:
        // binding.fabPaste.hide()
    }

    private fun executeNetworkDownload() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("TRANSFER_DEBUG", "Starting Network Download...")
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.VISIBLE
                }

                val targetDir = currentPath
                val isCut = TransferManager.isCut

                TransferManager.filesToMove.forEach { model ->
                    Log.d("TRANSFER_DEBUG", "Downloading: ${model.name} from ${model.path}")
                    val localDest = File(targetDir, model.name)

                    if (TransferManager.sourceIsSftp) {
                        // --- SFTP DOWNLOAD ---
                        val jsch = com.jcraft.jsch.JSch()
                        val session = jsch.getSession(TransferManager.sftpUser, TransferManager.sftpHost, 22)
                        session.setPassword(TransferManager.sftpPass)
                        session.setConfig("StrictHostKeyChecking", "no")
                        session.connect()

                        val channel = session.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
                        channel.connect()
                        channel.get(model.path, localDest.absolutePath)

                        if (isCut) channel.rm(model.path)

                        channel.disconnect()
                        session.disconnect()

                    } else if (TransferManager.sourceIsSmb) {
                        // --- SMB DOWNLOAD ---
                        val props = java.util.Properties()
                        props.setProperty("jcifs.smb.client.dfs.disabled", "true")
                        val config = org.codelibs.jcifs.smb.config.PropertyConfiguration(props)
                        val baseContext = org.codelibs.jcifs.smb.context.BaseContext(config)
                        val auth = org.codelibs.jcifs.smb.impl.NtlmPasswordAuthenticator(
                            null, TransferManager.smbUser, TransferManager.smbPass
                        )
                        val smbContext = baseContext.withCredentials(auth)
                        val smbSource = org.codelibs.jcifs.smb.impl.SmbFile(model.path, smbContext)

                        smbSource.getInputStream().use { input ->
                            localDest.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                        if (isCut) smbSource.delete()
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    loadFiles(currentPath)

                    // --- ΧΡΗΣΗ ΤΗΣ ΚΕΝΤΡΙΚΗΣ ΣΥΝΑΡΤΗΣΗΣ ΚΑΘΑΡΙΣΜΟΥ ---
                    cleanupTransfer()

                    Toast.makeText(this@MainActivity, "Η λήψη ολοκληρώθηκε!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("TRANSFER_DEBUG", "Network Download Error: ${e.message}")
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Σφάλμα: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
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
                val isInsideVirtualZip = currentPath.absolutePath.contains(cacheDir.absolutePath)

                // Αν είμαστε μέσα σε ZIP και το parent είναι ο φάκελος "virtual_contents",
                // τότε το επόμενο "πίσω" πρέπει να μας βγάλει στην κανονική μνήμη.
                if (isInsideVirtualZip && (currentPath.parentFile?.name == "virtual_contents" || currentPath.name == "virtual_contents")) {
                    currentPath = Environment.getExternalStorageDirectory()
                    loadFiles(currentPath)
                    supportActionBar?.title = "FT File Manager" // Επαναφορά τίτλου
                    return
                }

                // Η κανονική σου λογική για την πλοήγηση στους φακέλους
                if (currentPath.absolutePath != storageRoot && currentPath.absolutePath != "/" && currentPath.parentFile != null) {
                    loadFiles(currentPath.parentFile!!)
                } else {
                    // Αν είμαστε στο Root της μνήμης, κλείσε την εφαρμογή
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
                        sizeCache.remove(currentPath.absolutePath)
                        Toast.makeText(this@MainActivity, "Διαγράφηκε", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Αποτυχία διαγραφής", Toast.LENGTH_SHORT)
                            .show()
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

    private fun shareMultipleFiles(files: List<FileModel>) {
        try {
            val uris = files.map {
                FileProvider.getUriForFile(
                    this,
                    "${packageName}.provider",
                    File(it.path)
                )
            }

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "Κοινοποίηση"))
        } catch (e: Exception) {
            Log.e("SHARE", "Error sharing multiple", e)
            Toast.makeText(this, "Σφάλμα: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
                    if (newName.contains("/")) {
                        Toast.makeText(this, "Μη έγκυρο όνομα (περιέχει /)", Toast.LENGTH_SHORT)
                            .show()
                        return@setPositiveButton
                    }

                    MainScope().launch {
                        val oldPathReal = if (fileModel.path.startsWith("/storage/emulated/0")) {
                            fileModel.path.replace("/storage/emulated/0", "/data/media/0")
                        } else {
                            fileModel.path
                        }

                        val parentDirFile = File(oldPathReal).parentFile
                        val parentDir = parentDirFile?.absolutePath ?: run {
                            Toast.makeText(
                                this@MainActivity,
                                "Αδύνατος γονικός φάκελος",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }

                        val isSystem = oldPathReal.startsWith("/system") ||
                                oldPathReal.startsWith("/vendor") ||
                                oldPathReal == "/"

                        if (isSystem) RootTools.unlockSystem()

                        val result = withContext(Dispatchers.IO) {
                            try {
                                fun shellEscape(s: String): String {
                                    return "'" + s.replace("'", "'\\''") + "'"
                                }

                                val safeParent = shellEscape(parentDir)
                                val safeNewName = shellEscape(newName)
                                val safeOldPath = shellEscape(oldPathReal)

                                // 1) Εύρεση Inode
                                var inode: String? = null
                                val statOutput =
                                    RootTools.getOutput("stat -c %i -- $safeOldPath 2>&1") ?: ""
                                val statCandidate =
                                    statOutput.trim().split(Regex("\\s+")).firstOrNull()

                                if (statCandidate != null && statCandidate.matches(Regex("\\d+"))) {
                                    inode = statCandidate
                                }

                                if (inode == null) {
                                    val listOutput =
                                        RootTools.getOutput("cd $safeParent && find . -maxdepth 1 -printf '%i\t%P\n' 2>&1")
                                            ?: ""
                                    val lines = listOutput.split("\n")
                                    for (ln in lines) {
                                        if (ln.isBlank()) continue
                                        val parts = ln.split("\t", limit = 2)
                                        if (parts.size < 2) continue
                                        var namePart = parts[1].removePrefix("./").removeSuffix("/")
                                        val candidate = fileModel.name.removeSuffix("/")

                                        if (namePart == candidate) {
                                            inode = parts[0].trim()
                                            break
                                        }
                                    }
                                }

                                if (inode == null) return@withContext Pair(
                                    false,
                                    "Δεν βρέθηκε το Inode του αρχείου"
                                )

                                // 2) Εκτέλεση μετονομασίας μέσω Inode
                                val mvCmd =
                                    "cd $safeParent && find . -maxdepth 1 -inum $inode -exec mv -T {} $safeNewName \\; 2>&1"
                                val mvOutput = RootTools.getOutput(mvCmd) ?: ""

                                // 3) ΕΛΕΓΧΟΣ ΕΠΙΤΥΧΙΑΣ ΜΕΣΩ SHELL (Όχι Java/File.exists)
                                // Περιμένουμε ελάχιστα για το FUSE συγχρονισμό
                                Thread.sleep(150)
                                val checkOutput = RootTools.getOutput("ls -a $safeParent") ?: ""

                                if (checkOutput.contains(newName)) {
                                    Pair(true, "Μετονομασία επιτυχής")
                                } else {
                                    Pair(false, "Το αρχείο δεν βρέθηκε μετά το mv: $mvOutput")
                                }

                            } catch (e: Exception) {
                                Pair(false, "Σφάλμα: ${e.message}")
                            }
                        }

                        if (isSystem) RootTools.lockSystem()

                        val (ok, msg) = result
                        if (ok) {
                            // ΚΡΙΣΙΜΟ: Καθαρισμός Cache για να μη δείχνει το παλιό όνομα
                            sizeCache.remove(fileModel.path)
                            sizeCache.remove(currentPath.absolutePath)

                            // Μικρό delay πριν το refresh για να προλάβει το UI
                            delay(200)

                            loadFiles(currentPath)
                            exitSelectionMode()
                            Toast.makeText(this@MainActivity, "Επιτυχία!", Toast.LENGTH_SHORT)
                                .show()
                        } else {
                            // Ακόμα και σε αποτυχία κάνουμε refresh μήπως το αρχείο άλλαξε αλλά η ls απέτυχε
                            loadFiles(currentPath)
                            Toast.makeText(this@MainActivity, "Αποτυχία: $msg", Toast.LENGTH_LONG)
                                .show()
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
        fileToMove = null
        exitSelectionMode()
        // Τίποτα άλλο εδώ!
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
                                val command =
                                    if (isDirectory) "mkdir -p '$realRootPath'" else "touch '$realRootPath'"

                                // Εκτέλεση μέσω της νέας RootTools που φτιάξαμε
                                RootTools.executeSilent(command)
                            } catch (e: Exception) {
                                Log.e("FILE_MANAGER", "Error: ${e.message}")
                                false
                            }
                        }

                        if (success) {
                            loadFiles(currentPath)
                            Toast.makeText(this@MainActivity, "Επιτυχία!", Toast.LENGTH_SHORT)
                                .show()
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Αποτυχία δημιουργίας",
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
        val navView = binding.navigationView
        val menu = navView.menu
        val favoriteItem = menu.findItem(R.id.nav_favorites_group) ?: menu.addSubMenu(
            0,
            R.id.nav_favorites_group,
            100,
            "Αγαπημένα"
        ).item

        // 1. Καθαρισμός Header (για να μην διπλασιάζεται η εικόνα)
        while (navView.headerCount > 0) {
            navView.removeHeaderView(navView.getHeaderView(0))
        }
        navView.inflateHeaderView(R.layout.nav_header_favorites)

        // 2. ΚΑΘΟΛΙΚΟΣ ΚΑΘΑΡΙΣΜΟΣ του Group και του SubMenu
        // Αφαιρούμε το Group ΚΑΙ το Item που κρατάει το SubMenu
        menu.removeItem(R.id.nav_favorites_group)
        menu.removeGroup(R.id.nav_favorites_group)

        navView.invalidate()

        // 3. Δημιουργία από το μηδέν
        val favoriteSubMenu = menu.addSubMenu(0, R.id.nav_favorites_group, 100, "ΑΓΑΠΗΜΕΝΑ")

        favoritePaths.forEachIndexed { index, entry ->
            val parts = entry.split("*")
            val (displayName, realPath) = if (parts.size > 1) {
                parts[0] to parts[1]
            } else {
                val name = when {
                    entry.startsWith("ftp://") -> entry.replace("ftp://", "")
                    entry.startsWith("smb://") -> entry.substringAfter("@")
                    entry.startsWith("sftp://") -> {
                        if (entry.contains("@")) entry.substringAfter("@")
                        else entry.replace("sftp://", "")
                    }

                    else -> File(entry).name
                }
                name to entry
            }

            // ΕΔΩ Η ΔΙΟΡΘΩΣΗ: Μία γραμμή που περιλαμβάνει τα πάντα
            val menuItem = favoriteSubMenu.add(
                R.id.nav_favorites_group, // Group ID
                android.view.Menu.NONE,    // Item ID (σταθερό NONE)
                index,                    // ORDER (εδώ βασιζόμαστε για τη σειρά)
                displayName               // Το κείμενο
            )

            menuItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)

            // --- Εικονίδιο ---
            when {
                realPath.startsWith("ftp://") -> menuItem.setIcon(android.R.drawable.ic_menu_share)
                realPath.startsWith("smb://") || entry.startsWith("SFTP:") -> menuItem.setIcon(
                    android.R.drawable.ic_menu_set_as
                )

                else -> menuItem.setIcon(android.R.drawable.ic_dialog_map)
            }

            // --- Action View (για το κουμπί ...) ---
            menuItem.setActionView(R.layout.menu_item_favorite)
            val actionView = menuItem.actionView

            // 1. ΑΠΛΟ ΚΛΙΚ (Στο όνομα)
            // Χρησιμοποιούμε τη δική σου λογική (FTP, SMB, Local)
            menuItem.setOnMenuItemClickListener {
                when {
                    realPath.startsWith("ftp://") -> {
                        val host = realPath.replace("ftp://", "")
                        val intent = Intent(this, FtpActivity::class.java)
                        intent.putExtra("TARGET_HOST", host)
                        startActivity(intent)
                    }

                    realPath.startsWith("smb://") || entry.startsWith("SFTP:") -> {
                        val intent = Intent(this, NetworkClientActivity::class.java)
                        intent.putExtra("FAVORITE_SMB_DATA", entry)
                        startActivity(intent)
                    }

                    else -> {
                        val folder = File(realPath)
                        if (folder.exists()) loadFiles(folder)
                    }
                }
                binding.drawerLayout.closeDrawers()
                true
            }

            // 2. ΚΛΙΚ ΣΤΙΣ ΤΡΕΙΣ ΤΕΛΕΙΕΣ (ImageButton)
            // Χρησιμοποιούμε το ImageButton από το νέο XML
            actionView?.findViewById<android.widget.ImageButton>(R.id.btnMoreOptions)
                ?.setOnClickListener { view ->
                    showFavoritePopupMenu(view, entry, index)
                }

            // 3. Καθαρισμός για το Drag & Drop
            // Αφαιρούμε τυχόν παλιά Clicks από το actionView για να μην "κλέβουν" το Long Click
            actionView?.setOnClickListener(null)
            actionView?.setOnLongClickListener(null)
        }
    }

    private fun handleFavoriteClickInMain(realPath: String) {
        when {
            realPath.startsWith("ftp://") -> {
                val host = realPath.replace("ftp://", "")
                val intent = Intent(this, FtpActivity::class.java).apply {
                    putExtra("TARGET_HOST", host)
                }
                startActivity(intent)
            }

            realPath.startsWith("smb://") -> {
                // Χρησιμοποιούμε τη λογική που φτιάξαμε για το SMB
                val netPrefs = getSharedPreferences("network_settings", MODE_PRIVATE)
                val savedUser = netPrefs.getString("user_$realPath", null)
                    ?: netPrefs.getString("user_${realPath.removePrefix("smb://")}", "")
                val savedPass = netPrefs.getString("pass_$realPath", null)
                    ?: netPrefs.getString("pass_${realPath.removePrefix("smb://")}", "")

                val intent = Intent(this, NetworkClientActivity::class.java).apply {
                    putExtra("TARGET_SMB_PATH", realPath)
                    putExtra("SMB_USER", savedUser)
                    putExtra("SMB_PASS", savedPass)
                    putExtra("FAVORITE_SMB_DATA", "SMB: Favorite*$realPath")
                }
                startActivity(intent)
            }

            // --- ΠΕΡΙΠΤΩΣΗ SFTP (Rebex κτλ) ---
            realPath.startsWith("sftp://") -> {
                val netPrefs = getSharedPreferences("network_settings", MODE_PRIVATE)
                val savedUser = netPrefs.getString("user_$realPath", "") ?: ""
                val savedPass = netPrefs.getString("pass_$realPath", "") ?: ""

                val intent = Intent(this, NetworkClientActivity::class.java).apply {
                    // Στέλνουμε το format "SFTP: Favorite*sftp://..."
                    putExtra("FAVORITE_SMB_DATA", "SFTP: Favorite*$realPath")
                }
                startActivity(intent)
            }

            else -> {
                // Για τοπικούς φακέλους στη MainActivity
                val file = java.io.File(realPath)
                if (file.exists()) {
                    loadFiles(file) // Ή navigateToPath(realPath) ανάλογα τι έχεις ορίσει
                } else {
                    Toast.makeText(this, "Ο φάκελος δεν υπάρχει", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showFavoritePopupMenu(view: android.view.View, entry: String, index: Int) {
        val popup = androidx.appcompat.widget.PopupMenu(this, view)
        popup.menu.add("Μετονομασία")
        popup.menu.add("Διαγραφή")
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Μετονομασία" -> showRenameFavoriteDialog(entry, index)
                "Διαγραφή" -> showRemoveFavoriteDialog(entry)
            }
            true
        }
        popup.show()
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

    private fun getFavoriteIndexFromMenuPosition(position: Int): Int {
        val menu = binding.navigationView.menu
        var favIndex = 0

        for (i in 0..position) {
            if (menu.getItem(i).groupId == 100) {
                favIndex++
            }
        }
        return favIndex - 1
    }

    private fun saveFavorites() {
        android.util.Log.d("DRAG_DEBUG", "--- SAVING FAVORITES ---")
        // 1. Αποθήκευση για το Drawer
        val prefsFav = getSharedPreferences("favorites", MODE_PRIVATE)
        val orderedString = favoritePaths.joinToString("|")
        android.util.Log.d("DRAG_DEBUG", "Saving orderedString: $orderedString")
        prefsFav.edit().putString("paths_ordered", orderedString).apply()

        // 2. Ενημέρωση Dashboard
        val prefsDash = getSharedPreferences("dashboard_pins", MODE_PRIVATE)
        val currentDashString = prefsDash.getString("paths", "") ?: ""
        android.util.Log.d("DRAG_DEBUG", "Current Dashboard state before sync: $currentDashString")
        val currentDashList =
            currentDashString.split("|").filter { it.isNotEmpty() }.toMutableList()

        // ΑΦΑΙΡΕΣΗ: Αν κάτι δεν υπάρχει πια στα favoritePaths, το βγάζουμε και από το Dashboard
        val iterator = currentDashList.iterator()
        while (iterator.hasNext()) {
            val dashEntry = iterator.next()
            if (!favoritePaths.contains(dashEntry)) {
                iterator.remove()
                android.util.Log.d("DRAG_DEBUG", "--- SAVE COMPLETED ---")
            }
        }

        // ΠΡΟΣΘΗΚΗ: Αν κάτι είναι νέο στα favorites, το βάζουμε στο Dashboard
        favoritePaths.forEach { favEntry ->
            if (!currentDashList.contains(favEntry)) {
                currentDashList.add(favEntry)
            }
        }

        prefsDash.edit().putString("paths", currentDashList.joinToString("|")).apply()
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

        binding.navigationView.post {

            val navRecycler = binding.navigationView.getChildAt(0) as? RecyclerView ?: return@post

            val callback = object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                0
            ) {

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {

                    val fromPos = viewHolder.bindingAdapterPosition
                    val toPos = target.bindingAdapterPosition

                    if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION)
                        return false

                    val adapter = recyclerView.adapter ?: return false

                    if (fromPos >= adapter.itemCount || toPos >= adapter.itemCount)
                        return false

                    val fromFavIndex = getFavoriteIndexSafe(fromPos)
                    val toFavIndex = getFavoriteIndexSafe(toPos)

                    if (fromFavIndex == -1 || toFavIndex == -1)
                        return false

                    if (fromFavIndex !in favoritePaths.indices || toFavIndex !in favoritePaths.indices)
                        return false

                    Collections.swap(favoritePaths, fromFavIndex, toFavIndex)

                    adapter.notifyItemMoved(fromPos, toPos)

                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun isLongPressDragEnabled(): Boolean = true

                override fun clearView(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ) {
                    super.clearView(recyclerView, viewHolder)

                    android.util.Log.d("DRAG_DEBUG", "New order: $favoritePaths")

                    saveFavorites()
                    updateDrawerMenu()
                }
            }

            ItemTouchHelper(callback).attachToRecyclerView(navRecycler)
        }
    }

    private fun getFavoriteIndexSafe(position: Int): Int {

        val recycler = binding.navigationView.getChildAt(0) as? RecyclerView ?: return -1
        val viewHolder = recycler.findViewHolderForAdapterPosition(position) ?: return -1

        val itemView = viewHolder.itemView

        val titleView =
            itemView.findViewById<TextView>(com.google.android.material.R.id.design_menu_item_text)
                ?: return -1

        val title = titleView.text?.toString() ?: return -1

        val index = favoritePaths.indexOfFirst { it.startsWith("$title*") }

        return index
    }


    private fun getFavoritesStartIndex(): Int {
        val menu = binding.navigationView.menu

        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (item.groupId == 100) {
                return i
            }
        }
        return menu.size()
    }


    private fun loadFavoritesFromPrefs() {
        val prefs = getSharedPreferences("favorites", MODE_PRIVATE)
        val savedPaths = prefs.getString("paths_ordered", "")
        if (!savedPaths.isNullOrEmpty()) {
            favoritePaths.clear()
            favoritePaths.addAll(savedPaths.split("|"))
        }
    }

    private fun getDisplayTitle(path: String): String {
        // Αν το path είναι ακριβώς "/", το επιστρέφουμε αμέσως
        if (path == "/") return "Root"

        // Για όλα τα άλλα, αφαιρούμε το τελικό slash αν υπάρχει
        val normalized = path.removeSuffix("/")

        return when {
            // Περίπτωση Internal Storage
            normalized == "/storage/emulated/0" || normalized == "/data/media/0" ->
                "Internal Storage"

            // Περίπτωση SD Card
            normalized.startsWith("/storage/") && !normalized.contains("emulated") -> {
                val parts = normalized.split("/")
                if (parts.size >= 3) "SD Card" else "Storage"
            }

            // Οτιδήποτε άλλο (όνομα φακέλου)
            else -> {
                val name = File(normalized).name
                if (name.isEmpty()) normalized else name
            }
        }
    }

    private fun updateBreadcrumbs(path: String) {
        val crumbs = mutableListOf<Pair<String, String>>()

        // 1. Διαχείριση Internal Storage
        val internalBase = "/storage/emulated/0"
        if (path.startsWith(internalBase)) {
            crumbs.add("Internal" to internalBase)
            val relativePath = path.removePrefix(internalBase)
            val parts = relativePath.split("/").filter { it.isNotEmpty() }
            var currentAccumulated = internalBase
            parts.forEach { part ->
                currentAccumulated += "/$part"
                crumbs.add(part to currentAccumulated)
            }
        } else {
            // 2. Διαχείριση Root (/)
            crumbs.add("Root" to "/")
            val parts = path.split("/").filter { it.isNotEmpty() }
            var currentAccumulated = ""
            parts.forEach { part ->
                currentAccumulated += "/$part"
                crumbs.add(part to currentAccumulated)
            }
        }

        // 3. Σύνδεση με το RecyclerView που βάλαμε στην Toolbar
        val breadcrumbRv = binding.toolbar.findViewById<RecyclerView>(R.id.breadcrumbRecyclerView)
        val bcAdapter = BreadcrumbAdapter(crumbs) { clickedPath ->
            loadFiles(File(clickedPath)) // Κλικ σε οποιοδήποτε φάκελο της διαδρομής
        }

        breadcrumbRv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        breadcrumbRv.adapter = bcAdapter

        // Σκρολάρισμα στο τέλος για να βλέπουμε τον τρέχοντα φάκελο αν η διαδρομή είναι μεγάλη
        if (crumbs.isNotEmpty()) {
            breadcrumbRv.scrollToPosition(crumbs.size - 1)
        }
    }

    private fun openFile(file: File) {
        val extension = file.extension.lowercase()

        // Τα αρχεία που ανοίγουν εσωτερικά στην εφαρμογή σου
        when (extension) {
            "txt", "log", "java", "py", "xml", "json", "html" -> {
                val intent = Intent(this, TextEditorActivity::class.java)
                intent.putExtra("PATH", file.absolutePath)
                startActivity(intent)
                return
            }
            "zip", "rar", "7z" -> {
                // Αντί για Intent, "ανοίγουμε" το ZIP ως εικονικό φάκελο
                prepareVirtualFolder(file)
                return
            }
            "mp4", "mkv", "3gp", "webm" -> {
                val intent = Intent(this, VideoPlayerActivity::class.java)
                intent.putExtra("PATH", file.absolutePath)
                startActivity(intent)
                return
            }
            "jpg", "jpeg", "png", "gif", "webp" -> {
                val intent = Intent(this, ImageViewActivity::class.java)
                intent.putExtra("PATH", file.absolutePath)
                startActivity(intent)
                return
            }
        }

        if (extension == "apk") {
            openApk(file)
            return
        }

        // ΓΙΑ ΟΛΑ ΤΑ ΑΛΛΑ (APK, PDF, MP4 κλπ)
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
        val prefs = getSharedPreferences("AppPreferences", MODE_PRIVATE)
        val savedPackage = prefs.getString("pref_$mimeType", null)

        if (savedPackage != null) {
            // Αν υπάρχει σωσμένη εφαρμογή, την ανοίγουμε απευθείας
            openFileExternally(file)
        } else {
            // ΑΝ ΔΕΝ ΥΠΑΡΧΕΙ, ρωτάμε τον χρήστη (Μία φορά / Πάντα)
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            showOpenWithDecisionDialog(file, intent, mimeType)
        }
    }

    private fun openApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Έλεγχος αν έχουμε άδεια για εγκατάσταση (για Android 8+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (!packageManager.canRequestPackageInstalls()) {
                    val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(manageIntent)
                    Toast.makeText(this, "Παρακαλώ δώστε την άδεια εγκατάστασης", Toast.LENGTH_LONG).show()
                    return
                }
            }

            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Αδυναμία έναρξης εγκατάστασης: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFileExternally(file: File) {
        val extension = file.extension.lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)

        // 1. Έλεγχος αν υπάρχει ήδη σωσμένη προτίμηση (για να ανοίγει σφαίρα)
        val prefs = getSharedPreferences("AppPreferences", MODE_PRIVATE)
        val savedData = prefs.getString("pref_$mimeType", null)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (savedData != null && savedData.contains("|")) {
            val parts = savedData.split("|")
            intent.setClassName(parts[0], parts[1])
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                prefs.edit().remove("pref_$mimeType").apply()
            }
        }

        // 2. Αν δεν υπάρχει προτίμηση, ανοίγουμε τον Chooser και περιμένουμε την απάντηση
        CustomChooserReceiver.onAppSelected = { component ->
            // ΕΔΩ ΚΛΕΒΟΥΜΕ ΤΟ ΚΛΙΚ!
            runOnUiThread {
                showAlwaysAskDialog(file, component, mimeType)
            }
        }

        val receiverIntent = Intent(this, CustomChooserReceiver::class.java)
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            this, 0, receiverIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        )

        val chooser = Intent.createChooser(intent, "Άνοιγμα αρχείου...", pendingIntent.intentSender)
        startActivity(chooser)
    }

    private fun showOpenWithDecisionDialog(file: File, intent: Intent, mimeType: String) {
        val options = arrayOf("Άνοιγμα (Μία φορά)", "Επιλογή προεπιλεγμένης εφαρμογής (Πάντα)")

        AlertDialog.Builder(this)
            .setTitle("Άνοιγμα αρχείου")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        // Χρησιμοποιούμε τον Chooser του Android για το "Μία φορά"
                        val chooser = Intent.createChooser(intent, "Επιλογή εφαρμογής")
                        startActivity(chooser)
                    }
                    1 -> showSetDefaultAppDialog(file) // Το δικό μας μενού για το "Πάντα"
                }
            }
            .show()
    }

    private fun showSetDefaultAppDialog(file: File) {
        val extension = file.extension.lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // 1. Ρυθμίζουμε τον Receiver να "κλέψει" το κλικ
        CustomChooserReceiver.onAppSelected = { component ->
            runOnUiThread {
                // Μόλις ο χρήστης επιλέξει από τον κλασικό επιλογέα, αποθηκεύουμε την προτίμηση
                val dataToSave = "${component.packageName}|${component.className}"
                getSharedPreferences("AppPreferences", MODE_PRIVATE).edit()
                    .putString("pref_$mimeType", dataToSave)
                    .apply()

                Toast.makeText(this, "Η προεπιλογή ορίστηκε!", Toast.LENGTH_SHORT).show()
            }
        }

        // 2. Δημιουργούμε το PendingIntent για τον Receiver
        val receiverIntent = Intent(this, CustomChooserReceiver::class.java)
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            this, 0, receiverIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        )

        // 3. Ανοίγουμε τον κλασικό, όμορφο Chooser του Android
        val chooser = Intent.createChooser(intent, "Επιλέξτε προεπιλεγμένη εφαρμογή", pendingIntent.intentSender)
        startActivity(chooser)
    }

    private fun showAlwaysAskDialog(file: File, component: android.content.ComponentName, mimeType: String) {
        AlertDialog.Builder(this)
            .setTitle("Προεπιλεγμένη εφαρμογή")
            .setMessage("Θέλετε να ανοίγετε πάντα τα αρχεία .${file.extension} με αυτή την εφαρμογή;")
            .setPositiveButton("Ναι") { _, _ ->
                val dataToSave = "${component.packageName}|${component.className}"
                getSharedPreferences("AppPreferences", MODE_PRIVATE).edit()
                    .putString("pref_$mimeType", dataToSave)
                    .apply()
                Toast.makeText(this, "Η προτίμηση αποθηκεύτηκε!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Όχι", null)
            .show()
    }

    private fun openPdfInternal(file: File) {
        // Προσωρινά τα στέλνουμε έξω μέχρι να φτιάξουμε τον δικό μας Viewer
        openFileExternally(file)
    }

    private fun addToZip(file: File, zos: ZipOutputStream, basePath: String) {
        val entryName = if (basePath.isEmpty()) file.name else "$basePath/${file.name}"
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                addToZip(child, zos, entryName)
            }
        } else {
            val entry = ZipEntry(entryName)
            zos.putNextEntry(entry)
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    private fun unzipDirectly(zipFile: File) {
        Log.d("ZIP_LOG", "--- ΕΝΑΡΞΗ ΑΠΟΣΥΜΠΙΕΣΗΣ (Atomic Mode) ---")

        val parentDir = zipFile.parentFile ?: return
        val finalTargetDir = File(parentDir, zipFile.nameWithoutExtension)
        // Δημιουργούμε έναν μοναδικό temp φάκελο
        val tempDir = File(parentDir, "temp_unzip_${System.currentTimeMillis()}")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Δημιουργία του temp φακέλου
                if (!tempDir.mkdirs()) {
                    Log.e("ZIP_LOG", "Αποτυχία δημιουργίας temp φακέλου")
                    return@launch
                }

                // 2. Διαδικασία αποσυμπίεσης στον temp
                ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val newFile = File(tempDir, entry.name)

                        if (entry.isDirectory) {
                            newFile.mkdirs()
                        } else {
                            // Εξασφάλιση γονικών φακέλων εντός του ZIP
                            newFile.parentFile?.mkdirs()

                            FileOutputStream(newFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                // 3. Αν όλα πήγαν καλά, διαγράφουμε τον παλιό φάκελο (αν υπάρχει)
                if (finalTargetDir.exists()) {
                    Log.d("ZIP_LOG", "Διαγραφή παλιού φακέλου...")
                    finalTargetDir.deleteRecursively()
                    delay(100) // Μικρή παύση για το File System sync
                }

                // 4. Μετονομασία του temp στον τελικό φάκελο
                val success = tempDir.renameTo(finalTargetDir)
                Log.d("ZIP_LOG", "Rename target folder success: $success")

                // 5. Ενημέρωση MediaScanner για όλα τα αρχεία που βγήκαν
                val extractedFiles = finalTargetDir.walkTopDown().map { it.absolutePath }.toList().toTypedArray()
                MediaScannerConnection.scanFile(this@MainActivity, extractedFiles, null, null)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Αποσυμπιέστηκε επιτυχώς!", Toast.LENGTH_SHORT).show()
                    loadFiles(currentPath)
                    exitSelectionMode()
                }

            } catch (e: Exception) {
                Log.e("ZIP_LOG", "ΣΦΑΛΜΑ UNZIP: ${e.message}")
                tempDir.deleteRecursively() // Καθαρισμός αν αποτύχει
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Αποτυχία: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun prepareVirtualFolder(zipFile: File) {
        // Δημιουργούμε έναν φάκελο "virtual" μέσα στα προσωρινά αρχεία της εφαρμογής
        val virtualRoot = File(cacheDir, "virtual_contents/${zipFile.nameWithoutExtension}")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Καθαρισμός προηγούμενων εικονικών περιηγήσεων
                if (virtualRoot.exists()) virtualRoot.deleteRecursively()
                virtualRoot.mkdirs()

                // 2. Γρήγορη αποσυμπίεση στην Cache
                ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val newFile = File(virtualRoot, entry.name)
                        if (entry.isDirectory) {
                            newFile.mkdirs()
                        } else {
                            newFile.parentFile?.mkdirs()
                            FileOutputStream(newFile).use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                withContext(Dispatchers.Main) {
                    // Αντί για currentPath = virtualRoot.absolutePath
                    currentPath = virtualRoot
                    loadFiles(currentPath)

                    supportActionBar?.title = "Περιήγηση: ${zipFile.name}"
                    Toast.makeText(this@MainActivity, "Εικονική περιήγηση ZIP", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("VIRTUAL_ZIP", "Error: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Αποτυχία ανοίγματος ZIP", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun resetDefaultApp() {
        // Επιλογή Α: Καθαρισμός ΟΛΩΝ των προτιμήσεων εφαρμογών (Το πιο σίγουρο)
        val prefs = getSharedPreferences("AppPreferences", MODE_PRIVATE)

        AlertDialog.Builder(this)
            .setTitle("Επαναφορά Προεπιλογών")
            .setMessage("Θέλετε να καθαρίσετε όλες τις προτιμήσεις εφαρμογών για το άνοιγμα αρχείων;")
            .setPositiveButton("Ναι, καθαρισμός") { _, _ ->
                // Διαγράφουμε μόνο τα κλειδιά που ξεκινούν με "pref_"
                val allEntries = prefs.all
                val editor = prefs.edit()
                for (entry in allEntries.keys) {
                    if (entry.startsWith("pref_")) {
                        editor.remove(entry)
                    }
                }
                editor.apply()
                Toast.makeText(this, "Όλες οι προτιμήσεις καθαρίστηκαν!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }
}

data class FolderMetadata(val size: Long, val fileCount: Int, val folderCount: Int)