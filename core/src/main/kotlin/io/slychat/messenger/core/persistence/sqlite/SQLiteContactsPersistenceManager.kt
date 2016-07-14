package io.slychat.messenger.core.persistence.sqlite

import com.almworks.sqlite4java.SQLiteConnection
import com.almworks.sqlite4java.SQLiteConstants
import com.almworks.sqlite4java.SQLiteException
import com.almworks.sqlite4java.SQLiteStatement
import io.slychat.messenger.core.PlatformContact
import io.slychat.messenger.core.UserId
import io.slychat.messenger.core.persistence.*
import nl.komponents.kovenant.Promise
import java.util.*

/** A contact is made up of an entry in the contacts table and an associated conv_ table containing their message log. */
class SQLiteContactsPersistenceManager(private val sqlitePersistenceManager: SQLitePersistenceManager) : ContactsPersistenceManager {
    private fun contactInfoFromRow(stmt: SQLiteStatement) =
        ContactInfo(
            UserId(stmt.columnLong(0)),
            stmt.columnString(1),
            stmt.columnString(2),
            AllowedMessageLevel.fromInt(stmt.columnInt(3)),
            stmt.columnInt(4) != 0,
            stmt.columnString(5),
            stmt.columnString(6)
        )

    private fun contactInfoToRow(contactInfo: ContactInfo, stmt: SQLiteStatement) {
        stmt.bind(1, contactInfo.id.long)
        stmt.bind(2, contactInfo.email)
        stmt.bind(3, contactInfo.name)
        stmt.bind(4, contactInfo.allowedMessageLevel.level)
        stmt.bind(5, contactInfo.isPending.toInt())
        stmt.bind(6, contactInfo.phoneNumber)
        stmt.bind(7, contactInfo.publicKey)
    }

    override fun get(userId: UserId): Promise<ContactInfo?, Exception> = sqlitePersistenceManager.runQuery { connection ->
        connection.prepare("SELECT id, email, name, allowed_message_level, is_pending, phone_number, public_key FROM contacts WHERE id=?").use { stmt ->
            stmt.bind(1, userId.long)
            if (!stmt.step())
                null
            else
                contactInfoFromRow(stmt)
        }
    }

    override fun getAll(): Promise<List<ContactInfo>, Exception> = sqlitePersistenceManager.runQuery { connection ->
        connection.prepare("SELECT id, email, name, allowed_message_level, is_pending, phone_number, public_key FROM contacts").use { stmt ->
            val r = ArrayList<ContactInfo>()
            while (stmt.step()) {
                r.add(contactInfoFromRow(stmt))
            }
            r
        }
    }

    override fun exists(userId: UserId): Promise<Boolean, Exception> = sqlitePersistenceManager.runQuery { connection ->
        connection.prepare("SELECT 1 FROM contacts WHERE id=?").use { stmt ->
            stmt.bind(1, userId.long)
            stmt.step()
        }
    }

    override fun exists(users: Set<UserId>): Promise<Set<UserId>, Exception> = sqlitePersistenceManager.runQuery { connection ->
        val sql = "SELECT id FROM contacts WHERE id IN (${getPlaceholders(users.size)})"
        connection.prepare(sql).use { stmt ->
            users.forEachIndexed { i, userId ->
                stmt.bind(i+1, userId.long)
            }

            stmt.mapToSet { UserId(stmt.columnLong(0)) }
        }
    }

    override fun getBlockList(): Promise<Set<UserId>, Exception> = sqlitePersistenceManager.runQuery { connection ->
        connection.withPrepared("SELECT id FROM contacts WHERE allowed_message_level=${AllowedMessageLevel.BLOCKED.level}") { stmt ->
            stmt.mapToSet { UserId(stmt.columnLong(0)) }
        }
    }

    override fun filterBlocked(users: Collection<UserId>): Promise<Set<UserId>, Exception> = sqlitePersistenceManager.runQuery { connection ->
        val ids = users.map { it.long }.joinToString(",")
        val blocked = connection.withPrepared("SELECT id FROM contacts WHERE allowed_message_level == ${AllowedMessageLevel.BLOCKED.level} AND id IN ($ids)") { stmt ->
            stmt.mapToSet { UserId(stmt.columnLong(0)) }
        }

        val filtered = HashSet(users)
        filtered.removeAll(blocked)
        filtered
    }

