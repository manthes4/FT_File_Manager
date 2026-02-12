package com.example.ft_file_manager

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FileAdapter(
    private val files: List<FileModel>,
    var isInSelectionMode: Boolean = false, // Μεταβλητή ελέγχου για το mode επιλογής
    private val onItemClick: (FileModel) -> Unit,
    private val onItemLongClick: (FileModel) -> Unit,
    private val onSelectionChanged: () -> Unit // Callback για να ενημερώνουμε την Toolbar
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.imgIcon)
        val name: TextView = view.findViewById(R.id.tvFileName)
        val info: TextView = view.findViewById(R.id.tvFileInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = files[position]

        holder.name.text = file.name
        holder.info.text = file.size

        // Εικονίδια
        if (file.isDirectory) {
            holder.icon.setImageResource(android.R.drawable.ic_menu_directions)
        } else {
            holder.icon.setImageResource(android.R.drawable.ic_menu_agenda)
        }

        // Αλλαγή φόντου αν είναι επιλεγμένο
        holder.itemView.setBackgroundColor(
            if (file.isSelected) Color.parseColor("#D3D3D3") else Color.TRANSPARENT
        )

        // Long Click: Ενεργοποιεί το Selection Mode αν δεν είναι ήδη ενεργό
        holder.itemView.setOnLongClickListener {
            if (!isInSelectionMode) {
                file.isSelected = true
                isInSelectionMode = true
                notifyDataSetChanged() // Ενημέρωση όλων για να δείξουν τα backgrounds
                onItemLongClick(file)
            }
            true
        }

        // Simple Click
        holder.itemView.setOnClickListener {
            if (isInSelectionMode) {
                file.isSelected = !file.isSelected
                notifyItemChanged(position)
                onSelectionChanged() // Ενημέρωσε τη MainActivity να μετρήσει τα επιλεγμένα
            } else {
                onItemClick(file)
            }
        }
    }

    override fun getItemCount() = files.size
}