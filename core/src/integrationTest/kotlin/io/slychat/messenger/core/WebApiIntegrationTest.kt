package io.slychat.messenger.core

import io.slychat.messenger.core.crypto.*
import io.slychat.messenger.core.crypto.signal.GeneratedPreKeys
import io.slychat.messenger.core.http.JavaHttpClient
import io.slychat.messenger.core.http.api.ApiException
import io.slychat.messenger.core.http.api.accountupdate.*
import io.slychat.messenger.core.http.api.authentication.AuthenticationClient
import io.slychat.messenger.core.http.api.authentication.AuthenticationRequest
import io.slychat.messenger.core.http.api.authentication.AuthenticationResponse
import io.slychat.messenger.core.http.api.authentication.DeviceInfo
import io.slychat.messenger.core.http.api.contacts.*
import io.slychat.messenger.core.http.api.gcm.GcmClient
import io.slychat.messenger.core.http.api.prekeys.*
import io.slychat.messenger.core.http.api.registration.RegistrationClient
import io.slychat.messenger.core.http.api.registration.RegistrationInfo
import io.slychat.messenger.core.http.api.registration.registrationRequestFromKeyVault
import io.slychat.messenger.core.http.api.versioncheck.ClientVersionClientImpl
import io.slychat.messenger.core.http.get
import io.slychat.messenger.core.persistence.AddressBookUpdate
import io.slychat.messenger.core.persistence.AllowedMessageLevel
import io.slychat.messenger.core.persistence.ContactInfo
import io.slychat.messenger.core.persistence.RemoteAddressBookEntry
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assume
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.whispersystems.libsignal.state.PreKeyRecord
import org.whispersystems.libsignal.state.SignedPreKeyRecord
import java.net.ConnectException
import kotlin.test.*

data class GeneratedSiteUser(
    val user: SiteUser,
    val keyVault: KeyVault
)

fun SiteUser.toContactInfo(): ContactInfo =
    ContactInfo(id, username, name, AllowedMessageLevel.ALL, phoneNumber, publicKey)

fun GeneratedSiteUser.getUserCredentials(authToken: AuthToken, deviceId: Int = DEFAULT_DEVICE_ID): UserCredentials {
    return user.getUserCredentials(authToken, deviceId)
}

fun SiteUser.getUserCredentials(authToken: AuthToken, deviceId: Int = DEFAULT_DEVICE_ID): UserCredentials {
    return UserCredentials(
        SlyAddress(id, deviceId),
        authToken
    )
}

class WebApiIntegrationTest {
    companion object {
        val serverBaseUrl = "http://localhost:8000"

        //this is md5('')
        val emptyMd5 = "d41d8cd98f00b204e9800998ecf8427e"

        val defaultRegistrationId = 12345

        //kinda hacky...
        var currentUserId = 1L

        fun nextUserId(): UserId {
            val r = currentUserId
            currentUserId += 1
            return UserId(r)
        }

        fun newSiteUser(registrationInfo: RegistrationInfo, password: String): GeneratedSiteUser {
            val keyVault = generateNewKeyVault(password)
            val serializedKeyVault = keyVault.serialize()

            val user = SiteUser(
                nextUserId(),
                registrationInfo.email,
                keyVault.remotePasswordHashParams.serialize(),
                keyVault.fingerprint,
                registrationInfo.name,
                registrationInfo.phoneNumber,
                serializedKeyVault
            )

            return GeneratedSiteUser(user, keyVault)
        }

        /** Short test for server dev functionality sanity. */
        private fun checkDevServerSanity() {
            val devClient = DevClient(serverBaseUrl, JavaHttpClient())
            val password = "test"
            val username = "a@a.com"

            devClient.clear()

            //users
            val userA = newSiteUser(RegistrationInfo(username, "a", "000-000-0000"), password)
            val siteUser = userA.user

            devClient.addUser(userA)

            val users = devClient.getUsers()

            if (users != listOf(siteUser))
                throw DevServerInsaneException("Register functionality failed")

            //address book hash
            assertEquals(emptyMd5, devClient.getAddressBookHash(username), "Unable to fetch address book hash")

            //auth token
            val authToken = devClient.createAuthToken(username)

            val gotToken = devClient.getAuthToken(username)

            if (gotToken != authToken)
                throw DevServerInsaneException("Auth token functionality failed")

            //prekeys
            val oneTimePreKeys = listOf("a", "b").sorted()
            devClient.addOneTimePreKeys(username, oneTimePreKeys)

            val gotPreKeys = devClient.getPreKeys(username).oneTimePreKeys.sorted()
            if (gotPreKeys != oneTimePreKeys)
                throw DevServerInsaneException("One-time prekey functionality failed")

            val signedPreKey = "s"
            devClient.setSignedPreKey(username, signedPreKey)

            if (devClient.getSignedPreKey(username) != signedPreKey)
                throw DevServerInsaneException("Signed prekey functionality failed")

            val lastResortPreKey = "l"
            devClient.setLastResortPreKey(username, lastResortPreKey)

            if (devClient.getLastResortPreKey(username) != lastResortPreKey)
                throw DevServerInsaneException("Last resort prekey functionality failed")

            //contacts list
            val userB = newSiteUser(RegistrationInfo("b@a.com", "B", "000-000-0000"), password)

            val update = AddressBookUpdate.Contact(userB.user.id, AllowedMessageLevel.ALL)
            val contactsA = encryptRemoteAddressBookEntries(userA.keyVault, listOf(update))
            devClient.addAddressBookEntries(username, contactsA)

            val contacts = devClient.getAddressBook(username)

            if (contacts != contactsA)
                throw DevServerInsaneException("Address book functionality failed")

            //devices
            val deviceId = devClient.addDevice(username, defaultRegistrationId, DeviceState.ACTIVE)
            val deviceId2 = devClient.addDevice(username, defaultRegistrationId +1, DeviceState.INACTIVE)

            val devices = devClient.getDevices(username)

            val expected = listOf(
                Device(deviceId, defaultRegistrationId, DeviceState.ACTIVE),
                Device(deviceId2, defaultRegistrationId + 1, DeviceState.INACTIVE)
            )

            if (devices != expected)
                throw DevServerInsaneException("Device functionality failed")

            //GCM
            val gcmToken = randomUUID()
            devClient.registerGcmToken(username, deviceId, gcmToken)

            val gcmTokens = devClient.getGcmTokens(username)

            if (gcmTokens != listOf(UserGcmTokenInfo(deviceId, gcmToken)))
                throw DevServerInsaneException("GCM functionality failed")

            devClient.unregisterGcmToken(username, deviceId)

            if (devClient.getGcmTokens(username).size != 0)
                throw DevServerInsaneException("GCM functionality failed")
        }

        //only run if server is up
        @BeforeClass
        @JvmStatic
        fun beforeClass() {
            try {
                val response = JavaHttpClient().get("$serverBaseUrl/dev")
                if (response.code == 404)
                    throw ServerDevModeDisabledException()
            }
            catch (e: ConnectException) {
                Assume.assumeTrue(false)
            }

            checkDevServerSanity()
        }
    }

