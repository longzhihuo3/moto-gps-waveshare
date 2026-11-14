package com.motogps.watch

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Moto BLE Navigation Protocol v1 的帧层实现。
 * 规范：shared/protocol/ble-navigation-v1.md（第 4 节 帧格式）
 * 常量与编码器对齐 shared/ble_protocol 的 C++ 实现。
 */
object BleSpec {
    const val SERVICE_UUID = "7e57a000-b50c-4b6a-9c57-40a54e8e1000"
    const val CHAR_RX_UUID = "7e57a001-b50c-4b6a-9c57-40a54e8e1000"
    const val CHAR_TX_UUID = "7e57a002-b50c-4b6a-9c57-40a54e8e1000"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    /** iPhone App 按服务 UUID 扫描（scanForPeripherals(withServices:)），名字仅用于展示。 */
    const val ADVERTISED_NAME = "MOTO GPS"

    const val PROTOCOL_VERSION = 1
    const val FRAME_MAGIC = 0xB7
    const val FRAME_HEADER_SIZE = 10
    const val FRAME_CRC_SIZE = 2
    const val MAX_FRAME_VALUE = 512
    const val MAX_REASSEMBLY_BYTES = 4096

    const val FLAG_START = 0x01
    const val FLAG_END = 0x02
    const val FLAG_ACK_REQUESTED = 0x04
    const val FLAG_URGENT = 0x08
    const val FLAG_RESERVED_MASK = 0xF0

    // 能力位（ConnectionStatus.capabilities）
    const val CAP_NAVIGATION = 1 shl 0
    const val CAP_ROUTE_GEOMETRY = 1 shl 1
    const val CAP_TRAFFIC = 1 shl 2
    const val CAP_MEDIA_STATE = 1 shl 3
    const val CAP_TOUCH_COMMANDS = 1 shl 4
    const val CAP_MUSIC_COMMANDS = 1 shl 5
    const val CAP_COMMAND_ACK = 1 shl 6
    const val CAP_MAP_SCENE = 1 shl 7

    const val HEARTBEAT_INTERVAL_MS = 1000
    const val LINK_DEAD_TIMEOUT_MS = 3000
    const val REASSEMBLY_TIMEOUT_MS = 1000

    // 消息类型
    const val MSG_CONNECTION_STATUS = 0x01
    const val MSG_HEARTBEAT = 0x02
    const val MSG_ACK = 0x03
    const val MSG_NAVIGATION_SNAPSHOT = 0x10
    const val MSG_ROUTE_GEOMETRY = 0x11
    const val MSG_TRAFFIC_DEVIATION = 0x12
    const val MSG_MEDIA_STATE = 0x13
    const val MSG_MAP_SCENE = 0x14
    const val MSG_DEVICE_COMMAND = 0x20
}

/** CRC-16/CCITT-FALSE：poly 0x1021、init 0xFFFF、不反射、xorout 0。 */
fun crc16CcittFalse(data: ByteArray, from: Int, toExclusive: Int): Int {
    var crc = 0xFFFF
    for (i in from until toExclusive) {
        crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
        repeat(8) {
            crc = if (crc and 0x8000 != 0) {
                ((crc shl 1) xor 0x1021) and 0xFFFF
            } else {
                (crc shl 1) and 0xFFFF
            }
        }
    }
    return crc
}

/** 一次 GATT characteristic write/notify 承载的一个 v1 分片。 */
data class Frame(
    val type: Int,
    val flags: Int,
    val sequence: Int,
    val fragmentOffset: Int,
    val messageLength: Int,
    /** 本分片的净载荷（不含帧头与 CRC）。 */
    val fragmentPayload: ByteArray,
) {
    val isStart: Boolean get() = flags and BleSpec.FLAG_START != 0
    val isEnd: Boolean get() = flags and BleSpec.FLAG_END != 0
    val ackRequested: Boolean get() = flags and BleSpec.FLAG_ACK_REQUESTED != 0
}

object FrameCodec {

    /**
     * 把逻辑消息的一个分片编码为完整 GATT value。
     * 帧头 10 字节 + 净载荷 N + CRC 2 字节。
     */
    fun encodeFragment(
        type: Int,
        flags: Int,
        sequence: Int,
        fragmentOffset: Int,
        messageLength: Int,
        payloadChunk: ByteArray,
    ): ByteArray {
        val value = ByteArray(BleSpec.FRAME_HEADER_SIZE + payloadChunk.size + BleSpec.FRAME_CRC_SIZE)
        value[0] = BleSpec.FRAME_MAGIC.toByte()
        value[1] = BleSpec.PROTOCOL_VERSION.toByte()
        value[2] = type.toByte()
        value[3] = flags.toByte()
        putU16(value, 4, sequence)
        putU16(value, 6, fragmentOffset)
        putU16(value, 8, messageLength)
        payloadChunk.copyInto(value, BleSpec.FRAME_HEADER_SIZE)
        val crc = crc16CcittFalse(value, 0, BleSpec.FRAME_HEADER_SIZE + payloadChunk.size)
        putU16(value, BleSpec.FRAME_HEADER_SIZE + payloadChunk.size, crc)
        return value
    }

