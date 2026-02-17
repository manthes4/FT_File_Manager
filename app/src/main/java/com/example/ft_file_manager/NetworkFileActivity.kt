package com.example.ft_file_manager

import android.content.Intent
import android.os.Bundle
import android.widget.EditText // Προστέθηκε
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog // Προστέθηκε
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.codelibs.jcifs.smb.CIFSContext
import org.codelibs.jcifs.smb.config.PropertyConfiguration
import org.codelibs.jcifs.smb.context.BaseContext
import org.codelibs.jcifs.smb.impl.NtlmPasswordAuthenticator
import org.codelibs.jcifs.smb.impl.SmbFile
import java.util.Properties

class NetworkFileActivity : AppCompatActivity() {

    private lateinit var rvFiles: RecyclerView
    private lateinit var tvPath: TextView
    private lateinit var fileAdapter: FileAdapter

    private val networkFiles = mutableListOf<FileModel>()
    private var smbFilesToMove = listOf<FileModel>()

    private var host = ""
    private var user = ""
    private var pass = ""
    private var currentPath = ""
    private var shareName = ""

    private var isCutOperation = false
    private var alreadyAskedFavorite = false
    private var smbContext: CIFSContext? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_file)

        rvFiles = findViewById(R.id.rvNetworkFiles)
        tvPath = findViewById(R.id.tvCurrentPath)
        rvFiles.layoutManager = LinearLayoutManager(this)

        host = intent.getStringExtra("HOST") ?: ""
        user = intent.getStringExtra("USER") ?: ""
        pass = intent.getStringExtra("PASS") ?: ""

        // Μέσα στην onCreate
        // --- ΣΤΗΝ onCreate ---
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.home_24px)
        supportActionBar?.title = "SMB: $host"

// Το NavigationClickListener μένει ως έχει για το Home
        toolbar.setNavigationOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }
// ----------------------

// Φόρτωσε το μενού
        toolbar.inflateMenu(R.menu.contextual_menu)
        toolbar.setOnMenuItemClickListener { item ->
            val selectedFiles = networkFiles.filter { it.isSelected }
            when (item.itemId) {
                R.id.action_copy, R.id.action_cut -> {
                    if (selectedFiles.isNotEmpty()) {
                        preparePaste(selectedFiles, item.itemId == R.id.action_cut)

                        // ΚΡΙΣΙΜΟ: Μετά το Copy/Cut, ξετσεκάρουμε τα πάντα
                        networkFiles.forEach { it.isSelected = false }
                        fileAdapter.notifyDataSetChanged()
                    }
                    true
                }
                R.id.action_delete -> {
                    if (selectedFiles.isNotEmpty()) showDeleteConfirmDialog(selectedFiles)
                    true
                }
                R.id.action_rename -> {
                    if (selectedFiles.size == 1) showRenameDialog(selectedFiles[0])
                    true
                }
                R.id.action_favorite -> {
                    showNewFolderDialog()
                    true
                }
                else -> false
            }
        }

        // --- Μέσα στην onCreate ---
        val fabPaste = findViewById<FloatingActionButton>(R.id.fabPasteSmb)

// 1. ΕΜΦΑΝΙΣΗ: Αν υπάρχουν ήδη αρχεία στον TransferManager (από το κινητό), δείξε το κουμπί
        if (TransferManager.filesToMove.isNotEmpty()) {
            fabPaste.show()
        }

