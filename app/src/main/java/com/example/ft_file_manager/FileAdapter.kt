package com.example.ft_file_manager

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FileAdapter(
    private val files: List<FileModel>,
    private val onItemClick: (FileModel) -> Unit,
    private val onItemLongClick: (FileModel) -> Unit
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.imgIcon)
        val name: TextView = view.findViewById(R.id.tvFileName) // <--- Εδώ πρέπει να είναι tvFileName
        val info: TextView = view.findViewById(R.id.tvFileInfo) // <--- Εδώ πρέπει να είναι tvFileInfo
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = files[position]

        // ΕΔΩ ΕΙΝΑΙ ΤΟ ΚΛΕΙΔΙ:
        holder.name.text = file.name  // Πρέπει να είναι το όνομα του αρχείου
        holder.info.text = file.size  // Πρέπει να είναι το "Φάκελος" ή το μέγεθος

        if (file.isDirectory) {
            holder.icon.setImageResource(android.R.drawable.ic_menu_directions)
        } else {
            holder.icon.setImageResource(android.R.drawable.ic_menu_agenda)
        }

        holder.itemView.setOnClickListener { onItemClick(file) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(file)
            true
        }
    }

    override fun getItemCount() = files.size
}