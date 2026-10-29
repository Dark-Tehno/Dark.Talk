package com.example.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Рингтон входящего звонка и гудки исходящего. */
class CallSounds(private val context: Context) {
    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    @Synchronized
    @Suppress("DEPRECATION")
    fun startRingtone() {
        stopAll()
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                    play()
                }
            }
            if (audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                val pattern = longArrayOf(0, 800, 800)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator()?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    vibrator()?.vibrate(pattern, 0)
                }
            }
        }
    }

    @Synchronized
    fun startRingback() {
        stopAll()
        runCatching {
            tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).also {
                it.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        }
    }

    @Synchronized
    fun stopAll() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { tone?.stopTone(); tone?.release() }
        tone = null
        runCatching { vibrator()?.cancel() }
    }
}
