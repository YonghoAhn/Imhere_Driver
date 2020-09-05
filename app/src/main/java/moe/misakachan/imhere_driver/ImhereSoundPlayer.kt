package moe.misakachan.imhere_driver

import android.content.Context
import android.media.SoundPool

class ImhereSoundPlayer(context: Context) {
    companion object {
        val soundPool: SoundPool = SoundPool.Builder().build()
        const val DING = R.raw.ding
    }
    private val sound = soundPool.load(context, DING, 1)

    public fun play()
    {
        soundPool.play(sound,1.0f,1.0f,0,0,1.0f)
    }
}