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
        Thread {
            try {
                ftpClient.connect(host, 21)
                ftpClient.login(user, pass)
                ftpClient.enterLocalPassiveMode()
                ftpClient.setFileType(FTP.BINARY_FILE_TYPE)

                runOnUiThread {
                    binding.loginLayout.visibility = View.GONE
                    binding.ftpRecyclerView.visibility = View.VISIBLE
                    loadFtpFiles("/")
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Σφάλμα: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun loadFtpFiles(path: String) {
        Thread {
            try {
                ftpClient.changeWorkingDirectory(path)
                currentFtpPath = path
                val ftpFiles = ftpClient.listFiles()
                val fileList = mutableListOf<FileModel>()

                ftpFiles.forEach { file ->
                    // Αγνοούμε τα τρέχοντα και γονικά directories του Linux
                    if (file.name != "." && file.name != "..") {
                        fileList.add(FileModel(
                            name = file.name,
                            path = if (path == "/") "/${file.name}" else "$path/${file.name}",
                            isDirectory = file.isDirectory,
                            size = if (file.isDirectory) "Φάκελος" else "${file.size / 1024} KB",
                            isRoot = false // Τα FTP αρχεία δεν θεωρούνται local root
                        ))
                    }
                }

                // Ταξινόμηση
                fileList.sortWith(compareByDescending<FileModel> { it.isDirectory }.thenBy { it.name.lowercase() })

                runOnUiThread {
                    // Μέσα στη loadFtpFiles, στο σημείο που ορίζεις τον Adapter:
                    binding.ftpRecyclerView.adapter = FileAdapter(fileList,
                        onItemClick = { selectedFile ->
                            if (selectedFile.isDirectory) {
                                loadFtpFiles(selectedFile.path)
                            } else {
                                // Επιβεβαίωση λήψης με ένα απλό Dialog
                                AlertDialog.Builder(this)
                                    .setTitle("Λήψη αρχείου")
                                    .setMessage("Θέλετε να κατεβάσετε το ${selectedFile.name};")
                                    .setPositiveButton("Ναι") { _, _ ->
                                        downloadFile(selectedFile.path, selectedFile.name)
                                    }
                                    .setNegativeButton("Άκυρο", null)
                                    .show()
                            }
                        },
                        onItemLongClick = { /* ... */ }
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
                        Toast.makeText(this, "Το αρχείο αποθηκεύτηκε στα λήψεις!", Toast.LENGTH_LONG).show()
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

        val localFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)

        Thread {
            val success = try {
                val outputStream = java.io.FileOutputStream(localFile)
                val result = ftpClient.retrieveFile(remoteFilePath, outputStream)
                outputStream.close()
                result
            } catch (e: Exception) { false }

            runOnUiThread {
                progressDialog.dismiss()
                if (success) {
                    Toast.makeText(this, "Ολοκληρώθηκε!", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}