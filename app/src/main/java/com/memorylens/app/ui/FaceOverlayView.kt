package com.memorylens.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay drawn on top of the PreviewView to render face bounding boxes.
 * Updated via [updateDetections] whenever new MediaPipe results arrive.
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boxPaint = Paint().apply {
        color = Color.parseColor("#FF00E676")   // green for known faces
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val unknownBoxPaint = Paint().apply {
        color = Color.parseColor("#FFFF1744")   // red for unknown faces
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private var detections: List<RectF> = emptyList()
    private var previewWidth: Int = 1
    private var previewHeight: Int = 1
    private var isUnknown: Boolean = false

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
    }

    /**
     * Receive normalised detection rectangles from the face detection stage.
     * [sourceWidth] and [sourceHeight] are the preview view dimensions for scaling.
     */
    fun updateDetections(rects: List<RectF>, sourceWidth: Int, sourceHeight: Int, unknown: Boolean = false) {
        detections = rects
        previewWidth = sourceWidth.coerceAtLeast(1)
        previewHeight = sourceHeight.coerceAtLeast(1)
        isUnknown = unknown
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val paint = if (isUnknown) unknownBoxPaint else boxPaint
        for (rect in detections) {
            // Rect coords are normalised [0,1]; scale to view dimensions
            val scaled = RectF(
                rect.left * width,
                rect.top * height,
                rect.right * width,
                rect.bottom * height
            )
            canvas.drawRoundRect(scaled, 8f, 8f, paint)
        }
    }
}
