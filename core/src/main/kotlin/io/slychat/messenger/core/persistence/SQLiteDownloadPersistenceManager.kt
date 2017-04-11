package io.slychat.messenger.core.persistence

import com.almworks.sqlite4java.SQLiteConnection
import com.almworks.sqlite4java.SQLiteStatement
import io.slychat.messenger.core.persistence.sqlite.*
import nl.komponents.kovenant.Promise

class SQLiteDownloadPersistenceManager(
    private val sqlitePersistenceManager: SQLitePersistenceManager
) : DownloadPersistenceManager {
    private val fileUtils = FileUtils()

    private fun insertDownload(connection: SQLiteConnection, download: Download) {
        //language=SQLite
        val sql = """
INSERT INTO
    downloads
    (id, file_id, state, file_path, remote_file_path, do_decrypt, error)
VALUES
    (?, ?, ?, ?, ?, ?, ?)
"""

        connection.withPrepared(sql) {
            it.bind(1, download.id)
            it.bind(2, download.fileId)
            it.bind(3, download.state)
            it.bind(4, download.filePath)
            it.bind(5, download.remoteFilePath)
            it.bind(6, download.doDecrypt)
            it.bind(7, download.error)
            it.step()
        }
    }

    override fun add(downloads: List<Download>): Promise<Unit, Exception> = sqlitePersistenceManager.runQuery { connection ->
        connection.withTransaction {
            downloads.forEach {
                insertDownload(connection, it)
            }
        }
    }

    override fun remove(downloadIds: List<String>): Promise<Unit, Exception> {
        if (downloadIds.isEmpty())
            return Promise.ofSuccess(Unit)

        return sqlitePersistenceManager.runQuery { connection ->
            //language=SQLite
            val sql = """
DELETE FROM
    downloads
WHERE
    id = ?
"""

            connection.withTransaction {
                it.withPrepared(sql) { stmt ->
                    downloadIds.forEach {
                        stmt.bind(1, it)
                        stmt.step()
                        if (connection.changes <= 0)
                            throw InvalidDownloadException(it)
                        stmt.reset()
                    }
                }
            }
        }
    }

    override fun setState(downloadId: String, state: DownloadState): Promise<Unit, Exception> = sqlitePersistenceManager.runQuery {
        //language=SQLite
        val sql = """
UPDATE
    downloads
SET
    state = ?,
    error = null
WHERE
    id = ?
"""
        it.withPrepared(sql) {
            it.bind(1, state)
            it.bind(2, downloadId)
            it.step()
        }

        if (it.changes <= 0)
            throw InvalidDownloadException(downloadId)
    }

    override fun setError(downloadId: String, error: DownloadError?): Promise<Unit, Exception> = sqlitePersistenceManager.runQuery {
        //language=SQLite
        val sql = """
UPDATE
    downloads
SET
    error = ?
WHERE
    id = ?
"""

        it.withPrepared(sql) {
            it.bind(1, error)
            it.bind(2, downloadId)
            it.step()
        }

        if (it.changes <= 0)
            throw InvalidDownloadException(downloadId)
    }

    override fun getAll(): Promise<List<DownloadInfo>, Exception> = sqlitePersistenceManager.runQuery {
        //language=SQLite
        val sql = """
SELECT
    d.id, d.file_id, d.state,
    d.file_path, d.remote_file_path, d.do_decrypt,
    d.error,

    f.id, f.share_key, f.last_update_version,
    f.is_deleted, f.creation_date, f.modification_date,
    f.remote_file_size, f.file_key, f.file_name,
    f.directory, f.cipher_id, f.chunk_size,
    f.file_size, f.mime_type, f.shared_from_user_id,
    f.shared_from_group_id
FROM
    downloads AS d
JOIN
    files AS f
ON
    f.id = d.file_id
"""

        it.withPrepared(sql) {
            it.map {
                DownloadInfo(
                    rowToDownload(it),
                    fileUtils.rowToRemoteFile(it, 7)
                )
            }
        }
    }

    override fun get(downloadId: String): Promise<Download?, Exception> = sqlitePersistenceManager.runQuery {
        //language=SQLite
        val sql = """
SELECT
    id, file_id, state, file_path, remote_file_path, do_decrypt, error
FROM
    downloads
WHERE
    id = ?
"""

        it.withPrepared(sql) {
            it.bind(1, downloadId)
            if (it.step())
                rowToDownload(it)
            else
                null
        }
    }

    private fun rowToDownload(stmt: SQLiteStatement): Download {
        return Download(
            stmt.columnString(0),
            stmt.columnString(1),
            DownloadState.valueOf(stmt.columnString(2)),
            stmt.columnString(3),
            stmt.columnString(4),
            stmt.columnBool(5),
            stmt.columnString(6)?.let { DownloadError.valueOf(it) }
        )
    }
}