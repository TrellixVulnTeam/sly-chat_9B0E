package io.slychat.messenger.core.relay.base

/** Low-level RelayConnection event. */
interface RelayConnectionEvent

/** Sent when a connection to the relay is established. */
data class RelayConnectionEstablished(val connection: RelayConnection) : RelayConnectionEvent

class RelayConnectionLost() : RelayConnectionEvent

/** Represents an incoming or outgoing message to the relay server. */
class RelayMessage(
    val header: Header,
    val content: ByteArray
) : RelayConnectionEvent

