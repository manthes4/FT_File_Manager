package com.example.ft_file_manager

import java.io.DataOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RootTools {

    /**
     * Εκτελεί μια εντολή root και επιστρέφει true αν πέτυχε.
     * Ιδανικό για rm, cp, mv, chmod.
     */
    suspend fun executeSilent(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)

            // Remount system αν χρειάζεται (προαιρετικά μπορείς να το καλείς ξεχωριστά)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()

            val exitValue = process.waitFor()
            exitValue == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Εκτελεί εντολή root και επιστρέφει το αποτέλεσμα ως String (π.χ. για ls ή cat).
     */
    suspend fun getOutput(command: String): String = withContext(Dispatchers.IO) {
        val output = StringBuilder()
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
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
     * Ξεκλειδώνει το σύστημα αρχείων για εγγραφή.
     */
    suspend fun unlockSystem(): Boolean {
        // Λίστα με τις πιο συνηθισμένες εντολές remount για διαφορετικές εκδόσεις Android
        val mountCommands = arrayOf(
            "mount -o remount,rw /",                 // Η κλασική (CoreELEC / Παλιά Android)
            "mount -o remount,rw /system",          // Για τυπικά Android 7-9
            "mount -o remount,rw /dev/block/by-name/system /system", // Πιο επιθετική
            "toybox mount -o remount,rw /",         // Χρήση toybox αν το mount είναι περιορισμένο
            "busybox mount -o remount,rw /system"   // Χρήση busybox αν υπάρχει
        )

        for (cmd in mountCommands) {
            if (executeSilent(cmd)) {
                android.util.Log.d("ROOT_TOOLS", "System unlocked with: $cmd")
                return true // Αν πετύχει έστω και μία, σταματάμε και επιστρέφουμε true
            }
        }

        android.util.Log.e("ROOT_TOOLS", "All unlock attempts failed!")
        return false
    }

    suspend fun lockSystem(): Boolean = executeSilent("mount -o remount,ro /")
}