    val dummyRegistrationInfo = RegistrationInfo("c@a.com", "name", "000-000-0000")
    val password = "test"
    val invalidUserCredentials = UserCredentials(SlyAddress(UserId(999999), 999), AuthToken(""))

    val devClient = DevClient(serverBaseUrl, JavaHttpClient())

    var counter = 1111111111

    fun injectSiteUser(registrationInfo: RegistrationInfo): GeneratedSiteUser {
        val siteUser = newSiteUser(registrationInfo, password)

        devClient.addUser(siteUser)

        return siteUser
    }

    fun injectNamedSiteUser(username: String, phoneNumber: String = counter.toString()): GeneratedSiteUser {
        val registrationInfo = RegistrationInfo(username, "name", phoneNumber)
        counter++
        return injectSiteUser(registrationInfo)
    }

    fun injectNewSiteUser(): GeneratedSiteUser {
        return injectSiteUser(dummyRegistrationInfo)
    }

    fun generatePreKeysForRequest(keyVault: KeyVault): Pair<GeneratedPreKeys, PreKeyRecord> {
        val generatedPreKeys = generatePrekeys(keyVault.identityKeyPair, 1, 1, 1)
        val lastResortPreKey = generateLastResortPreKey()

        return generatedPreKeys to lastResortPreKey
    }

    @Before
    fun before() {
        counter = 1111111111
        devClient.clear()
    }

    @Test
    fun `register request should succeed when given a unique username`() {
        val keyVault = generateNewKeyVault(password)
        val request = registrationRequestFromKeyVault(dummyRegistrationInfo, keyVault)

        val client = RegistrationClient(serverBaseUrl, JavaHttpClient())
        val result = client.register(request)
        assertNull(result.errorMessage)
        assertNull(result.validationErrors)

        val users = devClient.getUsers()

        assertEquals(1, users.size)
        val user = users[0]

        val expected = SiteUser(
            //don't care about the id
            user.id,
            dummyRegistrationInfo.email,
            keyVault.remotePasswordHashParams.serialize(),
            keyVault.fingerprint,
            dummyRegistrationInfo.name,
            dummyRegistrationInfo.phoneNumber,
            keyVault.serialize()
        )

        assertEquals(expected, user)
    }

    @Test
    fun `register request should fail when a duplicate username is used`() {
        val siteUser = injectNewSiteUser().user
        val registrationInfo = RegistrationInfo(siteUser.username, "name", "0")

        val keyVault = generateNewKeyVault(password)
        val request = registrationRequestFromKeyVault(registrationInfo, keyVault)

        val client = RegistrationClient(serverBaseUrl, JavaHttpClient())
        val result = client.register(request)

        assertNotNull(result.errorMessage, "Null error message")
        val errorMessage = result.errorMessage!!
        assertTrue(errorMessage.contains("taken"), "Invalid error message: $errorMessage}")
    }

    fun sendAuthRequestForUser(userA: GeneratedSiteUser, deviceId: Int): AuthenticationResponse {
        val username = userA.user.username

        val client = AuthenticationClient(serverBaseUrl, JavaHttpClient())

        val paramsApiResult = client.getParams(username)
        assertTrue(paramsApiResult.isSuccess, "Unable to fetch params")

        val csrfToken = paramsApiResult.params!!.csrfToken
        val authRequest = AuthenticationRequest(username, userA.keyVault.remotePasswordHash.hexify(), csrfToken, defaultRegistrationId, deviceId)

        return client.auth(authRequest)
    }

    @Test
    fun `authentication request should succeed when given a valid username and password hash for an existing device`() {
        val userA = injectNewSiteUser()
        val siteUser = userA.user
        val username = siteUser.username

        val deviceId = devClient.addDevice(username, defaultRegistrationId, DeviceState.ACTIVE)

        val authApiResult = sendAuthRequestForUser(userA, deviceId)
        assertTrue(authApiResult.isSuccess, "auth failed: ${authApiResult.errorMessage}")

        val receivedSerializedKeyVault = authApiResult.data!!.keyVault

        assertEquals(siteUser.keyVault, receivedSerializedKeyVault)
    }

