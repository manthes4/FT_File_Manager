package com.example.ft_file_manager

import android.net.Uri
import android.os.Bundle
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class VideoPlayerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        val videoView = findViewById<VideoView>(R.id.videoView)
        val path = intent.getStringExtra("PATH")

        if (path != null) {
            val file = File(path)

            // Ρύθμιση των χειριστηρίων (Play/Pause/Seekbar)
            val mediaController = MediaController(this)
            mediaController.setAnchorView(videoView)
            videoView.setMediaController(mediaController)

            // Φόρτωση και έναρξη
            videoView.setVideoPath(file.absolutePath)

            videoView.setOnPreparedListener {
                videoView.start()
            }

            // Κλείσιμο αν τελειώσει το βίντεο (προαιρετικό)
            videoView.setOnCompletionListener {
                finish()
            }
        } else {
            Toast.makeText(this, "Το αρχείο δεν βρέθηκε", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}