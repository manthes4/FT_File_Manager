package com.example.ft_file_manager

import java.io.DataOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RootTools {

    /**
     * Ανιχνεύει την καλύτερη διαδρομή για το SU.
     * Στο Android 14, η πλήρης διαδρομή του Magisk είναι συχνά απαραίτητη.
     */
    private fun getSuCommand(): String {
        val paths = arrayOf(
            "/data/adb/magisk/magisk", // Η διαδρομή που δούλεψε στο Android 14 σου
            "/system/xbin/su",
            "/system/bin/su",
            "/sbin/su"
        )
        for (path in paths) {
            if (File(path).exists()) {
                // Αν είναι το magisk binary, επιστρέφουμε "διαδρομή su", αλλιώς σκέτο το path
                return if (path.contains("magisk")) "$path su" else path
            }
        }
        return "su" // Fallback αν δεν βρεθεί τίποτα
    }

    /**
     * Εκτελεί μια εντολή root "σιωπηλά" (π.χ. rm, cp, chmod).
     * Επιστρέφει true αν το exit value είναι 0.
     */
    suspend fun executeSilent(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(getSuCommand())
            val os = DataOutputStream(process.outputStream)

            // Στέλνουμε την εντολή ΚΑΙ το \n μαζί σε UTF-8
            val fullCommand = "$command\n"
            os.write(fullCommand.toByteArray(Charsets.UTF_8))

            os.writeBytes("exit\n")
            os.flush()

            val exitValue = process.waitFor()
            exitValue == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Εκτελεί εντολή root και επιστρέφει το αποτέλεσμα (stdout).
     * Προστέθηκε το 2>&1 για να πιάνουμε και τα μηνύματα σφάλματος.
     */
    suspend fun getOutput(command: String): String = withContext(Dispatchers.IO) {
        val output = StringBuilder()
        try {
            val process = Runtime.getRuntime().exec(getSuCommand())
            val os = DataOutputStream(process.outputStream)

            // Στέλνουμε την εντολή με UTF-8
            val fullCommand = "$command 2>&1\n"
            os.write(fullCommand.toByteArray(Charsets.UTF_8))

            os.writeBytes("exit\n")
            os.flush()

            // ΚΡΙΣΙΜΟ: Ορίζουμε Charsets.UTF_8 στον Reader
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
        } catch (e: Exception) {
            output.append("Error: ${e.message}")
        }
        output.toString().trim()
    }

    /**
     * Ξεκλειδώνει το σύστημα αρχείων για εγγραφή (RW).
     */
    suspend fun unlockSystem(): Boolean {
        val mountCommands = arrayOf(
            "mount -o remount,rw /",
            "mount -o remount,rw /system",
            "mount -o remount,rw /data/media/0", // Ξεκλείδωμα για Android/data πρόσβαση
            "toybox mount -o remount,rw /",
            "busybox mount -o remount,rw /"
        )

        for (cmd in mountCommands) {
            if (executeSilent(cmd)) {
                android.util.Log.d("ROOT_TOOLS", "System unlocked with: $cmd")
                return true
            }
        }
        return false
    }

    /**
     * Κλειδώνει το σύστημα αρχείων (Read-Only).
     */
    suspend fun lockSystem(): Boolean {
        val lockCommands = arrayOf(
            "mount -o remount,ro /",
            "mount -o remount,ro /system"
        )
        for (cmd in lockCommands) {
            if (executeSilent(cmd)) return true
        }
        return false
    }
}