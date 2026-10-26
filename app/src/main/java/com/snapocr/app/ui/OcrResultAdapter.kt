package com.snapocr.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.snapocr.app.R
import com.snapocr.app.databinding.ItemOcrResultBinding
import com.snapocr.app.model.OcrItem
import com.snapocr.app.model.OcrStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OcrResultAdapter(
    private val items: MutableList<OcrItem>,
    private val onDeleteItem: (OcrItem) -> Unit
) : RecyclerView.Adapter<OcrResultAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    inner class ViewHolder(val binding: ItemOcrResultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOcrResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        holder.binding.tvItemTitle.text = item.title

        // Format metadata
        val timeStr = dateFormat.format(Date(item.timestamp))
        val metaStr = when (item.status) {
            OcrStatus.PROCESSING -> context.getString(R.string.status_recognizing)
            OcrStatus.EMPTY -> context.getString(R.string.status_empty)
            OcrStatus.ERROR -> "${context.getString(R.string.status_error)}: ${item.errorMessage ?: ""}"
            OcrStatus.SUCCESS, OcrStatus.PENDING -> "$timeStr · 共 ${item.charCount} 字符"
        }
        holder.binding.tvItemMeta.text = metaStr

        // Progress indicator
        holder.binding.progressBarItem.visibility =
            if (item.status == OcrStatus.PROCESSING) View.VISIBLE else View.GONE

        // Recognized text content
        holder.binding.etRecognizedText.setText(item.recognizedText)
        holder.binding.etRecognizedText.isFocusable = true
        holder.binding.etRecognizedText.isFocusableInTouchMode = true
        holder.binding.etRecognizedText.setTextIsSelectable(true)

        // Thumbnail
        if (item.sourceBitmap != null) {
            holder.binding.ivThumbnail.setImageBitmap(item.sourceBitmap)
        } else if (item.sourceUri != null) {
            holder.binding.ivThumbnail.setImageURI(item.sourceUri)
        } else {
            holder.binding.ivThumbnail.setImageResource(R.drawable.ic_images)
        }

        // Copy button
        holder.binding.btnCopyItem.setOnClickListener {
            val textToCopy = holder.binding.etRecognizedText.text.toString().trim()
            if (textToCopy.isNotEmpty()) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("SnapOCR Text", textToCopy)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, R.string.toast_copied, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.toast_nothing_to_copy, Toast.LENGTH_SHORT).show()
            }
        }

        // Delete button
        holder.binding.btnDeleteItem.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                onDeleteItem(item)
            }
        }
    }

    fun addItem(item: OcrItem) {
        items.add(0, item)
        notifyItemInserted(0)
    }

    fun updateItem(item: OcrItem) {
        val index = items.indexOfFirst { it.id == item.id }
        if (index != -1) {
            notifyItemChanged(index)
        }
    }

    fun removeItem(item: OcrItem) {
        val index = items.indexOfFirst { it.id == item.id }
        if (index != -1) {
            items.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    fun clearAll() {
        val count = items.size
        items.clear()
        notifyItemRangeRemoved(0, count)
    }

    fun getAllText(): String {
        return items
            .filter { it.recognizedText.isNotBlank() }
            .joinToString(separator = "\n\n━━━━━━━━━━━━━━━━━━━━\n\n") { item ->
                "【${item.title}】\n${item.recognizedText}"
            }
    }
}
