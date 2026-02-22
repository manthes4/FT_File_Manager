package com.example.ft_file_manager

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import java.io.File
import java.text.DecimalFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.pow
import java.util.concurrent.Future

data class FolderResult(val size: Long, val fileCount: Int, val folderCount: Int)

object FolderCalculator {
    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val runningTasks = ConcurrentHashMap<Int, Future<*>>()

    // --- ΑΥΤΗ ΕΙΝΑΙ Η ΣΥΝΑΡΤΗΣΗ ΠΟΥ ΕΛΕΙΠΕ ---
    fun cancelTask(position: Int) {
        runningTasks[position]?.cancel(true)
        runningTasks.remove(position)
    }

    fun calculateFolderSize(fileModel: FileModel, position: Int, adapter: FileAdapter) {
        val path = fileModel.path
        // Αν είναι FTP, μην κάνεις τίποτα ακόμα (για να μη λαγκάρει το δίκτυο)
        if (path.startsWith("ftp://")) return

        val root = File(path)
        if (!root.exists() || !root.isDirectory) return

        // ΑΚΥΡΩΣΗ ΑΜΕΣΩΣ
        cancelTask(position)

        val task = executor.submit {
            try {
                // 1. ΠΡΩΤΑ ΕΛΕΓΧΟΣ ΜΝΗΜΗΣ (πολύ γρήγορο)
                val currentLM = root.lastModified()

                // 2. ΕΛΕΓΧΟΣ ΒΑΣΗΣ
                val db = AppDatabase.getDatabase(adapter.context)
                val cached = db.folderDao().getFolder(path)

                if (cached != null && cached.lastModified == currentLM) {
                    val spannable = buildColorfulInfo(cached.fileCount, cached.folderCount, cached.size)
                    // Ενημέρωση UI μόνο αν δεν έχει αλλάξει το position
                    mainHandler.post {
                        fileModel.size = spannable.toString()
                        adapter.notifyItemChanged(position, spannable)
                    }
                    return@submit
                }

                // 3. ΥΠΟΛΟΓΙΣΜΟΣ (Μόνο αν χρειάζεται)
                // Προσθέτουμε μια μικρή παύση για να μη "γονατίζει" ο δίσκος στο σκρολάρισμα
                Thread.sleep(50)

                if (Thread.currentThread().isInterrupted) return@submit
                val result = getFolderDataRecursive(root)

                if (Thread.currentThread().isInterrupted) return@submit

                // ΑΠΟΘΗΚΕΥΣΗ
                db.folderDao().insertFolder(FolderCacheEntity(path, result.size, result.fileCount, result.folderCount, currentLM))

                val spannable = buildColorfulInfo(result.fileCount, result.folderCount, result.size)
                updateUI(fileModel, position, adapter, spannable)

            } catch (e: Exception) {
                runningTasks.remove(position)
            }
        }
        runningTasks[position] = task
    }

    // Αλλαγή σε public για να διορθωθεί το σφάλμα στη MainActivity
    fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        val result = size / 1024.0.pow(digitGroups.toDouble())
        return DecimalFormat("#,##0.#").format(result) + " " + units[digitGroups]
    }

    private fun getFolderDataRecursive(file: File): FolderResult {
        // ΤΕΡΑΣΤΙΑ ΒΕΛΤΙΩΣΗ: Έλεγχος διακοπής σε κάθε επίπεδο
        if (Thread.currentThread().isInterrupted) return FolderResult(0,0,0)

        var size: Long = 0
        var filesC = 0
        var foldersC = 0

        val list = file.listFiles()
        if (list != null) {
            for (f in list) {
                if (Thread.currentThread().isInterrupted) break
                if (f.isDirectory) {
                    foldersC++
                    val sub = getFolderDataRecursive(f)
                    size += sub.size
                    filesC += sub.fileCount
                    foldersC += sub.folderCount
                } else {
                    filesC++
                    size += f.length()
                }
            }
        }
        return FolderResult(size, filesC, foldersC)
    }

    private fun updateUI(model: FileModel, pos: Int, adapter: FileAdapter, text: CharSequence) {
        mainHandler.post {
            // model.size = text.toString() <-- Αυτό χάνει τα χρώματα αν το UI ξανασχεδιαστεί
            // Αν το FileModel.size είναι τύπου CharSequence, κράτα το ολόκληρο:
            model.size = text.toString()
            adapter.notifyItemChanged(pos, text) // Το text εδώ έχει τα χρώματα για το payload
            runningTasks.remove(pos)
        }
    }

    private fun buildColorfulInfo(files: Int, folders: Int, sizeBytes: Long): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        if (files == 0 && folders == 0) return builder.append("Κενός φάκελος")

        // Αρχεία
        val startFiles = builder.length
        builder.append("$files αρχεία, ")
        builder.setSpan(ForegroundColorSpan(Color.parseColor("#4CAF50")), startFiles, builder.length - 2, 0)

        // Φάκελοι
        val startFolders = builder.length
        builder.append("$folders φακέλοι, ")
        builder.setSpan(ForegroundColorSpan(Color.parseColor("#2196F3")), startFolders, builder.length - 2, 0)

        // Μέγεθος
        val startSize = builder.length
        builder.append(formatSize(sizeBytes))
        builder.setSpan(ForegroundColorSpan(Color.parseColor("#FF9800")), startSize, builder.length, 0)

        return builder
    }
}