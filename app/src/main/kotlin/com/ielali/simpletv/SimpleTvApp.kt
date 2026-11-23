package com.ielali.simpletv

import android.app.Application
import com.ielali.simpletv.config.ConfigServer
import com.ielali.simpletv.data.CatalogRepository
import com.ielali.simpletv.data.CatalogService
import com.ielali.simpletv.data.ChannelRepository
import com.ielali.simpletv.data.HttpFetcher
import com.ielali.simpletv.data.ProviderLoader
import com.ielali.simpletv.data.QueuePlayback
import com.ielali.simpletv.data.SecretsStore
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

    lateinit var secrets: SecretsStore
        private set

    lateinit var loader: ProviderLoader
        private set

    lateinit var catalogs: CatalogRepository
        private set

    /** Cached subscription catalogs and the daily refresh. */
    lateinit var catalog: CatalogService
        private set

    /** Film and series channels: what plays next and where each stopped. */
    lateinit var queue: QueuePlayback
        private set

    /** Subtitles and soundtracks of the film or episode on screen, by channel id (set by the player). */
    val nowTracks = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, com.ielali.simpletv.player.TrackPicker.Report>?>(null)

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var configServer: ConfigServer? = null

    override fun onCreate() {
        super.onCreate()
        channels = ChannelRepository(this)
        settings = SettingsRepository(this)
        providers = ProviderRepository(this)
        pickerReference = PickerReferenceStore(this)
        GuardSetup.ensureEnabledIfWanted(this, settings.current().guardEnabled)
        tv = TvController(appScope, channels.channels, settings.settings, ExternalPlayer(this), PrefsLastChannelStore(this))
        secrets = SecretsStore(this)
        loader = ProviderLoader(HttpFetcher())
        catalogs = CatalogRepository(this)
        catalog = CatalogService(appScope, providers, channels, settings, catalogs, loader)
        queue = QueuePlayback(this, catalog, catalogs, providers)
        catalog.startScheduler()
        configServer = ConfigServer(this, CONFIG_PORT).also { it.start() }
    }

    companion object {
        const val CONFIG_PORT = 8080
    }
}
