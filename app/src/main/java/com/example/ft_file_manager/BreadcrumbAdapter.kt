package com.example.ft_file_manager

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BreadcrumbAdapter(
    private val crumbs: List<Pair<String, String>>, // (Όνομα, Πλήρες Path)
    private val onItemClick: (String) -> Unit
) : RecyclerView.Adapter<BreadcrumbAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.breadcrumbName)
        val arrow: View = view.findViewById(R.id.breadcrumbArrow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_breadcrumb, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (name, path) = crumbs[position]
        holder.name.text = name

        // Κρύβουμε το βελάκι στο τελευταίο στοιχείο
        holder.arrow.visibility = if (position == crumbs.size - 1) View.GONE else View.VISIBLE

        holder.name.setOnClickListener { onItemClick(path) }
    }

    override fun getItemCount() = crumbs.size
}