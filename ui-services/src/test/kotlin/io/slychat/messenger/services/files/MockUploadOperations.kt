package io.slychat.messenger.services.files

import io.slychat.messenger.core.files.RemoteFile
import io.slychat.messenger.core.http.api.upload.NewUploadResponse
import io.slychat.messenger.core.persistence.Upload
import io.slychat.messenger.core.persistence.UploadPart
import io.slychat.messenger.core.randomQuota
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import rx.Observable
import rx.schedulers.TestScheduler
import rx.subjects.PublishSubject
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.fail

class MockUploadOperations(private val scheduler: TestScheduler) : UploadOperations {
    var createDeferred = deferred<NewUploadResponse, Exception>()
    var completeDeferred = deferred<Unit, Exception>()
    var uploadSubjects = HashMap<Int, PublishSubject<Long>>()
    val cacheSubjects = HashMap<String, PublishSubject<Long>>()

    var autoResolveCreate = false

    private data class CreateArgs(val upload: Upload, val file: RemoteFile)
    private data class UploadArgs(val upload: Upload, val part: UploadPart, val file: RemoteFile)
    private data class CacheArgs(val upload: Upload, val file: RemoteFile)

    private var createArgs: CreateArgs? = null
    private var uploadArgs = HashMap<Pair<String, Int>, UploadArgs>()
    private var completeArgs: Upload? = null
    private val unsubscriptions = HashSet<Pair<String, Int>>()

    override fun create(upload: Upload, file: RemoteFile): Promise<NewUploadResponse, Exception> {
        if (autoResolveCreate)
            return Promise.of(NewUploadResponse(null, randomQuota()))

        createArgs = CreateArgs(upload, file)
        return createDeferred.promise
    }

    override fun uploadPart(upload: Upload, part: UploadPart, file: RemoteFile, isCancelled: AtomicBoolean): Observable<Long> {
        uploadArgs[upload.id to part.n] = UploadArgs(upload, part, file)

        if (part.n in uploadSubjects)
            throw RuntimeException("Attempted to upload part ${part.n} twice")

        val s = PublishSubject.create<Long>()
        uploadSubjects[part.n] = s

        return s.doOnUnsubscribe { unsubscriptions.add(upload.id to part.n) }
    }

    fun assertCreateNotCalled() {
        if (createArgs != null)
            fail("create() called with args=$createArgs")
    }

    fun assertCreateCalled(upload: Upload, file: RemoteFile) {
        val args = createArgs ?: fail("create() not called")

        val expected = CreateArgs(upload, file)
        if (args != expected)
            fail("create() called with differing args\nExpected:\n$expected\n\nActual:\n$args")
    }

    fun assertUploadPartNotCalled() {
        if (uploadArgs.isNotEmpty())
            fail("uploadPart() called ${uploadArgs.size}")
    }

    fun assertUploadPartNotCalled(uploadId: String, n: Int) {
        val args = uploadArgs[uploadId to n]
        if (args != null)
            fail("uploadPart() called with args=$args")
    }

    private fun failWithDiff(fnName: String, expected: Any, actual: Any) {
        fail("$fnName() called with differing args\nExpected:\n$expected\n\nActual:\n$actual")
    }

    fun assertUploadPartCalled(upload: Upload, part: UploadPart, file: RemoteFile) {
        val args = uploadArgs[upload.id to part.n] ?: fail("uploadPart() not called for part ${part.n}")

        if (args.upload != upload)
            failWithDiff("uploadPart", upload, args.upload)
        if (args.part != part)
            failWithDiff("uploadPart", part, args.part)
        if (args.file != file)
            failWithDiff("uploadPart", file, args.file)
    }

    private fun getUploadPartSubject(n: Int): PublishSubject<Long> {
        return uploadSubjects[n] ?: fail("uploadPart() not called for part $n")
    }

    fun completeUploadPartOperation(n: Int) {
        getUploadPartSubject(n).onCompleted()
        scheduler.triggerActions()
    }

    fun sendUploadProgress(uploadId: String, partN: Int, transferedBytes: Long) {
        val s = getUploadPartSubject(partN)
        s.onNext(transferedBytes)
        scheduler.triggerActions()
    }

    fun assertUnsubscribed(uploadId: String, partN: Int) {
        if ((uploadId to partN) !in unsubscriptions)
            fail("$uploadId/$partN was not unsubscribed")
    }

    override fun complete(upload: Upload): Promise<Unit, Exception> {
        completeArgs = upload

        return completeDeferred.promise
    }

    fun assertCompleteNotCalled() {
        if (completeArgs != null)
            fail("complete($completeArgs) called")
    }

    fun assertCompleteCalled(upload: Upload) {
        val args = completeArgs ?: fail("complete() not called")

        assertEquals(upload, args, "complete() called with differing args")
    }

    fun completeCompleteUploadOperation() {
        completeDeferred.resolve(Unit)
    }

    fun rejectCompleteUploadOperation(e: Exception) {
        completeDeferred.reject(e)
    }

    override fun cache(upload: Upload, file: RemoteFile): Observable<Long> {
        val s = PublishSubject.create<Long>()
        cacheSubjects[upload.id] = s

        return s
    }

    fun completeCacheOperation(uploadId: String) {
        val s = cacheSubjects[uploadId] ?: fail("cache($uploadId) not called")

        s.onCompleted()
        scheduler.triggerActions()
    }
}