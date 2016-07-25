package io.slychat.messenger.services.contacts

import com.nhaarman.mockito_kotlin.*
import io.slychat.messenger.core.SlyAddress
import io.slychat.messenger.core.UserId
import io.slychat.messenger.core.crypto.generateNewKeyVault
import io.slychat.messenger.core.http.api.contacts.*
import io.slychat.messenger.core.persistence.*
import io.slychat.messenger.core.randomUserId
import io.slychat.messenger.core.randomUserIds
import io.slychat.messenger.services.PlatformContacts
import io.slychat.messenger.services.UserData
import io.slychat.messenger.services.crypto.MockAuthTokenManager
import io.slychat.messenger.testutils.KovenantTestModeRule
import io.slychat.messenger.testutils.thenAnswerSuccess
import io.slychat.messenger.testutils.thenReturn
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import kotlin.test.assertEquals

class ContactSyncJobImplTest {
    companion object {
        @JvmField
        @ClassRule
        val kovenantTestMode = KovenantTestModeRule()

        val keyVault = generateNewKeyVault("test")
    }

    val contactAsyncClient: ContactAsyncClient = mock()
    val contactListAsyncClient: ContactListAsyncClient = mock()
    val contactsPersistenceManager: ContactsPersistenceManager = mock()
    val userLoginData = UserData(SlyAddress(randomUserId(), 1), keyVault)
    val accountInfoPersistenceManager: AccountInfoPersistenceManager = mock()
    val platformContacts: PlatformContacts = mock()

    @Before
    fun before() {
        whenever(accountInfoPersistenceManager.retrieve()).thenReturn(
            AccountInfo(userLoginData.userId, "name", "email", "15555555555", 1)
        )

        whenever(platformContacts.fetchContacts()).thenReturn(emptyList())

        whenever(contactsPersistenceManager.findMissing(any())).thenReturn(listOf())
        whenever(contactsPersistenceManager.add(any<Collection<ContactInfo>>())).thenReturn(emptySet())
        whenever(contactsPersistenceManager.getRemoteUpdates()).thenReturn(emptyList())
        whenever(contactsPersistenceManager.applyDiff(any(), any())).thenReturn(Unit)
        whenever(contactsPersistenceManager.exists(anySet())).thenAnswerSuccess {
            val a = it.arguments[0]
            @Suppress("UNCHECKED_CAST")
            (a as Set<UserId>)
        }

        whenever(contactAsyncClient.findLocalContacts(any(), any())).thenReturn(FindLocalContactsResponse(emptyList()))
        whenever(contactAsyncClient.fetchContactInfoById(any(), any())).thenReturn(FetchContactInfoByIdResponse(emptyList()))

        whenever(contactListAsyncClient.getContacts(any())).thenReturn(GetContactsResponse(emptyList()))
    }

    fun newJob(): ContactSyncJobImpl {
        return ContactSyncJobImpl(
            MockAuthTokenManager(),
            contactAsyncClient,
            contactListAsyncClient,
            contactsPersistenceManager,
            userLoginData,
            accountInfoPersistenceManager,
            platformContacts
        )
    }

    fun runRemoteSync() {
        val syncJob = newJob()

        val description = ContactSyncJobDescription()
        description.doRemoteSync()

        syncJob.run(description).get()
    }

    @Test
    fun `a remote sync should fetch any missing contact info`() {
        val missing = randomUserIds()
        val remoteEntries = encryptRemoteContactEntries(keyVault, missing.map { RemoteContactUpdate(it, AllowedMessageLevel.ALL) })

        whenever(contactListAsyncClient.getContacts(any())).thenReturn(GetContactsResponse(remoteEntries))
        whenever(contactsPersistenceManager.exists(missing)).thenReturn(emptySet())

        runRemoteSync()

        verify(contactAsyncClient).fetchContactInfoById(any(), capture {
            assertThat(it.ids).apply {
                `as`("Missing ids should be looked up")
                containsOnlyElementsOf(missing)
            }
        })
    }

    @Test
    fun `a remote sync should add missing contacts with the proper message levels`() {
        val missing = randomUserIds(3)
        val messageLevels = missing.zip(listOf(
            AllowedMessageLevel.ALL,
            AllowedMessageLevel.BLOCKED,
            AllowedMessageLevel.GROUP_ONLY
        )).toMap()

        val apiContacts = missing.map { ApiContactInfo(it, "$it@a.com", it.toString(), it.toString(), it.toString()) }
        val remoteEntries = encryptRemoteContactEntries(keyVault, missing.map { RemoteContactUpdate(it, messageLevels[it]!!) })

        whenever(contactListAsyncClient.getContacts(any())).thenReturn(GetContactsResponse(remoteEntries))
        whenever(contactsPersistenceManager.exists(missing)).thenReturn(emptySet())
        whenever(contactAsyncClient.fetchContactInfoById(any(), any())).thenReturn(FetchContactInfoByIdResponse(apiContacts))

        runRemoteSync()

        verify(contactsPersistenceManager).applyDiff(capture {
            assertEquals(missing.size, it.size, "New contacts size doesn't match")

            it.forEach {
                assertEquals(messageLevels[it.id]!!, it.allowedMessageLevel, "Invalid message level")
            }
        }, any())
    }

    @Test
    fun `a remote sync should update existing contacts with the proper message level`() {
        val present = randomUserIds(3)
        val messageLevels = listOf(AllowedMessageLevel.ALL, AllowedMessageLevel.GROUP_ONLY, AllowedMessageLevel.BLOCKED)
        val remoteUpdates = present.zip(messageLevels).map { RemoteContactUpdate(it.first, it.second) }
        val remoteEntries = encryptRemoteContactEntries(keyVault, remoteUpdates)

        whenever(contactListAsyncClient.getContacts(any())).thenReturn(GetContactsResponse(remoteEntries))
        whenever(contactsPersistenceManager.exists(present)).thenReturn(present)

        runRemoteSync()

        verify(contactsPersistenceManager).applyDiff(any(), capture {
            assertThat(it).apply {
                `as`("Existing contacts should have their message levels updated")
                containsOnlyElementsOf(remoteUpdates)
            }
        })
    }

    @Test
    fun `a local sync should not issue a remote request if no missing platform contacts are found`() {
        TODO()
    }

    @Test
    fun `a remote sync should not issue a remote request if no contacts need to be added`() {
        runRemoteSync()

        verify(contactAsyncClient, never()).fetchContactInfoById(any(), any())
    }
}