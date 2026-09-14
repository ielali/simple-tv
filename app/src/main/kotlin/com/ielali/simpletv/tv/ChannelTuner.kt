package com.ielali.simpletv.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Reproduces how a 1990s television handles the number pad.
 *
 * Digits accumulate in [pendingDigits]. A tune request is emitted when:
 *  - the viewer presses OK / ENTER, or
 *  - [maxDigits] digits have been entered, or
 *  - [entryTimeoutMs] elapses since the last digit.
 *
 * This class has no Android dependencies so it is unit-tested with virtual time.
 */
class ChannelTuner(
    private val scope: CoroutineScope,
    private val entryTimeoutMs: Long = 1500L,
    private val maxDigits: Int = 3,
) {
    private val _pendingDigits = MutableStateFlow("")
    val pendingDigits: StateFlow<String> = _pendingDigits.asStateFlow()

    private val _tuneRequests = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val tuneRequests: SharedFlow<Int> = _tuneRequests.asSharedFlow()

    private var timeoutJob: Job? = null

    fun onDigit(digit: Int) {
        require(digit in 0..9) { "digit must be 0..9" }
        val next = (_pendingDigits.value + digit).takeLast(maxDigits)
        _pendingDigits.value = next
        if (next.length >= maxDigits) {
            commit()
        } else {
            restartTimeout()
        }
    }

    /** OK / ENTER pressed while digits are pending. Returns true if a tune was requested. */
    fun onConfirm(): Boolean {
        if (_pendingDigits.value.isEmpty()) return false
        commit()
        return true
    }

    fun onCancel() {
        timeoutJob?.cancel()
        _pendingDigits.value = ""
    }

    private fun restartTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(entryTimeoutMs)
            commit()
        }
    }

    private fun commit() {
        timeoutJob?.cancel()
        val number = _pendingDigits.value.toIntOrNull()
        _pendingDigits.value = ""
        if (number != null) _tuneRequests.tryEmit(number)
    }
}
