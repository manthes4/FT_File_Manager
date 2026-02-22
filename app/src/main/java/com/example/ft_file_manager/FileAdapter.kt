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

    // 1. Πρόσθεσε αυτό για να έχουμε πρόσβαση στο context
    val context get() = recyclerView?.context ?: throw IllegalStateException("Adapter not attached")
    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            // ΕΝΗΜΕΡΩΣΗ ΜΟΝΟ ΤΟΥ TEXT, ΧΩΡΙΣ Glide/Icons κτλ.
            holder.info.text = payloads[0] as CharSequence
        } else {
            onBindViewHolder(holder, position)
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

        // Μέσα στην onBindViewHolder, εκεί που ελέγχεις αν είναι φάκελος:
        if (fileModel.isDirectory) {
            holder.icon.setImageResource(R.drawable.ic_folder_yellow)
            holder.itemView.removeCallbacks(null)

            // Πάρε το πότε άλλαξε ο φάκελος τελευταία φορά
            val currentTimestamp = file.lastModified()

            // Κάλεσε τον Calculator αν:
            // 1. Δεν έχουμε τιμή
            // 2. Ή αν ο φάκελος άλλαξε (το timestamp είναι διαφορετικό από αυτό που ξέραμε)
            if (fileModel.size == "..." || fileModel.size == "--" || fileModel.size.isEmpty() || fileModel.lastModifiedCached != currentTimestamp) {
                holder.info.text = "..."
                fileModel.lastModifiedCached = currentTimestamp // Ενημέρωσε το cache στο model
                FolderCalculator.calculateFolderSize(fileModel, holder.bindingAdapterPosition, this)
            } else {
                holder.info.text = fileModel.size
            }

        } else {
            // Λογική εικονιδίων για αρχεία
            // Μέσα στην onBindViewHolder του FileAdapter.kt
            if (!fileModel.path.startsWith("ftp://") &&
                extension in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "mp4", "mkv", "mov")
            ) {
                Glide.with(holder.icon.context)
                    .asBitmap() // Πολύ πιο γρήγορο από το να φορτώνει ολόκληρο το drawable
                    .load(fileModel.path)
                    // Χρήση ΜΟΝΟ του cache key. ΠΟΤΕ file.lastModified() εδώ!
                    .signature(ObjectKey(fileModel.path + fileModel.lastModifiedCached))
                    .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565) // 50% λιγότερη RAM
                    .override(100, 100) // Thumbnail size
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE) // Αποθήκευση μόνο του μικρού αρχείου
                    .thumbnail(0.1f) // Φόρτωσε ακαριαία μια θολή εικόνα
                    .placeholder(R.drawable.ic_image_placeholder)
                    .into(holder.icon)
            } else {
                when (extension) {
                    "pdf" -> holder.icon.setImageResource(R.drawable.picture_as_pdf_24px)
                    "txt", "log" -> holder.icon.setImageResource(R.drawable.text_ad_24px)
                    "zip", "rar", "7z" -> holder.icon.setImageResource(R.drawable.folder_zip_24px)
                    "mp4", "mkv", "avi" -> holder.icon.setImageResource(R.drawable.video_camera_back_24px)
                    "mp3", "wav" -> holder.icon.setImageResource(R.drawable.music_note_2_24px)
                    "apk" -> {
                        // Χρήση Glide για τα APK!
                        // Το Glide θα τρέξει τοPackageManager στο παρασκήνιο (background thread)
                        // οπότε το scroll θα μείνει απόλυτα ομαλό.
                        Glide.with(holder.icon.context)
                            .load(fileModel.path)
                            .placeholder(R.drawable.apk_document_24px)
                            .error(R.drawable.apk_document_24px)
                            .into(holder.icon)
                    }

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
    }

    override fun onViewRecycled(holder: FileViewHolder) {
        super.onViewRecycled(holder)
        Glide.with(holder.itemView.context).clear(holder.icon)

        // Πολύ σημαντικό: Ακύρωση του Task στη Room/Executor
        FolderCalculator.cancelTask(holder.bindingAdapterPosition)

        holder.itemView.removeCallbacks(null)
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