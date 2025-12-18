package com.goodwy.commons.views

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Detects two-finger slide/swipe gestures.
 * Similar to ScaleGestureDetector but for detecting two-finger sliding gestures.
 */
class TwoFingerSlideGestureDetector(
    context: Context,
    private val listener: OnTwoFingerSlideGestureListener
) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val swipeThreshold = touchSlop * 2 // Minimum distance for a swipe
    
    private var firstFingerDownX = 0f
    private var firstFingerDownY = 0f
    private var secondFingerDownX = 0f
    private var secondFingerDownY = 0f
    
    private var isGestureInProgress = false
    private var gestureDetected = false
    
    /**
     * Process a motion event and detect two-finger slide gestures.
     * Returns true if the event was consumed, false otherwise.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // First finger down
                firstFingerDownX = event.x
                firstFingerDownY = event.y
                isGestureInProgress = false
                gestureDetected = false
            }
            
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger down
                if (event.pointerCount == 2) {
                    secondFingerDownX = event.getX(1)
                    secondFingerDownY = event.getY(1)
                    isGestureInProgress = true
                    gestureDetected = false
                }
            }
            
            MotionEvent.ACTION_MOVE -> {
                // Check if we have two fingers and detect slide
                if (event.pointerCount == 2 && isGestureInProgress && !gestureDetected) {
                    val firstFingerX = event.getX(0)
                    val firstFingerY = event.getY(0)
                    val secondFingerX = event.getX(1)
                    val secondFingerY = event.getY(1)
                    
                    // Calculate movement for both fingers
                    val firstFingerDeltaX = firstFingerX - firstFingerDownX
                    val firstFingerDeltaY = firstFingerY - firstFingerDownY
                    val secondFingerDeltaX = secondFingerX - secondFingerDownX
                    val secondFingerDeltaY = secondFingerY - secondFingerDownY
                    
                    // Calculate distances moved
                    val firstFingerDistance = sqrt(
                        (firstFingerDeltaX * firstFingerDeltaX + firstFingerDeltaY * firstFingerDeltaY).toDouble()
                    ).toFloat()
                    val secondFingerDistance = sqrt(
                        (secondFingerDeltaX * secondFingerDeltaX + secondFingerDeltaY * secondFingerDeltaY).toDouble()
                    ).toFloat()
                    
                    // Both fingers must move at least the threshold distance
                    if (firstFingerDistance > swipeThreshold && secondFingerDistance > swipeThreshold) {
                        // Check if both fingers are moving in roughly the same direction
                        val firstFingerDirection = atan2(firstFingerDeltaY, firstFingerDeltaX)
                        val secondFingerDirection = atan2(secondFingerDeltaY, secondFingerDeltaX)
                        val directionDiff = abs(firstFingerDirection - secondFingerDirection)
                        
                        // Allow up to 45 degrees difference in direction (approximately same direction)
                        val maxDirectionDiff = Math.PI / 4
                        if (directionDiff < maxDirectionDiff || directionDiff > 7 * Math.PI / 4) {
                            gestureDetected = true
                            val avgDeltaX = (firstFingerDeltaX + secondFingerDeltaX) / 2f
                            val avgDeltaY = (firstFingerDeltaY + secondFingerDeltaY) / 2f
                            val avgDistance = (firstFingerDistance + secondFingerDistance) / 2f
                            
                            listener.onTwoFingerSlide(
                                firstFingerX, firstFingerY,
                                secondFingerX, secondFingerY,
                                avgDeltaX, avgDeltaY,
                                avgDistance
                            )
                            return true
                        }
                    }
                }
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                // Reset on finger up
                if (event.pointerCount <= 1) {
                    isGestureInProgress = false
                    gestureDetected = false
                }
            }
        }
        return false
    }
    
    /**
     * Listener interface for two-finger slide gestures.
     */
    interface OnTwoFingerSlideGestureListener {
        /**
         * Called when a two-finger slide gesture is detected.
         * 
         * @param firstFingerX Current X position of first finger
         * @param firstFingerY Current Y position of first finger
         * @param secondFingerX Current X position of second finger
         * @param secondFingerY Current Y position of second finger
         * @param avgDeltaX Average X movement of both fingers
         * @param avgDeltaY Average Y movement of both fingers
         * @param avgDistance Average distance moved by both fingers
         */
        fun onTwoFingerSlide(
            firstFingerX: Float,
            firstFingerY: Float,
            secondFingerX: Float,
            secondFingerY: Float,
            avgDeltaX: Float,
            avgDeltaY: Float,
            avgDistance: Float
        )
    }
}

