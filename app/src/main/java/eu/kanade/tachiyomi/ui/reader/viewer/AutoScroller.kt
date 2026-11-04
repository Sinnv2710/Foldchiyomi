package eu.kanade.tachiyomi.ui.reader.viewer

import android.view.Choreographer

/**
 * Steady, frame-driven scrolling for the reader's continuous viewers.
 *
 * Every frame it asks [step] to move the content by the distance [speedDpPerSecond] covers in the
 * time since the last frame, so the speed stays the same whatever the refresh rate - 120 Hz on a
 * Z Fold included. [step] gets whole pixels and returns whether the content actually moved; once
 * it hasn't for [STALL_FRAMES] frames the end has been reached and the scroller stops itself,
 * calling [onStopped].
 */
class AutoScroller(
    private val density: Float,
    private val speedDpPerSecond: () -> Float,
    private val step: (pixels: Int) -> Boolean,
    private val onStopped: () -> Unit,
) {

    private val choreographer = Choreographer.getInstance()

    var isRunning = false
        private set

    private var lastFrameNanos = 0L
    private var carry = 0f
    private var stalledFrames = 0

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) return
            // The first frame has nothing to measure against, and a long gap (a dropped frame, the
            // app paused) must not turn into a jump.
            val elapsed = if (lastFrameNanos == 0L) 0f else (frameTimeNanos - lastFrameNanos) / NANOS_PER_SECOND
            lastFrameNanos = frameTimeNanos

            carry += speedDpPerSecond() * density * elapsed.coerceAtMost(MAX_FRAME_SECONDS)
            val pixels = carry.toInt()
            if (pixels > 0) {
                carry -= pixels
                if (step(pixels)) {
                    stalledFrames = 0
                } else if (++stalledFrames >= STALL_FRAMES) {
                    stop()
                    return
                }
            }
            choreographer.postFrameCallback(this)
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        lastFrameNanos = 0L
        carry = 0f
        stalledFrames = 0
        choreographer.postFrameCallback(callback)
    }

    /** Stops without a callback to the caller when it was already stopped. */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        choreographer.removeFrameCallback(callback)
        onStopped()
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_FRAME_SECONDS = 0.05f
        const val STALL_FRAMES = 30
    }
}
