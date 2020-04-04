/*
 * Copyright 2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

@file: OptIn(LowLevelAPI::class)
@file:Suppress("EXPERIMENTAL_API_USAGE")

package net.mamoe.mirai.qqandroid.network.protocol.packet.chat.receive

import kotlinx.atomicfu.loop
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.io.core.ByteReadPacket
import kotlinx.io.core.discardExact
import net.mamoe.mirai.LowLevelAPI
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.MemberPermission
import net.mamoe.mirai.contact.QQ
import net.mamoe.mirai.data.MemberInfo
import net.mamoe.mirai.event.events.BotJoinGroupEvent
import net.mamoe.mirai.event.events.BotOfflineEvent
import net.mamoe.mirai.event.events.MemberJoinEvent
import net.mamoe.mirai.getFriendOrNull
import net.mamoe.mirai.message.FriendMessage
import net.mamoe.mirai.message.data.MessageChain
import net.mamoe.mirai.qqandroid.QQAndroidBot
import net.mamoe.mirai.qqandroid.contact.GroupImpl
import net.mamoe.mirai.qqandroid.contact.checkIsQQImpl
import net.mamoe.mirai.qqandroid.message.MessageSourceToFriendImpl
import net.mamoe.mirai.qqandroid.message.MessageSourceToGroupImpl
import net.mamoe.mirai.qqandroid.message.toMessageChain
import net.mamoe.mirai.qqandroid.message.toRichTextElems
import net.mamoe.mirai.qqandroid.network.MultiPacketByIterable
import net.mamoe.mirai.qqandroid.network.Packet
import net.mamoe.mirai.qqandroid.network.QQAndroidClient
import net.mamoe.mirai.qqandroid.network.protocol.data.jce.RequestPushForceOffline
import net.mamoe.mirai.qqandroid.network.protocol.data.jce.RequestPushNotify
import net.mamoe.mirai.qqandroid.network.protocol.data.proto.ImMsgBody
import net.mamoe.mirai.qqandroid.network.protocol.data.proto.MsgComm
import net.mamoe.mirai.qqandroid.network.protocol.data.proto.MsgSvc
import net.mamoe.mirai.qqandroid.network.protocol.data.proto.SyncCookie
import net.mamoe.mirai.qqandroid.network.protocol.packet.*
import net.mamoe.mirai.qqandroid.network.protocol.packet.chat.GroupInfoImpl
import net.mamoe.mirai.qqandroid.network.protocol.packet.list.FriendList
import net.mamoe.mirai.qqandroid.utils.io.serialization.decodeUniPacket
import net.mamoe.mirai.qqandroid.utils.io.serialization.readProtoBuf
import net.mamoe.mirai.qqandroid.utils.io.serialization.toByteArray
import net.mamoe.mirai.qqandroid.utils.io.serialization.writeProtoBuf
import net.mamoe.mirai.utils.MiraiExperimentalAPI
import net.mamoe.mirai.utils.MiraiInternalAPI
import net.mamoe.mirai.utils.currentTimeSeconds
import kotlin.math.absoluteValue
import kotlin.random.Random

internal class MessageSvc {
    /**
     * 告知要刷新好友消息
     */
    internal object PushNotify : IncomingPacketFactory<RequestPushNotify>("MessageSvc.PushNotify") {
        override suspend fun ByteReadPacket.decode(bot: QQAndroidBot, sequenceId: Int): RequestPushNotify {
            discardExact(4) // don't remove
            return decodeUniPacket(RequestPushNotify.serializer())
        }

        override suspend fun QQAndroidBot.handle(packet: RequestPushNotify, sequenceId: Int): OutgoingPacket? {
            network.run {
                return PbGetMsg(
                    client,
                    MsgSvc.SyncFlag.START,
                    packet.stMsgInfo?.uMsgTime ?: currentTimeSeconds
                )
            }
        }
    }


    /**
     * 获取好友消息和消息记录
     */
    @OptIn(MiraiInternalAPI::class)
    internal object PbGetMsg : OutgoingPacketFactory<PbGetMsg.Response>("MessageSvc.PbGetMsg") {
        @Suppress("SpellCheckingInspection")
        operator fun invoke(
            client: QQAndroidClient,
            syncFlag: MsgSvc.SyncFlag = MsgSvc.SyncFlag.START,
            msgTime: Long //PbPushMsg.msg.msgHead.msgTime
        ): OutgoingPacket = buildOutgoingUniPacket(
            client
        ) {
            //println("syncCookie=${client.c2cMessageSync.syncCookie?.toUHexString()}")
            writeProtoBuf(
                MsgSvc.PbGetMsgReq.serializer(),
                MsgSvc.PbGetMsgReq(
                    msgReqType = 1, // from.ctype.toInt()
                    contextFlag = 1,
                    rambleFlag = 0,
                    latestRambleNumber = 20,
                    otherRambleNumber = 3,
                    onlineSyncFlag = 1,
                    whisperSessionId = 0,
                    syncFlag = syncFlag,
                    //  serverBuf = from.serverBuf ?: EMPTY_BYTE_ARRAY,
                    syncCookie = client.c2cMessageSync.syncCookie
                        ?: SyncCookie(time = msgTime).toByteArray(SyncCookie.serializer())//.also { client.c2cMessageSync.syncCookie = it },
                    // syncFlag = client.c2cMessageSync.syncFlag,
                    //msgCtrlBuf = client.c2cMessageSync.msgCtrlBuf,
                    //pubaccountCookie = client.c2cMessageSync.pubAccountCookie
                )
            )
        }

        @OptIn(MiraiInternalAPI::class)
        open class GetMsgSuccess(delegate: List<Packet>) : Response(MsgSvc.SyncFlag.STOP, delegate) {
            override fun toString(): String = "MessageSvc.PbGetMsg.GetMsgSuccess(messages=<Iterable>))"
        }

        /**
         * 不要直接 expect 这个 class. 它可能还没同步完成
         */
        @MiraiInternalAPI
        open class Response(internal val syncFlagFromServer: MsgSvc.SyncFlag, delegate: List<Packet>) :
            MultiPacketByIterable<Packet>(delegate) {
            override fun toString(): String =
                "MessageSvc.PbGetMsg.Response($syncFlagFromServer=$syncFlagFromServer, messages=<Iterable>))"
        }

        object EmptyResponse : GetMsgSuccess(emptyList())

        @OptIn(MiraiInternalAPI::class, MiraiExperimentalAPI::class, FlowPreview::class, LowLevelAPI::class)
        override suspend fun ByteReadPacket.decode(bot: QQAndroidBot): Response {
            // 00 00 01 0F 08 00 12 00 1A 34 08 FF C1 C4 F1 05 10 FF C1 C4 F1 05 18 E6 ED B9 C3 02 20 89 FE BE A4 06 28 8A CA 91 D1 0C 48 9B A5 BD 9B 0A 58 DE 9D 99 F8 08 60 1D 68 FF C1 C4 F1 05 70 00 20 02 2A 9D 01 08 F3 C1 C4 F1 05 10 A2 FF 8C F0 03 18 01 22 8A 01 0A 2A 08 A2 FF 8C F0 03 10 DD F1 92 B7 07 18 A6 01 20 0B 28 AE F9 01 30 F4 C1 C4 F1 05 38 A7 E3 D8 D4 84 80 80 80 01 B8 01 CD B5 01 12 08 08 01 10 00 18 00 20 00 1A 52 0A 50 0A 27 08 00 10 F4 C1 C4 F1 05 18 A7 E3 D8 D4 04 20 00 28 0C 30 00 38 86 01 40 22 4A 0C E5 BE AE E8 BD AF E9 9B 85 E9 BB 91 12 08 0A 06 0A 04 4E 4D 53 4C 12 15 AA 02 12 9A 01 0F 80 01 01 C8 01 00 F0 01 00 F8 01 00 90 02 00 12 04 4A 02 08 00 30 01 2A 15 08 97 A2 C1 F1 05 10 95 A6 F5 E5 0C 18 01 30 01 40 01 48 81 01 2A 10 08 D3 F7 B5 F1 05 10 DD F1 92 B7 07 18 01 30 01 38 00 42 00 48 00
            val resp = readProtoBuf(MsgSvc.PbGetMsgResp.serializer())

            if (resp.result != 0) {
                bot.network.logger
                    .warning("MessageSvc.PushNotify: result != 0, result = ${resp.result}, errorMsg=${resp.errmsg}")
                return EmptyResponse
            }

            bot.client.c2cMessageSync.syncCookie = resp.syncCookie
            bot.client.c2cMessageSync.pubAccountCookie = resp.pubAccountCookie
            bot.client.c2cMessageSync.msgCtrlBuf = resp.msgCtrlBuf

            if (resp.uinPairMsgs == null) {
                return EmptyResponse
            }

            val messages = resp.uinPairMsgs.asFlow()
                .filterNot { it.msg == null }
                .flatMapConcat { it.msg!!.asFlow() }
                .mapNotNull<MsgComm.Msg, Packet> { msg ->
                    when (msg.msgHead.msgType) {
                        33 -> {
                            val group = bot.getGroupByUinOrNull(msg.msgHead.fromUin)
                            if (msg.msgHead.authUin == bot.id) {
                                if (group != null) {
                                    return@mapNotNull null
                                }
                                // 新群

                                val troopNum = bot.network.run {
                                    FriendList.GetTroopListSimplify(bot.client)
                                        .sendAndExpect<FriendList.GetTroopListSimplify.Response>(retry = 2)
                                }.groups.first { it.groupUin == msg.msgHead.fromUin }


                                @Suppress("DuplicatedCode")
                                val newGroup = GroupImpl(
                                    bot = bot,
                                    coroutineContext = bot.coroutineContext,
                                    id = Group.calculateGroupCodeByGroupUin(msg.msgHead.fromUin),
                                    groupInfo = bot._lowLevelQueryGroupInfo(troopNum.groupCode).apply {
                                        this as GroupInfoImpl

                                        if (this.delegate.groupName == null) {
                                            this.delegate.groupName = troopNum.groupName
                                        }

                                        if (this.delegate.groupMemo == null) {
                                            this.delegate.groupMemo = troopNum.groupMemo
                                        }

                                        if (this.delegate.groupUin == null) {
                                            this.delegate.groupUin = troopNum.groupUin
                                        }

                                        this.delegate.groupCode = troopNum.groupCode
                                    },
                                    members = bot._lowLevelQueryGroupMemberList(
                                        troopNum.groupUin,
                                        troopNum.groupCode,
                                        troopNum.dwGroupOwnerUin
                                    )
                                )
                                bot.groups.delegate.addLast(newGroup)
                                return@mapNotNull BotJoinGroupEvent(newGroup)
                            } else {
                                group ?: return@mapNotNull null

                                if (group.members.contains(msg.msgHead.authUin)) {
                                    return@mapNotNull null
                                }
                                return@mapNotNull MemberJoinEvent(group.newMember(object : MemberInfo {
                                    override val nameCard: String get() = ""
                                    override val permission: MemberPermission get() = MemberPermission.MEMBER
                                    override val specialTitle: String get() = ""
                                    override val muteTimestamp: Int get() = 0
                                    override val uin: Long get() = msg.msgHead.authUin
                                    override val nick: String = msg.msgHead.authNick.takeIf { it.isNotEmpty() }
                                        ?: msg.msgHead.fromNick
                                }).also { group.members.delegate.addLast(it) })
                            }
                        }
                        166 -> {
                            val friend = bot.getFriendOrNull(msg.msgHead.fromUin) ?: return@mapNotNull null
                            friend.checkIsQQImpl()

                            if (msg.msgHead.fromUin == bot.id || !bot.firstLoginSucceed) {
                                return@mapNotNull null
                            }

                            friend.lastMessageSequence.loop { instant ->
                                if (msg.msgHead.msgSeq > instant) {
                                    if (friend.lastMessageSequence.compareAndSet(instant, msg.msgHead.msgSeq)) {
                                        return@mapNotNull FriendMessage(
                                            friend,
                                            msg.toMessageChain(bot, isGroup = false, addSource = true)
                                        )
                                    }
                                } else return@mapNotNull null
                            }
                        }
                        else -> return@mapNotNull null
                    }
                }

            val list: List<Packet> = messages.toList()
            if (resp.syncFlag == MsgSvc.SyncFlag.STOP) {
                return GetMsgSuccess(list)
            }
            return Response(resp.syncFlag, list)
        }

        override suspend fun QQAndroidBot.handle(packet: Response) {
            when (packet.syncFlagFromServer) {
                MsgSvc.SyncFlag.STOP -> return
                MsgSvc.SyncFlag.START -> {
                    network.run {
                        PbGetMsg(client, MsgSvc.SyncFlag.CONTINUE, currentTimeSeconds).sendAndExpect<Packet>()
                    }
                    return
                }

                MsgSvc.SyncFlag.CONTINUE -> {
                    network.run {
                        PbGetMsg(client, MsgSvc.SyncFlag.CONTINUE, currentTimeSeconds).sendAndExpect<Packet>()
                    }
                    return
                }
            }
        }
    }


    /**
     * 被挤下线
     */
    internal object PushForceOffline : OutgoingPacketFactory<BotOfflineEvent.Force>("MessageSvc.PushForceOffline") {
        override suspend fun ByteReadPacket.decode(bot: QQAndroidBot): BotOfflineEvent.Force {
            val struct = this.decodeUniPacket(RequestPushForceOffline.serializer())
            return BotOfflineEvent.Force(bot, title = struct.title ?: "", message = struct.tips ?: "")
        }
    }

    internal object PbSendMsg : OutgoingPacketFactory<PbSendMsg.Response>("MessageSvc.PbSendMsg") {
        sealed class Response : Packet {
            object SUCCESS : Response() {
                override fun toString(): String = "MessageSvc.PbSendMsg.Response.SUCCESS"
            }

            /**
             * 121: 被限制? 个别号才不能发
             */
            data class Failed(val resultType: Int, val errorCode: Int, val errorMessage: String) : Response() {
                override fun toString(): String =
                    "MessageSvc.PbSendMsg.Response.Failed(resultType=$resultType, errorCode=$errorCode, errorMessage=$errorMessage)"
            }
        }

        inline fun createToFriend(
            client: QQAndroidClient,
            qq: QQ,
            message: MessageChain,
            crossinline sourceCallback: (MessageSourceToFriendImpl) -> Unit
        ): OutgoingPacket {
            val source = MessageSourceToFriendImpl(
                id = Random.nextInt().absoluteValue,
                sender = client.bot,
                target = qq,
                time = currentTimeSeconds.toInt(),
                sequenceId = client.atomicNextMessageSequenceId(),
                originalMessage = message
            )
            sourceCallback(source)
            return createToFriend(client, qq.id, message, source)
        }

        /**
         * 发送好友消息
         */
        @Suppress("FunctionName")
        private fun createToFriend(
            client: QQAndroidClient,
            toUin: Long,
            message: MessageChain,
            source: MessageSourceToFriendImpl
        ): OutgoingPacket = buildOutgoingUniPacket(client) {
            ///writeFully("0A 08 0A 06 08 89 FC A6 8C 0B 12 06 08 01 10 00 18 00 1A 1F 0A 1D 12 08 0A 06 0A 04 F0 9F 92 A9 12 11 AA 02 0E 88 01 00 9A 01 08 78 00 F8 01 00 C8 02 00 20 9B 7A 28 F4 CA 9B B8 03 32 34 08 92 C2 C4 F1 05 10 92 C2 C4 F1 05 18 E6 ED B9 C3 02 20 89 FE BE A4 06 28 89 84 F9 A2 06 48 DE 8C EA E5 0E 58 D9 BD BB A0 09 60 1D 68 92 C2 C4 F1 05 70 00 40 01".hexToBytes())

            ///return@buildOutgoingUniPacket
            writeProtoBuf(
                MsgSvc.PbSendMsgReq.serializer(), MsgSvc.PbSendMsgReq(
                    routingHead = MsgSvc.RoutingHead(c2c = MsgSvc.C2C(toUin = toUin)),
                    contentHead = MsgComm.ContentHead(pkgNum = 1),
                    msgBody = ImMsgBody.MsgBody(
                        richText = ImMsgBody.RichText(
                            elems = message.toRichTextElems(forGroup = false, withGeneralFlags = true)
                        )
                    ),
                    msgSeq = source.sequenceId,
                    msgRand = source.id,
                    syncCookie = SyncCookie(time = source.time.toULong().toLong()).toByteArray(SyncCookie.serializer())
                    // msgVia = 1
                )
            )
        }


        inline fun createToGroup(
            client: QQAndroidClient,
            group: Group,
            message: MessageChain,
            sourceCallback: (MessageSourceToGroupImpl) -> Unit
        ): OutgoingPacket {

            val source = MessageSourceToGroupImpl(
                id = Random.nextInt().absoluteValue,
                sender = client.bot,
                target = group,
                time = currentTimeSeconds.toInt(),
                originalMessage = message//,
                //   sourceMessage = message
            )
            sourceCallback(source)
            return createToGroup(client, group.id, message, source)
        }

        /**
         * 发送群消息
         */
        @Suppress("FunctionName")
        private fun createToGroup(
            client: QQAndroidClient,
            groupCode: Long,
            message: MessageChain,
            source: MessageSourceToGroupImpl
        ): OutgoingPacket = buildOutgoingUniPacket(client) {
            ///writeFully("0A 08 0A 06 08 89 FC A6 8C 0B 12 06 08 01 10 00 18 00 1A 1F 0A 1D 12 08 0A 06 0A 04 F0 9F 92 A9 12 11 AA 02 0E 88 01 00 9A 01 08 78 00 F8 01 00 C8 02 00 20 9B 7A 28 F4 CA 9B B8 03 32 34 08 92 C2 C4 F1 05 10 92 C2 C4 F1 05 18 E6 ED B9 C3 02 20 89 FE BE A4 06 28 89 84 F9 A2 06 48 DE 8C EA E5 0E 58 D9 BD BB A0 09 60 1D 68 92 C2 C4 F1 05 70 00 40 01".hexToBytes())

            // DebugLogger.debug("sending group message: " + message.toRichTextElems().contentToString())

            ///return@buildOutgoingUniPacket
            writeProtoBuf(
                MsgSvc.PbSendMsgReq.serializer(), MsgSvc.PbSendMsgReq(
                    routingHead = MsgSvc.RoutingHead(grp = MsgSvc.Grp(groupCode = groupCode)),
                    contentHead = MsgComm.ContentHead(pkgNum = 1),
                    msgBody = ImMsgBody.MsgBody(
                        richText = ImMsgBody.RichText(
                            elems = message.toRichTextElems(forGroup = true, withGeneralFlags = true)
                        )
                    ),
                    msgSeq = client.atomicNextMessageSequenceId(),
                    msgRand = source.id,
                    syncCookie = EMPTY_BYTE_ARRAY,
                    msgVia = 1
                )
            )
        }

        override suspend fun ByteReadPacket.decode(bot: QQAndroidBot): Response {
            val response = readProtoBuf(MsgSvc.PbSendMsgResp.serializer())
            return if (response.result == 0) {
                Response.SUCCESS
            } else {
                Response.Failed(response.result, response.errtype, response.errmsg)
            }
        }
    }
}

