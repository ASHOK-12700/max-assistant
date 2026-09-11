package com.example.maxassistant.emotion

import android.content.Context
import android.graphics.drawable.AnimationDrawable
import android.view.animation.AnimationUtils
import android.widget.ImageView
import com.example.maxassistant.R

class EmotionManager(
    private val context: Context,
    private val robot: ImageView
) {

    fun setEmotion(emotion: Emotion) {

        // reset defaults (important)
        robot.clearAnimation()
        robot.alpha = 1.0f

        when (emotion) {

            // 😊 HAPPY
            Emotion.HAPPY -> {
                robot.setImageResource(R.drawable.happy_anim)
                val anim = robot.drawable as AnimationDrawable
                anim.start()

                robot.startAnimation(
                    AnimationUtils.loadAnimation(context, R.anim.bounce)
                )
            }

            // 😢 SAD
            Emotion.SAD -> {
                robot.setImageResource(R.drawable.sad_anim)
                val anim = robot.drawable as AnimationDrawable
                anim.start()

                robot.alpha = 0.6f
            }

            // 🤔 THINKING
            Emotion.THINKING -> {
                robot.setImageResource(R.drawable.thinking_anim) // ✅ name fix
                val anim = robot.drawable as AnimationDrawable
                anim.start()

                robot.startAnimation(
                    AnimationUtils.loadAnimation(context, R.anim.rotate)
                )
            }

            // 😡 ANGRY
            Emotion.ANGRY -> {
                robot.setImageResource(R.drawable.angry_anim)
                val anim = robot.drawable
                if (anim is AnimationDrawable) {
                    anim.start()
                }

                robot.startAnimation(
                    AnimationUtils.loadAnimation(context, R.anim.shake)
                )
            }

            // 😮 SURPRISED
            Emotion.SURPRISED -> {
                robot.setImageResource(R.drawable.surprised_anim)
                val anim = robot.drawable
                if (anim is AnimationDrawable) {
                    anim.start()
                }
            }

            // ❤️ LOVE
            Emotion.LOVE -> {
                robot.setImageResource(R.drawable.love_anim)
                val anim = robot.drawable
                if (anim is AnimationDrawable) {
                    anim.start()
                }

                robot.startAnimation(
                    AnimationUtils.loadAnimation(context, R.anim.zoom_in)
                )
            }

            // 😎 COOL
            Emotion.COOL -> {
                robot.setImageResource(R.drawable.cool_anim)
                val anim = robot.drawable
                if (anim is AnimationDrawable) {
                    anim.start()
                }
            }

            // 🔥 EXCITED
            Emotion.EXCITED -> {
                robot.setImageResource(R.drawable.excited_anim)
                val anim = robot.drawable as AnimationDrawable
                anim.start()

                robot.startAnimation(
                    AnimationUtils.loadAnimation(context, R.anim.shake)
                )
            }

            // 😴 SLEEPY
            Emotion.SLEEP -> {
                robot.setImageResource(R.drawable.sleep_anim)
                val anim = robot.drawable as AnimationDrawable
                anim.start()

                robot.alpha = 0.7f
            }

            // 👀 BLINK
            Emotion.BLINK -> {
                robot.setImageResource(R.drawable.blink_anim)
                val anim = robot.drawable as AnimationDrawable
                anim.start()
            }

            // 😐 IDLE
            Emotion.IDLE -> {
                robot.setImageResource(R.drawable.loona_idel)
            }
        }
    }
}