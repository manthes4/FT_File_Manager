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
                        currentPath = if (lastSlashIndex <= 0) "/" else currentPath.substring(
                            0,
                            lastSlashIndex
                        )
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
                        // Επίπεδο Shares
                        shareName = fileModel.name
                        currentPath = "/"
                    } else {
                        // Επίπεδο Υποφακέλων
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
                props.setProperty("jcifs.smb.client.dfs.disabled", "true")
                props.setProperty("jcifs.smb.client.minVersion", "SMB210")
                props.setProperty("jcifs.smb.client.maxVersion", "SMB311")

                val config = PropertyConfiguration(props)
                val baseContext = BaseContext(config)
                val auth = NtlmPasswordAuthenticator(null, user, pass)
                val context = baseContext.withCredentials(auth)

                val smbUrl = if (currentPath.isEmpty()) {
                    "smb://$host/"
                } else {
                    val cleanedPath = if (currentPath == "/") "/" else "$currentPath/"
                    "smb://$host/$shareName$cleanedPath".replace("//", "/").replace("smb:/", "smb://")
                }

                val directory = SmbFile(smbUrl, context)
                val newList = mutableListOf<FileModel>()

                // 1. Προσπαθούμε να πάρουμε τη λίστα των αρχείων/shares
                val files = directory.listFiles()

                // 2. ΑΝ είμαστε στο αρχικό επίπεδο (currentPath κενό)
                // και η listFiles() δεν πέταξε exception (άρα η σύνδεση είναι OK)
                if (currentPath.isEmpty() && !alreadyAskedFavorite) {
                    withContext(Dispatchers.Main) {
                        // Καλούμε τη συνάρτηση στέλνοντας host, user και pass
                        askToSaveFavoriteSMB(host, user, pass)
                        alreadyAskedFavorite = true
                    }
                }

                files.forEach { file ->
                    val name = file.name.replace("/", "")
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
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NetworkFileActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun askToSaveFavoriteSMB(host: String, user: String, pass: String) {
        val prefsFav = getSharedPreferences("favorites", MODE_PRIVATE)
        val savedPaths = prefsFav.getString("paths_ordered", "") ?: ""

        // Το format αποθήκευσης θα είναι: Όνομα|smb://user:pass@host
        val smbUriWithAuth = "smb://$user:$pass@$host"

        // Αν υπάρχει ήδη το host, μην ξαναρωτάς
        if (savedPaths.contains("@$host")) return

        android.app.AlertDialog.Builder(this)
            .setTitle("Αποθήκευση Σύνδεσης")
            .setMessage("Θέλετε να αποθηκεύσετε τη σύνδεση στο $host στα αγαπημένα;")
            .setPositiveButton("Ναι") { _, _ ->
                val favoritePaths = if (savedPaths.isEmpty()) mutableListOf<String>()
                else savedPaths.split("|").toMutableList()

                // Αποθηκεύουμε ως "Server (host)*smb://user:pass@host"
                val entryToSave = "SMB: $host*$smbUriWithAuth"
                favoritePaths.add(entryToSave)

                prefsFav.edit().putString("paths_ordered", favoritePaths.joinToString("|")).apply()

                // Ενημέρωση και για το Dashboard αν θέλεις
                val prefsDash = getSharedPreferences("dashboard_pins", MODE_PRIVATE)
                val currentDash = prefsDash.getString("paths", "") ?: ""
                if (!currentDash.contains(smbUriWithAuth)) {
                    val newDash =
                        if (currentDash.isEmpty()) entryToSave else "$currentDash|$entryToSave"
                    prefsDash.edit().putString("paths", newDash).apply()
                }

                Toast.makeText(this, "Η σύνδεση αποθηκεύτηκε!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Όχι", null)
            .show()
    }
}