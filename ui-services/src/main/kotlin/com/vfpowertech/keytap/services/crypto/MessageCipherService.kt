package com.vfpowertech.keytap.services.crypto

import com.fasterxml.jackson.databind.ObjectMapper
import com.vfpowertech.keytap.core.BuildConfig
import com.vfpowertech.keytap.core.crypto.hexify
import com.vfpowertech.keytap.core.crypto.unhexify
import com.vfpowertech.keytap.core.http.api.prekeys.*
import com.vfpowertech.keytap.services.NoAuthTokenException
import com.vfpowertech.keytap.services.UserLoginData
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.map
import nl.komponents.kovenant.task
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.SessionBuilder
import org.whispersystems.libsignal.SessionCipher
import org.whispersystems.libsignal.protocol.PreKeySignalMessage
import org.whispersystems.libsignal.protocol.SignalMessage
import org.whispersystems.libsignal.state.PreKeyBundle
import org.whispersystems.libsignal.state.SignalProtocolStore
import java.util.*

data class DecryptionFailure(val cause: Throwable)
data class MessageListDecryptionResult(
    val succeeded: List<String>,
    val failed: List<DecryptionFailure>
)

fun SerializedPreKeySet.toPreKeyBundle(): PreKeyBundle {
    val objectMapper = ObjectMapper()
    val registrationId = 0
    val deviceId = 1
    val oneTimePreKey = objectMapper.readValue(preKey, UnsignedPreKeyPublicData::class.java)
    val signedPreKey = objectMapper.readValue(signedPreKey, SignedPreKeyPublicData::class.java)
    return PreKeyBundle(
        registrationId,
        deviceId,
        oneTimePreKey.id,
        oneTimePreKey.getECPublicKey(),
        signedPreKey.id,
        signedPreKey.getECPublicKey(),
        signedPreKey.signature,
        IdentityKey(publicKey.unhexify(), 0)
    )
}

class MessageCipherService(
    private val userLoginData: UserLoginData,
    private val signalStore: SignalProtocolStore,
    private val serverUrls: BuildConfig.ServerUrls
) {
    //TODO
    //XXX various things can interact with the store; so need to sync on the store directly
    //or force store access on main thread?
    private inline fun <R> withStore(body: (SignalProtocolStore) -> R): R = synchronized(signalStore) {
        body(signalStore)
    }

    private fun fetchPreKeyBundle(contactEmail: String): Promise<PreKeyBundle, Exception> {
        val authToken = userLoginData.authToken ?: throw NoAuthTokenException()
        val request = PreKeyRetrievalRequest(authToken, contactEmail)
        return PreKeyRetrievalAsyncClient(serverUrls.API_SERVER).retrieve(request) map { response ->
            if (!response.isSuccess)
                throw RuntimeException(response.errorMessage)
            else {
                response.keyData ?: throw RuntimeException("No key data for $contactEmail")
                response.keyData.toPreKeyBundle()
            }
        }
    }

    private fun getSessionCipher(contactEmail: String): Promise<SessionCipher, Exception> {
        val address = KeyTapAddress(contactEmail).toSignalAddress()
        val containsSession = withStore { it.containsSession(address) }
        return if (!containsSession) {
            fetchPreKeyBundle(contactEmail) map { bundle ->
                withStore {
                    val builder = SessionBuilder(it, address)
                    builder.process(bundle)
                    SessionCipher(signalStore, address)
                }
            }
        }
        else
            Promise.ofSuccess(SessionCipher(signalStore, address))
    }

    //requires fetching a prekey if a session is missing
    fun encrypt(contactEmail: String, message: String): Promise<EncryptedMessageV0, Exception> {
        return getSessionCipher(contactEmail) map { sessionCipher ->
            val encrypted = withStore {
                sessionCipher.encrypt(message.toByteArray(Charsets.UTF_8))
            }

            val isPreKey = when (encrypted) {
                is PreKeySignalMessage -> true
                is SignalMessage -> false
                else -> throw RuntimeException("Invalid message type: ${encrypted.javaClass.name}")
            }

            EncryptedMessageV0(isPreKey, encrypted.serialize().hexify())
        }
    }

    /** Must be called with store lock held. */
    private fun decryptEncryptedMessage(sessionCipher: SessionCipher, encryptedMessage: EncryptedMessageV0): String {
        val payload = encryptedMessage.payload.unhexify()

        val messageData = if (encryptedMessage.isPreKeyWhisper)
            sessionCipher.decrypt(PreKeySignalMessage(payload))
        else
            sessionCipher.decrypt(SignalMessage(payload))

        return String(messageData, Charsets.UTF_8)
    }

    fun decrypt(contactEmail: String, encryptedMessage: EncryptedMessageV0): Promise<String, Exception> = task {
        val address = KeyTapAddress(contactEmail).toSignalAddress()
        val sessionCipher = SessionCipher(signalStore, address)

        withStore {
            decryptEncryptedMessage(sessionCipher, encryptedMessage)
        }
    }

    /** Must be called with store lock held. */
    private fun decryptMessagesForUser(contactEmail: String, encryptedMessages: List<EncryptedMessageV0>): MessageListDecryptionResult {
        val failed = ArrayList<DecryptionFailure>()
        val succeeded = ArrayList<String>()

        val address = KeyTapAddress(contactEmail).toSignalAddress()
        val sessionCipher = SessionCipher(signalStore, address)

        encryptedMessages.forEach { encryptedMessage ->
            try {
                val message = decryptEncryptedMessage(sessionCipher, encryptedMessage)
                succeeded.add(message)
            }
            catch (e: Throwable) {
                failed.add(DecryptionFailure(e))
            }
        }

        return MessageListDecryptionResult(succeeded, failed)
    }

    /** Decrypts multiple messages from multiple users at once. */
    fun decryptMultiple(encryptedMessages: Map<String, List<EncryptedMessageV0>>): Promise<Map<String, MessageListDecryptionResult>, Exception> = task {
        withStore { store ->
            encryptedMessages.mapValues { entry ->
                decryptMessagesForUser(entry.key, entry.value)
            }
        }
    }
}
