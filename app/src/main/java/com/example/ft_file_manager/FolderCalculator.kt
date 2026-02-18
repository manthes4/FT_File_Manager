package com.example.ft_file_manager

import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.DecimalFormat
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.pow

object FolderCalculator {

    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Κλάση για τη μεταφορά όλων των αποτελεσμάτων μαζί
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

                // Διαμόρφωση του κειμένου: "X αρ., Y φακ. | Z MB"
                val infoText = if (result.fileCount == 0 && result.folderCount == 0) {
                    "Κενός φάκελος"
                } else {
                    "${result.fileCount} αρ., ${result.folderCount} φακ. | ${formatSize(result.size)}"
                }

                updateUI(fileModel, position, adapter, infoText)
            } catch (e: Exception) {
                updateUI(fileModel, position, adapter, "Error")
            }
        }
    }

    private fun updateUI(fileModel: FileModel, position: Int, adapter: FileAdapter, result: String) {
        mainHandler.post {
            fileModel.size = result
            adapter.notifyItemChanged(position, result)
        }
    }

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