package com.hashmi.familylink.data

import java.security.SecureRandom

object PairingKeys {
    private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray()

    fun generate(): String {
        val rng = SecureRandom()
        val chars = CharArray(8) { alphabet[rng.nextInt(alphabet.size)] }
        return "${chars.concatToString().substring(0, 4)}-${chars.concatToString().substring(4)}"
    }

    fun isValid(value: String): Boolean = PAIRING_KEY_REGEX.matches(value.trim().uppercase())
}
