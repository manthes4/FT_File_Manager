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
import androidx.recyclerview.widget.DiffUtil
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey

class FileAdapter(
    private var files: List<FileModel>,
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

    // Η συνάρτηση για την αναζήτηση πλέον θα δουλεύει κανονικά!
    fun updateList(newList: List<FileModel>) {
        val diffCallback = FileDiffCallback(files, newList)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        files = newList
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            // Αλλάζουμε το "as String" σε "as CharSequence"
            // ώστε να δέχεται και τα χρώματα από το FolderCalculator
            val newInfo = payloads[0] as CharSequence
            holder.info.text = newInfo
        } else {
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

        // FileAdapter.kt -> onBindViewHolder
        if (fileModel.isDirectory) {
            holder.icon.setImageResource(R.drawable.ic_folder_yellow)

            // 1. Ακύρωση οποιουδήποτε προηγούμενου Runnable είναι δεμένο με αυτό το View
            holder.itemView.removeCallbacks(null)

            // 2. Έλεγχος αν το μέγεθος είναι ήδη γνωστό (Cache)
            if (fileModel.size == "--" || fileModel.size.isEmpty()) {
                holder.info.text = "Υπολογισμός..."

                // 3. Χρησιμοποιούμε ένα runnable για να δώσουμε χρόνο στο scroll να αναπνεύσει
                val folderRunnable = Runnable {
                    FolderCalculator.calculateFolderSize(fileModel, position, this)
                }

                // Αποθηκεύουμε το runnable στο tag του view για να μπορούμε να το ελέγξουμε
                holder.itemView.tag = folderRunnable
                holder.itemView.postDelayed(folderRunnable, 400) // Αυξημένο delay στα 400ms
            } else {
                holder.info.text = fileModel.size
            }

        } else {
            // Λογική εικονιδίων για αρχεία
            if (!fileModel.path.startsWith("ftp://") &&
                extension in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "mp4", "mkv", "mov")) {

                Glide.with(holder.icon.context)
                    .asBitmap()
                    .load(file)
                    // Χρησιμοποίησε μόνο το path αν είσαι σίγουρος ότι τα αρχεία δεν αλλάζουν περιεχόμενο συχνά
                    // ή βάλε το lastModified() διαιρεμένο δια 1000 για να αγνοεί τα milliseconds
                    .signature(ObjectKey(file.absolutePath + (file.lastModified() / 1000)))
                    .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                    .override(100, 100)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE) // RESOURCE = Αποθηκεύει μόνο το 100x100 thumbnail
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
            if (fileModel.isSelected) Color.parseColor("#4D2196F3") else Color.TRANSPARENT
        )

        // 4. Click Listeners - ΜΟΝΟ ΑΥΤΑ ΤΑ ΔΥΟ ΧΡΕΙΑΖΟΝΤΑΙ
        holder.itemView.setOnLongClickListener {
            onItemLongClick(fileModel)
            true
        }

        holder.itemView.setOnClickListener {
            onItemClick(fileModel)
        }
    } // Εδώ κλείνει η onBindViewHolder

    override fun onViewRecycled(holder: FileViewHolder) {
        super.onViewRecycled(holder)
        val runnable = holder.itemView.tag as? Runnable
        if (runnable != null) {
            holder.itemView.removeCallbacks(runnable)
        }
        holder.itemView.tag = null
        Glide.with(holder.itemView.context).clear(holder.icon)
    }

    override fun getItemCount() = files.size
}

class FileDiffCallback(
    private val oldList: List<FileModel>,
    private val newList: List<FileModel>
) : DiffUtil.Callback() {
    override fun getOldListSize() = oldList.size
    override fun getNewListSize() = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
        oldList[oldItemPosition].path == newList[newItemPosition].path

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
        oldList[oldItemPosition] == newList[newItemPosition]
}