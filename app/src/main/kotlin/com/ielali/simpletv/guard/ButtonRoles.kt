package com.ielali.simpletv.guard

/**
 * Jobs a caregiver can give a remote button from the key log on the config page (ADR-022): open the
 * channel list, or go back to the previous channel. Pure, so the guard, the screen and the settings
 * sanitiser agree on which buttons may take a job.
 */
object ButtonRoles {
    enum class Role { CHANNEL_LIST, PREVIOUS_CHANNEL }

    /**
     * Out of the box the blue "Live TV" button opens the channel list. Remotes send it as TV or GUIDE,
     * so both do the same.
     */
    val DEFAULT_LIST: List<Int> = listOf(GuardKeys.TV, GuardKeys.GUIDE)

    /** Buttons with a fixed job (digits, arrows, OK, BACK, volume, power, Home, Menu...) never take another. */
    fun assignable(keyCode: Int): Boolean =
        keyCode == GuardKeys.TV || keyCode == GuardKeys.GUIDE || keyCode == GuardKeys.INFO ||
            HomeGuardPolicy.isBlockable(keyCode)

    data class Assignment(val list: List<Int>, val previous: List<Int>, val blocked: List<Int>)

    /** One job per button: the channel list wins over previous channel, and a button with a job is not blocked. */
    fun sanitize(list: List<Int>, previous: List<Int>, blocked: List<Int>): Assignment {
        val l = list.filter(::assignable).distinct().take(MAX_PER_ROLE)
        val p = previous.filter { assignable(it) && it !in l }.distinct().take(MAX_PER_ROLE)
        val b = HomeGuardPolicy.sanitizeBlockedKeys(blocked).filter { it !in l && it !in p }
        return Assignment(l.sorted(), p.sorted(), b)
    }

    fun roleOf(keyCode: Int, list: Collection<Int>, previous: Collection<Int>): Role? = when (keyCode) {
        in list -> Role.CHANNEL_LIST
        in previous -> Role.PREVIOUS_CHANNEL
        else -> null
    }

    private const val MAX_PER_ROLE = 4
}
