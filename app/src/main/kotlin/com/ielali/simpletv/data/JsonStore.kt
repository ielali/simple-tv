package com.ielali.simpletv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** One JSON file, one value, one StateFlow. Whole-file rewrite on every change (values are small). */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private val _value = MutableStateFlow(load())
    val value: StateFlow<T> = _value.asStateFlow()

    fun current(): T = _value.value

    suspend fun replace(newValue: T): T {
        withContext(Dispatchers.IO) { file.writeText(json.encodeToString(serializer, newValue)) }
        _value.value = newValue
        return newValue
    }

    private fun load(): T = runCatching {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else default()
    }.getOrElse { default() }
}
