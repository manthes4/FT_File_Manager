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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFtpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.ftpRecyclerView.layoutManager = LinearLayoutManager(this)

        binding.btnConnect.setOnClickListener {
            val host = binding.etHost.text.toString()
            val user = binding.etUser.text.toString()
            val pass = binding.etPass.text.toString()

            if (host.isNotEmpty()) connectToFtp(host, user, pass)
        }
    }

    private fun connectToFtp(host: String, user: String, pass: String) {
        // 1. Δείξε ένα μήνυμα αναμονής
        val pd = android.app.ProgressDialog(this)
        pd.setMessage("Σύνδεση στο $host...")
        pd.show()

        Thread {
            try {
                ftpClient.connect(host, 21)
                val loginSuccess = ftpClient.login(user, pass)

                if (loginSuccess) {
                    ftpClient.enterLocalPassiveMode()
                    ftpClient.setFileType(FTP.BINARY_FILE_TYPE)

                    runOnUiThread {
                        pd.dismiss()
                        binding.loginLayout.visibility = View.GONE
                        binding.ftpRecyclerView.visibility = View.VISIBLE
                        loadFtpFiles("/")
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
                    // ΑΥΤΟ ΘΑ ΣΟΥ ΠΕΙ ΤΟ ΠΡΑΓΜΑΤΙΚΟ ΣΦΑΛΜΑ (π.χ. Connection Refused)
                    Toast.makeText(
                        this,
                        "Σφάλμα Σύνδεσης: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun loadFtpFiles(path: String) {
        Thread {
            try {
                ftpClient.changeWorkingDirectory(path)
                currentFtpPath = path

                // 1. Έλεγχος αν τα αρχεία είναι null
                val ftpFiles = ftpClient.listFiles()
                if (ftpFiles == null) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Αδυναμία ανάγνωσης φακέλου",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@Thread
                }

                val fileList = mutableListOf<FileModel>()
                ftpFiles.forEach { file ->
                    if (file.name != "." && file.name != "..") {
                        fileList.add(
                            FileModel(
                                name = file.name,
                                path = if (path == "/") "/${file.name}" else "$path/${file.name}",
                                isDirectory = file.isDirectory,
                                size = if (file.isDirectory) "Φάκελος" else "${file.size / 1024} KB",
                                isRoot = false
                            )
                        )
                    }
                }

                // 2. Έλεγχος αν η λίστα είναι άδεια
                if (fileList.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, "Ο φάκελος είναι άδειος", Toast.LENGTH_SHORT).show()
                        // Ακόμα και αν είναι άδειος, πρέπει να βάλουμε τον adapter για να καθαρίσει η οθόνη
                    }
                }

                fileList.sortWith(compareByDescending<FileModel> { it.isDirectory }.thenBy { it.name.lowercase() })

                runOnUiThread {
                    binding.ftpRecyclerView.adapter = FileAdapter(
                        files = fileList,
                        isInSelectionMode = false,
                        onItemClick = { selectedFile ->
                            if (selectedFile.isDirectory) {
                                loadFtpFiles(selectedFile.path)
                            } else {
                                // Χρησιμοποιούμε το όνομα της συνάρτησης που ήδη έχεις ορίσει παρακάτω
                                AlertDialog.Builder(this)
                                    .setTitle("Λήψη αρχείου")
                                    .setMessage("Θέλετε να κατεβάσετε το ${selectedFile.name};")
                                    .setPositiveButton("Ναι") { _, _ ->
                                        downloadFileWithProgress(selectedFile.path, selectedFile.name)
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

    private fun downloadFile(remoteFilePath: String, fileName: String) {
        // Ορίζουμε το τοπικό path (φάκελος Downloads)
        val localFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )

        Thread {
            try {
                val outputStream = java.io.FileOutputStream(localFile)

                // Η εντολή retrieveFile κάνει όλη τη δουλειά της μεταφοράς
                val success = ftpClient.retrieveFile(remoteFilePath, outputStream)
                outputStream.close()

                runOnUiThread {
                    if (success) {
                        Toast.makeText(
                            this,
                            "Το αρχείο αποθηκεύτηκε στα λήψεις!",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(this, "Αποτυχία λήψης", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Σφάλμα: ${e.message}", Toast.LENGTH_LONG).show()
                }
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
}