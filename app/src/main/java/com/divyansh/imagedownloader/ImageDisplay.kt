package com.divyansh.imagedownloader

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.divyansh.imagedownloader.Data.AdapterClass
import com.divyansh.imagedownloader.databinding.ActivityImageDisplayBinding

class ImageDisplay : AppCompatActivity() {
    private lateinit var binding: ActivityImageDisplayBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageDisplayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val images = intent.getStringArrayListExtra("dimage")
            ?.filterNotNull()
            .orEmpty()

        if (images.isEmpty()) {
            binding.recycler.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
            return
        }

        binding.recycler.layoutManager = GridLayoutManager(applicationContext, 2)
        binding.recycler.adapter = AdapterClass(images, this)
    }
}