    /** 把整条逻辑 payload 按 negotiated frame value 大小切成若干 GATT value。 */
    fun segment(
        type: Int,
        ackRequested: Boolean,
        urgent: Boolean,
        sequence: Int,
        payload: ByteArray,
        frameValueSize: Int,
    ): List<ByteArray> {
        require(frameValueSize >= 13) { "frameValueSize too small: $frameValueSize" }
        val chunkCapacity = frameValueSize - BleSpec.FRAME_HEADER_SIZE - BleSpec.FRAME_CRC_SIZE
        require(chunkCapacity > 0)
        val baseFlags = (if (ackRequested) BleSpec.FLAG_ACK_REQUESTED else 0) or
            (if (urgent) BleSpec.FLAG_URGENT else 0)
        val fragments = ArrayList<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val count = minOf(chunkCapacity, payload.size - offset)
            val flags = baseFlags or
                (if (offset == 0) BleSpec.FLAG_START else 0) or
                (if (offset + count == payload.size) BleSpec.FLAG_END else 0)
            fragments += encodeFragment(type, flags, sequence, offset, payload.size, payload.copyOfRange(offset, offset + count))
            offset += count
        }
        if (payload.isEmpty()) {
            fragments += encodeFragment(
                type, baseFlags or BleSpec.FLAG_START or BleSpec.FLAG_END,
                sequence, 0, 0, ByteArray(0),
            )
        }
        return fragments
    }

    /** 校验并解析一个 GATT value；magic/版本/CRC/reserved flags 任一错误返回 null。 */
    fun parse(value: ByteArray): Frame? {
        if (value.size < BleSpec.FRAME_HEADER_SIZE + BleSpec.FRAME_CRC_SIZE) return null
        if ((value[0].toInt() and 0xFF) != BleSpec.FRAME_MAGIC) return null
        if ((value[1].toInt() and 0xFF) != BleSpec.PROTOCOL_VERSION) return null
        val flags = value[3].toInt() and 0xFF
        if (flags and BleSpec.FLAG_RESERVED_MASK != 0) return null
        val expectedCrc =
            (value[value.size - 2].toInt() and 0xFF) or ((value[value.size - 1].toInt() and 0xFF) shl 8)
        if (crc16CcittFalse(value, 0, value.size - BleSpec.FRAME_CRC_SIZE) != expectedCrc) return null
        val payloadSize = value.size - BleSpec.FRAME_HEADER_SIZE - BleSpec.FRAME_CRC_SIZE
        val messageLength = u16(value, 8)
        val fragmentOffset = u16(value, 6)
        // START 当且仅当 offset==0；END 当且仅当 offset+N==length
        if ((fragmentOffset == 0) != (flags and BleSpec.FLAG_START != 0)) return null
        if ((fragmentOffset + payloadSize == messageLength) != (flags and BleSpec.FLAG_END != 0)) return null
        return Frame(
            type = value[2].toInt() and 0xFF,
            flags = flags,
            sequence = u16(value, 4),
            fragmentOffset = fragmentOffset,
            messageLength = messageLength,
            fragmentPayload = value.copyOfRange(BleSpec.FRAME_HEADER_SIZE, BleSpec.FRAME_HEADER_SIZE + payloadSize),
        )
    }

    fun putU16(target: ByteArray, offset: Int, v: Int) {
        target[offset] = (v and 0xFF).toByte()
        target[offset + 1] = ((v shr 8) and 0xFF).toByte()
    }

    fun u16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)
}

/**
 * 序号比较：模 65536 前半区规则。两次有效消息之间不能跨越 32767 个未观察序号。
 */
object Sequencing {
    fun isNewer(candidate: Int, lastObserved: Int): Boolean {
        val diff = (candidate - lastObserved + 65536) % 65536
        return diff in 1..32767
    }
}

data class AssembledMessage(
    val type: Int,
    val sequence: Int,
    val ackRequested: Boolean,
    val payload: ByteArray,
)

/**
 * 分片重组器：一个方向同一时刻只允许一个未完成逻辑消息。
 * 规则（规范 4.1）：
 * - 只接受紧邻的 fragment_offset；缺口/冲突拒绝整条消息。
 * - 完全相同的已收分片是重复分片，忽略。
 * - 自最后一个有效新分片起 1000 ms 未完成即丢弃。
 * - 较新序号的 START 可原子取代未完成消息。
 */
class Reassembler(private val maxMessageBytes: Int = BleSpec.MAX_REASSEMBLY_BYTES) {

    sealed interface OfferResult {
        /** 尚未集齐。 */
        data object Incomplete : OfferResult

        /** 整条消息重组完成。 */
        data class Complete(val message: AssembledMessage) : OfferResult

        /** 该分片属于已完成或重复的消息，无副作用。 */
        data object Duplicate : OfferResult

        /** 冲突/缺口/超时等，等待新的 START。 */
        data object Rejected : OfferResult
    }

