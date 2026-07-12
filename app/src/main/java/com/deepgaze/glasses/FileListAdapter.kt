package com.deepgaze.glasses

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class FileListAdapter(
    private val onItemClick: (DataFileInfo) -> Unit
) : RecyclerView.Adapter<FileListAdapter.FileViewHolder>() {

    private var files = listOf<DataFileInfo>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun submitList(newFiles: List<DataFileInfo>) {
        files = newFiles
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = files[position]
        holder.bind(file, onItemClick, onItemClick)
    }

    override fun getItemCount(): Int = files.size

    class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textFileName: TextView = itemView.findViewById(R.id.textFileName)
        private val textFileInfo: TextView = itemView.findViewById(R.id.textFileInfo)
        private val textFileSize: TextView = itemView.findViewById(R.id.textFileSize)
        private val buttonOpen: Button = itemView.findViewById(R.id.buttonOpen)

        fun bind(
            file: DataFileInfo,
            onOpenClick: (DataFileInfo) -> Unit,
            onItemClick: (DataFileInfo) -> Unit,
        ) {
            textFileName.text = file.name
            textFileInfo.text = "Modified: ${
                SimpleDateFormat(
                    "yyyy-MM-dd HH:mm",
                    Locale.getDefault()
                ).format(file.modifiedDate)
            }"

            // Format size
            val sizeText = when {
                file.size >= 1024 * 1024 -> String.format("%.2f MB", file.size / (1024.0 * 1024.0))
                file.size >= 1024 -> String.format("%.2f KB", file.size / 1024.0)
                else -> "${file.size} B"
            }
            textFileSize.text = sizeText

            itemView.setOnClickListener {
                onItemClick(file)
            }

            buttonOpen.setOnClickListener {
                onOpenClick(file)
            }
        }
    }
}
