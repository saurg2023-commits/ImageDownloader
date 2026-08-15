package com.divyansh.imagedownloader.Data

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.divyansh.imagedownloader.FullDisplay
import com.divyansh.imagedownloader.R
import com.squareup.picasso.Picasso

class AdapterClass(
    private val imageUrls: List<String>,
    private val context: Context
) : RecyclerView.Adapter<AdapterClass.ImageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val itemView = LayoutInflater.from(context).inflate(R.layout.imagecard, parent, false)
        return ImageViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val uri = imageUrls[position]

        Picasso.get()
            .load(uri)
            .placeholder(R.mipmap.ic_launcher)
            .error(R.drawable.ic_broken_image)
            .into(holder.imageView)

        holder.imageView.setOnClickListener {
            val intent = Intent(context, FullDisplay::class.java)
            intent.putExtra("image_uri", uri)
            context.startActivity(intent)
        }
    }

    override fun getItemCount(): Int = imageUrls.size

    class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.imagecardView)
    }
}
