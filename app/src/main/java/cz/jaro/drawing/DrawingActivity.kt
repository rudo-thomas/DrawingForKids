package cz.jaro.drawing

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationManagerCompat
import android.support.v4.content.LocalBroadcastManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import cz.jaro.drawing.DrawingActivity.Companion.vectorInRadToStringInDeg
import kotlinx.android.synthetic.main.activity_drawing.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.PI

/**
 * An activity that prevents interaction with outside of the app. Specifically:
 * - Fullscreen activity (sticky immersive mode), single instance
 * - Contain only the drawing view
 * - Prevent screen rotation
 * - Keep screen on (but user can turn it off by pressing power button)
 * - If the screen is turned off by pressing the power button, then 1. turn the screen on (unreliable based on testing) and 2. don't require password/PIN (reliable after pressing the power button again)
 * - Prevent all keys/buttons, including Volume Up/Down, Back, Recent Apps
 * - Clear the image if the orientation changes by more than 70 degrees (and back) in last 3 seconds
 * - A notification exists during the life of the activity - for quitting the app
 * - Bring the app to front regularly every 3 seconds. Useful when the user presses the Home key (on the navigation bar).
 * - Save the image on quit and before it is cleared
 *
 * The activity can be quit (only) byt the following
 * - 1. Pull down the status bar (needs two swipes as the app is in fullscreen sticky immersive mode), 2. press the Quit action in the notification
 * - 1. Press Home key, 2. press Recent Apps key, 3. swipe the app
 *
 * What is not prevented:
 * - The status bar and navigation bar cannot be removed. The interaction is minimized by sticky immersive mode and blocking the Apps and Back (not Home)
 *   buttons in navigation bar.
 * - Power button. This includes both short press (to turn off the screen) and long press with menu to power off the phone.
 */
class DrawingActivity : Activity() {

    private val tag = DrawingActivity::class.java.name

    private var alarmManager: AlarmManager? = null
    private lateinit var keeperIntent: PendingIntent

    private lateinit var keyguardLock: KeyguardManager.KeyguardLock