    @Test
    fun `authentication request should return other active devices for the current user`() {
        val userA = injectNewSiteUser()
        val siteUser = userA.user
        val username = siteUser.username

        val deviceId = devClient.addDevice(username, defaultRegistrationId, DeviceState.ACTIVE)
        devClient.addDevice(username, defaultRegistrationId, DeviceState.INACTIVE)
        devClient.addDevice(username, defaultRegistrationId, DeviceState.PENDING)
        val activeDeviceRegistrationId = randomRegistrationId()
        val activeDeviceId = devClient.addDevice(username, activeDeviceRegistrationId, DeviceState.ACTIVE)

        val authApiResult = sendAuthRequestForUser(userA, deviceId)
        assertTrue(authApiResult.isSuccess, "auth failed: ${authApiResult.errorMessage}")

        val authData = authApiResult.data!!

        Assertions.assertThat(authData.otherDevices).apply {
            `as`("Should only list active devices")
            containsOnly(DeviceInfo(activeDeviceId, activeDeviceRegistrationId))
        }
    }

    fun runMaxDeviceTest(state: DeviceState) {
        val userA = injectNewSiteUser()
        val username = userA.user.username
        val maxDevices = devClient.getMaxDevices()

        for (i in 0..maxDevices-1)
            devClient.addDevice(username, 12345, state)

        val response = sendAuthRequestForUser(userA, 0)

        assertFalse(response.isSuccess, "Auth succeeded")
        assertTrue("too many registered devices" in response.errorMessage!!.toLowerCase())
    }

    @Test
    fun `attempting to authenticate with active devices maxed out should fail`() {
        runMaxDeviceTest(DeviceState.ACTIVE)
    }

    @Test
    fun `attempting to authenticate with pending devices maxed out should fail`() {
        runMaxDeviceTest(DeviceState.PENDING)
    }

    @Test
    fun `prekey info should reflect the current server prekey count`() {
        val siteUser = injectNewSiteUser()
        val username = siteUser.user.username

        val deviceId = devClient.addDevice(username, defaultRegistrationId, DeviceState.ACTIVE)

        val authToken = devClient.createAuthToken(siteUser.user.username, deviceId)

        val maxCount = devClient.getPreKeyMaxCount()

        val generatedPreKeys = injectPreKeys(username, siteUser.keyVault, deviceId, maxCount)

        val client = HttpPreKeyClient(serverBaseUrl, JavaHttpClient())

        val response = client.getInfo(siteUser.getUserCredentials(authToken))

        assertEquals(generatedPreKeys.oneTimePreKeys.size, response.remaining, "Invalid remaining keys")
        assertEquals(0, response.uploadCount, "Invalid uploadCount")
    }

    @Test
    fun `prekey storage request should fail when an invalid auth token is used`() {
        val keyVault = generateNewKeyVault(password)
        val (generatedPreKeys, lastResortPreKey) = generatePreKeysForRequest(keyVault)

        val request = preKeyStorageRequestFromGeneratedPreKeys(defaultRegistrationId, keyVault, generatedPreKeys, lastResortPreKey)

        val client = HttpPreKeyClient(serverBaseUrl, JavaHttpClient())

        assertFailsWith(UnauthorizedException::class) {
            client.store(invalidUserCredentials, request)
        }
    }

    fun injectPreKeys(username: String, keyVault: KeyVault, deviceId: Int = DEFAULT_DEVICE_ID, count: Int = 1): GeneratedPreKeys {
        val generatedPreKeys = generatePrekeys(keyVault.identityKeyPair, 1, 1, count)
        devClient.addOneTimePreKeys(username, serializeOneTimePreKeys(generatedPreKeys.oneTimePreKeys), deviceId)
        devClient.setSignedPreKey(username, serializeSignedPreKey(generatedPreKeys.signedPreKey), deviceId)
        return generatedPreKeys
    }

    fun injectLastResortPreKey(username: String, deviceId: Int = DEFAULT_DEVICE_ID): PreKeyRecord {
        val lastResortPreKey = generateLastResortPreKey()
        devClient.setLastResortPreKey(username, serializeOneTimePreKeys(listOf(lastResortPreKey))[0], deviceId)
        return lastResortPreKey
    }

    fun assertPreKeysStored(username: String, deviceId: Int, generatedPreKeys: GeneratedPreKeys, lastResortPreKey: PreKeyRecord) {
        val preKeys = devClient.getPreKeys(username, deviceId)

        val expectedOneTimePreKeys = serializeOneTimePreKeys(generatedPreKeys.oneTimePreKeys)
        val expectedSignedPreKey = serializeSignedPreKey(generatedPreKeys.signedPreKey)
        val expectedLastResortPreKey = serializePreKey(lastResortPreKey)

        assertEquals(expectedOneTimePreKeys, preKeys.oneTimePreKeys, "One-time prekeys don't match")
        assertEquals(expectedSignedPreKey, preKeys.signedPreKey, "Signed prekey doesn't match")
        assertEquals(expectedLastResortPreKey, preKeys.lastResortPreKey, "Last resort prekey doesn't match")
    }

