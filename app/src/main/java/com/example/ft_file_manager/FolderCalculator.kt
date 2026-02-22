package com.example.ft_file_manager

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import java.io.File
import java.text.DecimalFormat
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.pow

object FolderCalculator {

    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    data class FolderResult(val size: Long, val fileCount: Int, val folderCount: Int)

    fun calculateFolderSize(
        fileModel: FileModel,
        position: Int,
        adapter: FileAdapter
    ) {
        val shouldCalculate = fileModel.size == "..." || fileModel.size == "--" || fileModel.size.isEmpty()
        if (!fileModel.isDirectory || !shouldCalculate) return

        executor.execute {
            try {
                val root = File(fileModel.path)
                val result = getFolderDataRecursive(root)

                // 1. Δημιουργία του πολύχρωμου κειμένου
                val spannable = buildColorfulInfo(result.fileCount, result.folderCount, result.size)

                mainHandler.post {
                    // ΔΙΟΡΘΩΣΗ: Αποθηκεύουμε το spannable (CharSequence) για να μείνουν τα χρώματα
                    fileModel.size = spannable
                    adapter.notifyItemChanged(position, spannable)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    // Στο catch βάζουμε σταθερό κείμενο, γιατί η μεταβλητή spannable δεν υπάρχει εδώ
                    val errorText = "Error"
                    fileModel.size = errorText
                    adapter.notifyItemChanged(position, errorText)
                }
            }
        }
    }

    private fun buildColorfulInfo(files: Int, folders: Int, sizeBytes: Long): SpannableStringBuilder {
        val builder = SpannableStringBuilder()

        if (files == 0 && folders == 0) {
            builder.append("Κενός φάκελος")
            return builder
        }

        // 1. Αρχεία (Πράσινο #4CAF50)
        val filesText = "$files αρχεία,  "
        val startFiles = builder.length
        builder.append(filesText)
        builder.setSpan(ForegroundColorSpan(Color.parseColor("#4CAF50")), startFiles, builder.length - 2, 0)

        // 2. Φάκελοι (Μπλε #2196F3)
        val foldersText = "$folders φακέλοι,  "
        val startFolders = builder.length
        builder.append(foldersText)
        builder.setSpan(ForegroundColorSpan(Color.parseColor("#2196F3")), startFolders, builder.length - 2, 0)

        // 3. Μέγεθος (Πορτοκαλί #FF9800)
        val sizeText = formatSize(sizeBytes)
        val startSize = builder.length
        builder.append(sizeText)
        builder.setSpan(ForegroundColorSpan(Color.parseColor("#FF9800")), startSize, builder.length, 0)

        return builder
    }

    // Η αναδρομική συνάρτηση παραμένει ίδια...
    private fun getFolderDataRecursive(file: File): FolderResult {
        var size: Long = 0
        var fileCount = 0
        var folderCount = 0
        val files = file.listFiles()
        if (files != null) {
            for (f in files) {
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

    fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
    }
}