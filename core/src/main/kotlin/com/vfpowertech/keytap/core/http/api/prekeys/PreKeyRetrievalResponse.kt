package com.vfpowertech.keytap.core.http.api.prekeys

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.vfpowertech.keytap.core.UserId

/** Lack of keyData indicates a non-registered user. */
data class PreKeyRetrievalResponse(
    @param:JsonProperty("error-message")
    @get:JsonProperty("error-message")
    val errorMessage: String?,

    @param:JsonProperty("for")
    @get:JsonProperty("for")
    val forUser: UserId,

    @param:JsonProperty("key-data")
    @get:JsonProperty("key-data")
    val keyData: SerializedPreKeySet?
) {
    @get:JsonIgnore
    val isSuccess: Boolean = errorMessage == null
}