    @Test
    fun `prekey storage request should store keys on the server when a valid auth token and device id is used`() {
        val siteUser = injectNewSiteUser()
        val keyVault = siteUser.keyVault
        val username = siteUser.user.username

        val deviceId = devClient.addDevice(username, defaultRegistrationId, DeviceState.ACTIVE)

        val authToken = devClient.createAuthToken(username, deviceId)
        val (generatedPreKeys, lastResortPreKey) = generatePreKeysForRequest(keyVault)

        val request = preKeyStorageRequestFromGeneratedPreKeys(defaultRegistrationId, keyVault, generatedPreKeys, lastResortPreKey)

        val client = HttpPreKeyClient(serverBaseUrl, JavaHttpClient())

        val response = client.store(siteUser.getUserCredentials(authToken, deviceId), request)
        assertTrue(response.isSuccess)

        assertPreKeysStored(username, deviceId, generatedPreKeys, lastResortPreKey)
    }

    @Test
    fun `attempting to push prekeys to a non-registered device should fail`() {
        val siteUser = injectNewSiteUser()
        val keyVault = siteUser.keyVault
        val username = siteUser.user.username

        val authToken = devClient.createAuthToken(username)
        val (generatedPreKeys, lastResortPreKey) = generatePreKeysForRequest(keyVault)

        val request = preKeyStorageRequestFromGeneratedPreKeys(defaultRegistrationId, keyVault, generatedPreKeys, lastResortPreKey)

        val client = HttpPreKeyClient(serverBaseUrl, JavaHttpClient())

        val response = client.store(siteUser.getUserCredentials(authToken), request)
        assertFalse(response.isSuccess, "Upload succeeded")
    }

    @Test
    fun `attempting to push prekeys to an inactive device should fail`() {
        val siteUser = injectNewSiteUser()
        val keyVault = siteUser.keyVault
        val username = siteUser.user.username

        val deviceId = devClient.addDevice(username, defaultRegistrationId, DeviceState.INACTIVE)

        val authToken = devClient.createAuthToken(username, deviceId)
        val (generatedPreKeys, lastResortPreKey) = generatePreKeysForRequest(keyVault)

        val request = preKeyStorageRequestFromGeneratedPreKeys(defaultRegistrationId, keyVault, generatedPreKeys, lastResortPreKey)

        val client = HttpPreKeyClient(serverBaseUrl, JavaHttpClient())

        val response = client.store(siteUser.getUserCredentials(authToken, deviceId), request)
        assertFalse(response.isSuccess, "Upload succeeded")
    }

    @Test
    fun `attempting to push prekeys to an pending device should success and update device state`() {
        val siteUser = injectNewSiteUser()
        val keyVault = siteUser.keyVault
        val username = siteUser.user.username
        val registrationId = defaultRegistrationId

        val deviceId = devClient.addDevice(username, registrationId, DeviceState.PENDING)

        val authToken = devClient.createAuthToken(username, deviceId)
        val (generatedPreKeys, lastResortPreKey) = generatePreKeysForRequest(keyVault)

        val request = preKeyStorageRequestFromGeneratedPreKeys(registrationId, keyVault, generatedPreKeys, lastResortPreKey)

        val client = HttpPreKeyClient(serverBaseUrl, JavaHttpClient())

        val response = client.store(siteUser.getUserCredentials(authToken, deviceId), request)
        assertTrue(response.isSuccess, "Upload failed: ${response.errorMessage}")

        val devices = devClient.getDevices(username)
        val updatedDevice = Device(deviceId, registrationId, DeviceState.ACTIVE)
        assertEquals(listOf(updatedDevice), devices)
    }

    @Test
    fun `prekey storage should fail when too many keys are uploaded`() {
        val siteUser = injectNewSiteUser()
        val username = siteUser.user.username

        val deviceId = devClient.addDevice(username, defaultRegistrationId, DeviceState.ACTIVE)

        val authToken = devClient.createAuthToken(siteUser.user.username, deviceId)

        val maxCount = devClient.getPreKeyMaxCount()

        val generatedPreKeys = injectPreKeys(username, siteUser.keyVault, deviceId, maxCount+1)
        val lastResortPreKey = generateLastResortPreKey()

        val request = preKeyStorageRequestFromGeneratedPreKeys(defaultRegistrationId, siteUser.keyVault, generatedPreKeys, lastResortPreKey)

        val client = HttpPreKeyClient(serverBaseUrl, JavaHttpClient())

        val exception = assertFailsWith<ApiException> {
            client.store(siteUser.getUserCredentials(authToken, deviceId), request)
        }

        assertTrue("too many" in exception.message!!.toLowerCase(), "Invalid error message: ${exception.message}")
    }

    @Test
    fun `prekey retrieval should fail when an invalid auth token is used`() {
        val siteUser = injectNewSiteUser()

        val client = HttpPreKeyClient(serverBaseUrl, JavaHttpClient())
        assertFailsWith(UnauthorizedException::class) {
            client.retrieve(invalidUserCredentials, PreKeyRetrievalRequest(siteUser.user.id, listOf()))
        }
    }

    @Test
    fun `prekey retrieval should return data only for asked devices`() {
        val siteUser = injectNewSiteUser()
        val username = siteUser.user.username

        val requestingSiteUser = injectNamedSiteUser("b@a.com")
        val requestingUsername = requestingSiteUser.user.username

        val deviceIds = (0..2).map { devClient.addDevice(username, defaultRegistrationId, DeviceState.ACTIVE) }

        val authToken = devClient.createAuthToken(requestingUsername)

        val client = HttpPreKeyClient(serverBaseUrl, JavaHttpClient())

        val requestedDeviceIds = deviceIds.subList(0, deviceIds.size-2)
        val request = PreKeyRetrievalRequest(siteUser.user.id, requestedDeviceIds)
        val response = client.retrieve(requestingSiteUser.getUserCredentials(authToken), request)

        assertTrue(response.isSuccess)

        assertEquals(requestedDeviceIds, response.bundles.keys.toList().sorted(), "Received invalid devices")
    }

