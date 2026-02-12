package com.example.ft_file_manager

import java.io.DataOutputStream
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

object RootHelper {
    fun isDeviceRooted(): Boolean {
        return File("/system/xbin/su").exists() || File("/system/bin/su").exists()
    }

    fun runRootCommand(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun runRootCommandWithOutput(command: String): String {
        val output = StringBuilder()
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return output.toString()
    }
}