package cz.jaro.drawing

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View


class DrawingView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private val tag = DrawingView::class.java.name

    internal var bitmap: Bitmap? = null
    private val canvas: Canvas = Canvas()

    private val curves: MutableMap<Int, MyCurve> = HashMap() // Key is pointerId

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val bitmapCreated = bitmap == null

        bitmap = if (bitmapCreated)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        else
            Bitmap.createScaledBitmap(bitmap, w, h, false)

        canvas.setBitmap(bitmap)

        if (bitmapCreated)
            clear()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        Log.v(tag, "onTouchEvent() action=${actionToString(action)} ($action), pointerCount=${event.pointerCount}")

        for (pointerIndex in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(pointerIndex)

            when (action) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val point = PointF(event.getX(pointerIndex), event.getY(pointerIndex))

                    val curve = MyCurve(context)
                    curve.addPoint(point)

                    curves[pointerId] = curve

                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    val point = PointF(event.getX(pointerIndex), event.getY(pointerIndex))

                    val curve = curves[pointerId]
                    if (curve != null) {
                        curve.addPoint(point)

                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_CANCEL -> {
                    val curve = curves[pointerId]
                    if (curve != null) {
                        curve.draw(canvas)

                        curves.remove(pointerId)

                        invalidate()
                    }
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw image (with finished curved)
        if (bitmap != null)
            canvas.drawBitmap(bitmap, 0f, 0f, null)

        // Draw open curves
        for (curve: MyCurve in curves.values)
            curve.draw(canvas)
    }

    fun clear() {
        if (bitmap != null) {
            val whitePaint = Paint()
            whitePaint.setColor(Color.WHITE)
            whitePaint.setStyle(Paint.Style.FILL)
            canvas.drawPaint(whitePaint)

            invalidate()
        }
    }

    /**
     * Given an action int, returns a string description
     *
     * @param action Action id
     * @return String description of the action
     */
    private fun actionToString(action: Int): String {
        return when (action) {
            MotionEvent.ACTION_DOWN -> "Down"
            MotionEvent.ACTION_POINTER_DOWN -> "Pointer Down"
            MotionEvent.ACTION_MOVE -> "Move"
            MotionEvent.ACTION_UP -> "Up"
            MotionEvent.ACTION_POINTER_UP -> "Pointer Up"
            MotionEvent.ACTION_OUTSIDE -> "Outside"
            MotionEvent.ACTION_CANCEL -> "Cancel"
            else -> "?"
        }
    }
}