    //TODO more elaborate tests

    @Test
    fun `prekey retrieval should fail when the target user has no active devices`() {
        val siteUser = injectNewSiteUser()
        val username = siteUser.user.username

        val requestingSiteUser = injectNamedSiteUser("b@a.com")
        val requestingUsername = requestingSiteUser.user.username

        devClient.addDevice(username, defaultRegistrationId, DeviceState.PENDING)
        devClient.addDevice(username, defaultRegistrationId, DeviceState.INACTIVE)

        val authToken = devClient.createAuthToken(requestingUsername)

        val client = HttpPreKeyClient(serverBaseUrl, JavaHttpClient())

        val response = client.retrieve(requestingSiteUser.getUserCredentials(authToken), PreKeyRetrievalRequest(siteUser.user.id, listOf()))

        assertTrue(response.isSuccess)

        assertTrue(response.bundles.isEmpty(), "Bundle not empty")
    }

    @Test
    fun `prekey retrieval should return the next available prekey when a valid auth token is used`() {
        val siteUser = injectNewSiteUser()
        val username = siteUser.user.username

        val requestingSiteUser = injectNamedSiteUser("b@a.com")
        val requestingUsername = requestingSiteUser.user.username

        val deviceId = devClient.addDevice(username, defaultRegistrationId, DeviceState.ACTIVE)

        val generatedPreKeys = injectPreKeys(username, siteUser.keyVault, deviceId)

        val authToken = devClient.createAuthToken(requestingUsername)

        val client = HttpPreKeyClient(serverBaseUrl, JavaHttpClient())

        val response = client.retrieve(requestingSiteUser.getUserCredentials(authToken, deviceId), PreKeyRetrievalRequest(siteUser.user.id, listOf()))

        assertTrue(response.isSuccess)

        assertNotNull(response.bundles, "No prekeys found")
        val bundles = response.bundles

        assertEquals(1, bundles.size, "Invalid number of bundles")
        assertTrue(deviceId in bundles, "Missing device id in bundle")

        val preKeyData = bundles[deviceId]!!

        val serializedOneTimePreKeys = serializeOneTimePreKeys(generatedPreKeys.oneTimePreKeys)
        val expectedSignedPreKey = serializeSignedPreKey(generatedPreKeys.signedPreKey)

        assertEquals(expectedSignedPreKey, preKeyData.signedPreKey, "Signed prekey doesn't match")

        assertTrue(serializedOneTimePreKeys.contains(preKeyData.preKey), "No matching one-time prekey found")
    }

    fun assertNextPreKeyIs(userId: UserId, authToken: AuthToken, expected: PreKeyRecord, signedPreKey: SignedPreKeyRecord) {
        val client = HttpPreKeyClient(serverBaseUrl, JavaHttpClient())

        val userCredentials = UserCredentials(SlyAddress(userId, DEFAULT_DEVICE_ID), authToken)

        val response = client.retrieve(userCredentials, PreKeyRetrievalRequest(userId, listOf()))

        assertTrue(response.isSuccess)

        assertNotNull(response.bundles, "No prekeys found")
        val bundles = response.bundles

        assertEquals(1, bundles.size, "Invalid number of bundles")
        assertTrue(DEFAULT_DEVICE_ID in bundles, "Missing device id in bundle")

        val preKeyData = bundles[DEFAULT_DEVICE_ID]!!

        val expectedOneTimePreKeys = serializePreKey(expected)
        val expectedSignedPreKey = serializeSignedPreKey(signedPreKey)

        assertEquals(expectedSignedPreKey, preKeyData.signedPreKey, "Signed prekey doesn't match")

        assertEquals(expectedOneTimePreKeys, preKeyData.preKey, "One-time prekey doesn't match")
    }

    @Test
    fun `prekey retrieval should return the last resort key once no other keys are available`() {
        val siteUser = injectNewSiteUser()
        val username = siteUser.user.username
        val userId = siteUser.user.id

        val deviceId = devClient.addDevice(username, defaultRegistrationId, DeviceState.ACTIVE)

        val authToken = devClient.createAuthToken(siteUser.user.username, deviceId)

        val generatedPreKeys = injectPreKeys(username, siteUser.keyVault, deviceId)
        val lastResortPreKey = injectLastResortPreKey(username, deviceId)

        assertNextPreKeyIs(userId, authToken, generatedPreKeys.oneTimePreKeys[0], generatedPreKeys.signedPreKey)
        assertNextPreKeyIs(userId, authToken, lastResortPreKey, generatedPreKeys.signedPreKey)
    }

    @Test
    fun `new contact fetch from email should return the contact information`() {
        val siteUser = injectNewSiteUser()
        val authToken = devClient.createAuthToken(siteUser.user.username)

        val contactDetails = ContactInfo(siteUser.user.id, siteUser.user.username, siteUser.user.name, AllowedMessageLevel.ALL, siteUser.user.phoneNumber, siteUser.user.publicKey)

        val client = ContactClient(serverBaseUrl, JavaHttpClient())

        val contactResponseEmail = client.find(siteUser.getUserCredentials(authToken), FindContactRequest(siteUser.user.username, null))
        assertTrue(contactResponseEmail.isSuccess)

        val receivedEmailContactInfo = contactResponseEmail.contactInfo!!

        assertEquals(contactDetails, receivedEmailContactInfo.toCore(AllowedMessageLevel.ALL))
    }

