package com.example.ft_file_manager

import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ft_file_manager.databinding.ActivityFtpBinding
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.File
import androidx.appcompat.app.AlertDialog

class FtpActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFtpBinding
    private val ftpClient = FTPClient()
    private var currentFtpPath = "/"
    private var ftpServer: org.apache.ftpserver.FtpServer? = null

    private val filePickerLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploadFileToFtp(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFtpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("ftp_prefs", MODE_PRIVATE)

        // --- ΛΕΙΤΟΥΡΓΙΑ CLIENT (Σύνδεση σε άλλους) ---

        val lastHost = prefs.getString("last_host", "")
        val lastUser = prefs.getString("last_user", "")
        val lastPass = prefs.getString("last_pass", "")

        val targetHost = intent.getStringExtra("TARGET_HOST")

        if (targetHost != null) {
            binding.etHost.setText(targetHost)
        } else {
            binding.etHost.setText(lastHost)
        }

        binding.etUser.setText(lastUser)
        binding.etPass.setText(lastPass)

        binding.ftpRecyclerView.layoutManager = LinearLayoutManager(this)

        // Κουμπί Σύνδεσης ως Client
        binding.btnConnect.setOnClickListener {
            val host = binding.etHost.text.toString()
            val user = binding.etUser.text.toString()
            val pass = binding.etPass.text.toString()

            prefs.edit().apply {
                putString("last_host", host)
                putString("last_user", user)
                putString("last_pass", pass)
                apply()
            }

            if (host.isNotEmpty()) connectToFtp(host, user, pass)
        }

        // 1. Σύνδεση κουμπιού Σάρωσης IP
        binding.btnScanIp.setOnClickListener {
            scanLocalNetworkForFtp { foundIp ->
                Toast.makeText(this, "Επιλέχθηκε η IP: $foundIp", Toast.LENGTH_SHORT).show()
            }
        }

        // 2. Σύνδεση κουμπιού Upload / Paste
        if (TransferManager.filesToMove.isNotEmpty()) {
            binding.btnUpload.visibility = View.VISIBLE
        }

// Κουμπί Upload / Paste
        binding.btnUpload.setOnClickListener {
            if (TransferManager.filesToMove.isNotEmpty()) {
                executeFtpPaste() // Αν υπάρχουν αρχεία, κάνε επικόλληση!
            } else {
                filePickerLauncher.launch("*/*") // Αλλιώς, άνοιξε τον file picker για απλό upload
            }
        }

        // --- ΛΕΙΤΟΥΡΓΙΑ SERVER (Το δικό μου κινητό) ---

        val lastServerUser = prefs.getString("server_user", "admin")
        val lastServerPass = prefs.getString("server_pass", "1234")
        binding.etServerUser.setText(lastServerUser)
        binding.etServerPass.setText(lastServerPass)

        val ip = getWifiIPAddress()
        binding.tvServerInfo.text = "Η IP σου: $ip\n(Port: 2121)"

        binding.btnStartServer.setOnClickListener {
            if (ftpServer == null) {
                prefs.edit().apply {
                    putString("server_user", binding.etServerUser.text.toString())
                    putString("server_pass", binding.etServerPass.text.toString())
                    apply()
                }
                startFtpServer()
            } else {
                stopFtpServer()
            }
        }

// ==========================================
        // ΑΥΤΟΜΑΤΗ ΣΥΝΔΕΣΗ ΜΕ ΤΟΥΣ ΠΟΥ ΑΝΟΙΓΕΙ Η ΟΘΟΝΗ
        // ==========================================
        if (!targetHost.isNullOrEmpty()) {
            // Αν έρθαμε από το Dashboard, παίρνουμε τα αποθηκευμένα lastUser και lastPass
            val userToLogin = lastUser ?: ""
            val passToLogin = lastPass ?: ""

            // Καλούμε απευθείας τη σύνδεση
            connectToFtp(targetHost, userToLogin, passToLogin)
        }
    }

    override fun onResume() {
        super.onResume()

        android.util.Log.d("TRANSFER_DEBUG", "onResume called in FtpActivity. Files count: ${TransferManager.filesToMove.size}")

        if (TransferManager.filesToMove.isNotEmpty()) {
            binding.btnUpload.visibility = View.VISIBLE
            android.util.Log.d("TRANSFER_DEBUG", "FORCING btnUpload to VISIBLE")
        } else {
            binding.btnUpload.visibility = View.GONE
            android.util.Log.d("TRANSFER_DEBUG", "Hiding btnUpload (Manager is empty)")
        }
    }

    private fun connectToFtp(host: String, user: String, pass: String) {
        val pd = android.app.ProgressDialog(this)
        pd.setMessage("Σύνδεση στο $host...")
        pd.show()

        Thread {
            try {
                // --- ΕΝΕΡΓΟΠΟΙΗΣΗ UTF-8 ΓΙΑ ΑΣΦΑΛΗ ΟΝΟΜΑΤΑ (Underscores, Ελληνικά κλπ) ---
                val config = org.apache.commons.net.ftp.FTPClientConfig(
                    org.apache.commons.net.ftp.FTPClientConfig.SYST_UNIX
                )
                config.serverLanguageCode = "en" // ή "el" αν ο server είναι ελληνικός
                ftpClient.configure(config)
                ftpClient.controlEncoding = "UTF-8"
                ftpClient.autodetectUTF8 = true
                ftpClient.connect(host, 21)


                val loginSuccess = ftpClient.login(user, pass)

                if (loginSuccess) {
                    ftpClient.enterLocalPassiveMode()
                    ftpClient.setFileType(FTP.BINARY_FILE_TYPE)

                    // Στέλνουμε ρητά εντολή στον FTP Server να επιτρέψει UTF-8 χαρακτήρες
                    try {
                        ftpClient.sendCommand("OPTS UTF8", "ON")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    runOnUiThread {
                        pd.dismiss()

                        binding.cardClient.visibility = View.GONE
                        binding.cardServer.visibility = View.GONE
                        binding.ftpRecyclerView.visibility = View.VISIBLE

                        // --- ΚΑΛΟΥΜΕ ΕΔΩ ΤΟΝ ΕΛΕΓΧΟ ΓΙΑ ΤΟ PASTE ---
                        updatePasteButtonVisibility()

                        loadFtpFiles("/")
                        askToSaveFavorite(host)
                    }
                } else {
                    runOnUiThread {
                        pd.dismiss()
                        Toast.makeText(this, "Λάθος στοιχεία σύνδεσης", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    pd.dismiss()
                    Toast.makeText(
                        this,
                        "Σφάλμα Σύνδεσης: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun updatePasteButtonVisibility() {
        val count = TransferManager.filesToMove.size

        if (count > 0) {
            // 1. Υπάρχουν αρχεία στη μνήμη -> ΑΛΛΑΓΗ ΣΕ ΕΙΚΟΝΙΔΙΟ ΕΠΙΚΟΛΛΗΣΗΣ (PASTE)
            binding.btnUpload.setImageResource(android.R.drawable.ic_menu_agenda) // ή το δικό σου ic_menu_paste / ic_content_paste

            // Προαιρετικά: Αλλάζεις το Content Description για προσβασιμότητα/Android TV
            binding.btnUpload.contentDescription = "Επικόλληση ($count)"

            // Εξασφαλίζουμε ότι είναι ορατό
            binding.btnUpload.visibility = View.VISIBLE
        } else {
            // 2. Δεν υπάρχουν αρχεία -> ΕΠΑΝΑΦΟΡΑ ΣΕ ΕΙΚΟΝΙΔΙΟ ΑΝΕΒΑΣΜΑΤΟΣ (UPLOAD)
            binding.btnUpload.setImageResource(android.R.drawable.ic_menu_upload) // ή το δικό σου εικονίδιο upload

            binding.btnUpload.contentDescription = "Ανέβασμα Αρχείου"

            // Εδώ επιλέγεις αν θα φαίνεται πάντα για Upload ή αν θα κρύβεται
            binding.btnUpload.visibility = View.VISIBLE
        }
    }

    private fun loadFtpFiles(path: String) {
        Thread {
            try {
                // Διορθωμένο path handling για το FTP
                val success = ftpClient.changeWorkingDirectory(path)
                if (!success) {
                    runOnUiThread {
                        Toast.makeText(this, "Αδυναμία αλλαγής φακέλου", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                currentFtpPath = ftpClient.printWorkingDirectory() ?: path

                // Παίρνουμε τη λίστα των αρχείων
                val ftpFiles = ftpClient.listFiles()
                val fileList = mutableListOf<FileModel>()

                if (ftpFiles != null) {
                    for (file in ftpFiles) {
                        // Πάρε το όνομα (αν είναι κενό, δοκίμασε το rawString)
                        val fileName = file.name ?: file.rawListing?.substringAfterLast(" ") ?: continue

                        if (fileName != "." && fileName != "..") {
                            val isDir = file.isDirectory

                            // Υπολογισμός μεγέθους
                            val sizeStr = if (isDir) {
                                "--"
                            } else {
                                val kb = file.size / 1024
                                if (kb < 1) "< 1 KB" else "$kb KB"
                            }

                            val fullPath = if (currentFtpPath == "/") "/$fileName" else "$currentFtpPath/$fileName"

                            fileList.add(
                                FileModel(
                                    name = fileName,
                                    path = fullPath,
                                    isDirectory = isDir,
                                    size = sizeStr,
                                    isRoot = false
                                )
                            )
                        }
                    }
                }

                fileList.sortWith(compareByDescending<FileModel> { it.isDirectory }.thenBy { it.name.lowercase() })

                runOnUiThread {
                    // --- ΚΑΛΟΥΜΕ ΚΑΙ ΕΔΩ ΓΙΑ ΝΑ ΜΗΝ ΧΑΝΕΤΑΙ ΤΟ ΚΟΥΜΠΙ ΣΕ ΦΑΚΕΛΟΥΣ ---
                    updatePasteButtonVisibility()
                    binding.ftpRecyclerView.adapter = FileAdapter(
                        files = fileList,
                        isInSelectionMode = false,
                        onItemClick = { selectedFile ->
                            if (selectedFile.isDirectory) {
                                loadFtpFiles(selectedFile.path)
                            } else {
                                AlertDialog.Builder(this)
                                    .setTitle("Λήψη αρχείου")
                                    .setMessage("Θέλετε να κατεβάσετε το ${selectedFile.name};")
                                    .setPositiveButton("Ναι") { _, _ ->
                                        downloadFileWithProgress(
                                            selectedFile.path,
                                            selectedFile.name
                                        )
                                    }
                                    .setNegativeButton("Άκυρο", null)
                                    .show()
                            }
                        },
                        onItemLongClick = { /* ... */ },
                        onSelectionChanged = { /* ... */ }
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Σφάλμα λίστας: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        Thread {
            if (ftpClient.isConnected) {
                ftpClient.logout()
                ftpClient.disconnect()
            }
        }.start()
    }

    // Τροποποίηση της downloadFile με Progress Bar
    private fun downloadFileWithProgress(remoteFilePath: String, fileName: String) {
        val progressDialog = android.app.ProgressDialog(this)
        progressDialog.setMessage("Λήψη: $fileName...")
        progressDialog.setCancelable(false)
        progressDialog.show()

        // Τοπικό αρχείο στο Downloads
        val localFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )

        Thread {
            var success = false
            try {
                // Εξασφαλίζουμε Binary Mode πριν τη λήψη
                ftpClient.setFileType(FTP.BINARY_FILE_TYPE)

                val outputStream = java.io.FileOutputStream(localFile)
                // Χρησιμοποιούμε το remoteFilePath που έρχεται από το FileModel
                success = ftpClient.retrieveFile(remoteFilePath, outputStream)
                outputStream.close()
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Σφάλμα Thread: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            runOnUiThread {
                progressDialog.dismiss()
                if (success) {
                    Toast.makeText(this, "Η λήψη ολοκληρώθηκε στο Downloads!", Toast.LENGTH_LONG)
                        .show()
                } else {
                    // Αν αποτύχει, ίσως φταίει το path. Δοκιμάζουμε χωρίς το αρχικό "/" αν υπάρχει
                    Toast.makeText(
                        this,
                        "Αποτυχία λήψης. Server Code: ${ftpClient.replyCode}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun askToSaveFavorite(host: String) {
        // 1. Φόρτωση των υπαρχόντων favorites για άμεσο έλεγχο
        val prefsFav = getSharedPreferences("favorites", MODE_PRIVATE)
        val savedPaths = prefsFav.getString("paths_ordered", "") ?: ""
        val ftpPath = "ftp://$host"

        // 2. ΕΛΕΓΧΟΣ: Αν το ftpPath περιέχεται ήδη σε κάποιο entry, σταμάτα τη συνάρτηση εδώ
        if (savedPaths.split("|").any { it.endsWith("*$ftpPath") }) {
            // Προαιρετικά: Toast.makeText(this, "Ήδη στα αγαπημένα", Toast.LENGTH_SHORT).show()
            return // Βγαίνουμε από τη συνάρτηση, δεν θα εμφανιστεί το Alert
        }

        // 3. Αν ΔΕΝ υπάρχει, τότε μόνο δείξε το Dialog
        AlertDialog.Builder(this)
            .setTitle("Προσθήκη στα Αγαπημένα")
            .setMessage("Θέλετε να προσθέσετε το $host στα αγαπημένα σας;")
            .setPositiveButton("Ναι") { _, _ ->
                val favoritePaths = if (savedPaths.isEmpty()) mutableListOf<String>()
                else savedPaths.split("|").toMutableList()

                val entryToSave = "$host*$ftpPath"

                // Προσθήκη στη λίστα
                favoritePaths.add(entryToSave)

                // Αποθήκευση στα Favorites (Drawer)
                prefsFav.edit().putString("paths_ordered", favoritePaths.joinToString("|")).apply()

                // Αποθήκευση στο Dashboard (Sync)
                val prefsDash = getSharedPreferences("dashboard_pins", MODE_PRIVATE)
                val currentDashString = prefsDash.getString("paths", "") ?: ""
                val currentDashList =
                    currentDashString.split("|").filter { it.isNotEmpty() }.toMutableList()

                if (!currentDashList.contains(entryToSave)) {
                    currentDashList.add(entryToSave)
                    prefsDash.edit().putString("paths", currentDashList.joinToString("|")).apply()
                }

                Toast.makeText(this, "Προστέθηκε παντού!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Όχι", null)
            .show()
    }

    private fun startFtpServer() {
        val user = binding.etServerUser.text.toString()
        val pass = binding.etServerPass.text.toString()

        val serverFactory = org.apache.ftpserver.FtpServerFactory()
        val factory = org.apache.ftpserver.listener.ListenerFactory()
        factory.port = 2121 // Χρησιμοποιούμε 2121 γιατί το 21 θέλει root στο Android

        serverFactory.addListener("default", factory.createListener())

        // Ρύθμιση Χρήστη
        val userFactory = org.apache.ftpserver.usermanager.impl.WriteRequest()
        val userBuilder = org.apache.ftpserver.usermanager.UserFactory()
        userBuilder.name = user
        userBuilder.password = pass
        userBuilder.homeDirectory = Environment.getExternalStorageDirectory().absolutePath

        val authorities = mutableListOf<org.apache.ftpserver.ftplet.Authority>()
        authorities.add(org.apache.ftpserver.usermanager.impl.WritePermission())
        userBuilder.authorities = authorities

        serverFactory.userManager.save(userBuilder.createUser())

        try {
            ftpServer = serverFactory.createServer()
            ftpServer?.start()

            val ip = getWifiIPAddress()
            binding.tvServerInfo.text = "Server Ενεργός!\nΣύνδεση σε: ftp://$ip:2121"
            binding.btnStartServer.text = "Διακοπή Server"
            binding.btnStartServer.backgroundTintList =
                android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)

            Toast.makeText(this, "Ο Server ξεκίνησε!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Σφάλμα: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopFtpServer() {
        ftpServer?.stop()
        ftpServer = null
        binding.tvServerInfo.text = "IP: --"
        binding.btnStartServer.text = "Έναρξη Server"
        binding.btnStartServer.backgroundTintList =
            android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4CAF50"))
        Toast.makeText(this, "Ο Server σταμάτησε", Toast.LENGTH_SHORT).show()
    }

    private fun uploadFileToFtp(uri: android.net.Uri) {
        val contentResolver = contentResolver
        val fileName = getFileNameFromUri(uri) ?: "uploaded_file"

        val progressDialog = android.app.ProgressDialog(this)
        progressDialog.setMessage("Ανεβαίνει το αρχείο: $fileName...")
        progressDialog.setCancelable(false)
        progressDialog.show()

        Thread {
            var success = false
            try {
                ftpClient.setFileType(FTP.BINARY_FILE_TYPE)
                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    // Ανεβάζουμε το αρχείο στον τρέχοντα φάκελο (currentFtpPath)
                    val remotePath = if (currentFtpPath == "/") "/$fileName" else "$currentFtpPath/$fileName"
                    success = ftpClient.storeFile(remotePath, inputStream)
                    inputStream.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            runOnUiThread {
                progressDialog.dismiss()
                if (success) {
                    Toast.makeText(this, "Το αρχείο ανέβηκε με επιτυχία!", Toast.LENGTH_LONG).show()
                    // Ανανέωση λίστας φακέλου για να φανεί το νέο αρχείο
                    loadFtpFiles(currentFtpPath)
                } else {
                    Toast.makeText(this, "Αποτυχία ανεβάσματος. Κωδικός: ${ftpClient.replyCode}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // Βοηθητική συνάρτηση για να παίρνουμε το όνομα του αρχείου από το Uri
    private fun getFileNameFromUri(uri: android.net.Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex)
                }
            }
        }
        if (name == null) {
            name = uri.path?.substringAfterLast('/')
        }
        return name
    }

    private fun executeFtpPaste() {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage("Μεταφορά αρχείων στον FTP...")
            setCancelable(false)
            show()
        }

        Thread {
            var allSuccess = true
            try {
                ftpClient.controlEncoding = "UTF-8"
                ftpClient.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)
                ftpClient.enterLocalPassiveMode()

                // Παίρνουμε μια φορά τη λίστα των αρχείων του τρέχοντος φακέλου για τον έλεγχο διπλότυπων
                val existingFiles = ftpClient.listFiles().map { it.name }.toSet()
                val currentFolderFiles = existingFiles.toMutableSet()

                TransferManager.filesToMove.forEach { model ->
                    val localFile = java.io.File(model.path)
                    if (localFile.exists()) {
                        val originalName = localFile.name
                        val targetDir = if (currentFtpPath.endsWith("/")) currentFtpPath else "$currentFtpPath/"

                        val cacheFile = java.io.File(cacheDir, "ftp_temp_$originalName")
                        localFile.copyTo(cacheFile, overwrite = true)

                        var uploadSuccess = false
                        var targetRemoteName = originalName

                        // --- ΒΗΜΑ 1: Καθαρισμός ονόματος εξ αρχής (για να φεύγουν πάντα underscores, παρενθέσεις, παύλες) ---
                        val extension = originalName.substringAfterLast('.', "")
                        val nameWithoutExt = originalName.substringBeforeLast('.', originalName)

                        var cleanBaseName = nameWithoutExt.substringBefore("_")
                        cleanBaseName = cleanBaseName.replace("(", "").replace(")", "").replace("-", "").trim()
                        if (cleanBaseName.isEmpty()) cleanBaseName = "file"

                        targetRemoteName = if (extension.isNotEmpty()) "$cleanBaseName.$extension" else cleanBaseName

                        // --- ΒΗΜΑ 2: Έλεγχος σύγκρουσης & Μοναδικότητας ΓΙΑ ΟΛΑ ΤΑ ΑΡΧΕΙΑ ---
                        var counter = 1
                        val baseWithoutExt = targetRemoteName.substringBeforeLast('.')
                        val fileExt = targetRemoteName.substringAfterLast('.', "")

                        var finalRemoteName = targetRemoteName
                        while (currentFolderFiles.contains(finalRemoteName)) {
                            finalRemoteName = if (fileExt.isNotEmpty()) {
                                "$baseWithoutExt$counter.$fileExt"
                            } else {
                                "$baseWithoutExt$counter"
                            }
                            counter++
                        }

                        targetRemoteName = finalRemoteName
                        val remotePath = if (targetDir == "/") "/$targetRemoteName" else "$targetDir$targetRemoteName"

                        android.util.Log.w("FTP_PASTE", "Τελικό ασφαλές & μοναδικό όνομα προς αποστολή: $targetRemoteName")

                        // --- ΒΗΜΑ 3: Αποστολή αρχείου ---
                        java.io.FileInputStream(cacheFile).use { inputStream ->
                            uploadSuccess = ftpClient.storeFile(remotePath, inputStream)
                        }

                        if (uploadSuccess) {
                            // Προσθέτουμε το όνομα στη λίστα ώστε αν υπάρχει κι α bao παρόμοιο αρχείο στην ίδια παρτίδα, να πάρει επόμενο νούμερο
                            currentFolderFiles.add(targetRemoteName)
                            if (TransferManager.isCut) {
                                localFile.delete()
                            }
                        } else {
                            allSuccess = false
                            android.util.Log.e("FTP_PASTE", "Αποτυχία αποστολής για: $originalName ως $targetRemoteName")
                        }

                        if (cacheFile.exists()) cacheFile.delete()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                allSuccess = false
            }

            runOnUiThread {
                progressDialog.dismiss()
                if (allSuccess) {
                    Toast.makeText(this, "Η επικόλληση ολοκληρώθηκε επιτυχώς!", Toast.LENGTH_SHORT).show()
                    TransferManager.filesToMove = emptyList()
                    updatePasteButtonVisibility()
                    loadFtpFiles(currentFtpPath)
                } else {
                    Toast.makeText(this, "Κάποια αρχεία απέτυχαν τελικά", Toast.LENGTH_LONG).show()
                    loadFtpFiles(currentFtpPath)
                }
            }
        }.start()
    }

    // Μια συνάρτηση αυτόματης σύνδεσης για τα Pinned items
    private fun connectToFtpAutomatically(host: String, user: String, pass: String) {
        val pd = android.app.ProgressDialog(this)
        pd.setMessage("Αυτόματη σύνδεση στο $host...")
        pd.show()

        Thread {
            try {
                ftpClient.controlEncoding = "UTF-8"
                ftpClient.autodetectUTF8 = true
                ftpClient.connect(host, 21)
                val loginSuccess = ftpClient.login(user, pass)

                if (loginSuccess) {
                    ftpClient.enterLocalPassiveMode()
                    ftpClient.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)

                    runOnUiThread {
                        pd.dismiss()
                        binding.cardClient.visibility = View.GONE
                        binding.cardServer.visibility = View.GONE
                        binding.ftpRecyclerView.visibility = View.VISIBLE

                        // Εμφάνιση κουμπιού upload/paste αν υπάρχουν αρχεία στη μνήμη
                        if (TransferManager.filesToMove.isNotEmpty()) {
                            binding.btnUpload.visibility = View.VISIBLE
                        }

                        // ΣΩΣΤΟ: Ξεκινάμε πάντα από το root "/" και όχι από λάθος URL!
                        loadFtpFiles("/")
                    }
                } else {
                    runOnUiThread {
                        pd.dismiss()
                        Toast.makeText(this, "Απέτυχε η αυτόματη σύνδεση (Λάθος στοιχεία)", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    pd.dismiss()
                    Toast.makeText(this, "Σφάλμα: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun getWifiIPAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                android.util.Log.d("FtpScan", "Interface: ${iface.name}, Up: ${iface.isUp}, Loopback: ${iface.isLoopback}")
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    android.util.Log.d("FtpScan", "  -> IP found: ${addr.hostAddress}")
                    if (addr is java.net.Inet4Address) {
                        val hostAddr = addr.hostAddress
                        // Αποφυγή του localhost
                        if (!hostAddr.startsWith("127.")) {
                            android.util.Log.d("FtpScan", "SELECTED IP for scanning: $hostAddr")
                            return hostAddr
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FtpScan", "Error getting IP: ${e.message}")
        }
        return "Μη διαθέσιμη"
    }

    private fun scanLocalNetworkForFtp(onIpFound: (String) -> Unit) {
        val myIp = getWifiIPAddress()
        android.util.Log.d("FtpScan", "=== ΕΝΑΡΞΗ ΠΛΗΡΟΥΣ ΣΑΡΩΣΗΣ LAN ===")
        android.util.Log.d("FtpScan", "Η IP μου: $myIp")

        if (myIp == "Μη διαθέσιμη" || myIp.isEmpty()) {
            Toast.makeText(this, "Δεν βρέθηκε ενεργή IP δικτύου", Toast.LENGTH_SHORT).show()
            return
        }

        val progressDialog = android.app.ProgressDialog(this)
        progressDialog.setMessage("Σάρωση όλων των συσκευών στο δίκτυο...")
        progressDialog.setCancelable(false)
        progressDialog.show()

        Thread {
            val foundIps = mutableListOf<String>()
            val executor = java.util.concurrent.Executors.newFixedThreadPool(40)

            // Δυναμικός εντοπισμός υποδικτύων προς σάρωση:
            // 1. Το δικό μας υποδίκτυο (π.χ. 192.168.10.)
            val mySubnet = myIp.substringBeforeLast(".")

            // 2. Το κλασικό υποδίκτυο του router αν είμαστε σε extender (192.168.1. και 192.168.0.)
            val subnetsToScan = mutableSetOf(mySubnet)
            if (mySubnet.startsWith("192.168.")) {
                subnetsToScan.add("192.168.1")
                subnetsToScan.add("192.168.0")
            }

            android.util.Log.d("FtpScan", "Υποδίκτυα προς σάρωση: $subnetsToScan")

            for (subnet in subnetsToScan) {
                for (i in 1..254) {
                    val targetIp = "$subnet.$i"
                    if (targetIp == myIp) continue

                    executor.submit {
                        // Ελέγχουμε τις standard θύρες FTP αλλά και άλλες γνωστές θύρες server
                        val portsToCheck = listOf(21, 2121, 8080, 22)
                        for (port in portsToCheck) {
                            try {
                                val socket = java.net.Socket()
                                // Μικρό timeout 250ms για να σκανάρει γρήγορα και τα 254x2 IPs
                                socket.connect(java.net.InetSocketAddress(targetIp, port), 250)
                                socket.close()

                                val serverAddress = if (port == 21) targetIp else "$targetIp:$port"
                                android.util.Log.d("FtpScan", ">>> ΒΡΕΘΗΚΕ ΣΥΣΚΕΥΗ/SERVER στο: $serverAddress")

                                synchronized(foundIps) {
                                    if (!foundIps.contains(serverAddress)) {
                                        foundIps.add(serverAddress)
                                    }
                                }
                                break
                            } catch (e: Exception) {
                                // Δεν υπάρχει ανοιχτή πόρτα εδώ
                            }
                        }
                    }
                }
            }

            executor.shutdown()
            executor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)

            runOnUiThread {
                progressDialog.dismiss()
                if (foundIps.isNotEmpty()) {
                    val items = foundIps.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Βρέθηκαν Servers / Συσκευές")
                        .setItems(items) { _, which ->
                            val selectedFullAddress = items[which]
                            val ipOnly = selectedFullAddress.substringBefore(":")
                            binding.etHost.setText(ipOnly)
                            onIpFound(selectedFullAddress)
                        }
                        .setNegativeButton("Άκυρο", null)
                        .show()
                } else {
                    Toast.makeText(this, "Δεν βρέθηκαν συσκευές. Ελέγξτε αν είναι ενεργό το AP Isolation στο Extender.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}