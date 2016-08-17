package io.slychat.messenger.core.persistence.sqlite

import com.almworks.sqlite4java.SQLiteConnection
import com.almworks.sqlite4java.SQLiteException
import io.slychat.messenger.testutils.withTempFile
import org.assertj.core.api.Assertions.assertThat
import org.junit.BeforeClass
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseMigrationTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun loadLibrary() {
            SQLitePreKeyPersistenceManager::class.java.loadSQLiteLibraryFromResources()
        }
    }

    fun withTestDatabase(from: Int, to: Int, body: (SQLitePersistenceManager, SQLiteConnection) -> Unit) {
        val path = "/migration-db/%02dto%02d.db".format(from, to)
        javaClass.getResource(path) ?: throw RuntimeException("Missing migration test db: $path")

        withTempFile { file ->
            javaClass.getResourceAsStream(path).use { inputStream ->
                file.outputStream().use { inputStream.copyTo(it) }
            }

            val persistenceManager = SQLitePersistenceManager(file, null, null)
            try {
                persistenceManager.init(to)
                assertEquals(to, persistenceManager.currentDatabaseVersionSync(), "Invalid database version after init")
                try {
                    persistenceManager.syncRunQuery { connection ->
                        body(persistenceManager, connection)
                    }
                }
                catch (e: SQLitePersistenceManagerErrorException) {
                    throw e.cause!!
                }
            }
            finally {
                persistenceManager.shutdown()
            }
        }
    }

    fun getTableDef(connection: SQLiteConnection, tableName: String): String {
        return connection.prepare("""SELECT sql FROM sqlite_master WHERE type="table" and name=?""").use { stmt ->
            stmt.bind(1, tableName)
            if (!stmt.step())
                throw RuntimeException("Missing table: $tableName")
            stmt.columnString(0).toLowerCase()
        }
    }

    //XXX really low tech, but works
    fun assertColDef(connection: SQLiteConnection, tableName: String, colDef: String) {
        val sql = getTableDef(connection, tableName)
        assertTrue(sql.contains(colDef.toLowerCase(), true), "Missing column def: $colDef")
    }

    fun assertNoColDef(connection: SQLiteConnection, tableName: String, colDef: String) {
        val sql = getTableDef(connection, tableName)
        assertFalse(sql.contains(colDef.toLowerCase(), true), "Found column def: $colDef")
    }

    fun assertTableExists(connection: SQLiteConnection, tableName: String) {
        try {
            connection.prepare("SELECT 1 FROM $tableName").use { stmt ->
                stmt.step()
            }
        }
        catch (e: SQLiteException) {
            if (e.message?.contains("no such table") ?: false)
                throw AssertionError("Table $tableName is missing")
            else
                throw e
        }
    }

    fun assertTableNotExists(connection: SQLiteConnection, tableName: String) {
        try {
            connection.prepare("SELECT 1 FROM $tableName").use { stmt ->
                stmt.step()
            }
            throw AssertionError("Table $tableName exists")
        }
        catch (e: SQLiteException) {
            if (e.message?.contains("no such table") ?: false)
                return
            else
                throw e
        }

    }

    fun assertTableRowCount(connection: SQLiteConnection, tableName: String, rowCount: Int) {
        connection.withPrepared("SELECT count(1) FROM $tableName") { stmt ->
            stmt.step()
            val n = stmt.columnInt(0)
            //make sure we discarded the old contact session
            assertEquals(rowCount, n, "Invalid number of rows")
        }
    }

    fun check0To1(persistenceManager: SQLitePersistenceManager, connection: SQLiteConnection) {
        ConversationTable.getConversationTableNames(connection).forEach { tableName ->
            assertColDef(connection, tableName, "received_timestamp INTEGER NOT NULL")

            connection.prepare("SELECT id, timestamp, received_timestamp FROM $tableName").use { stmt ->
                stmt.foreach {
                    val id = stmt.columnString(0)
                    val timestamp = stmt.columnLong(1)
                    val receivedTimestamp = stmt.columnLong(2)

                    assertEquals(timestamp, receivedTimestamp, "Message id=$id has an invalid timestamp")
                }
            }
        }
    }

    @Test
    fun `migration 0 to 1`() {
        withTestDatabase(0, 1) { persistenceManager, connection ->
            check0To1(persistenceManager, connection)
        }
    }

    fun check1To2(persistenceManager: SQLitePersistenceManager, connection: SQLiteConnection) {
        assertTableExists(connection, "package_queue")
    }

    @Test
    fun `migration 1 to 2`() {
        withTestDatabase(1, 2) { persistenceManager, connection ->
            check1To2(persistenceManager, connection)
        }
    }

    fun check2To3(persistenceManager: SQLitePersistenceManager, connection: SQLiteConnection) {
        assertColDef(connection, "contacts", "is_pending INTEGER NOT NULL")
    }

    @Test
    fun `migration 2 to 3`() {
        withTestDatabase(2, 3) { persistenceManager, connection ->
            check2To3(persistenceManager, connection)
        }
    }

    fun check3To4(persistenceManager: SQLitePersistenceManager, connection: SQLiteConnection) {
        assertTableExists(connection, "remote_contact_updates")
    }

    @Test
    fun `migration 3 to 4`() {
        withTestDatabase(3, 4) { persistenceManager, connection ->
            check3To4(persistenceManager, connection)
        }
    }

    fun check4To5(persistenceManager: SQLitePersistenceManager, connection: SQLiteConnection) {
        //check conversion + old table drop
        assertTableNotExists(connection, "signal_sessions_old")

        assertTableRowCount(connection, "signal_sessions", 2)

        connection.withPrepared("SELECT contact_id, device_id, session FROM signal_sessions WHERE contact_id=154") { stmt ->
            stmt.foreach {
                assertEquals(154, stmt.columnLong(0), "Invalid user id")
                assertEquals(1, stmt.columnInt(1), "Invalid device id")
                assertTrue(stmt.columnBlob(2).size != 0, "Empty session")
            }
        }
    }

    @Test
    fun `migration 4 to 5`() {
        withTestDatabase(4, 5) { persistenceManager, connection ->
            check4To5(persistenceManager, connection)
        }
    }

    fun check5To6(persistenceManager: SQLitePersistenceManager, connection: SQLiteConnection) {
        assertTableRowCount(connection, "conversation_info", 1)
        assertColDef(connection, "conversation_info", "FOREIGN KEY (contact_id) REFERENCES contacts (id) ON DELETE CASCADE")

        //TODO make this process generic somehow for future migrations

        connection.withPrepared("SELECT contact_id, unread_count, last_message, last_timestamp FROM conversation_info") { stmt ->
            stmt.step()

            assertEquals(154, stmt.columnLong(0), "Invalid contact_id")
            assertEquals(0, stmt.columnInt(1), "Invalid unread_count")
            assertEquals("Test", stmt.columnString(2), "Invalid last_message")
            assertEquals(1467047660422, stmt.columnLong(3), "Invalid last_timestamp")
        }
    }

    @Test
    fun `migration 5 to 6`() {
        withTestDatabase(5, 6) { persistenceManager, connection ->
            check5To6(persistenceManager, connection)
        }
    }

    private data class ContactV7(
        val id: Long,
        val email: String,
        val name: String,
        val allowedMessageLevel: Int,
        val phoneNumber: String?,
        val publicKey: String
    )

    /*
     *  +contacts.allow_message_level
     *  -contacts.is_pending
     *  contacts.public_key[BLOB -> STRING]
     *
     *  +send_message_queue
     *  +groups
     *  +group_members
     *  +group_conversation_info
     */
    private fun check6To7(persistenceManager: SQLitePersistenceManager, connection: SQLiteConnection) {
        assertColDef(connection, "contacts", "allowed_message_level INTEGER NOT NULL")
        assertNoColDef(connection, "contacts", "is_pending INTEGER NOT NULL")
        assertColDef(connection, "contacts", "public_key TEXT NOT NULL")

        //this is just to make sure the migration ran with foreign_keys=off; if it didn't this'll have contacts_old
        //as the referenced table name
        val def = getTableDef(connection, "conversation_info")
        assertTrue("references contacts (id)" in def, "Migration ran with foreign_keys=on")

        assertTableExists(connection, "send_message_queue")
        assertTableExists(connection, "groups")
        assertTableExists(connection, "group_members")
        assertTableExists(connection, "group_conversation_info")

        connection.withPrepared("SELECT id, email, name, allowed_message_level, phone_number, public_key FROM contacts ORDER BY id") { stmt ->
            val expected = listOf(
                ContactV7(153, "d@a.com", "Desktop", 2, "15145555555", "05427fbf4460492480d82e42f8dba6d381edeef340646150944b377dc581b4e31d"),
                ContactV7(154, "e@a.com", "Eeee", 2, "15145555554", "054fe37c651f261bd541f4df980b4aaee467ccd6c5cb1b8a7d898cf86c91acc56b")
            )

            val found = ArrayList<ContactV7>()

            while (stmt.step()) {
                found.add(ContactV7(
                    stmt.columnLong(0),
                    stmt.columnString(1),
                    stmt.columnString(2),
                    stmt.columnInt(3),
                    stmt.columnString(4),
                    stmt.columnString(5)
                ))
            }

            assertThat(found).apply {
                `as`("Contacts")
                containsAll(expected)
            }
        }
    }

    @Test
    fun `migration 6 to 7`() {
        withTestDatabase(6, 7) { persistenceManager, connection ->
            check6To7(persistenceManager, connection)
        }
    }

    @Test
    fun `migration 7 to 8`() {
        withTestDatabase(7, 8) { persistenceManager, connection ->
            check7To8(persistenceManager, connection)
        }
    }

    private data class RemoteContactUpdateV7(val userId: Long, val allowedMessageLevel: Int)

    private fun check7To8(persistenceManager: SQLitePersistenceManager, connection: SQLiteConnection) {
        assertColDef(connection, "remote_contact_updates",  "allowed_message_level INTEGER NOT NULL")
        assertNoColDef(connection, "remote_contact_updates", "type TEXT NOT NULL")

        val remoteUpdates = connection.withPrepared("SELECT contact_id, allowed_message_level FROM remote_contact_updates") { stmt ->
            stmt.map { RemoteContactUpdateV7(stmt.columnLong(0), stmt.columnInt(1)) }
        }

        val expected = listOf(
            RemoteContactUpdateV7(153, 2),
            RemoteContactUpdateV7(157, 1)
        )

        assertThat(remoteUpdates).apply {
            `as`("Pending remote updates should be updated")
            containsOnlyElementsOf(expected)
        }
    }

    @Test
    fun `migration 8 to 9`() {
        withTestDatabase(8, 9) { persistenceManager, connection ->
            check8To9(persistenceManager, connection)
        }
    }

    private fun check8To9(persistenceManager: SQLitePersistenceManager, connection: SQLiteConnection) {
        assertNoColDef(connection, "remote_contact_updates", "allowed_message_level INTEGER NOT NULL")
        assertTableExists(connection, "remote_group_updates")

        val contactUpdates = connection.withPrepared("SELECT contact_id FROM remote_contact_updates") { stmt ->
            stmt.mapToSet { it.columnLong(0) }
        }

        assertThat(contactUpdates).apply {
            `as`("Should contain the upgraded row")
            containsOnly(153L)
        }
    }

    @Test
    fun `migration 9 to 10`() {
        withTestDatabase(9, 10) { persistenceManager, connection ->
            check9to10(persistenceManager, connection)
        }
    }

    private fun check9to10(persistenceManager: SQLitePersistenceManager, connection: SQLiteConnection) {
        assertTableExists(connection, "address_book_remote_version")
    }
}