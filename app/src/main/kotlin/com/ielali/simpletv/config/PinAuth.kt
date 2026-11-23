package com.ielali.simpletv.config

import java.security.SecureRandom

/**
 * PIN sign-in for the config page: 4 to 6 digits, 5 wrong tries then a 5-minute lock, sessions held
 * in memory so they end when the TV restarts. Plain HTTP on the home network (HTTPS is parked), so
 * this guards against casual access, not a determined attacker on the Wi-Fi.
 *
 * Pure apart from the random token source, so the rules are unit tested with a fake clock.
 */
class PinAuth(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val maxTries: Int = 5,
    private val lockMs: Long = 5 * 60_000L,
) {
    sealed interface LoginResult {
        data class Ok(val token: String) : LoginResult
        data class Wrong(val triesLeft: Int) : LoginResult
        data class Locked(val retryAfterSeconds: Long) : LoginResult
        data object NoPin : LoginResult
    }

    private val sessions = HashSet<String>()
    private var failures = 0
    private var lockedUntil = 0L

    @Synchronized
    fun lockedUntil(): Long = if (clock() < lockedUntil) lockedUntil else 0L

    @Synchronized
    fun login(entered: String, pin: String?): LoginResult {
        if (pin.isNullOrEmpty()) return LoginResult.NoPin
        val now = clock()
        if (now < lockedUntil) return LoginResult.Locked((lockedUntil - now + 999) / 1000)
        if (constantTimeEquals(entered, pin)) {
            failures = 0
            return LoginResult.Ok(issue())
        }
        failures++
        if (failures >= maxTries) {
            failures = 0
            lockedUntil = now + lockMs
            return LoginResult.Locked((lockMs + 999) / 1000)
        }
        return LoginResult.Wrong(maxTries - failures)
    }

    /** A new session, for whoever just set the PIN (they proved nothing more is needed). */
    @Synchronized
    fun issue(): String {
        val token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        sessions += token
        return token
    }

    @Synchronized
    fun isValid(token: String?): Boolean = token != null && token in sessions

    @Synchronized
    fun revoke(token: String?) { if (token != null) sessions.remove(token) }

    /** Changing or removing the PIN signs everyone out. */
    @Synchronized
    fun reset() {
        sessions.clear()
        failures = 0
        lockedUntil = 0L
    }

    companion object {
        private val valid = Regex("""^\d{4,6}$""")
        fun isValidPin(pin: String): Boolean = valid.matches(pin)

        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }
}