// 2. Η ΔΡΑΣΗ: Εδώ συνδέουμε το κλικ με τη συνάρτηση executePaste()
        fabPaste.setOnClickListener {
            android.util.Log.d("SMB_DEBUG", "Paste button clicked!")
            executePaste()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentPath.isNotEmpty()) {
                    if (currentPath == "/") {
                        currentPath = ""
                        shareName = ""
                    } else {
                        val lastSlashIndex = currentPath.lastIndexOf('/')
                        currentPath = if (lastSlashIndex <= 0) "/" else currentPath.substring(0, lastSlashIndex)
                    }
                    loadNetworkFiles()
                } else {
                    finish()
                }
            }
        })

        fileAdapter = FileAdapter(
            files = networkFiles,
            onItemClick = { fileModel ->
                val hasSelection = networkFiles.any { it.isSelected }
                android.util.Log.d("SMB_DEBUG", "Click on: ${fileModel.name} | Mode: ${if(hasSelection) "Selection" else "Navigation"}")

                if (hasSelection) {
                    // Αν είμαστε σε mode επιλογής, το κλικ αλλάζει μόνο το state
                    fileModel.isSelected = !fileModel.isSelected
                    android.util.Log.d("SMB_DEBUG", "Item toggled: ${fileModel.name} is now ${fileModel.isSelected}")
                    fileAdapter.notifyDataSetChanged()

                    // Αν ξετσεκαρίστηκαν όλα, επιστρέφουμε σε navigation mode
                    if (!networkFiles.any { it.isSelected }) {
                        android.util.Log.d("SMB_DEBUG", "All items deselected. Navigation restored.")
                    }
                } else {
                    // Κανονική πλοήγηση
                    if (fileModel.isDirectory) {
                        android.util.Log.d("SMB_DEBUG", "Navigating into: ${fileModel.name}")
                        if (currentPath.isEmpty()) {
                            shareName = fileModel.name
                            currentPath = "/"
                        } else {
                            val base = if (currentPath.endsWith("/")) currentPath else "$currentPath/"
                            currentPath = base + fileModel.name
                        }
                        loadNetworkFiles()
                    }
                }
            },
            onItemLongClick = { fileModel ->
                android.util.Log.d("SMB_DEBUG", "LONG CLICK on: ${fileModel.name}")

                // 1. Δόνηση για να ξέρεις ότι "έπιασε"
                val vibrator = getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))

                // 2. Toggle την επιλογή
                fileModel.isSelected = !fileModel.isSelected

                // 3. Ενημέρωση UI
                fileAdapter.notifyDataSetChanged()
            },
            onSelectionChanged = { }
        )
        rvFiles.adapter = fileAdapter
        loadNetworkFiles()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.contextual_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        val selectedFiles = networkFiles.filter { it.isSelected }

        when (item.itemId) {
            // Η Αποκοπή (Cut) τώρα θα δουλεύει γιατί προσθέσαμε το renameTo στην executePaste
            R.id.action_copy, R.id.action_cut -> {
                if (selectedFiles.isNotEmpty()) {
                    preparePaste(selectedFiles, item.itemId == R.id.action_cut)
                    networkFiles.forEach { it.isSelected = false }
                    fileAdapter.notifyDataSetChanged()
                }
                return true
            }
            R.id.action_delete -> {
                if (selectedFiles.isNotEmpty()) showDeleteConfirmDialog(selectedFiles)
                return true
            }
            R.id.action_rename -> {
                if (selectedFiles.size == 1) showRenameDialog(selectedFiles[0])
                return true
            }
            R.id.action_favorite -> {
                showNewFolderDialog()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadNetworkFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (smbContext == null) {
                    val props = Properties()
                    props.setProperty("jcifs.smb.client.dfs.disabled", "true")
                    props.setProperty("jcifs.smb.client.minVersion", "SMB210")
                    props.setProperty("jcifs.smb.client.maxVersion", "SMB311")
                    val config = PropertyConfiguration(props)
                    val baseContext = BaseContext(config)
                    val auth = NtlmPasswordAuthenticator(null, user, pass)
                    smbContext = baseContext.withCredentials(auth)
                }

                val smbUrl = if (currentPath.isEmpty()) "smb://$host/"
                else "smb://$host/$shareName${if (currentPath == "/") "/" else "$currentPath/"}".replace("//", "/").replace("smb:/", "smb://")

                val directory = SmbFile(smbUrl, smbContext!!)
                val files = directory.listFiles()

                if (currentPath.isEmpty() && !alreadyAskedFavorite) {
                    withContext(Dispatchers.Main) {
                        askToSaveFavoriteSMB(host, user, pass)
                        alreadyAskedFavorite = true
                    }
                }

                val newList = files.map { file ->
                    val name = file.name.replace("/", "")
                    FileModel(
                        name = name,
                        path = file.canonicalPath,
                        size = if (file.isDirectory) "Φάκελος" else "${file.length() / 1024} KB",
                        isDirectory = file.isDirectory,
                        isSelected = false
                    )
                }.filterNot { currentPath.isEmpty() && it.name.endsWith("$") }

                withContext(Dispatchers.Main) {
                    networkFiles.clear()
                    networkFiles.addAll(newList)
                    fileAdapter.notifyDataSetChanged()
                    tvPath.text = if (currentPath.isEmpty()) "Host: $host" else "$shareName$currentPath"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NetworkFileActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun askToSaveFavoriteSMB(host: String, user: String, pass: String) {
        val prefsFav = getSharedPreferences("favorites", MODE_PRIVATE)
        val savedPaths = prefsFav.getString("paths_ordered", "") ?: ""
        if (savedPaths.contains("@$host")) return

        AlertDialog.Builder(this)
            .setTitle("Αποθήκευση Σύνδεσης")
            .setMessage("Θέλετε να αποθηκεύσετε τη σύνδεση στο $host;")
            .setPositiveButton("Ναι") { _, _ ->
                val favoritePaths = if (savedPaths.isEmpty()) mutableListOf() else savedPaths.split("|").toMutableList()
                val entryToSave = "SMB: $host*smb://$user:$pass@$host"
                favoritePaths.add(entryToSave)
                prefsFav.edit().putString("paths_ordered", favoritePaths.joinToString("|")).apply()
                Toast.makeText(this, "Αποθηκεύτηκε!", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Όχι", null).show()
    }

    private fun showDeleteConfirmDialog(files: List<FileModel>) {
        AlertDialog.Builder(this)
            .setTitle("Διαγραφή")
            .setMessage("Διαγραφή ${files.size} στοιχείων;")
            .setPositiveButton("Ναι") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        files.forEach { SmbFile(it.path, smbContext!!).delete() }
                        withContext(Dispatchers.Main) { loadNetworkFiles() }
                    } catch (e: Exception) { showError(e.message) }
                }
            }.setNegativeButton("Όχι", null).show()
    }



    private fun preparePaste(files: List<FileModel>, isCut: Boolean) {
        this.smbFilesToMove = files
        this.isCutOperation = isCut
        // Εμφανίζει το κουμπί μόνο όταν έχουμε αρχεία στο "clipboard"
            TransferManager.filesToMove = files
            TransferManager.isCut = isCut
            TransferManager.sourceIsSmb = true // Η πηγή είναι το δίκτυο
            TransferManager.smbHost = host
            TransferManager.smbUser = user
            TransferManager.smbPass = pass

            findViewById<FloatingActionButton>(R.id.fabPasteSmb).show()
            Toast.makeText(this, "Αρχεία δικτύου στην ουρά", Toast.LENGTH_SHORT).show()
        }

    private fun executePaste() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Αν για κάποιο λόγο οι μεταβλητές της Activity είναι κενές (π.χ. φρεσκοανοιγμένη),
                // πάρε τα στοιχεία από τον TransferManager
                if (host.isEmpty()) host = TransferManager.smbHost
                if (user.isEmpty()) user = TransferManager.smbUser
                if (pass.isEmpty()) pass = TransferManager.smbPass

                if (smbContext == null) {
                    // Αν δεν έχει προλάβει να συνδεθεί, δημιούργησε το context τώρα
                    val props = Properties()
                    props.setProperty("jcifs.smb.client.dfs.disabled", "true")
                    val config = PropertyConfiguration(props)
                    val baseContext = BaseContext(config)
                    val auth = NtlmPasswordAuthenticator(null, user, pass)
                    smbContext = baseContext.withCredentials(auth)
                }

                // Ενημέρωση UI ότι ξεκινήσαμε
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NetworkFileActivity, "Έναρξη επικόλλησης...", Toast.LENGTH_SHORT).show()
                }

                val destUrl = if (currentPath == "/" || currentPath.isEmpty()) "smb://$host/$shareName/"
                else "smb://$host/$shareName$currentPath/".replace("//", "/")

                TransferManager.filesToMove.forEach { model ->
                    val destSmbFile = SmbFile(destUrl + java.io.File(model.path).name, smbContext!!)

                    if (!TransferManager.sourceIsSmb) {
                        // --- UPLOAD: Κινητό -> CoreELEC ---
                        val localFile = java.io.File(model.path)
                        java.io.FileInputStream(localFile).use { input ->
                            destSmbFile.outputStream.use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (TransferManager.isCut) localFile.delete()
                    } else {
                        // --- SMB -> SMB: Εντός CoreELEC ---
                        val sourceSmb = SmbFile(model.path, smbContext!!)
                        if (TransferManager.isCut) {
                            try { sourceSmb.renameTo(destSmbFile) }
                            catch (e: Exception) { sourceSmb.copyTo(destSmbFile); sourceSmb.delete() }
                        } else {
                            sourceSmb.copyTo(destSmbFile)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    TransferManager.filesToMove = emptyList()
                    loadNetworkFiles()
                    findViewById<FloatingActionButton>(R.id.fabPasteSmb).hide()
                    Toast.makeText(this@NetworkFileActivity, "Επιτυχής μεταφορά!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.util.Log.e("SMB_ERROR", "Paste failed: ${e.message}")
                    showError("Paste Error: ${e.message}")
                }
            }
        }
    }

    private fun showRenameDialog(file: FileModel) {
        val input = EditText(this).apply { setText(file.name) }
        AlertDialog.Builder(this).setTitle("Μετονομασία").setView(input)
            .setPositiveButton("OK") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val oldFile = SmbFile(file.path, smbContext!!)
                        val newFile = SmbFile(oldFile.parent + input.text.toString(), smbContext!!)
                        oldFile.renameTo(newFile)
                        withContext(Dispatchers.Main) { loadNetworkFiles() }
                    } catch (e: Exception) { showError(e.message) }
                }
            }.show()
    }

    private fun showNewFolderDialog() {
        val input = EditText(this).apply { hint = "Όνομα φακέλου" }
        AlertDialog.Builder(this).setTitle("Νέος Φάκελος").setView(input)
            .setPositiveButton("Δημιουργία") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val url = if (currentPath == "/") "smb://$host/$shareName/" else "smb://$host/$shareName$currentPath/"
                        SmbFile("$url${input.text}/", smbContext!!).mkdir()
                        withContext(Dispatchers.Main) { loadNetworkFiles() }
                    } catch (e: Exception) { showError(e.message) }
                }
            }.show()
    }

    private fun showError(msg: String?) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(this@NetworkFileActivity, "Error: $msg", Toast.LENGTH_SHORT).show()
        }
    }
}