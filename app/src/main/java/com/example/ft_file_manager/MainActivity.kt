package com.example.ft_file_manager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ft_file_manager.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import androidx.activity.OnBackPressedCallback

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var fileToCopy: FileModel? = null
    private var isCutOperation: Boolean = false
    // Αντί για Environment.getExternalStorageDirectory()
    private var currentPath: File = File("/storage/emulated/0")
    private var fullFileList = mutableListOf<FileModel>() // <--- ΠΡΟΣΘΕΣΤΕ ΑΥΤΗ ΤΗ ΓΡΑΜΜΗ

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)

        binding.fabPaste.setOnClickListener { pasteFile() }
        binding.fabFtp.setOnClickListener {
            // Βεβαιωθείτε ότι έχετε δημιουργήσει την FtpActivity
            try {
                startActivity(Intent(this, FtpActivity::class.java))
            } catch (e: Exception) {
                Toast.makeText(this, "FTP Activity not found", Toast.LENGTH_SHORT).show()
            }
        }

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

    private fun loadFiles(directory: File) {
        currentPath = directory
        // Ενημέρωση τίτλου για να ξέρεις πού βρίσκεσαι
        binding.toolbar.title = directory.name.ifEmpty { "Internal Storage" }

        val fileList = mutableListOf<FileModel>()
        val files = directory.listFiles()

        if (files != null) {
            // ΚΑΝΟΝΙΚΗ ΠΡΟΣΒΑΣΗ
            files.forEach {
                fileList.add(FileModel(
                    it.name,
                    it.absolutePath,
                    it.isDirectory,
                    if (it.isDirectory) "Φάκελος" else "${it.length() / 1024} KB",
                    isRoot = false
                ))
            }
        } else {
            // ROOT ΠΡΟΣΒΑΣΗ (Μόνο αν η Java επιστρέψει null)
            val output = RootHelper.runRootCommandWithOutput("ls -ap '${directory.absolutePath}'")
            if (output.isNotEmpty()) {
                output.split("\n").forEach { line ->
                    val name = line.trim()
                    if (name.isNotEmpty() && name != "./" && name != "../") {
                        val isDir = name.endsWith("/")
                        val cleanName = if (isDir) name.dropLast(1) else name
                        fileList.add(FileModel(
                            cleanName,
                            "${directory.absolutePath}/$cleanName",
                            isDir,
                            if (isDir) "Φάκελος" else "System File",
                            isRoot = true
                        ))
                    }
                }
            }
        }

        // Αν η λίστα είναι ακόμα άδεια, ίσως φταίει το Permission
        if (fileList.isEmpty() && !directory.canRead()) {
            Toast.makeText(this, "Αποτυχία ανάγνωσης. Ελέγξτε τα δικαιώματα!", Toast.LENGTH_LONG).show()
        }

        // Ταξινόμηση
        fileList.sortWith(compareByDescending<FileModel> { it.isDirectory }.thenBy { it.name.lowercase() })

        // Ενημέρωση της λίστας για την αναζήτηση
        fullFileList = fileList

        // Ανάθεση στον Adapter
        binding.recyclerView.adapter = FileAdapter(fileList,
            onItemClick = { selectedFile ->
                val file = File(selectedFile.path)
                if (selectedFile.isDirectory) loadFiles(file) else openFile(file)
            },
            onItemLongClick = { selectedFile -> showOptionsDialog(selectedFile) }
        )
    }

    private fun showOptionsDialog(file: FileModel) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_file_options, null)

        view.findViewById<TextView>(R.id.btnCopy).setOnClickListener {
            fileToCopy = file
            isCutOperation = false
            Toast.makeText(this, "Αντιγράφηκε", Toast.LENGTH_SHORT).show()
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

    private fun confirmDelete(file: FileModel) {
        AlertDialog.Builder(this)
            .setTitle("Διαγραφή")
            .setMessage("Είσαι σίγουρος ότι θέλεις να διαγράψεις το ${file.name};")
            .setPositiveButton("Ναι") { _, _ ->
                val deleted = if (file.isRoot) {
                    RootHelper.runRootCommand("rm -rf '${file.path}'")
                } else {
                    File(file.path).deleteRecursively()
                }

                if (deleted) {
                    Toast.makeText(this, "Διαγράφηκε", Toast.LENGTH_SHORT).show()
                    loadFiles(currentPath)
                } else {
                    Toast.makeText(this, "Αποτυχία διαγραφής", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Όχι", null)
            .show()
    }

    private fun shareFile(fileModel: FileModel) {
        val file = File(fileModel.path)
        if (file.isDirectory) {
            Toast.makeText(this, "Δεν μπορείτε να μοιραστείτε φάκελο", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share via"))
        } catch (e: Exception) {
            Toast.makeText(this, "Σφάλμα share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFile(file: File) {
        // Βασική υλοποίηση ανοίγματος αρχείου ανάλογα με το extension
        Toast.makeText(this, "Άνοιγμα: ${file.name}", Toast.LENGTH_SHORT).show()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val parent = currentPath.parentFile

                // Έλεγχος αν μπορούμε να πάμε πίσω στους φακέλους
                // Σταματάμε αν φτάσουμε στο αρχικό Root του Storage ή στο "/" αν είμαστε σε root mode
                val storageRoot = Environment.getExternalStorageDirectory().absolutePath

                if (parent != null && currentPath.absolutePath != storageRoot && currentPath.absolutePath != "/") {
                    loadFiles(parent)
                } else {
                    // Αν δεν υπάρχει άλλος φάκελος πίσω, απενεργοποιούμε το callback
                    // και καλούμε ξανά το back για να κλείσει η εφαρμογή
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun showRenameDialog(fileModel: FileModel) {
        val input = android.widget.EditText(this)
        input.setText(fileModel.name)

        AlertDialog.Builder(this)
            .setTitle("Μετονομασία")
            .setView(input)
            .setPositiveButton("ΟΚ") { _, _ ->
                val newName = input.text.toString()
                val oldFile = File(fileModel.path)
                val newFile = File(oldFile.parent, newName)

                val success = if (fileModel.isRoot) {
                    RootHelper.runRootCommand("mv '${oldFile.absolutePath}' '${newFile.absolutePath}'")
                } else {
                    oldFile.renameTo(newFile)
                }

                if (success) {
                    loadFiles(currentPath)
                    Toast.makeText(this, "Έγινε!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Αποτυχία", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }

    private fun pasteFile() {
        fileToCopy?.let { source ->
            val destination = File(currentPath, source.name)

            Thread {
                val success = if (source.isRoot) {
                    RootHelper.runRootCommand("cp -r '${source.path}' '${destination.absolutePath}'")
                } else {
                    try {
                        File(source.path).copyRecursively(destination, overwrite = true)
                        true
                    } catch (e: Exception) { false }
                }

                runOnUiThread {
                    if (success) {
                        loadFiles(currentPath)
                        binding.fabPaste.hide()
                        fileToCopy = null
                    } else {
                        Toast.makeText(this, "Αποτυχία επικόλλησης", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }

    private fun updateStorageInfo() {
        val stat = android.os.StatFs(Environment.getExternalStorageDirectory().path)
        val bytesAvailable = stat.blockSizeLong * stat.availableBlocksLong
        val bytesTotal = stat.blockSizeLong * stat.blockCountLong

        val used = (bytesTotal - bytesAvailable) / (1024 * 1024 * 1024)
        val total = bytesTotal / (1024 * 1024 * 1024)
        val progress = ((bytesTotal - bytesAvailable).toDouble() / bytesTotal * 100).toInt()

        // Ενημέρωση UI (υποθέτοντας ότι το έχετε κάνει inflate στο header)
        // binding.storageProgress.progress = progress
        // binding.tvStorageText.text = "$used GB χρησιμοποιημένα από $total GB"
    }
}