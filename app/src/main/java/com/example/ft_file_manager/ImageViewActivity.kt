package com.example.ft_file_manager

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.example.ft_file_manager.databinding.ActivityImageViewBinding
import java.io.File
// Αν χρειαστεί, κάνε import το PhotoView, αν και το Glide το δέχεται ως View
import com.github.chrisbanes.photoview.PhotoView

class ImageViewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityImageViewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra("PATH") ?: ""
        val file = File(path)

        setSupportActionBar(binding.toolbarImage)
        supportActionBar?.title = file.name
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Η Glide θα "φορτώσει" την εικόνα στο PhotoView
        // και αυτό θα αναλάβει αυτόματα το Zoom.
        Glide.with(this)
            .load(file)
            .dontTransform() // <--- ΠΡΟΣΘΕΣΕ ΑΥΤΟ
            .into(binding.ivFullImage)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}