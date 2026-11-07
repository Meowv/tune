package com.meowv.tune.radio

import com.meowv.tune.domain.CountryDirectory
import com.meowv.tune.network.RadioBrowserClient

internal class RadioBrowserCountries(
    private val get: suspend (String, Map<String, String>) -> String
) : CountryDirectory {
    constructor(client: RadioBrowserClient) : this(client::get)

    override suspend fun countries() =
        RadioBrowserMapping.countries(get("/json/countrycodes", mapOf("hidebroken" to "true")))
}