    override fun getAllConversations(): Promise<List<Conversation>, Exception> = sqlitePersistenceManager.runQuery { connection ->
        val sql = """
SELECT
    id, email, name, allowed_message_level, is_pending, phone_number, public_key,
    unread_count, last_message, last_timestamp
FROM
    contacts
JOIN
    conversation_info
ON
    contacts.id=conversation_info.contact_id
        """

        connection.prepare(sql).use { stmt ->
            stmt.map { stmt ->
                val contact = contactInfoFromRow(stmt)
                val lastTimestamp = if (!stmt.columnNull(9)) stmt.columnLong(9) else null
                val info = ConversationInfo(contact.id, stmt.columnInt(7), stmt.columnString(8), lastTimestamp)
                Conversation(contact, info)
            }
        }
    }

    private fun queryConversationInfo(connection: SQLiteConnection, userId: UserId): ConversationInfo {
        return connection.prepare("SELECT unread_count, last_message, last_timestamp FROM conversation_info WHERE contact_id=?").use { stmt ->
            stmt.bind(1, userId.long)
            if (!stmt.step())
                throw InvalidConversationException(userId)

            val unreadCount = stmt.columnInt(0)
            val lastMessage = stmt.columnString(1)
            val lastTimestamp = if (!stmt.columnNull(2)) stmt.columnLong(2) else null
            ConversationInfo(userId, unreadCount, lastMessage, lastTimestamp)
        }
    }


    override fun getConversationInfo(userId: UserId): Promise<ConversationInfo, Exception> = sqlitePersistenceManager.runQuery { connection ->
        try {
            queryConversationInfo(connection, userId)
        }
        catch (e: SQLiteException) {
            if (isInvalidTableException(e))
                throw InvalidConversationException(userId)
            else
                throw e
        }
    }

    override fun markConversationAsRead(userId: UserId): Promise<Unit, Exception> = sqlitePersistenceManager.runQuery { connection ->
        connection.prepare("UPDATE conversation_info SET unread_count=0 WHERE contact_id=?").use { stmt ->
            stmt.bind(1, userId.long)
            stmt.step()
        }
        if (connection.changes <= 0)
            throw InvalidConversationException(userId)

        Unit
    }


    private fun searchByLikeField(connection: SQLiteConnection, fieldName: String, searchValue: String): List<ContactInfo> =
        connection.prepare("SELECT id, email, name, allowed_message_level, is_pending, phone_number, public_key FROM contacts WHERE $fieldName LIKE ? ESCAPE '!'").use { stmt ->
            val escaped = escapeLikeString(searchValue, '!')
            stmt.bind(1, "%$escaped%")
            val r = ArrayList<ContactInfo>()
            while (stmt.step()) {
                r.add(contactInfoFromRow(stmt))
            }
            r
        }

    override fun searchByEmail(email: String): Promise<List<ContactInfo>, Exception> = sqlitePersistenceManager.runQuery {
        searchByLikeField(it, "email", email)
    }

    override fun searchByPhoneNumber(phoneNumber: String): Promise<List<ContactInfo>, Exception> = sqlitePersistenceManager.runQuery {
        searchByLikeField(it, "phone_number", phoneNumber)
    }

    override fun searchByName(name: String): Promise<List<ContactInfo>, Exception> = sqlitePersistenceManager.runQuery {
        searchByLikeField(it, "name", name)
    }

    //never call when not inside a transition
    private fun removeContactNoTransaction(connection: SQLiteConnection, userId: UserId): Boolean {
        ConversationTable.delete(connection, userId)

        connection.prepare("DELETE FROM contacts WHERE id=?").use { stmt ->
            stmt.bind(1, userId.long)

            stmt.step()
        }

        val wasRemoved = connection.changes > 0

        return wasRemoved
    }

