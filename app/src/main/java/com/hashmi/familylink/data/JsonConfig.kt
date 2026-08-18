package com.hashmi.familylink.data

import kotlinx.serialization.json.Json

object JsonConfig {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
        explicitNulls = false
        isLenient = true
    }
}
