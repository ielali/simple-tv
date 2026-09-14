package com.ielali.simpletv.data

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

/** IPTV subscriptions. Passwords live only in this file inside app-private storage. */
class ProviderRepository(context: Context) {
    private val store = JsonStore(File(context.filesDir, "providers.json"), ProviderList.serializer()) { ProviderList() }
    val providers: StateFlow<ProviderList> = store.value
    fun current(): ProviderList = store.current()
    fun find(id: String): Provider? = current().providers.firstOrNull { it.id == id }

    /**
     * Replace the list. A provider whose password is null or blank keeps the password already stored
     * for that id, so the config page can round-trip providers without ever seeing the secret.
     */
    suspend fun replaceAll(list: ProviderList): ProviderList {
        val existing = current().providers.associateBy { it.id }
        val merged = list.providers.map { p ->
            val id = p.id.ifBlank { UUID.randomUUID().toString() }
            val password = p.password?.takeIf { it.isNotEmpty() } ?: existing[id]?.password
            p.copy(id = id, name = p.name.trim().ifBlank { "Provider" }, url = p.url.trim(), password = password)
        }
        return store.replace(ProviderList(version = 1, providers = merged))
    }
}
