package com.guny90.reader

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.Window
import android.view.WindowManager

/**
 * A picture on its own, as large as the screen allows. Gray, no animation; a tap closes it.
 */
class PictureViewer(activity: Activity, private val bitmap: Bitmap, onDismiss: () -> Unit) {
    // Not a "fullscreen" theme: hiding the status bar would resize the reader behind it and re-paginate.
    private val dialog = Dialog(activity)

    init {
        val view = object : View(activity) {
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.WHITE)
                val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
                val w = bitmap.width * scale
                val h = bitmap.height * scale
                val left = (width - w) / 2
                val top = (height - h) / 2
                canvas.drawBitmap(bitmap, null, RectF(left, top, left + w, top + h), PictureSpan.picturePaint)
            }
        }
        view.setOnClickListener { dialog.dismiss() }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view, android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        dialog.setOnDismissListener { onDismiss() }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.WHITE)) // also removes the default dialog margins
            setWindowAnimations(0)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(MATCH_PARENT, MATCH_PARENT)
        }
    }

    fun show() = dialog.show()
}
