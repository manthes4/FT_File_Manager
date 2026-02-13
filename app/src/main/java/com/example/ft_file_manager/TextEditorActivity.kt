package com.example.ft_file_manager

import android.os.Bundle
import android.view.Menu
import android.widget.Toast
import java.io.File

import android.view.MenuItem
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.ft_file_manager.databinding.ActivityTextEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TextEditorActivity : AppCompatActivity() {
    private lateinit var filePath: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityTextEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        filePath = intent.getStringExtra("PATH") ?: ""
        val file = File(filePath)

        setSupportActionBar(binding.toolbarText)
        supportActionBar?.title = file.name
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Διάβασμα αρχείου σε Coroutine για να μην κολλήσει το UI
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val content = file.readText()
                withContext(Dispatchers.Main) {
                    binding.etTextContent.setText(content)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TextEditorActivity, "Σφάλμα ανάγνωσης", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add("Αποθήκευση")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.title == "Αποθήκευση") {
            saveFile()
            return true
        }
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun saveFile() {
        val content = findViewById<EditText>(R.id.etTextContent).text.toString()
        try {
            File(filePath).writeText(content)
            Toast.makeText(this, "Αποθηκεύτηκε!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Αποτυχία αποθήκευσης", Toast.LENGTH_SHORT).show()
        }
    }
}