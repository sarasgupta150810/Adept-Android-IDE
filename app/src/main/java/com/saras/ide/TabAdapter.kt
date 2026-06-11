package com.saras.ide // Ensure this matches your package

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class TabAdapter(
var tabs: MutableList<File>,
var selectedFile: File?,
private val onTabClicked: (File) -> Unit,
private val onTabClosed: (File) -> Unit
) : RecyclerView.Adapter<TabAdapter.TabViewHolder>() {

class TabViewHolder(view: View) : RecyclerView.ViewHolder(view) {
val txtTabName: TextView = view.findViewById(R.id.txtTabName)
val btnTabClose: ImageView = view.findViewById(R.id.btnTabClose)
val rootLayout: View = view // The background container
}

override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false)
return TabViewHolder(view)
}

override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
val file = tabs[position]
holder.txtTabName.text = file.name

// Style the tab based on whether it is currently selected
if (file == selectedFile) {
holder.rootLayout.setBackgroundColor(Color.parseColor("#1E1E1E")) // Match editor background
holder.txtTabName.setTextColor(Color.parseColor("#E5C07B")) // Yellow active text
} else {
holder.rootLayout.setBackgroundColor(Color.parseColor("#252526")) // Darker inactive tab
holder.txtTabName.setTextColor(Color.parseColor("#ABB2BF")) // Grey text
}

// Send clicks back to MainActivity
holder.itemView.setOnClickListener { onTabClicked(file) }
holder.btnTabClose.setOnClickListener { onTabClosed(file) }
}

override fun getItemCount() = tabs.size
}