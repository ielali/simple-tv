package com.ielali.simpletv

import android.app.Application
import com.ielali.simpletv.config.ConfigServer
import com.ielali.simpletv.data.ChannelRepository

class SimpleTvApp : Application() {

    lateinit var channels: ChannelRepository
        private set

    private var configServer: ConfigServer? = null

    override fun onCreate() {
        super.onCreate()
        channels = ChannelRepository(this)
        configServer = ConfigServer(this, channels, CONFIG_PORT).also { it.start() }
    }

    companion object {
        const val CONFIG_PORT = 8080
    }
}
