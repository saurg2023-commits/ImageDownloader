package com.divyansh.imagedownloader

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.divyansh.imagedownloader.databinding.ActivityFullDisplayBinding
import com.squareup.picasso.Picasso

class FullDisplay : AppCompatActivity() {
    private lateinit var binding: ActivityFullDisplayBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityFullDisplayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uri = intent.getStringExtra("image_uri")

        if (uri.isNullOrBlank()) {
            Toast.makeText(this, "No image to display", Toast.LENGTH_LONG).show()
            binding.downloadButton.isEnabled = false
            binding.downloadButton.alpha = 0.4f
            return
        }

        Picasso.get()
            .load(uri)
            .error(R.drawable.ic_broken_image)
            .into(binding.fullimageView)

        binding.downloadButton.setOnClickListener {
            val queued = Downloader.enqueue(uri, this)
            val message = if (queued) "Download started" else "Couldn't start the download"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
