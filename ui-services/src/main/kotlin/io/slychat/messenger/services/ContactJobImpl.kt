package io.slychat.messenger.services

import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.slychat.messenger.core.UserId
import io.slychat.messenger.core.http.api.contacts.*
import io.slychat.messenger.core.persistence.*
import io.slychat.messenger.services.auth.AuthTokenManager
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.functional.map
import org.slf4j.LoggerFactory
import java.util.*

class ContactJobImpl(
    private val authTokenManager: AuthTokenManager,
    private val contactClient: ContactAsyncClient,
    private val contactListClient: ContactListAsyncClient,
    private val contactsPersistenceManager: ContactsPersistenceManager,
    private val userLoginData: UserData,
    private val accountInfoPersistenceManager: AccountInfoPersistenceManager,
    private val platformContacts: PlatformContacts
) : ContactJob {
    private val log = LoggerFactory.getLogger(javaClass)

    val invalidContacts = HashSet<UserId>()
    val newContacts = HashSet<ContactInfo>()

    private fun getDefaultRegionCode(): Promise<String, Exception> {
        return accountInfoPersistenceManager.retrieve() map { getAccountRegionCode(it!!) }
    }

    private fun handleContactLookupResponse(users: Set<UserId>, response: FetchContactInfoByIdResponse): Promise<Unit, Exception> {
        val foundIds = response.contacts.mapTo(HashSet()) { it.id }

        val missing = HashSet(users)
        missing.removeAll(foundIds)

        //XXX blacklist? at least temporarily or something
        if (missing.isNotEmpty())
            invalidContacts.addAll(missing)

        val contacts = response.contacts.map { it.toCore(true, AllowedMessageLevel.GROUP_ONLY) }

        return contactsPersistenceManager.add(contacts) mapUi { newContacts ->
            this.newContacts.addAll(newContacts)
            Unit
        } fail { e ->
            log.error("Unable to add new contacts: {}", e.message, e)
        }
    }

    /** Process the given unadded users. */
    private fun addPendingContacts(users: Set<UserId>): Promise<Unit, Exception> {
        if (users.isEmpty())
            return Promise.ofSuccess(Unit)

        val request = FetchContactInfoByIdRequest(users.toList())
        return authTokenManager.bind { userCredentials ->
            contactClient.fetchContactInfoById(userCredentials, request) bindUi { response ->
                handleContactLookupResponse(users, response)
            } fail { e ->
                //the only recoverable error would be a network error; when the network is restored, this'll get called again
                log.error("Unable to fetch contact info: {}", e.message, e)
            }
        }
    }

    /** Attempts to find any registered users matching the user's local contacts. */
    private fun syncLocalContacts(): Promise<Unit, Exception> {
        log.info("Beginning local contact sync")

        return getDefaultRegionCode() bind { defaultRegion ->
            authTokenManager.bind { userCredentials ->
                platformContacts.fetchContacts() map { contacts ->
                    val phoneNumberUtil = PhoneNumberUtil.getInstance()

                    val updated = contacts.map { contact ->
                        val phoneNumbers = contact.phoneNumbers
                            .map { parsePhoneNumber(it, defaultRegion) }
                            .filter { it != null }
                            .map { phoneNumberUtil.format(it, PhoneNumberUtil.PhoneNumberFormat.E164).substring(1) }
                        contact.copy(phoneNumbers = phoneNumbers)
                    }

                    log.debug("Platform contacts: {}", updated)

                    updated
                } bind { contacts ->
                    contactsPersistenceManager.findMissing(contacts)
                } bind { missingContacts ->
                    log.debug("Missing local contacts:", missingContacts)
                    contactClient.findLocalContacts(userCredentials, FindLocalContactsRequest(missingContacts))
                } bind { foundContacts ->
                    log.debug("Found local contacts: {}", foundContacts)

                    contactsPersistenceManager.add(foundContacts.contacts.map { it.toCore(false, AllowedMessageLevel.ALL) }) map { Unit }
                }
            }
        }
    }

    /** Syncs the local contact list with the remote contact list. */
    private fun syncRemoteContactsList(): Promise<Unit, Exception> {
        log.debug("Beginning remote contact list sync")

        val keyVault = userLoginData.keyVault

        val contactsPersistenceManager = contactsPersistenceManager

        return authTokenManager.bind { userCredentials ->
            contactListClient.getContacts(userCredentials) bind { response ->
                val emails = decryptRemoteContactEntries(keyVault, response.contacts)
                contactsPersistenceManager.getDiff(emails) bind { diff ->
                    log.debug("New contacts: {}", diff.newContacts)
                    log.debug("Removed contacts: {}", diff.removedContacts)

                    val request = FetchContactInfoByIdRequest(diff.newContacts.toList())
                    contactClient.fetchContactInfoById(userCredentials, request) bind { response ->
                        val newContacts = response.contacts.map { it.toCore(false, AllowedMessageLevel.ALL) }
                        contactsPersistenceManager.applyDiff(newContacts, diff.removedContacts.toList())
                    }
                }
            }
        }
    }

    private fun processUnadded(): Promise<Unit, Exception> {
        log.info("Processing unadded contacts")

        return contactsPersistenceManager.getUnadded() bind { users ->
            addPendingContacts(users)
        } fail { e ->
            log.error("Failed to fetch unadded users on startup: {}", e.message, e)
        }
    }

    private fun updateRemoteContactList(): Promise<Unit, Exception> {
        log.info("Beginning remote contact list update")

        return authTokenManager.bind { userCredentials ->
            contactsPersistenceManager.getRemoteUpdates() bind { updates ->
                val adds = updates.filter { it.type == RemoteContactUpdateType.ADD }.map { it.userId }
                val removes = updates.filter { it.type == RemoteContactUpdateType.REMOVE }.map { it.userId }

                if (adds.isEmpty() && removes.isEmpty()) {
                    log.info("No new contacts")
                    Promise.ofSuccess(Unit)
                } else {
                    log.info("Remote update: add={}; remove={}", adds.map { it.long }, removes.map { it.long })

                    val keyVault = userLoginData.keyVault

                    val request = updateRequestFromContactInfo(keyVault, adds, removes)
                    contactListClient.updateContacts(userCredentials, request) bind {
                        contactsPersistenceManager.removeRemoteUpdates(updates)
                    }
                }
            }
        }
    }

    override fun run(jobDescription: ContactJobDescription): Promise<Unit, Exception> {
        val jobRunners = ArrayList<(Unit) -> Promise<Unit, Exception>>()

        if (jobDescription.localSync)
            jobRunners.add { syncLocalContacts() }

        if (jobDescription.updateRemote)
            jobRunners.add { updateRemoteContactList() }

        if (jobDescription.remoteSync)
            jobRunners.add {
                syncRemoteContactsList()
            }

        return jobRunners.fold(Promise.ofSuccess(Unit)) { z, v ->
            z bindUi v
        }
    }
}