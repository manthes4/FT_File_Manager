package com.example.ft_file_manager

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.example.ft_file_manager.databinding.ActivityImageViewBinding
import java.io.File

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

        // Φόρτωση της εικόνας με τη βιβλιοθήκη Glide
        Glide.with(this)
            .load(file)
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