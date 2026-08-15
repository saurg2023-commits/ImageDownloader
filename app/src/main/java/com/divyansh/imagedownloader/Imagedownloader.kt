package com.divyansh.imagedownloader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.divyansh.imagedownloader.databinding.ImageLoaderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Imagedownloader : AppCompatActivity() {
    private lateinit var binding: ImageLoaderBinding
    private var permissionGranted = false

    companion object {
        // Binder/IPC transactions are capped around 1MB; keep well under that
        // even though scraper.py already caps its own results at 500.
        private const val MAX_IMAGES_PER_REQUEST = 500
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            permissionGranted = isGranted
            if (!isGranted) {
                Toast.makeText(
                    this, getString(R.string.storage_permission_needed), Toast.LENGTH_LONG
                ).show()
            }
        }

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Scoped storage (API 29+): DownloadManager writes to an
                // app-specific directory, so no runtime permission is needed.
                permissionGranted = true
            }
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED -> {
                permissionGranted = true
            }
            else -> {
                requestPermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        binding = ImageLoaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.footerMsg1.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dev-divyansh/ImageDownloader"))
            startActivity(intent)
        }

        binding.button.setOnClickListener {
            val url = binding.url.text.toString().trim()

            if (url.isEmpty()) {
                Toast.makeText(this, getString(R.string.url_required), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (!permissionGranted) {
                Toast.makeText(this, getString(R.string.storage_permission_needed), Toast.LENGTH_LONG).show()
                requestPermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return@setOnClickListener
            }

            if (!hasInternetConnection()) {
                Toast.makeText(this, getString(R.string.no_connection), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            scrapeAndContinue(url)
        }
    }

    private fun scrapeAndContinue(url: String) {
        setLoading(true)

        lifecycleScope.launch {
            val images = try {
                // The Chaquopy call is a blocking network operation, so it
                // must run off the main thread -- otherwise the UI freezes
                // (and Android may kill the activity with an ANR) for the
                // entire duration of the page fetch.
                withContext(Dispatchers.IO) {
                    val python = Python.getInstance()
                    val scraperModule: PyObject = python.getModule("scraper")
                    scraperModule.callAttr("main", url)
                        .asList()
                        .mapNotNull { it?.toString() }
                        .take(MAX_IMAGES_PER_REQUEST)
                }
            } catch (e: Exception) {
                setLoading(false)
                Toast.makeText(this@Imagedownloader, getString(R.string.scrape_failed), Toast.LENGTH_LONG).show()
                return@launch
            }

            setLoading(false)

            if (images.isEmpty()) {
                Toast.makeText(this@Imagedownloader, getString(R.string.no_images_found), Toast.LENGTH_LONG).show()
                return@launch
            }

            val intent = Intent(this@Imagedownloader, ImageDisplay::class.java)
            intent.putStringArrayListExtra("dimage", ArrayList(images))
            startActivity(intent)
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.url.isEnabled = !loading
        binding.button.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.INVISIBLE
    }

    private fun hasInternetConnection(): Boolean {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
