package com.snapocr.app.model

import android.graphics.Bitmap
import android.net.Uri

enum class OcrStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    EMPTY,
    ERROR
}

data class OcrItem(
    val id: String = System.currentTimeMillis().toString() + "_" + (1000..9999).random(),
    val title: String,
    val sourceUri: Uri? = null,
    val sourceBitmap: Bitmap? = null,
    var recognizedText: String = "",
    var status: OcrStatus = OcrStatus.PENDING,
    var errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val charCount: Int
        get() = recognizedText.trim().length
}
