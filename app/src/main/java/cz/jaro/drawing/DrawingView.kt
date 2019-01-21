package cz.jaro.drawing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.widget.ImageView


class DrawingView(context: Context, attrs: AttributeSet) : ImageView(context, attrs) {

    private val tag = DrawingView::class.java.name

    private var bitmap: Bitmap? = null
    private val canvas: Canvas = Canvas()

    private val curves: MutableMap<Int, MyCurve> = HashMap() // Key is pointerId

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        bitmap = if (bitmap == null)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        else
            Bitmap.createScaledBitmap(bitmap, w, h, false)

        setImageBitmap(bitmap)
        canvas.setBitmap(bitmap)
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

        // Draw open curves
        for (curve: MyCurve in curves.values)
            curve.draw(canvas)
    }

    fun clear() {
        if (bitmap != null) {
            bitmap!!.eraseColor(Color.TRANSPARENT)
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