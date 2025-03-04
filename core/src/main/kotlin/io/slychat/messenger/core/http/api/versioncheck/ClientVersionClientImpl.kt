package io.slychat.messenger.core.http.api.versioncheck

import io.slychat.messenger.core.http.HttpClient
import io.slychat.messenger.core.http.api.apiGetRequest
import io.slychat.messenger.core.typeRef

class ClientVersionClientImpl(private val serverBaseUrl: String, private val httpClient: HttpClient) : ClientVersionClient {
    /** Returns true if the client version is up to date, false otherwise. */
    override fun check(version: String): CheckResponse {
        val url = "$serverBaseUrl/v2/client-version/check?v=$version"

        return apiGetRequest(httpClient, url, null, emptyList(), typeRef())
    }
}