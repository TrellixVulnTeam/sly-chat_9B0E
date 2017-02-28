package io.slychat.messenger.core.http.api.storage

import io.slychat.messenger.core.Quota
import io.slychat.messenger.core.UserCredentials
import io.slychat.messenger.core.http.api.AcceptShareRequest
import io.slychat.messenger.core.http.api.AcceptShareResponse
import io.slychat.messenger.core.http.api.UpdateMetadataResponse

interface StorageClient {
    fun getQuota(userCredentials: UserCredentials): Quota

    fun getFileList(userCredentials: UserCredentials, sinceVersion: Int): FileListResponse

    fun getFileInfo(userCredentials : UserCredentials, fileId: String): GetFileInfoResponse

    fun updateMetadata(userCredentials: UserCredentials, fileId: String, newMetadata: ByteArray): UpdateMetadataResponse

    //may no longer be present if the original owner deleted it
    fun acceptShare(userCredentials: UserCredentials, request: AcceptShareRequest): AcceptShareResponse

    //if an error occurs, ApiException is thrown
    //if the file is missing, null is returned
    fun downloadFile(userCredentials: UserCredentials, fileId: String): DownloadFileResponse?
}