    @Test
    fun `new contact fetch from phone should return the contact information`() {
        val siteUser = injectNewSiteUser()
        val authToken = devClient.createAuthToken(siteUser.user.username)

        val contactDetails = ContactInfo(siteUser.user.id, siteUser.user.username, siteUser.user.name, AllowedMessageLevel.ALL, siteUser.user.phoneNumber, siteUser.user.publicKey)

        val client = ContactClient(serverBaseUrl, JavaHttpClient())

        val contactResponse = client.find(siteUser.getUserCredentials(authToken), FindContactRequest(null, siteUser.user.phoneNumber))
        assertTrue(contactResponse.isSuccess)

        val receivedContactInfo = contactResponse.contactInfo!!

        assertEquals(contactDetails, receivedContactInfo.toCore(AllowedMessageLevel.ALL))
    }

    fun assertAddressBookEquals(expected: List<RemoteAddressBookEntry>, actual: List<RemoteAddressBookEntry>, message: String? = null) {
        assertThat(actual).apply {
            `as`("Address book should match")
            containsOnlyElementsOf(expected)
        }
    }

    @Test
    fun `Fetching the address book should fetch only entries for that user`() {
        val userA = injectNamedSiteUser("a@a.com")
        val userB = injectNamedSiteUser("b@a.com")
        val userC = injectNamedSiteUser("c@a.com")

        val aContacts = encryptRemoteAddressBookEntries(userA.keyVault, listOf(AddressBookUpdate.Contact(userB.user.id, AllowedMessageLevel.ALL)))
        val bContacts = encryptRemoteAddressBookEntries(userB.keyVault, listOf(AddressBookUpdate.Contact(userC.user.id, AllowedMessageLevel.ALL)))

        devClient.addAddressBookEntries(userA.user.username, aContacts)
        devClient.addAddressBookEntries(userB.user.username, bContacts)

        val client = AddressBookClient(serverBaseUrl, JavaHttpClient())

        val authToken = devClient.createAuthToken(userA.user.username)
        val response = client.get(userA.getUserCredentials(authToken), GetAddressBookRequest(emptyMd5))

        assertAddressBookEquals(aContacts, response.entries)
    }

    @Test
    fun `Fetching the address book when hash has not changed should return an empty list`() {
        val userA = injectNamedSiteUser("a@a.com")
        val userB = injectNamedSiteUser("b@a.com")
        val username = userA.user.username

        val contacts = encryptRemoteAddressBookEntries(userA.keyVault, listOf(AddressBookUpdate.Contact(userB.user.id, AllowedMessageLevel.ALL)))

        devClient.addAddressBookEntries(username, contacts)

        val client = AddressBookClient(serverBaseUrl, JavaHttpClient())

        val currentHash = devClient.getAddressBookHash(username)

        val authToken = devClient.createAuthToken(username)
        val response = client.get(userA.getUserCredentials(authToken), GetAddressBookRequest(currentHash))

        assertThat(response.entries).apply {
            `as`("Should not return any entries")
            isEmpty()
        }
    }

    @Test
    fun `updating the address book should add return the updated hash`() {
        val userA = injectNamedSiteUser("a@a.com")
        val userB = injectNamedSiteUser("b@a.com")
        val userC = injectNamedSiteUser("c@a.com")

        val client = AddressBookClient(serverBaseUrl, JavaHttpClient())

        val authToken = devClient.createAuthToken(userA.user.username)

        val updates = listOf(
            AddressBookUpdate.Contact(userB.user.id, AllowedMessageLevel.GROUP_ONLY),
            AddressBookUpdate.Contact(userC.user.id, AllowedMessageLevel.BLOCKED)
        )

        val updated = encryptRemoteAddressBookEntries(userA.keyVault, updates)
        val localHash = hashFromRemoteAddressBookEntries(updated)

        val request = UpdateAddressBookRequest(localHash, updated)

        val response = client.update(userA.getUserCredentials(authToken), request)

        assertEquals(localHash, response.hash, "Server hash doesn't match local hash")
    }

    @Test
    fun `Updating the address book should update the given contacts`() {
        val userA = injectNamedSiteUser("a@a.com")
        val userB = injectNamedSiteUser("b@a.com")
        val userC = injectNamedSiteUser("c@a.com")
        val userD = injectNamedSiteUser("d@a.com")

        val contactList = encryptRemoteAddressBookEntries(userA.keyVault, listOf(userB, userC, userD).map { AddressBookUpdate.Contact(it.user.id, AllowedMessageLevel.ALL) })

        devClient.addAddressBookEntries(userA.user.username, contactList.subList(0, contactList.size))

        val client = AddressBookClient(serverBaseUrl, JavaHttpClient())

        val authToken = devClient.createAuthToken(userA.user.username)

        val updates = listOf(
            AddressBookUpdate.Contact(userB.user.id, AllowedMessageLevel.GROUP_ONLY),
            AddressBookUpdate.Contact(userC.user.id, AllowedMessageLevel.BLOCKED)
        )

        val updated = encryptRemoteAddressBookEntries(userA.keyVault, updates)
        val localHash = hashFromRemoteAddressBookEntries(updated)

        val request = UpdateAddressBookRequest(localHash, updated)

        val response = client.update(userA.getUserCredentials(authToken), request)

        assertTrue(response.updated, "Server says updates had no effect")

        val addressBook = devClient.getAddressBook(userA.user.username)

        val expected = listOf(
            updated[0],
            updated[1],
            contactList[2]
        )

        assertAddressBookEquals(expected, addressBook, "Local and remote address books don't match")
    }