    private lateinit var sensorManager: SensorManager
    private var sensor: Sensor? = null
    val sensorRecords: MutableList<OrientationRecord> = ArrayList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_drawing)

        // Keep the screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Start components

        // Disable keyguard - to NOT require password on lockscreen (and generally omit the lockscreen)
        disableKeyguard()

        // Listen for local intents
        val filter = IntentFilter(ACTION_QUIT)
        filter.addAction(ACTION_KEEP)
        val localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager.registerReceiver(receiver, filter)

        // Create notification
        createNotification()

        // Start keeper
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        registerKeeper()

        // Start orientation sensor
        startOrientationSensor()
    }

    override fun onResume() {
        super.onResume()

        // Fullscreen - sticky immersive mode
        // This is intentionally here to prevent status bar from appearing in certain situations.
        // Specifically without this, the status bar would appear after 1. leaving and returning to the app (but this could be solved by entering the immersive
        // mode again in onWindowFocusChanged() ) and 2. after pressing power button (to turn off the screen) and pressing the power button again (to return to
        // the app, possibly with unlocking) the status bar was visible.
        window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }

    override fun onNewIntent(intent: Intent?) {
        Log.v(tag, "onNewIntent() action=${intent?.action}")

        if (intent != null) {
            when (intent.action) {
                DrawingActivity.ACTION_KEEP -> {
                    // Try to dismiss keyguard (if the device is locked).
                    // Note: Works if keyguard is not secure or the device is currently in a trusted state.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // TODO This Block is untested
                        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                        keyguardManager.requestDismissKeyguard(this, null)
                    }

                    // Register keeper for next period
                    registerKeeper()
                }
                else -> {
                    super.onNewIntent(intent)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()

        // This immediately returns to the app after pressing the Recent Apps key
        // However, this method is also called when 1. the Home key is pressed or when 2. system alarm is registered (for keeper). In these instances the following code has no effect.
        val activityManager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.moveTaskToFront(taskId, 0)
    }

    override fun onDestroy() {
        super.onDestroy()

        saveDrawing()

        // Stop components (in reverse order compared to onCreate() )

        stopOrientationSensor()

        cancelKeeper()

        cancelNotification()

        val localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager.unregisterReceiver(receiver)

        enableKeyguard()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Block all keys, including keyCode = KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN
        Log.i(tag, "Blocked key ${keyCodeToString(keyCode)} (keyCode=$keyCode)")
        return true
    }

    private fun keyCodeToString(action: Int): String {
        return when (action) {
            KeyEvent.KEYCODE_BACK -> "Back"
            KeyEvent.KEYCODE_VOLUME_UP -> "Volume up"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "Volume down"
            else -> "?"
        }
    }

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.v(tag, "onReceive() action=${intent.action}")

            when (intent.action) {
                ACTION_QUIT -> {
                    Log.i(tag, "Quiting")
                    finish()
                }
                // ACTION_KEEP was handled in PublicReceiver and onNewIntent(Intent?)
                else -> {
                    throw IllegalArgumentException("Unexpected argument ${intent.action}")
                }
            }
        }
    }

    fun saveDrawing() {
        // Get the bitmap
        val bitmap = canvas.bitmap
        if (bitmap == null) {
            Log.e(tag, "Cannot save image because bitmap is null")
            return
        }

        // Construct the file name
        // Inspired by https://github.com/aosp-mirror/platform_frameworks_base/blob/master/packages/SystemUI/src/com/android/systemui/screenshot/GlobalScreenshot.java#L138
        val imageTime = System.currentTimeMillis()
        val imageDate = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(imageTime))
        val imageFileName = String.format(DRAWING_FILE_NAME_TEMPLATE, imageDate)

        // Save to external storage
        if (isExternalStorageWritable()) {
            val picturesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), DRAWING_DIR_NAME)

            // Create the directory (relevant only the first time)
            if (!picturesDir.exists()) {
                picturesDir.mkdirs()
            }

            val imageFilePath = File(picturesDir, imageFileName).getAbsolutePath()

            // Save bitmap to file
            try {
                FileOutputStream(imageFilePath).use({ out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                })
                Log.i(tag, "Image saved to $imageFilePath")
            } catch (e: IOException) {
                Log.w(tag, "Cannot save image to $imageFilePath", e)
            }
        } else {
            Log.e(tag, "Cannot save image because the external storage is not writable")
        }
    }

    fun isExternalStorageWritable(): Boolean {
        val state = Environment.getExternalStorageState()
        return if (Environment.MEDIA_MOUNTED == state) {
            true
        } else false
    }

    /*
     * Notification - allows quiting the app
     * =====================================
     */

    @SuppressLint("PrivateResource")
    private fun createNotification() {
        createNotificationChannel()

        val intent = Intent(this, DrawingActivity::class.java)
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, 0)

        val quitIntent = Intent(this, PublicReceiver::class.java).apply {
            action = ACTION_QUIT
        }
        val quitPendingIntent: PendingIntent = PendingIntent.getBroadcast(this, 0, quitIntent, 0)

        val mBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.abc_ic_clear_material, getString(R.string.notification_main_action_quit), quitPendingIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mBuilder.setContentTitle(getString(R.string.notification_main_text))
        } else {
            mBuilder.setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.notification_main_text))
        }

        // TODO Disable sound when the notification appears

        with(NotificationManagerCompat.from(this)) {
            notify(NOTIFICATION_MAIN_ID, mBuilder.build())
        }
    }

    private fun cancelNotification() {
        val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_MAIN_ID)
    }

    private fun createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.channel_name)
            val descriptionText = getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /*
     * Keeper - brings the app to front
     * ================================
     */

    private fun registerKeeper() {
        val now = GregorianCalendar()
        val target = now.clone() as Calendar
        target.add(Calendar.SECOND, KEEPER_INTERVAL_SEC)

        val context = this
        keeperIntent = Intent(context, PublicReceiver::class.java).let { intent ->
            intent.action = ACTION_KEEP
            PendingIntent.getBroadcast(context, 0, intent, 0)
        }

        if (alarmManager != null) {
            Log.d(tag, "Registering keeper")
            setSystemAlarm(alarmManager!!, target, keeperIntent)
        } else {
            Log.w(tag, "Cannot register keeper")
        }
    }

    private fun cancelKeeper() {
        Log.i(tag, "Cancelling keeper")
        keeperIntent.cancel()
    }

    /**
     * Register system alarm that works reliably - triggers on a specific time, regardless the Android version, and whether the device is asleep (in low-power
     * idle mode).
     *
     * @param alarmManager AlarmManager
     * @param time         Alarm time
     * @param intent       Intent to run on alarm time
     */
    private fun setSystemAlarm(alarmManager: AlarmManager, time: Calendar, intent: PendingIntent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, time.timeInMillis, intent)
        } else if (Build.VERSION_CODES.KITKAT <= Build.VERSION.SDK_INT && Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, time.timeInMillis, intent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time.timeInMillis, intent)
        }
    }

    /*
     * Disable keyguard - to NOT require password on lockscreen (and generally omit the lockscreen)
     * ============================================================================================
     */

    private fun disableKeyguard() {
        // TODO Requirement: When the power button is pressed (and screen turns off), then turn on the screen (without the keyguard). However, testing on Samsung Galaxy S7 shows that this works only once; afterwards the screen must be turned on by pressing the power button.

        // Make sure the activity is visible after the screen is turned on when the lockscreen is up. Also required to turn screen on when
        // KeyguardManager.requestDismissKeyguard(...) is called.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // TODO This block is untested
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        } else {
            window.addFlags(LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            window.addFlags(LayoutParams.FLAG_TURN_SCREEN_ON)
        }

        // Disable keyguard
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        keyguardLock = keyguardManager.newKeyguardLock(Context.KEYGUARD_SERVICE)
        keyguardLock.disableKeyguard()
    }

    private fun enableKeyguard() {
        keyguardLock.reenableKeyguard()
        // Note that the user may need to enter password/PIN after exiting from the app
    }

    /*
     * Orientation sensor - to clear the drawing
     * =========================================
     */

    private fun startOrientationSensor() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
