package com.ielali.simpletv

import android.app.Application
import com.ielali.simpletv.config.ConfigServer
import com.ielali.simpletv.data.ChannelRepository
import com.ielali.simpletv.data.ProviderRepository
import com.ielali.simpletv.data.SettingsRepository
import com.ielali.simpletv.guard.GuardSetup
import com.ielali.simpletv.guard.PickerReferenceStore
import com.ielali.simpletv.tv.ExternalPlayer
import com.ielali.simpletv.tv.PrefsLastChannelStore
import com.ielali.simpletv.tv.TvController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SimpleTvApp : Application() {

    lateinit var channels: ChannelRepository
        private set

    lateinit var settings: SettingsRepository
        private set

    lateinit var providers: ProviderRepository
        private set

    lateinit var pickerReference: PickerReferenceStore
        private set

    /** The TV's channel state; app-scoped so it keeps working while the YouTube app is in front. */
    lateinit var tv: TvController
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var configServer: ConfigServer? = null

    override fun onCreate() {
        super.onCreate()
        channels = ChannelRepository(this)
        settings = SettingsRepository(this)
        providers = ProviderRepository(this)
        pickerReference = PickerReferenceStore(this)
        GuardSetup.ensureEnabledIfWanted(this, settings.current().guardEnabled)
        tv = TvController(appScope, channels.channels, settings.settings, ExternalPlayer(this), PrefsLastChannelStore(this))
        configServer = ConfigServer(this, channels, settings, providers, CONFIG_PORT).also { it.start() }
    }

    companion object {
        const val CONFIG_PORT = 8080
    }
}