    @Test
    fun `updates that have no effects should be reported by the server`() {
        val userA = injectNamedSiteUser("a@a.com")
        val userB = injectNamedSiteUser("b@a.com")

        val contactList = encryptRemoteAddressBookEntries(userA.keyVault, listOf(userB).map { AddressBookUpdate.Contact(it.user.id, AllowedMessageLevel.ALL) })

        devClient.addAddressBookEntries(userA.user.username, contactList)

        val client = AddressBookClient(serverBaseUrl, JavaHttpClient())

        val authToken = devClient.createAuthToken(userA.user.username)

        val updates = listOf(
            AddressBookUpdate.Contact(userB.user.id, AllowedMessageLevel.ALL)
        )

        val updated = encryptRemoteAddressBookEntries(userA.keyVault, updates)
        val localHash = hashFromRemoteAddressBookEntries(updated)

        val request = UpdateAddressBookRequest(localHash, updated)

        val response = client.update(userA.getUserCredentials(authToken), request)

        assertFalse(response.updated, "Server reports that updates occured")
    }

    @Test
    fun `Updating the address book should create new contact entries`() {
        val userA = injectNamedSiteUser("a@a.com")
        val userB = injectNamedSiteUser("b@a.com")

        val authToken = devClient.createAuthToken(userA.user.username)
        val aContacts = encryptRemoteAddressBookEntries(userA.keyVault, listOf(AddressBookUpdate.Contact(userB.user.id, AllowedMessageLevel.ALL)))
        val localHash = hashFromRemoteAddressBookEntries(aContacts)

        val client = AddressBookClient(serverBaseUrl, JavaHttpClient())
        val userCredentials = userA.getUserCredentials(authToken)
        client.update(userCredentials, UpdateAddressBookRequest(localHash, aContacts))

        val contacts = devClient.getAddressBook(userA.user.username)

        assertAddressBookEquals(aContacts, contacts)
    }

    @Test
    fun `findLocalContacts should find matches for both phone number and emails`() {
        val bPhoneNumber = "15555555555"
        val userA = injectNamedSiteUser("a@a.com").user
        val userB = injectNamedSiteUser("b@a.com", bPhoneNumber).user
        val userC = injectNamedSiteUser("c@a.com").user

        val authToken = devClient.createAuthToken(userA.username)

        val client = ContactClient(serverBaseUrl, JavaHttpClient())

        val platformContacts = listOf(
            PlatformContact("B", listOf(userC.username), listOf()),
            PlatformContact("C", listOf(), listOf(bPhoneNumber))
        )
        val request = FindLocalContactsRequest(platformContacts)
        val response = client.findLocalContacts(userA.getUserCredentials(authToken), request)

        val expected = listOf(
            userB.toContactInfo(),
            userC.toContactInfo()
        )

        assertEquals(expected, response.contacts.sortedBy { it.email }.map { it.toCore(AllowedMessageLevel.ALL) })
    }

    @Test
    fun `fetchMultiContactInfoById should fetch users with the given ids`() {
        val userA = injectNamedSiteUser("a@a.com").user
        val userB = injectNamedSiteUser("b@a.com").user
        val userC = injectNamedSiteUser("c@a.com").user

        val authToken = devClient.createAuthToken(userA.username)

        val client = ContactClient(serverBaseUrl, JavaHttpClient())

        val request = FindAllByIdRequest(listOf(userB.id, userC.id))
        val response = client.findAllById(userA.getUserCredentials(authToken), request)

        val expected = listOf(
            userB.toContactInfo(),
            userC.toContactInfo()
        )

        assertEquals(expected, response.contacts.sortedBy { it.email }.map { it.toCore(AllowedMessageLevel.ALL) })
    }

    @Test
    fun `fetchContactInfoById should return valid contact info for an existing user`() {
        val userA = injectNamedSiteUser("a@a.com").user
        val userB = injectNamedSiteUser("b@a.com").user

        val authToken = devClient.createAuthToken(userA.username)

        val client = ContactClient(serverBaseUrl, JavaHttpClient())

        val response = client.findById(userA.getUserCredentials(authToken), userB.id)

        assertEquals(userB.toContactInfo(), response.contactInfo?.toCore(AllowedMessageLevel.ALL), "Invalid contact info")
    }

    @Test
    fun `fetchContactInfoById should return null for a non-existent user`() {
        val userA = injectNamedSiteUser("a@a.com").user

        val authToken = devClient.createAuthToken(userA.username)

        val client = ContactClient(serverBaseUrl, JavaHttpClient())

        val response = client.findById(userA.getUserCredentials(authToken), randomUserId())

        assertNull(response.contactInfo, "Should be not return contact info")
    }

    @Test
    fun `Update Phone should succeed when right password is provided`() {
        val user = injectNamedSiteUser("a@a.com")

        val client = RegistrationClient(serverBaseUrl, JavaHttpClient())

        val newPhone = "123453456"

        val request = UpdatePhoneRequest(user.user.username, user.keyVault.remotePasswordHash.hexify(), newPhone)
        val response = client.updatePhone(request)

        assertTrue(response.isSuccess, "Update request failed: ${response.errorMessage}")

        val expected = SiteUser(
            user.user.id,
            user.user.username,
            user.keyVault.remotePasswordHashParams.serialize(),
            user.keyVault.fingerprint,
            user.user.name,
            newPhone,
            user.keyVault.serialize()
        )

        assertEquals(listOf(expected), devClient.getUsers())
    }

    @Test
    fun `Update Phone should fail when wrong password is provided`() {
        val user = injectNamedSiteUser("a@a.com")

        val client = RegistrationClient(serverBaseUrl, JavaHttpClient())

        val request = UpdatePhoneRequest(user.user.username, "wrongPassword", "1111111111")
        val response = client.updatePhone(request)

        assertFalse(response.isSuccess)

        val expected = SiteUser(
            user.user.id,
            user.user.username,
            user.keyVault.remotePasswordHashParams.serialize(),
            user.keyVault.fingerprint,
            user.user.name,
            user.user.phoneNumber,
            user.keyVault.serialize()
        )

        assertEquals(listOf(expected), devClient.getUsers())
    }