//        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (sensor == null) {
            Log.w(tag, "Cannot monitor orientation changes (no gyroscope sensor)")
            // TODO Consider adding the "Clear" action to the notification (the only way o clear the canvas is to quit the app and start it again)
            return
        }

        sensorManager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun stopOrientationSensor() {
        if (sensor == null)
            return

        sensorManager.unregisterListener(sensorListener)
    }

    // Create listener
    private val sensorListener: SensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(sensorEvent: SensorEvent) {
            if (canBeTrusted(sensorEvent.accuracy)) { // If the sensor is accurate
                addEvent(sensorEvent)

                if (gesturePerformed()) {
                    Log.i(tag, "Cleaning the image")

                    saveDrawing()

                    // Clear the canvas
                    canvas.clear()

                    // Remove all the sensor values
                    sensorRecords.clear()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, i: Int) {
            // Do nothing
        }
    }

    private fun canBeTrusted(accuracy: Int): Boolean {
        return accuracy == SensorManager.SENSOR_STATUS_ACCURACY_HIGH ||
                accuracy == SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ||
                accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
    }

    private fun rotationSensorValuesToOrientations(values: FloatArray): FloatArray {
        // The rotation vector sensor combines raw data generated by the gyroscope, accelerometer, and magnetometer to create a quaternion (the values parameter)

        // Convert the quaternion into a rotation matrix (a 4x4 matrix)
        val rotationMatrix = FloatArray(16)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)

        // Remap coordinate system
        val remappedRotationMatrix = FloatArray(16)
        SensorManager.remapCoordinateSystem(rotationMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_Z,
                remappedRotationMatrix)

        // Convert to orientations (in radians)
        val orientations = FloatArray(3)
        SensorManager.getOrientation(remappedRotationMatrix, orientations)

        // Convert to orientations in degrees
//        val orientationsDeg = FloatArray(3)
//        for (i in 0..2) {
//            orientationsDeg[i] = Math.toDegrees(orientations[i].toDouble()).toFloat()
//        }

        return orientations
    }

    private fun addEvent(sensorEvent: SensorEvent): FloatArray {
        // Add the event to the list
        val timestamp = sensorEvent.timestamp
        val orientations = rotationSensorValuesToOrientations(sensorEvent.values)
        val record = OrientationRecord(timestamp, orientations)

        sensorRecords.add(record)

        // Remove old records from the list
        var firstRecent = 0
        while (firstRecent < sensorRecords.size &&
                SENSOR_HISTORY_NANOSEC < timestamp - sensorRecords[firstRecent].timestamp) {
            firstRecent++
        }
        // TODO ArrayList is not effective at removing first N elements. Choose another data structure. Also, we can discard subsequent record that are close.
        val subListToRemove = sensorRecords.subList(0, firstRecent)
        subListToRemove.clear()

        return orientations
    }

    /**
     * Detect the intended orientation change.
     * The intended orientation change is defined by: rotating the device substantially away and back to the original position, in a short period of time.
     */
    fun gesturePerformed(): Boolean {
        /*
        Algorithm:
        We go back and compare the current orientation with the past.
        First, we are looking for an orientation that is different by more than SENSOR_ANGLE_OUT_RAD from the current orientation.
        Second, we are looking for an orientation that is different by less than SENSOR_ANGLE_NEAR_RAD from the current orientation.
        */
        val orientations1 = sensorRecords[sensorRecords.size - 1].orientations

        var state = 1
        loop@ for (i in sensorRecords.size - 2 downTo 0) {
            val orientations2 = sensorRecords[i].orientations
            val angle =
                    Math.sqrt(
                            Math.pow(orientations1[0].toDouble() - orientations2[0], 2.0) +
                                    Math.pow(orientations1[1].toDouble() - orientations2[1], 2.0) +
                                    Math.pow(orientations1[2].toDouble() - orientations2[2], 2.0)
                    )

            when (state) {
                1 -> {
                    if (SENSOR_ANGLE_OUT_RAD < angle) {
                        state++
                    }
                }
                2 -> {
                    if (angle < SENSOR_ANGLE_NEAR_RAD) {
                        state++
                        break@loop
                    }
                }
            }
        }

        return state == 3
    }

    /**
     * Get the angle difference between two vectors.
     *
     * @return The angle between vectors in radians
     */
    private fun angleBetweenVectors(a: FloatArray, b: FloatArray): Float {
        // Source: https://math.stackexchange.com/a/2254379
        val innerProduct = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val normA = Math.sqrt((a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).toDouble())
        val normB = Math.sqrt((b[0] * b[0] + b[1] * b[1] + b[2] * b[2]).toDouble())

        return Math.acos(innerProduct / (normA * normB)).toFloat()
    }

    companion object {
        const val CHANNEL_ID = "MAIN_NOTIFICATION"
        const val NOTIFICATION_MAIN_ID = 0
        const val KEEPER_INTERVAL_SEC = 3

        const val SENSOR_HISTORY_NANOSEC = 3 * 1e9
        const val SENSOR_ANGLE_OUT_RAD = 70 / 180f * PI//Math.toRadians(SENSOR_ANGLE_MAX_DEG.toDouble())
        const val SENSOR_ANGLE_NEAR_RAD = 20 / 180f * PI

        const val ACTION_QUIT = "ACTION_QUIT"
        const val ACTION_KEEP = "ACTION_KEEP"

        const val DRAWING_DIR_NAME = "DrawingForKids"
        const val DRAWING_FILE_NAME_TEMPLATE = "%s.png"

        fun vectorInRadToStringInDeg(v: FloatArray): String {
            return "[${Math.round(Math.toDegrees(v[0].toDouble()))}, ${Math.round(Math.toDegrees(v[1].toDouble()))}, ${Math.round(Math.toDegrees(v[2].toDouble()))}]"
        }
    }

}

class OrientationRecord(val timestamp: Long, val orientations: FloatArray) {
    override fun toString(): String {
        return vectorInRadToStringInDeg(orientations) + " @${timestamp}"
    }
}