package io.slychat.messenger.core.relay

import io.slychat.messenger.core.SlyAddress
import io.slychat.messenger.core.UserCredentials
import io.slychat.messenger.core.UserId
import io.slychat.messenger.core.crypto.tls.SSLConfigurator
import io.slychat.messenger.core.relay.base.*
import org.slf4j.LoggerFactory
import rx.Observable
import rx.Observer
import rx.Scheduler
import rx.subjects.PublishSubject
import java.net.InetSocketAddress

/**
 * @param credentials
 * @param scheduler Must correspond to a scheduler that runs on the main app thread (eg: JavaFXScheduler). Will be used when observing relay messages.
 */
class RelayClientImpl(
    private val connector: RelayConnector,
    private val scheduler: Scheduler,
    private val serverAddress: InetSocketAddress,
    private val credentials : UserCredentials,
    private val sslConfigurator: SSLConfigurator
) : RelayClient {
    companion object {
        private fun String.toUserId(): UserId {
            return UserId(this.toLong())
        }
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private var relayConnection: RelayConnection? = null
    override var state: RelayClientState = RelayClientState.DISCONNECTED
        private set
    private var wasDisconnectRequested = false
    private val publishSubject = PublishSubject.create<RelayClientEvent>()

    /** Client event stream. Will never call onError; check ConnectionLost.error instead. */
    override val events: Observable<RelayClientEvent>
        get() = publishSubject

    private fun onNext(event: RelayConnectionEvent) {
        when (event) {
            is RelayConnectionEstablished -> {
                relayConnection = event.connection
                state = RelayClientState.CONNECTED
                log.info("Relay connection established")
                authenticate()
                emitEvent(ConnectionEstablished())
            }

            is RelayConnectionLost -> {
                log.info("Relay connection lost")
            }

            is RelayMessage -> handleRelayMessage(event)
        }
    }

    /** Throws NotConnectedException if not connected. */
    private fun getConnectionOrThrow(): RelayConnection {
        return relayConnection ?: throw NotConnectedException()
    }

    /** Throws NotConnectedException if not connected, or NotAuthenticatedException if not authenticated. */
    private fun getAuthConnectionOrThrow(): RelayConnection {
        val connection = getConnectionOrThrow()
        if (state != RelayClientState.AUTHENTICATED)
            throw NotAuthenticatedException(state)
        return connection
    }

    private fun authenticate() {
        log.info("Authenticating as {}", credentials.address.asString())
        val connection = getConnectionOrThrow()
        connection.sendMessage(createAuthRequest(credentials))
        state = RelayClientState.AUTHENTICATING
    }

    private fun emitEvent(ev: RelayClientEvent) {
        publishSubject.onNext(ev)
    }

    /** Handles all incoming relay messages, updating internal state as necessary. */
    private fun handleRelayMessage(message: RelayMessage) {
        when (message.header.commandCode) {
            CommandCode.SERVER_REGISTER_SUCCESSFUL -> {
                log.info("Registration successful")
                state = RelayClientState.AUTHENTICATED

                emitEvent(AuthenticationSuccessful())
            }

            CommandCode.SERVER_REGISTER_REQUEST -> {
                if (state == RelayClientState.AUTHENTICATING) {
                    log.info("Authentication failed, disconnecting")
                    emitEvent(AuthenticationFailure())
                    disconnect()
                }
                else {
                    //TODO still need to disconnect since the web api handles auth
                    log.info("Authentication expired")
                    emitEvent(AuthenticationExpired())
                    disconnect()
                }
            }

            CommandCode.SERVER_MESSAGE_SENT -> {
                val to = message.header.toUserId
                val messageId = message.header.messageId
                log.info(
                    "Message <{}> to <<{}>> has been successfully sent",
                    messageId,
                    to
                )

                //user was online and message was sent
                //not sure we should bother with this event; client might not view it immediately/etc anyways
                emitEvent(MessageSentToUser(to.toUserId(), messageId))
            }

            CommandCode.SERVER_MESSAGE_RECEIVED -> {
                val to = message.header.toUserId
                val messageId = message.header.messageId
                log.info(
                    "Server has received message <{}> to <<{}>>",
                    messageId,
                    to
                )

                emitEvent(ServerReceivedMessage(to.toUserId(), messageId))
            }

            //when receiving a message of this type, it indicates a new message from someone
            CommandCode.CLIENT_SEND_MESSAGE -> {
                val from = message.header.fromUserId
                val messageId = message.header.messageId
                log.info(
                    "Received message <{}> from <<{}>>",
                    messageId,
                    from
                )

                val content = readMessageContent(message.content)

                emitEvent(ReceivedMessage(SlyAddress.fromString(from)!!, content, messageId))
            }

            CommandCode.SERVER_USER_OFFLINE -> {
                val to = message.header.toUserId
                val messageId = message.header.messageId

                log.info(
                    "User {} is offline, unable to send message <{}>",
                    to,
                    messageId
                )

                emitEvent(UserOffline(to.toUserId(), messageId))
            }

            CommandCode.SERVER_PONG -> {
                log.debug("PONG")
            }

            CommandCode.SERVER_DEVICE_MISMATCH -> {
                val to = message.header.toUserId
                val content = readDeviceMismatchContent(message.content)

                log.info(
                    "Device mismatch for user {}: stale={}, missing={}, removed={}",
                    to,
                    content.stale,
                    content.missing,
                    content.removed
                )

                emitEvent(DeviceMismatch(to.toUserId(), message.header.messageId, content))
            }

            else -> {
                log.warn("Unhandled message type: {}", message.header.commandCode)
            }
        }
    }

    private fun onCompleted() {
        log.info("Connection closed")
        state = RelayClientState.DISCONNECTED

        emitEvent(ConnectionLost(wasDisconnectRequested))
        publishSubject.onCompleted()
    }

    private fun onError(e: Throwable) {
        state = RelayClientState.DISCONNECTED

        //if the error occured during connection
        if (relayConnection == null)
            publishSubject.onNext(ConnectionFailure(e))
        else {
            log.error("Relay error", e)
            emitEvent(ConnectionLost(wasDisconnectRequested, e))
        }

        publishSubject.onCompleted()
    }

    override fun connect() {
        connector.connect(serverAddress, sslConfigurator)
            .observeOn(scheduler)
            .subscribe(object : Observer<RelayConnectionEvent> {
                override fun onCompleted() {
                    this@RelayClientImpl.onCompleted()
                }

                override fun onNext(event: RelayConnectionEvent) {
                    this@RelayClientImpl.onNext(event)
                }

                override fun onError(e: Throwable) {
                    this@RelayClientImpl.onError(e)
                }
            })

        state = RelayClientState.CONNECTING
    }

    override fun disconnect() {
        val connection = relayConnection
        if (connection == null) {
            log.warn("Disconnect requested but not connected, ignoring")
            return
        }
        connection.disconnect()
        state = RelayClientState.DISCONNECTING
        wasDisconnectRequested = true
    }

    override fun sendMessage(to: UserId, content: RelayMessageBundle, messageId: String) {
        log.info("Sending message <<{}>> to <<{}>>", messageId, to.long)
        val connection = getAuthConnectionOrThrow()
        connection.sendMessage(createSendMessageMessage(credentials, to, content, messageId))
    }

    override fun sendMessageReceivedAck(messageId: String) {
        log.info("Sending ack to server for message <<{}>>", messageId)
        val connection = getAuthConnectionOrThrow()

        connection.sendMessage(createMessageReceivedMessage(credentials, messageId))
    }

    override fun sendPing() {
        log.debug("PING")
        val connection = getAuthConnectionOrThrow()
        connection.sendMessage(createPingMessage())
    }
}