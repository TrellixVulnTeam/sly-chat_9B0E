package com.vfpowertech.keytap.services

data class AuthTokenRefreshResult(val authToken: String, val keyRegenCount: Int)