    //never call when not inside a transition
    //is here for bulk addition within a single transaction when syncing up the contacts list
    private fun addContactNoTransaction(connection: SQLiteConnection, contactInfo: ContactInfo): Boolean {
        try {
            connection.prepare("INSERT INTO contacts (id, email, name, allowed_message_level, is_pending, phone_number, public_key) VALUES (?, ?, ?, ?, ?, ?, ?)").use { stmt ->
                contactInfoToRow(contactInfo, stmt)
                stmt.step()
            }

            connection.prepare("INSERT INTO conversation_info (contact_id, unread_count, last_message) VALUES (?, 0, NULL)").use { stmt ->
                stmt.bind(1, contactInfo.id.long)
                stmt.step()
            }

            ConversationTable.create(connection, contactInfo.id)
        }
        catch (e: SQLiteException) {
            if (e.baseErrorCode == SQLiteConstants.SQLITE_CONSTRAINT)
                return false

            throw e
        }

        return true
    }

    override fun add(contactInfo: ContactInfo): Promise<Boolean, Exception> = sqlitePersistenceManager.runQuery { connection ->
        connection.withTransaction {
            val added = addContactNoTransaction(connection, contactInfo)
            if (added) {
                val remoteUpdates = listOf(RemoteContactUpdate(contactInfo.id, RemoteContactUpdateType.ADD))
                addRemoteUpdateNoTransaction(connection, remoteUpdates)
            }

            added
        }
    }

    override fun add(contacts: Collection<ContactInfo>): Promise<Set<ContactInfo>, Exception> = sqlitePersistenceManager.runQuery { connection ->
        val newContacts = HashSet<ContactInfo>()

        connection.withTransaction {
            contacts.forEach {
                if (addContactNoTransaction(connection, it))
                    newContacts.add(it)
            }

            val remoteUpdates = newContacts
                .map { RemoteContactUpdate(it.id, RemoteContactUpdateType.ADD) }
            addRemoteUpdateNoTransaction(connection, remoteUpdates)
        }

        newContacts
    }

    override fun update(contactInfo: ContactInfo): Promise<Unit, Exception> = sqlitePersistenceManager.runQuery { connection ->
        connection.prepare("UPDATE contacts SET name=?, phone_number=?, public_key=? WHERE email=?").use { stmt ->
            stmt.bind(1, contactInfo.name)
            stmt.bind(2, contactInfo.phoneNumber)
            stmt.bind(3, contactInfo.publicKey)
            stmt.bind(4, contactInfo.email)

            stmt.step()
            if (connection.changes <= 0)
                throw InvalidContactException(contactInfo.id)
        }
    }

    override fun remove(userId: UserId): Promise<Boolean, Exception>  = sqlitePersistenceManager.runQuery { connection ->
        connection.withTransaction {
            val wasRemoved = removeContactNoTransaction(connection, userId)
            if (wasRemoved) {
                val remoteUpdates = listOf(RemoteContactUpdate(userId, RemoteContactUpdateType.REMOVE))
                addRemoteUpdateNoTransaction(connection, remoteUpdates)
            }

            wasRemoved
        }
    }

    override fun getDiff(ids: Collection<UserId>): Promise<ContactListDiff, Exception> = sqlitePersistenceManager.runQuery { connection ->
        val remoteIds = ids.toSet()

        val localIds = connection.prepare("SELECT id FROM contacts").use { stmt ->
            val r = HashSet<UserId>()
            while (stmt.step()) {
                r.add(UserId(stmt.columnLong(0)))
            }
            r
        }

        val removedEmails = HashSet(localIds)
        removedEmails.removeAll(remoteIds)

        val addedEmails = HashSet(remoteIds)
        addedEmails.removeAll(localIds)

        ContactListDiff(addedEmails, removedEmails)
    }

    override fun applyDiff(newContacts: Collection<ContactInfo>, removedContacts: Collection<UserId>): Promise<Unit, Exception> = sqlitePersistenceManager.runQuery { connection ->
        connection.withTransaction {
            newContacts.forEach { addContactNoTransaction(connection, it) }
            removedContacts.forEach { removeContactNoTransaction(connection, it) }
        }
    }

