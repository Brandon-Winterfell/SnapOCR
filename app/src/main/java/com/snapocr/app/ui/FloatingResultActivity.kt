package com.snapocr.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Selection
import android.text.Spannable
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.snapocr.app.MainActivity
import com.snapocr.app.R
import com.snapocr.app.service.FloatingCaptureService

/**
 * Translucent Dialog Activity for Floating Screen Capture OCR results.
 * Being an Activity with a PhoneWindow and Window.Callback, Android's Editor engine
 * natively renders the dual draggable selection handles (water-drop cursors) and
 * floating text action bar (复制/全选) without any Service WindowManager limitations.
 */
class FloatingResultActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_ocr_result)

        // Make window center-aligned with comfortable width and hidden virtual keyboard
        window?.let { win ->
            val displayMetrics = resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.92f).toInt()
            win.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            win.setGravity(Gravity.CENTER)
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }

        // Tap outside the card finishes activity smoothly
        setFinishOnTouchOutside(true)

        val text = intent.getStringExtra(FloatingCaptureService.EXTRA_OCR_TEXT) ?: ""
        initViews(text)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val text = intent?.getStringExtra(FloatingCaptureService.EXTRA_OCR_TEXT) ?: ""
        initViews(text)
    }

    private fun initViews(text: String) {
        val tvDialogText = findViewById<TextView>(R.id.tvDialogText)
        val tvCharCount = findViewById<TextView>(R.id.tvCharCount)
        val tvSelectionStatus = findViewById<TextView>(R.id.tvSelectionStatus)
        val btnSelectAllText = findViewById<Button>(R.id.btnSelectAllText)
        val btnCopySelected = findViewById<Button>(R.id.btnCopySelected)
        val btnDialogCopyAll = findViewById<Button>(R.id.btnDialogCopyAll)
        val btnDialogClose = findViewById<ImageButton>(R.id.btnDialogClose)
        val btnDialogViewInApp = findViewById<Button>(R.id.btnDialogViewInApp)

        tvCharCount?.text = "共 ${text.length} 字"
        tvDialogText?.setText(text, TextView.BufferType.SPANNABLE)
        tvDialogText?.setTextIsSelectable(true)

        fun updateSelectionUI() {
            val start = tvDialogText?.selectionStart ?: -1
            val end = tvDialogText?.selectionEnd ?: -1
            if (start in 0 until end) {
                val len = end - start
                btnCopySelected?.visibility = View.VISIBLE
                btnCopySelected?.text = "复制选中 (${len}字)"
                tvSelectionStatus?.text = "已选 $len 字，可拖动两端手柄调整"
                tvSelectionStatus?.setTextColor(ContextCompat.getColor(this, R.color.primary))
            } else {
                btnCopySelected?.visibility = View.GONE
                tvSelectionStatus?.text = getString(R.string.dialog_tip_selection)
                tvSelectionStatus?.setTextColor(ContextCompat.getColor(this, R.color.text_hint))
            }
        }

        // Quick select all action: activates native selection and shows start/end handles
        btnSelectAllText?.setOnClickListener {
            val spannable = tvDialogText?.text as? Spannable
            if (spannable != null && spannable.isNotEmpty()) {
                Selection.setSelection(spannable, 0, spannable.length)
                tvDialogText?.requestFocus()
                updateSelectionUI()
            }
        }

        // Quick copy currently selected text
        btnCopySelected?.setOnClickListener {
            val start = tvDialogText?.selectionStart ?: -1
            val end = tvDialogText?.selectionEnd ?: -1
            if (start in 0 until end) {
                val clipText = tvDialogText?.text?.substring(start, end) ?: ""
                if (clipText.isNotEmpty()) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("SnapOCR Selected", clipText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, getString(R.string.toast_copied_selection, clipText.length), Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Standard ActionMode for selection floating menu (选中/复制/全选)
        tvDialogText?.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                updateSelectionUI()
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                updateSelectionUI()
                return true
            }

            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                if (item?.itemId == android.R.id.copy) {
                    val start = tvDialogText?.selectionStart ?: -1
                    val end = tvDialogText?.selectionEnd ?: -1
                    if (start in 0 until end) {
                        val clipText = tvDialogText?.text?.substring(start, end) ?: ""
                        if (clipText.isNotEmpty()) {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("SnapOCR Selected", clipText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(this@FloatingResultActivity, getString(R.string.toast_copied_selection, clipText.length), Toast.LENGTH_SHORT).show()
                        }
                    }
                    mode?.finish()
                    return true
                }
                return false
            }

            override fun onDestroyActionMode(mode: ActionMode?) {
                tvDialogText?.postDelayed({ updateSelectionUI() }, 100)
            }
        }

        btnDialogCopyAll?.setOnClickListener {
            val allText = tvDialogText?.text?.toString()?.trim() ?: ""
            if (allText.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("SnapOCR Screen Text", allText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
            }
        }

        btnDialogClose?.setOnClickListener {
            finish()
        }

        btnDialogViewInApp?.setOnClickListener {
            finish()
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(mainIntent)
        }
    }
}
