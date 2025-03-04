package io.slychat.messenger.services.ui

import com.vfpowertech.jsbridge.processor.annotations.Exclude
import com.vfpowertech.jsbridge.processor.annotations.JSToJavaGenerate
import io.slychat.messenger.core.persistence.GroupId
import nl.komponents.kovenant.Promise

@JSToJavaGenerate("GroupService")
interface UIGroupService {
    fun addGroupEventListener(listener: (UIGroupEvent) -> Unit)

    fun getGroups(): Promise<List<UIGroupInfo>, Exception>

    fun getGroupConversations(): Promise<List<UIGroupConversation>, Exception>

    fun getGroupConversation(groupId: GroupId): Promise<UIGroupConversation?, Exception>

    fun inviteUsers(groupId: GroupId, contacts: List<UIContactInfo>): Promise<Unit, Exception>

    fun createNewGroup(name: String, initialMembers: List<UIContactInfo>): Promise<GroupId, Exception>

    fun getMembers(groupId: GroupId): Promise<List<UIContactInfo>, Exception>

    fun part(groupId: GroupId): Promise<Boolean, Exception>

    fun block(groupId: GroupId): Promise<Unit, Exception>

    fun unblock(groupId: GroupId): Promise<Unit, Exception>

    fun getBlockList(): Promise<Set<GroupId>, Exception>

    fun getLastMessages(groupId: GroupId, startingAt: Int, count: Int): Promise<List<UIGroupMessage>, Exception>

    fun deleteAllMessages(groupId: GroupId): Promise<Unit, Exception>

    fun deleteMessagesFor(groupId: GroupId, messageIds: List<String>): Promise<Unit, Exception>

    fun getInfo(groupId: GroupId): Promise<UIGroupInfo?, Exception>

    fun startMessageExpiration(groupId: GroupId, messageId: String): Promise<Unit, Exception>

    @Exclude
    fun clearListeners()
}