    @Test
    fun `Update Email should succeed when email is available`() {
        val userA = injectNamedSiteUser("a@a.com").user

        val authToken = devClient.createAuthToken(userA.username)

        val client = AccountUpdateClient(serverBaseUrl, JavaHttpClient())

        val newEmail = "b@b.com"

        val request = UpdateEmailRequest(newEmail)
        val response = client.updateEmail(userA.getUserCredentials(authToken), request)

        assertTrue(response.isSuccess)
        assertEquals(response.accountInfo!!.username, newEmail)
    }

    @Test
    fun `Update Email should fail when email is not available`() {
        val userA = injectNamedSiteUser("a@a.com").user
        injectNamedSiteUser("b@b.com").user

        val authToken = devClient.createAuthToken(userA.username)

        val client = AccountUpdateClient(serverBaseUrl, JavaHttpClient())

        val newEmail = "b@b.com"

        val request = UpdateEmailRequest(newEmail)
        val response = client.updateEmail(userA.getUserCredentials(authToken), request)

        assertFalse(response.isSuccess)
    }

    @Test
    fun `Update Name should succeed`() {
        val userA = injectNamedSiteUser("a@a.com").user

        val authToken = devClient.createAuthToken(userA.username)

        val client = AccountUpdateClient(serverBaseUrl, JavaHttpClient())

        val newName = "newName"

        val request = UpdateNameRequest(newName)
        val response = client.updateName(userA.getUserCredentials(authToken), request)

        assertTrue(response.isSuccess)
        assertEquals(response.accountInfo!!.name, newName)
    }

    @Test
    fun `Update Phone should succeed when phone is available`() {
        val userA = injectNamedSiteUser("a@a.com").user

        val authToken = devClient.createAuthToken(userA.username)

        val client = AccountUpdateClient(serverBaseUrl, JavaHttpClient())

        val newPhone = "12345678901"

        val request = RequestPhoneUpdateRequest(newPhone)
        val userCredentials = userA.getUserCredentials(authToken)
        val response = client.requestPhoneUpdate(userCredentials, request)

        assertTrue(response.isSuccess)

        val secondRequest = ConfirmPhoneNumberRequest("12345")
        val secondResponse = client.confirmPhoneNumber(userCredentials, secondRequest)

        assertTrue(secondResponse.isSuccess)
        assertEquals(secondResponse.accountInfo!!.phoneNumber, newPhone)
    }

    @Test
    fun `Update Phone should fail when phone is not available`() {
        val userA = injectNamedSiteUser("a@a.com").user
        injectNamedSiteUser("b@b.com", "2222222222").user

        val authToken = devClient.createAuthToken(userA.username)

        val client = AccountUpdateClient(serverBaseUrl, JavaHttpClient())

        val newPhone = "2222222222"

        val request = RequestPhoneUpdateRequest(newPhone)
        val response = client.requestPhoneUpdate(userA.getUserCredentials(authToken), request)

        assertFalse(response.isSuccess, "Update failed")
    }

    fun checkGCMTokenStatus(user: SiteUser, exists: Boolean) {
        val client = GcmClient(serverBaseUrl, JavaHttpClient())

        val authToken = devClient.createAuthToken(user.username)

        val response = client.isRegistered(user.getUserCredentials(authToken))

        assertEquals(exists, response.isRegistered, "Invalid gcm token status")
    }

    @Test
    fun `gcm isRegistered should return true if token is registered`() {
        val userA = injectNamedSiteUser("a@a.com").user

        val token = "gcm"

        val deviceId = devClient.addDevice(userA.username, defaultRegistrationId, DeviceState.ACTIVE)

        devClient.registerGcmToken(userA.username, deviceId, token)

        checkGCMTokenStatus(userA, true)
    }

    @Test
    fun `gcm isRegistered should return false if token is not registered`() {
        val userA = injectNamedSiteUser("a@a.com").user

        checkGCMTokenStatus(userA, false)
    }

    @Test
    fun `gcm unregister should unregister the current device token`() {
        val user = injectNamedSiteUser("a@a.com").user
        val token = "gcm"

        val deviceId = devClient.addDevice(user.username, defaultRegistrationId, DeviceState.ACTIVE)

        devClient.registerGcmToken(user.username, deviceId, token)

        val authToken = devClient.createAuthToken(user.username)

        val client = GcmClient(serverBaseUrl, JavaHttpClient())
        client.unregister(user.getUserCredentials(authToken))

        checkGCMTokenStatus(user, false)
    }

    @Test
    fun `version check should ignore SNAPSHOT versions`() {
        val client = ClientVersionClientImpl(serverBaseUrl, JavaHttpClient())

        assertTrue(client.check("0.0.0-SNAPSHOT"), "SNAPSHOT versions should always be valid")
    }

    @Test
    fun `version check should return false for an older version`() {
        val client = ClientVersionClientImpl(serverBaseUrl, JavaHttpClient())

        assertFalse(client.check("0.0.0"), "Version should be outdated")
    }

    @Test
    fun `version check should return true for an up-to-date version`() {
        val latestVersion = devClient.getLatestVersion()

        val client = ClientVersionClientImpl(serverBaseUrl, JavaHttpClient())

        assertTrue(client.check(latestVersion), "Latest version not accepted as up to date")
    }
}
