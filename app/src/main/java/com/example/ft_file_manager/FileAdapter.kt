package com.example.ft_file_manager

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.io.File
import android.webkit.MimeTypeMap
import com.bumptech.glide.load.engine.DiskCacheStrategy

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

    override fun onBindViewHolder(holder: FileViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            // Αν έχουμε payload, σημαίνει ότι ήρθε μόνο το νέο κείμενο μεγέθους
            val newSizeText = payloads[0] as String
            holder.info.text = newSizeText // Ενημερώνουμε ΜΟΝΟ το TextView, τίποτα άλλο!
        } else {
            // Αν η λίστα payloads είναι άδεια, κάνε την κανονική πλήρη σχεδίαση
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val fileModel = files[position]
        val file = File(fileModel.path)
        val extension = fileModel.name.substringAfterLast(".", "").lowercase()

        holder.name.text = fileModel.name
        holder.info.text = fileModel.size

        // Καθαρισμός και φόρτωση εικονιδίου (Glide κτλ)
        Glide.with(holder.itemView.context).clear(holder.icon)

        if (fileModel.isDirectory) {
            holder.icon.setImageResource(R.drawable.ic_folder_yellow)
        } else {
            // Λογική εικονιδίων για αρχεία
            if (!fileModel.path.startsWith("ftp://") &&
                extension in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")) {

                Glide.with(holder.itemView.context)
                    .load(file)
                    .centerCrop()
                    .thumbnail(0.1f) // Φορτώνει γρήγορα μια μικρογραφία
                    .diskCacheStrategy(DiskCacheStrategy.ALL) // Cache για να μην κολλάει στο scroll
                    .placeholder(R.drawable.ic_image_placeholder)
                    .into(holder.icon)
            } else {
                // Στατικά εικονίδια για FTP ή μη-εικόνες
                when (extension) {
                    "pdf" -> holder.icon.setImageResource(R.drawable.picture_as_pdf_24px)
                    "txt", "log" -> holder.icon.setImageResource(R.drawable.text_ad_24px)
                    "zip", "rar", "7z" -> holder.icon.setImageResource(R.drawable.folder_zip_24px)
                    "mp4", "mkv", "avi" -> holder.icon.setImageResource(R.drawable.video_camera_back_24px)
                    "mp3", "wav" -> holder.icon.setImageResource(R.drawable.music_note_2_24px)
                    "apk" -> {
                        if (!fileModel.path.startsWith("ftp://")) {
                            try {
                                val pm = holder.itemView.context.packageManager
                                val info = pm.getPackageArchiveInfo(fileModel.path, 0)

                                // Χρησιμοποιούμε ?.let για να εκτελεστεί ο κώδικας μόνο αν το info ΚΑΙ το applicationInfo ΔΕΝ είναι null
                                info?.applicationInfo?.let { appInfo ->
                                    appInfo.sourceDir = fileModel.path
                                    appInfo.publicSourceDir = fileModel.path

                                    val iconDrawable = appInfo.loadIcon(pm)
                                    holder.icon.setImageDrawable(iconDrawable)
                                } ?: run {
                                    // Αν το info ή το applicationInfo είναι null, δείξε το default εικονίδιο
                                    holder.icon.setImageResource(R.drawable.apk_document_24px)
                                }
                            } catch (e: Exception) {
                                holder.icon.setImageResource(R.drawable.apk_document_24px)
                            }
                        } else {
                            holder.icon.setImageResource(R.drawable.apk_document_24px)
                        }
                    }
                    // Προεπιλεγμένο εικονίδιο για άγνωστα αρχεία
                    else -> holder.icon.setImageResource(R.drawable.apk_document_24px)
                }
            }
        }

        // 3. Selection UI
        holder.itemView.setBackgroundColor(
            if (fileModel.isSelected) Color.parseColor("#D3D3D3") else Color.TRANSPARENT
        )

        // 4. Click Listeners (Long Click & Simple Click)
        holder.itemView.setOnLongClickListener {
            if (!isInSelectionMode) {
                fileModel.isSelected = true
                isInSelectionMode = true
                notifyDataSetChanged()
                onItemLongClick(fileModel)
            }
            true
        }

        holder.itemView.setOnClickListener {
            if (isInSelectionMode) {
                fileModel.isSelected = !fileModel.isSelected
                notifyItemChanged(position)
                onSelectionChanged()
            } else {
                onItemClick(fileModel)
            }
        }
    }

    override fun getItemCount() = files.size
}