package com.example.ft_file_manager

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DashboardAdapter(
    private val items: MutableList<DashboardItem>, // <--- Εδώ από List σε MutableList
    private val onClick: (DashboardItem) -> Unit,
    private val onLongClick: (DashboardItem) -> Unit // Προσθήκη Long Click
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // Ορισμός των δύο τύπων View
    private val TYPE_STORAGE = 1
    private val TYPE_PINNED = 2

    // Καθορίζουμε ποιο layout θα χρησιμοποιηθεί βάσει του ID του item
    override fun getItemViewType(position: Int): Int {
        return if (items[position].id == 5) TYPE_PINNED else TYPE_STORAGE
    }

    // ViewHolder για τις μεγάλες κάρτες (Storage)
    class StorageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.itemIcon)
        val title: TextView = view.findViewById(R.id.itemTitle)
        val progress: ProgressBar = view.findViewById(R.id.itemProgress)
        val details: TextView = view.findViewById(R.id.itemSpaceDetails)
    }

    // ViewHolder για τις μικρές κάρτες (Pinned/Favorites)
    class PinnedViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.itemIcon)
        val title: TextView = view.findViewById(R.id.itemTitle)
        val size: TextView = view.findViewById(R.id.itemSize) // Υποθέτοντας ότι το ονομάσαμε itemSize στο XML
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_STORAGE) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_dashboard, parent, false)
            StorageViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_dashboard_small, parent, false)
            PinnedViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]

        if (holder is StorageViewHolder) {
            holder.title.text = item.title
            holder.icon.setImageResource(item.iconRes)
            holder.progress.progress = item.percentage
            holder.details.text = "${item.usedSpace} / ${item.totalSpace}"
        }
        else if (holder is PinnedViewHolder) {
            holder.title.text = item.title
            holder.icon.setImageResource(item.iconRes)
            // Στο μικρό layout δείχνουμε μόνο το used space ή "N/A"
            holder.size.text = item.usedSpace ?: ""
        }

        // Κοινή λογική για τα κλικ
        holder.itemView.setOnClickListener { onClick(item) }

        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    override fun getItemCount() = items.size
}