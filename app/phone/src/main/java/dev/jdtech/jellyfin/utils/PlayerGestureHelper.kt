package dev.jdtech.jellyfin.utils

import android.annotation.SuppressLint
import android.content.res.Resources
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.jdtech.jellyfin.AppPreferences
import dev.jdtech.jellyfin.Constants
import dev.jdtech.jellyfin.PlayerActivity
import dev.jdtech.jellyfin.mpv.MPVPlayer
import kotlin.math.abs
import timber.log.Timber

class PlayerGestureHelper(
    private val appPreferences: AppPreferences,
    private val activity: PlayerActivity,
    private val playerView: PlayerView,
    private val audioManager: AudioManager
) {
    /**
     * Tracks whether video content should fill the screen, cutting off unwanted content on the sides.
     * Useful on wide-screen phones to remove black bars from some movies.
     */
    private var isZoomEnabled = false

    /**
     * Tracks a value during a swipe gesture (between multiple onScroll calls).
     * When the gesture starts it's reset to an initial value and gets increased or decreased
     * (depending on the direction) as the gesture progresses.
     */

    private var swipeGestureValueTrackerVolume = -1f
    private var swipeGestureValueTrackerBrightness = -1f
    private var swipeGestureValueTrackerProgress = -1L

    private var swipeGestureVolumeOpen = false
    private var swipeGestureBrightnessOpen = false
    private var swipeGestureProgressOpen = false

    private var lastScaleEvent: Long = 0

    private val screenWidth = Resources.getSystem().displayMetrics.widthPixels
    private val screenHeight = Resources.getSystem().displayMetrics.heightPixels

    private val tapGestureDetector = GestureDetector(
        playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                playerView.apply {
                    if (!isControllerFullyVisible) showController() else hideController()
                }

                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val viewCenterX = playerView.measuredWidth / 2
                val currentPos = playerView.player?.currentPosition ?: 0

                if (e.x.toInt() > viewCenterX) {
                    playerView.player?.seekTo(currentPos + appPreferences.playerSeekForwardIncrement)
                } else {
                    playerView.player?.seekTo((currentPos - appPreferences.playerSeekBackIncrement).coerceAtLeast(0))
                }
                return true
            }
        }
    )

    private val seekGestureDetector = GestureDetector(
        playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            @SuppressLint("SetTextI18n")
            override fun onScroll(
                firstEvent: MotionEvent,
                currentEvent: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // Excludes area where app gestures conflicting with system gestures
                if (inExclusionArea(firstEvent)) return false

                // Check whether swipe was oriented vertically
                if (abs(distanceY / distanceX) < 2) {
                    return if ((abs(currentEvent.x - firstEvent.x) > 50 || swipeGestureProgressOpen) &&
                        !swipeGestureBrightnessOpen &&
                        !swipeGestureVolumeOpen &&
                        (SystemClock.elapsedRealtime() - lastScaleEvent) > 200
                    ) {
                        val currentPos = playerView.player?.currentPosition ?: 0
                        val vidDuration = (playerView.player?.duration ?: 0).coerceAtLeast(0)

                        val difference = ((currentEvent.x - firstEvent.x) * 90).toLong()
                        val newPos = (currentPos + difference).coerceIn(0, vidDuration)

                        activity.binding.progressScrubberLayout.visibility = View.VISIBLE
                        activity.binding.progressScrubberText.text = "${longToTimestamp(difference)} [${longToTimestamp(newPos, true)}]"
                        swipeGestureValueTrackerProgress = newPos
                        swipeGestureProgressOpen = true
                        true
                    } else false
                }
                return true
            }
        }
    )

    private val vbGestureDetector = GestureDetector(
        playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            @SuppressLint("SetTextI18n")
            override fun onScroll(
                firstEvent: MotionEvent,
                currentEvent: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // Excludes area where app gestures conflicting with system gestures
                if (inExclusionArea(firstEvent)) return false

                if (abs(distanceY / distanceX) < 2) return false

                if (swipeGestureValueTrackerProgress > -1 || swipeGestureProgressOpen)
                    return false

                val viewCenterX = playerView.measuredWidth / 2

                // Distance to swipe to go from min to max
                val distanceFull = playerView.measuredHeight * Constants.FULL_SWIPE_RANGE_SCREEN_RATIO
                val ratioChange = distanceY / distanceFull

                if (firstEvent.x.toInt() > viewCenterX) {
                    // Swiping on the right, change volume

                    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (swipeGestureValueTrackerVolume == -1f) swipeGestureValueTrackerVolume = currentVolume.toFloat()

                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val change = ratioChange * maxVolume
                    swipeGestureValueTrackerVolume += change

                    val toSet = swipeGestureValueTrackerVolume.toInt().coerceIn(0, maxVolume)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, toSet, 0)

                    activity.binding.gestureVolumeLayout.visibility = View.VISIBLE
                    activity.binding.gestureVolumeProgressBar.max = maxVolume
                    activity.binding.gestureVolumeProgressBar.progress = toSet
                    activity.binding.gestureVolumeText.text = "${(toSet.toFloat() / maxVolume.toFloat()).times(100).toInt()}%"

                    swipeGestureVolumeOpen = true
                } else {
                    // Swiping on the left, change brightness
                    val window = activity.window
                    val brightnessRange = BRIGHTNESS_OVERRIDE_OFF..BRIGHTNESS_OVERRIDE_FULL

                    // Initialize on first swipe
                    if (swipeGestureValueTrackerBrightness == -1f) {
                        val brightness = window.attributes.screenBrightness
                        Timber.d("Brightness ${Settings.System.getFloat(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)}")
                        swipeGestureValueTrackerBrightness = when (brightness) {
                            in brightnessRange -> brightness
                            else -> Settings.System.getFloat(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255
                        }
                    }
                    swipeGestureValueTrackerBrightness = (swipeGestureValueTrackerBrightness + ratioChange).coerceIn(brightnessRange)
                    val lp = window.attributes
                    lp.screenBrightness = swipeGestureValueTrackerBrightness
                    window.attributes = lp

                    activity.binding.gestureBrightnessLayout.visibility = View.VISIBLE
                    activity.binding.gestureBrightnessProgressBar.max = BRIGHTNESS_OVERRIDE_FULL.times(100).toInt()
                    activity.binding.gestureBrightnessProgressBar.progress = lp.screenBrightness.times(100).toInt()
                    activity.binding.gestureBrightnessText.text = "${(lp.screenBrightness / BRIGHTNESS_OVERRIDE_FULL).times(100).toInt()}%"

                    swipeGestureBrightnessOpen = true
                }
                return true
            }
        }
    )

    private val hideGestureVolumeIndicatorOverlayAction = Runnable {
        activity.binding.gestureVolumeLayout.visibility = View.GONE
    }

    private val hideGestureBrightnessIndicatorOverlayAction = Runnable {
        activity.binding.gestureBrightnessLayout.visibility = View.GONE
        if (appPreferences.playerBrightnessRemember) {
            appPreferences.playerBrightness = activity.window.attributes.screenBrightness
        }
    }

    private val hideGestureProgressOverlayAction = Runnable {
        activity.binding.progressScrubberLayout.visibility = View.GONE
    }

    /**
     * Handles scale/zoom gesture
     */
    private val zoomGestureDetector = ScaleGestureDetector(
        playerView.context,
        object : ScaleGestureDetector.OnScaleGestureListener {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                lastScaleEvent = SystemClock.elapsedRealtime()
                val scaleFactor = detector.scaleFactor
                if (abs(scaleFactor - Constants.ZOOM_SCALE_BASE) > Constants.ZOOM_SCALE_THRESHOLD) {
                    isZoomEnabled = scaleFactor > 1
                    updateZoomMode(isZoomEnabled)
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) = Unit
        }
    ).apply { isQuickScaleEnabled = false }

    private fun updateZoomMode(enabled: Boolean) {
        if (playerView.player is MPVPlayer) {
            (playerView.player as MPVPlayer).updateZoomMode(enabled)
        } else {
            playerView.resizeMode = if (enabled) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun releaseAction(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_UP) {
            activity.binding.gestureVolumeLayout.apply {
                if (visibility == View.VISIBLE) {
                    removeCallbacks(hideGestureVolumeIndicatorOverlayAction)
                    postDelayed(hideGestureVolumeIndicatorOverlayAction, 1000)
                    swipeGestureVolumeOpen = false
                }
            }
            activity.binding.gestureBrightnessLayout.apply {
                if (visibility == View.VISIBLE) {
                    removeCallbacks(hideGestureBrightnessIndicatorOverlayAction)
                    postDelayed(hideGestureBrightnessIndicatorOverlayAction, 1000)
                    swipeGestureBrightnessOpen = false
                }
            }
            activity.binding.progressScrubberLayout.apply {
                if (visibility == View.VISIBLE) {
                    if (swipeGestureValueTrackerProgress > -1) {
                        playerView.player?.seekTo(swipeGestureValueTrackerProgress)
                    }
                    removeCallbacks(hideGestureProgressOverlayAction)
                    postDelayed(hideGestureProgressOverlayAction, 1000)
                    swipeGestureProgressOpen = false

                    swipeGestureValueTrackerProgress = -1L
                }
            }
        }
    }

    private fun longToTimestamp(duration: Long, noSign: Boolean = false): String {
        val sign = if (noSign) "" else if (duration < 0) "-" else "+"
        val seconds = abs(duration).div(1000)

        return String.format("%s%02d:%02d:%02d", sign, seconds / 3600, (seconds / 60) % 60, seconds % 60)
    }

    /**
     * Check if [firstEvent] is in the gesture exclusion area
     */
    private fun inExclusionArea(firstEvent: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = playerView.rootWindowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemGestures())

            if ((firstEvent.x < insets.left) || (firstEvent.x > (screenWidth - insets.right)) ||
                (firstEvent.y < insets.top) || (firstEvent.y > (screenHeight - insets.bottom))
            )
                return true
        } else if (firstEvent.y < playerView.resources.dip(Constants.GESTURE_EXCLUSION_AREA_VERTICAL) ||
            firstEvent.y > screenHeight - playerView.resources.dip(Constants.GESTURE_EXCLUSION_AREA_VERTICAL) ||
            firstEvent.x < playerView.resources.dip(Constants.GESTURE_EXCLUSION_AREA_HORIZONTAL) ||
            firstEvent.x > screenWidth - playerView.resources.dip(Constants.GESTURE_EXCLUSION_AREA_HORIZONTAL)
        )
            return true
        return false
    }

    init {
        if (appPreferences.playerBrightnessRemember) {
            activity.window.attributes.screenBrightness = appPreferences.playerBrightness
        }

        @Suppress("ClickableViewAccessibility")
        playerView.setOnTouchListener { _, event ->
            if (playerView.useController) {
                when (event.pointerCount) {
                    1 -> {
                        tapGestureDetector.onTouchEvent(event)
                        if (appPreferences.playerGesturesVB) vbGestureDetector.onTouchEvent(event)
                        if (appPreferences.playerGesturesSeek) seekGestureDetector.onTouchEvent(event)
                    }
                    2 -> {
                        if (appPreferences.playerGesturesZoom) zoomGestureDetector.onTouchEvent(event)
                    }
                }
            }
            releaseAction(event)
            true
        }
    }
}