    private var pendingType: Int = -1
    private var pendingFlags: Int = 0
    private var pendingSequence: Int = 0
    private var pendingLength: Int = 0
    private var buffer: ByteArray = ByteArray(0)
    private var nextOffset: Int = 0
    private var deadlineMs: Long = 0
    private var hasPending = false

    fun offer(frame: Frame, nowMs: Long): OfferResult {
        if (frame.isStart) {
            val startsFresh = !hasPending || Sequencing.isNewer(frame.sequence, pendingSequence)
            val sameAsPending = hasPending && frame.sequence == pendingSequence
            if (!startsFresh && !sameAsPending) {
                // 比 pending 更旧的 START：属于重复或历史消息。
                return OfferResult.Duplicate
            }
            if (!sameAsPending || !hasPending) {
                beginPending(frame, nowMs)
            }
        }
        if (!hasPending) return OfferResult.Rejected
        if (frame.sequence != pendingSequence) {
            return if (Sequencing.isNewer(frame.sequence, pendingSequence)) {
                OfferResult.Rejected // 非法跳号
            } else {
                OfferResult.Duplicate
            }
        }
        if (frame.type != pendingType || frame.messageLength != pendingLength ||
            (frame.flags and (BleSpec.FLAG_ACK_REQUESTED or BleSpec.FLAG_URGENT)) !=
            (pendingFlags and (BleSpec.FLAG_ACK_REQUESTED or BleSpec.FLAG_URGENT))
        ) {
            reset()
            return OfferResult.Rejected
        }
        val offset = frame.fragmentOffset
        val chunk = frame.fragmentPayload
        if (offset + chunk.size > pendingLength) {
            reset()
            return OfferResult.Rejected
        }
        if (offset == nextOffset) {
            chunk.copyInto(buffer, offset)
            nextOffset = offset + chunk.size
            deadlineMs = nowMs + BleSpec.REASSEMBLY_TIMEOUT_MS
        } else if (offset + chunk.size <= nextOffset) {
            // 已覆盖区间的重复分片；字节必须一致，否则冲突拒绝。
            for (i in chunk.indices) {
                if (buffer[offset + i] != chunk[i]) {
                    reset()
                    return OfferResult.Rejected
                }
            }
            return OfferResult.Incomplete
        } else {
            reset()
            return OfferResult.Rejected // 缺口
        }
        if (nextOffset == pendingLength) {
            val message = AssembledMessage(
                type = pendingType,
                sequence = pendingSequence,
                ackRequested = pendingFlags and BleSpec.FLAG_ACK_REQUESTED != 0,
                payload = buffer.copyOf(pendingLength),
            )
            reset()
            return OfferResult.Complete(message)
        }
        return OfferResult.Incomplete
    }

    /** 重组超时检查；超时后丢弃并等待新的 START。 */
    fun expire(nowMs: Long) {
        if (hasPending && nowMs >= deadlineMs) reset()
    }

    private fun beginPending(frame: Frame, nowMs: Long) {
        if (frame.messageLength > maxMessageBytes || frame.messageLength == 0) {
            hasPending = false
            return
        }
        pendingType = frame.type
        pendingFlags = frame.flags
        pendingSequence = frame.sequence
        pendingLength = frame.messageLength
        buffer = ByteArray(frame.messageLength)
        nextOffset = 0
        deadlineMs = nowMs + BleSpec.REASSEMBLY_TIMEOUT_MS
        hasPending = true
    }

    fun reset() {
        hasPending = false
        buffer = ByteArray(0)
        nextOffset = 0
        pendingType = -1
    }

    val isBusy: Boolean get() = hasPending
}

/** 小端序读写工具（payload 层共用）。 */
object Le {
    fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    fun u16(b: ByteBuffer): Int = b.short.toInt() and 0xFFFF
    fun u24(b: ByteBuffer): Int {
        val lo = u16(b)
        val hi = b.get().toInt() and 0xFF
        return lo or (hi shl 16)
    }
    fun u32(b: ByteBuffer): Long = b.int.toLong() and 0xFFFFFFFFL

    /**
     * ZigZag + canonical unsigned LEB128（最多 5 字节，结果必须落在 uint32 内）。
     * 非 canonical（末尾多余的 0x80/0x00 续位）或溢出时返回 null。
     */
    fun readSvarint(b: ByteBuffer): Int? {
        var result = 0L
        var shift = 0
        var bytes = 0
        var lastByte: Int
        while (true) {
            if (!b.hasRemaining() || bytes >= 5) return null
            lastByte = b.get().toInt() and 0xFF
            bytes++
            result = result or ((lastByte and 0x7F).toLong() shl shift)
            if (lastByte and 0x80 == 0) break
            shift += 7
        }
        if (result > 0xFFFFFFFFL) return null
        // canonical：最后字节非 0x00（除非整条就 1 字节）
        if (bytes > 1 && lastByte == 0x00) return null
        val value = (result ushr 1).toInt() xor -(result and 1).toInt()
        return value
    }

    fun writeSvarint(target: ArrayList<Byte>, value: Int) {
        var v = (value shl 1) xor (value shr 31)
        while (true) {
            if (v and 0x7F.inv() == 0) {
                target.add(v.toByte())
                return
            }
            target.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
    }
}