    override fun findMissing(platformContacts: List<PlatformContact>): Promise<List<PlatformContact>, Exception> = sqlitePersistenceManager.runQuery { connection ->
        val missing = ArrayList<PlatformContact>()

        for (contact in platformContacts) {
            val emails = contact.emails
            val phoneNumbers = contact.phoneNumbers

            val selection = ArrayList<String>()

            if (emails.isNotEmpty())
                selection.add("email IN (${getPlaceholders(emails.size)})")

            if (phoneNumbers.isNotEmpty())
                selection.add("phone_number IN (${getPlaceholders(phoneNumbers.size)})")

            if (selection.isEmpty())
                continue

            val sql = "SELECT 1 FROM contacts WHERE " + selection.joinToString(" OR ") + " LIMIT 1"

            connection.prepare(sql).use { stmt ->
                var i = 1

                for (email in emails) {
                    stmt.bind(i, email)
                    i += 1
                }

                for (phoneNumber in phoneNumbers) {
                    stmt.bind(i, phoneNumber)
                    i += 1
                }

                if (!stmt.step())
                    missing.add(contact)
            }
        }

        missing
    }

    override fun getPending(): Promise<List<ContactInfo>, Exception> = sqlitePersistenceManager.runQuery { connection ->
        connection.withPrepared("SELECT id, email, name, allowed_message_level, is_pending, phone_number, public_key FROM contacts WHERE is_pending=1") { stmt ->
            stmt.map { contactInfoFromRow(stmt) }
        }
    }

    override fun markAccepted(users: Set<UserId>): Promise<Unit, Exception> = sqlitePersistenceManager.runQuery { connection ->
        connection.withTransaction {
            connection.withPrepared("UPDATE contacts SET is_pending=0 WHERE id=?") { stmt ->
                users.forEach {
                    stmt.bind(1, it.long)
                    stmt.step()
                    stmt.reset()
                }
            }
        }
    }

    override fun getUnadded(): Promise<Set<UserId>, Exception> = sqlitePersistenceManager.runQuery { connection ->
        val sql = "SELECT DISTINCT user_id FROM package_queue LEFT OUTER JOIN contacts ON user_id=contacts.id WHERE id IS null"
        connection.withPrepared(sql) { stmt ->
            stmt.mapToSet { UserId(stmt.columnLong(0)) }
        }
    }

    private fun addRemoteUpdateNoTransaction(connection: SQLiteConnection, remoteUpdates: Collection<RemoteContactUpdate>) {
        connection.batchInsert("INSERT OR REPLACE INTO remote_contact_updates (contact_id, type) VALUES (?, ?)", remoteUpdates) { stmt, item ->
            stmt.bind(1, item.userId.long)
            stmt.bind(2, item.type.toString())
        }
    }

    override fun addRemoteUpdate(remoteUpdates: Collection<RemoteContactUpdate>): Promise<Unit, Exception> = sqlitePersistenceManager.runQuery { connection ->
        connection.withTransaction {
            addRemoteUpdateNoTransaction(connection, remoteUpdates)
        }
    }

    override fun getRemoteUpdates(): Promise<List<RemoteContactUpdate>, Exception> = sqlitePersistenceManager.runQuery { connection ->
        connection.withPrepared("SELECT contact_id, type FROM remote_contact_updates") { stmt ->
            stmt.map {
                val userId = UserId(stmt.columnLong(0))
                val type = RemoteContactUpdateType.valueOf(stmt.columnString(1))
                RemoteContactUpdate(userId, type)
            }
        }
    }

    override fun removeRemoteUpdates(remoteUpdates: Collection<RemoteContactUpdate>): Promise<Unit, Exception> = sqlitePersistenceManager.runQuery { connection ->
        connection.withTransaction {
            connection.withPrepared("DELETE FROM remote_contact_updates WHERE contact_id=?") { stmt ->
                remoteUpdates.forEach { item ->
                    stmt.bind(1, item.userId.long)
                    stmt.step()
                    stmt.reset()
                }
            }
        }
    }

    override fun updateMessageLevel(user: UserId, newMessageLevel: AllowedMessageLevel): Promise<Unit, Exception> = sqlitePersistenceManager.runQuery { connection ->
        connection.withTransaction {
            connection.withPrepared("UPDATE contacts SET allowed_message_level=?, is_pending=0 WHERE id=?") { stmt ->
                stmt.bind(1, newMessageLevel.level)
                stmt.bind(2, user.long)
                stmt.step()
            }

            Unit
        }
    }
}
