package com.youtubedownloaderandroid

import android.app.Application
import android.util.Log
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
            Aria2c.getInstance().init(this)
            DownloadQueueManager.init(this)
            Log.d("MainApplication", "YoutubeDL and DownloadQueueManager initialized successfully")
        } catch (e: Exception) {
            Log.e("MainApplication", "Failed to initialize YoutubeDL", e)
        }
    }
}
