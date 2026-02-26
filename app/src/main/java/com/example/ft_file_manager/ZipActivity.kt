package com.example.ft_file_manager

import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.*

class ZipActivity : AppCompatActivity() {

    private val TAG = "ZIP_DEBUG"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_zip)

        // Λήψη του path με έλεγχο για το Bundle error που είδαμε στα logs
        val zipPath = intent.getStringExtra("PATH")
        if (zipPath == null) {
            Log.e(TAG, "Σφάλμα: Το PATH είναι null στο Intent")
            finish()
            return
        }

        val zipFile = File(zipPath)
        Log.d(TAG, "Άνοιγμα αρχείου: ${zipFile.absolutePath}")

        findViewById<TextView>(R.id.zipName).text = zipFile.name

        // Προβολή περιεχομένων
        val recyclerView = findViewById<RecyclerView>(R.id.zipRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val fileList = mutableListOf<String>()
        try {
            if (zipFile.exists()) {
                ZipFile(zipFile).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        fileList.add(entries.nextElement().name)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Σφάλμα ανάγνωσης ZIP: ${e.message}")
        }

        findViewById<Button>(R.id.btnExtract).setOnClickListener {
            unzip(zipFile)
        }
    }

    private fun unzip(zipFile: File) {
        // Ορίζουμε τον φάκελο στόχο
        val targetDir = File(zipFile.parent, zipFile.nameWithoutExtension)
        Log.d(TAG, "Έναρξη unzip στο: ${targetDir.absolutePath}")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // ΚΑΘΑΡΙΣΜΟΣ: Αν υπήρχε παλιά αποτυχία, σβήνουμε τα πάντα
                if (targetDir.exists()) {
                    Log.d(TAG, "Ο φάκελος προϋπήρχε. Διαγραφή...")
                    targetDir.deleteRecursively()
                }

                if (!targetDir.mkdirs()) {
                    Log.e(TAG, "Αποτυχία δημιουργίας αρχικού φακέλου")
                }

                FileInputStream(zipFile).use { fis ->
                    ZipInputStream(BufferedInputStream(fis)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val newFile = File(targetDir, entry.name)
                            Log.d(TAG, "Αποσυμπίεση: ${entry.name}")

                            if (entry.isDirectory) {
                                newFile.mkdirs()
                            } else {
                                // ΚΡΙΣΙΜΟ: Δημιουργία όλων των ενδιάμεσων φακέλων του entry
                                val parent = newFile.parentFile
                                if (parent != null && !parent.exists()) {
                                    parent.mkdirs()
                                }

                                // Εγγραφή αρχείου με Buffer
                                FileOutputStream(newFile).use { fos ->
                                    zis.copyTo(fos)
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ZipActivity, "Επιτυχία!", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Κρίσιμο σφάλμα Unzip: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ZipActivity, "Σφάλμα (Δες Logs): ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}