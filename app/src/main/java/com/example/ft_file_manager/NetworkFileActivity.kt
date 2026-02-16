package com.example.ft_file_manager

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private var currentPath = "" // Ξεκινάει κενό για τη λίστα των shares
    private lateinit var host: String
    private lateinit var user: String
    private lateinit var pass: String
    private var shareName: String = ""
    private var alreadyAskedFavorite = false // Για να ρωτάει μόνο μια φορά

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_file)

        rvFiles = findViewById(R.id.rvNetworkFiles)
        tvPath = findViewById(R.id.tvCurrentPath)
        rvFiles.layoutManager = LinearLayoutManager(this)

        host = intent.getStringExtra("HOST") ?: ""
        user = intent.getStringExtra("USER") ?: ""
        pass = intent.getStringExtra("PASS") ?: ""

        // --- ΕΔΩ ΠΡΟΣΘΕΤΟΥΜΕ ΤΟ BACK LOGIC ---
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentPath.isNotEmpty()) {
                    // Αν είμαστε στο "/" (Root του Share), γυρνάμε στη λίστα των Shares
                    if (currentPath == "/") {
                        currentPath = ""
                        shareName = ""
                    } else {
                        // Αν είμαστε σε υποφάκελο, αφαιρούμε το τελευταίο κομμάτι του path
                        val lastSlashIndex = currentPath.lastIndexOf('/')
                        currentPath = if (lastSlashIndex <= 0) "/" else currentPath.substring(0, lastSlashIndex)
                    }
                    loadNetworkFiles() // Ξαναφορτώνουμε τα αρχεία για το νέο path
                } else {
                    // Αν είμαστε ήδη στην αρχή (Host level), κλείνουμε την οθόνη
                    finish()
                }
            }
        })
        // ------------------------------------

        fileAdapter = FileAdapter(
            files = networkFiles,
            onItemClick = { fileModel ->
                if (fileModel.isDirectory) {
                    if (currentPath.isEmpty()) {
                        // Μόλις πατήσαμε στο Share (π.χ. Videos)
                        shareName = fileModel.name
                        currentPath = "/" // ΣΗΜΑΝΤΙΚΟ: Ξεκινάμε από το root του share

                        if (!alreadyAskedFavorite) {
                            askToSaveFavoriteSMB(host, shareName)
                            alreadyAskedFavorite = true
                        }
                    } else {
                        // Πλοήγηση μέσα σε υποφακέλους
                        // Διασφαλίζουμε ότι δεν προσθέτουμε διπλά slashes
                        val base = if (currentPath.endsWith("/")) currentPath else "$currentPath/"
                        currentPath = base + fileModel.name
                    }
                    loadNetworkFiles()
                }
            },
            onItemLongClick = { /* Logic */ },
            onSelectionChanged = { /* Update UI */ }
        )
        rvFiles.adapter = fileAdapter
        loadNetworkFiles()
    }

    private fun loadNetworkFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val props = Properties()
                // Αναγκαίο για Linux/CoreELEC servers
                props.setProperty("jcifs.smb.client.dfs.disabled", "true")
                props.setProperty("jcifs.smb.client.minVersion", "SMB210")
                props.setProperty("jcifs.smb.client.maxVersion", "SMB311")

                val config = PropertyConfiguration(props)
                val baseContext = BaseContext(config)
                val auth = NtlmPasswordAuthenticator(null, user, pass)
                val context = baseContext.withCredentials(auth)

                // Χτίσιμο του URL με προσοχή στα slashes
                val smbUrl = if (currentPath.isEmpty()) {
                    "smb://$host/" // Επίπεδο Host (εδώ βλέπεις τα shares)
                } else {
                    // Επίπεδο Share/Folders: smb://192.168.1.10/Videos/Subfolder/
                    // Καθαρίζουμε το path για να μην έχει διπλά slashes
                    val cleanedPath = if (currentPath == "/") "/" else "$currentPath/"
                    "smb://$host/$shareName$cleanedPath".replace("//", "/").replace("smb:/", "smb://")
                }

                android.util.Log.d("SMB_DEBUG", "Connecting to: $smbUrl")

                val directory = SmbFile(smbUrl, context)
                val newList = mutableListOf<FileModel>()

                directory.listFiles().forEach { file ->
                    val name = file.name.replace("/", "")
                    // Αγνοούμε τα κρυφά shares συστήματος (C$, IPC$ κτλ)
                    if (currentPath.isEmpty() && name.endsWith("$")) return@forEach

                    newList.add(FileModel(
                        name = name,
                        path = file.canonicalPath,
                        size = if (file.isDirectory) "Φάκελος" else "${file.length() / 1024} KB",
                        isDirectory = file.isDirectory,
                        isSelected = false
                    ))
                }

                withContext(Dispatchers.Main) {
                    networkFiles.clear()
                    networkFiles.addAll(newList)
                    fileAdapter.notifyDataSetChanged()
                    tvPath.text = if (currentPath.isEmpty()) "Host: $host" else "$shareName$currentPath"
                }
            } catch (e: Exception) {
                android.util.Log.e("SMB_ERROR", "Error: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NetworkFileActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun askToSaveFavoriteSMB(host: String, share: String) {
        val prefsFav = getSharedPreferences("favorites", MODE_PRIVATE)
        val savedPaths = prefsFav.getString("paths_ordered", "") ?: ""
        val smbPath = "smb://$host/$share"

        // Αν υπάρχει ήδη, δεν ξαναρωτάμε
        if (savedPaths.split("|").any { it.endsWith("*$smbPath") }) return

        android.app.AlertDialog.Builder(this)
            .setTitle("Προσθήκη στα Αγαπημένα")
            .setMessage("Θέλετε να προσθέσετε το $share ($host) στα αγαπημένα σας;")
            .setPositiveButton("Ναι") { _, _ ->
                val favoritePaths = if (savedPaths.isEmpty()) mutableListOf<String>()
                else savedPaths.split("|").toMutableList()

                val entryToSave = "$share*$smbPath"
                favoritePaths.add(entryToSave)
                prefsFav.edit().putString("paths_ordered", favoritePaths.joinToString("|")).apply()

                // Dashboard Pins
                val prefsDash = getSharedPreferences("dashboard_pins", MODE_PRIVATE)
                val currentDashString = prefsDash.getString("paths", "") ?: ""
                val currentDashList = currentDashString.split("|").filter { it.isNotEmpty() }.toMutableList()
                if (!currentDashList.contains(entryToSave)) {
                    currentDashList.add(entryToSave)
                    prefsDash.edit().putString("paths", currentDashList.joinToString("|")).apply()
                }
                Toast.makeText(this, "SMB Favorite Saved!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Όχι", null)
            .show()
    }
}