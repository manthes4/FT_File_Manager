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

object FolderCalculator {
    // Η cache δέχεται CharSequence για να υποστηρίζει Spannable (χρώματα)
    private val sizeCache = ConcurrentHashMap<String, CharSequence>()

    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val runningTasks = ConcurrentHashMap<Int, Future<*>>()

    // Πρέπει να είναι public για να το βλέπει ο Task
    data class FolderResult(val size: Long, val fileCount: Int, val folderCount: Int)

    fun calculateFolderSize(fileModel: FileModel, position: Int, adapter: FileAdapter) {
        val root = File(fileModel.path)
        val currentLastModified = root.lastModified()

        // Εδώ ελέγχουμε αν αυτό που έχουμε ήδη στο UI είναι φρέσκο
        // Αν το fileModel έχει ήδη μέγεθος ΚΑΙ δεν έχει αλλάξει ο φάκελος, σταμάτα.
        if (fileModel.lastModified == currentLastModified && fileModel.size != "--") {
            return
        }

        runningTasks[position]?.cancel(true)

        val task = executor.submit {
            try {
                val result = getFolderDataRecursive(root)
                val spannable = buildColorfulInfo(result.fileCount, result.folderCount, result.size)

                mainHandler.post {
                    fileModel.size = spannable.toString()
                    fileModel.lastModified = currentLastModified // Αποθήκευση της ημερομηνίας
                    adapter.notifyItemChanged(position, spannable)
                }
            } catch (e: Exception) { /* ... */ }
        }
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
        // ΣΗΜΑΝΤΙΚΟ: Έλεγχος αν το Thread διακόπηκε (interrupted)
        // Αν ο χρήστης έφυγε από τον φάκελο, σταμάτα αμέσως το σκανάρισμα!
        if (Thread.currentThread().isInterrupted) return FolderResult(0, 0, 0)

        var size: Long = 0
        var fileCount = 0
        var folderCount = 0

        val files = file.listFiles()
        if (files != null) {
            for (f in files) {
                // Ξαναελέγχουμε μέσα στο loop για μέγιστη απόκριση
                if (Thread.currentThread().isInterrupted) break

                if (f.isDirectory) {
                    folderCount++
                    val sub = getFolderDataRecursive(f)
                    size += sub.size
                    fileCount += sub.fileCount
                    folderCount += sub.folderCount
                } else {
                    fileCount++
                    size += f.length()
                }
            }
        }
        return FolderResult(size, fileCount, folderCount)
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