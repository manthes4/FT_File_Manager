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

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var currentPath: File = File("/storage/emulated/0")
    private var fullFileList = mutableListOf<FileModel>()
    private var fileToMove: FileModel? = null
    private var isCutOperation: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)

        // LISTENERS ΚΟΥΜΠΙΩΝ
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        binding.fabPaste.setOnClickListener { pasteFile() }

        binding.btnNewFolder.setOnClickListener { showCreateFolderDialog() }
        binding.btnSort.setOnClickListener { showSortDialog() }
        binding.btnSelectAll.setOnClickListener { /* logic for select all */ }

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
                    val dirs = getExternalFilesDirs(null)
                    if (dirs.size > 1) loadFiles(dirs[1])
                    else Toast.makeText(this, "SD Card not found", Toast.LENGTH_SHORT).show()
                }
            }
            binding.drawerLayout.closeDrawers()
            true
        }
    }

    private fun loadFiles(directory: File) {
        currentPath = directory
        binding.toolbar.title = directory.name.ifEmpty { "Internal Storage" }

        val fileList = mutableListOf<FileModel>()
        val files = directory.listFiles()

        if (files != null) {
            files.forEach {
                fileList.add(FileModel(it.name, it.absolutePath, it.isDirectory,
                    if (it.isDirectory) "Φάκελος" else "${it.length() / 1024} KB", false))
            }
        } else {
            val output = RootHelper.runRootCommandWithOutput("ls -ap '${directory.absolutePath}'")
            if (output.isNotEmpty()) {
                output.split("\n").forEach { line ->
                    val name = line.trim()
                    if (name.isNotEmpty() && name != "./" && name != "../") {
                        val isDir = name.endsWith("/")
                        val cleanName = if (isDir) name.dropLast(1) else name
                        fileList.add(FileModel(cleanName, "${directory.absolutePath}/$cleanName", isDir,
                            if (isDir) "Φάκελος" else "System File", true))
                    }
                }
            }
        }

        fileList.sortWith(compareByDescending<FileModel> { it.isDirectory }.thenBy { it.name.lowercase() })
        fullFileList = fileList
        updateAdapter(fileList)
    }

    private fun updateAdapter(list: List<FileModel>) {
        binding.recyclerView.adapter = FileAdapter(list,
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
        val sourceFileModel = fileToMove ?: return
        val sourceFile = File(sourceFileModel.path)
        val destinationFile = File(currentPath, sourceFile.name)

        Thread {
            var success = false
            try {
                if (isCutOperation) {
                    // 1. Προσπάθεια για γρήγορη μετακίνηση (λειτουργεί μόνο στον ίδιο δίσκο)
                    success = if (sourceFileModel.isRoot) {
                        RootHelper.runRootCommand("mv '${sourceFile.absolutePath}' '${destinationFile.absolutePath}'")
                    } else {
                        sourceFile.renameTo(destinationFile)
                    }

                    // 2. FALLBACK: Αν το renameTo απέτυχε (π.χ. από Internal σε SD Card)
                    if (!success) {
                        if (sourceFile.copyRecursively(destinationFile, overwrite = true)) {
                            success = sourceFile.deleteRecursively() // Διαγραφή του αρχικού αν πέτυχε η αντιγραφή
                        }
                    }
                } else {
                    // ΑΠΛΗ ΑΝΤΙΓΡΑΦΗ
                    success = if (sourceFileModel.isRoot) {
                        RootHelper.runRootCommand("cp -r '${sourceFile.absolutePath}' '${destinationFile.absolutePath}'")
                    } else {
                        sourceFile.copyRecursively(destinationFile, overwrite = true)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            runOnUiThread {
                if (success) {
                    loadFiles(currentPath)
                    binding.fabPaste.hide()
                    fileToMove = null
                    Toast.makeText(this, "Ολοκληρώθηκε!", Toast.LENGTH_SHORT).show()
                } else {
                    // Αν αποτύχει στην SD κάρτα, πιθανότατα φταίει το SAF Permission
                    Toast.makeText(this, "Αποτυχία. Βεβαιωθείτε ότι η SD Card έχει δικαιώματα εγγραφής.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
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
                    2 -> { /* Add size logic */ }
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
                val success = if (file.isRoot) RootHelper.runRootCommand("rm -rf '${file.path}'")
                else File(file.path).deleteRecursively()
                if (success) loadFiles(currentPath)
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
                val newFile = File(File(fileModel.path).parent, input.text.toString())
                val success = if (fileModel.isRoot) RootHelper.runRootCommand("mv '${fileModel.path}' '${newFile.absolutePath}'")
                else File(fileModel.path).renameTo(newFile)
                if (success) loadFiles(currentPath)
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }
}