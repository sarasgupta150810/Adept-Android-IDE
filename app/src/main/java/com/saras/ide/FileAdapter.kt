package com.saras.ide

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Space
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

data class FileItem(val name: String, val isDirectory: Boolean, val depth: Int, val file: File)

class FileAdapter(
private val items: List<FileItem>,
private val onFileClicked: (File) -> Unit,
private val onFolderClicked: (File) -> Unit,
// 💥 UPDATED: We now pass the View so the PopupMenu knows where to anchor!
private val onItemLongClicked: (View, File) -> Unit
) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
val txtFileName: TextView = view.findViewById(R.id.txtFileName)
val imgFileIcon: ImageView = view.findViewById(R.id.imgFileIcon)
val indentSpacer: Space = view.findViewById(R.id.indentSpacer)
val rootLayout: View = view
}

override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
return ViewHolder(view)
}

override fun onBindViewHolder(holder: ViewHolder, position: Int) {
val item = items[position]
holder.txtFileName.text = item.name

// 🛠️ SPLIT-SCREEN FIX: Reduced depth multiplier from 40 to 25 to save space!
val params = holder.indentSpacer.layoutParams
params.width = item.depth * 25
holder.indentSpacer.layoutParams = params

if (item.isDirectory) {
holder.imgFileIcon.setImageResource(android.R.drawable.ic_menu_sort_by_size)
} else {
holder.imgFileIcon.setImageResource(R.drawable.ic_menu_myfiles)
}

holder.rootLayout.setOnClickListener {
if (item.isDirectory) onFolderClicked(item.file) else onFileClicked(item.file)
}

holder.rootLayout.setOnLongClickListener {
// 💥 We pass the specific item's view back to MainActivity
onItemLongClicked(holder.rootLayout, item.file)
true
}
}

override fun getItemCount() = items.size
}