package com.phytoy.sample

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/** Display-only transition. The TextureView is resized once by the host, never on animation frames. */
internal class PreviewStyleTransition(context: Context, private val preview: View) : View(context) {
    private var generation = 0
    private var fadeOut: ValueAnimator? = null
    private var frameMotion: ValueAnimator? = null
    private var fadeIn: ValueAnimator? = null
    private var committed = false
    private var frameFinished = false
    private var frameReady = false
    private var motionEnabled = false
    private var fromAspect = 1f
    private var toAspect = 1f
    private var progress = 1f
    private val fromFrame = RectF()
    private val toFrame = RectF()
    private val frame = RectF()
    private val mask = Paint().apply { color = Color.BLACK }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }

    val isRunning: Boolean get() = visibility == VISIBLE
    val isAwaitingFrame: Boolean get() = isRunning && committed && !frameReady

    init {
        id = R.id.preview_style_transition
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    fun begin(oldAspect: Float, newAspect: Float, commit: () -> Unit) {
        cancel()
        val token = generation
        fromAspect = oldAspect
        toAspect = newAspect
        progress = 0f
        // This overlay starts GONE, so it has no layout on the first switch.
        // The visible viewport tells us whether the host is ready to animate.
        motionEnabled = ValueAnimator.areAnimatorsEnabled() && preview.isLaidOut
        Log.i("ToviCamUi", "Style transition begin: $oldAspect -> $newAspect, motion=$motionEnabled")
        visibility = VISIBLE
        invalidate()
        val afterFade = {
            if (token == generation) {
                committed = true
                Log.i("ToviCamUi", "Style transition commit")
                commit()
                if (token == generation) animateFrame(token)
            }
        }
        if (!motionEnabled) {
            preview.alpha = 0f
            afterFade()
        } else {
            fadeOut = ValueAnimator.ofFloat(preview.alpha, 0f).apply {
                duration = 100L
                addUpdateListener { if (token == generation) preview.alpha = it.animatedValue as Float }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (token != generation) return
                        fadeOut = null
                        afterFade()
                    }
                })
                start()
            }
        }
    }

    private fun animateFrame(token: Int) {
        if (!motionEnabled || fromAspect == toAspect) {
            progress = 1f
            frameFinished = true
            invalidate()
            revealIfReady(token)
            return
        }
        frameMotion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                if (token == generation) { progress = it.animatedValue as Float; invalidate() }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token != generation) return
                    frameMotion = null
                    frameFinished = true
                    revealIfReady(token)
                }
            })
            start()
        }
    }

    /** Accepted only after the host has observed a presented frame from the new session. */
    fun onFrameReady() {
        if (!isAwaitingFrame) return
        frameReady = true
        Log.i("ToviCamUi", "Style transition received new presented frame")
        revealIfReady(generation)
    }

    private fun revealIfReady(token: Int) {
        if (token != generation || !frameReady || !frameFinished || fadeIn != null) return
        if (!motionEnabled) { finish(); return }
        fadeIn = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 150L
            addUpdateListener { if (token == generation) preview.alpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token != generation) return
                    fadeIn = null
                    finish()
                }
            })
            start()
        }
    }

    private fun finish() {
        Log.i("ToviCamUi", "Style transition finished")
        cancel()
    }

    /** Cancel pending callbacks on pause, errors, lens changes and detachment. */
    fun cancel() {
        if (isRunning && !frameReady) Log.i("ToviCamUi", "Style transition cancelled before reveal")
        generation += 1
        fadeOut?.cancel()
        frameMotion?.cancel()
        fadeIn?.cancel()
        fadeOut = null
        frameMotion = null
        fadeIn = null
        committed = false
        frameReady = false
        frameFinished = false
        preview.alpha = 1f
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        fit(fromAspect, fromFrame)
        fit(toAspect, toFrame)
        frame.set(
            fromFrame.left + (toFrame.left - fromFrame.left) * progress,
            fromFrame.top + (toFrame.top - fromFrame.top) * progress,
            fromFrame.right + (toFrame.right - fromFrame.right) * progress,
            fromFrame.bottom + (toFrame.bottom - fromFrame.bottom) * progress,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, mask)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), mask)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, mask)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, mask)
        val inset = border.strokeWidth / 2f
        canvas.drawRect(frame.left + inset, frame.top + inset, frame.right - inset, frame.bottom - inset, border)
    }

    private fun fit(aspect: Float, result: RectF) {
        val w = minOf(width.toFloat(), height * aspect)
        val h = w / aspect
        result.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }
}
