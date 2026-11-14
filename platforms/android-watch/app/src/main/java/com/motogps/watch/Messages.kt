package com.motogps.watch

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * v1 业务 payload 的编解码。每条 payload 的第 0 字节都是 payload_revision=1。
 * 字段布局严格按 shared/protocol/ble-navigation-v1.md 第 6 节。
 */
object MessageCodec {

    // ---------- 双向 ----------

    fun encodeConnectionStatus(
        role: Int,           // 1 Phone, 2 Device
        state: Int,          // 0 Starting, 1 Ready, 2 Degraded, 3 Closing
        sessionId: Long,
        maxFrameSize: Int,
        heartbeatIntervalMs: Int,
        capabilities: Int,
    ): ByteArray {
        val b = Le.buffer(17)
        b.put(1)                    // revision
        b.put(role.toByte())
        b.put(state.toByte())
        b.put(BleSpec.PROTOCOL_VERSION.toByte())  // minimum_version
        b.put(BleSpec.PROTOCOL_VERSION.toByte())  // maximum_version
        b.putInt(capabilities)
        b.putInt(sessionId.toInt())
        b.putShort(maxFrameSize.toShort())
        b.putShort(heartbeatIntervalMs.toShort())
        return b.array()
    }

    fun decodeConnectionStatus(payload: ByteArray): ConnectionStatusData? {
        if (payload.size != 17 || (payload[0].toInt() and 0xFF) != 1) return null
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        b.get() // revision
        return ConnectionStatusData(
            role = b.get().toInt() and 0xFF,
            state = b.get().toInt() and 0xFF,
            minimumVersion = b.get().toInt() and 0xFF,
            maximumVersion = b.get().toInt() and 0xFF,
            capabilities = b.int,
            sessionId = b.int.toLong() and 0xFFFFFFFFL,
            maxFrameSize = b.short.toInt() and 0xFFFF,
            heartbeatIntervalMs = b.short.toInt() and 0xFFFF,
        )
    }

    fun encodeHeartbeat(sessionId: Long, monotonicMs: Long): ByteArray {
        val b = Le.buffer(11)
        b.put(1) // revision
        b.putInt(sessionId.toInt())
        b.putInt(monotonicMs.toInt())
        b.putShort(0) // status_flags
        return b.array()
    }

    fun encodeAck(acknowledgedSequence: Int, status: Int, commandId: Int): ByteArray {
        val b = Le.buffer(6)
        b.put(1) // revision
        b.putShort(acknowledgedSequence.toShort())
        b.put(status.toByte())
        b.putShort(commandId.toShort())
        return b.array()
    }

    fun encodeDeviceCommand(
        kind: Int,
        commandId: Int,
        page: Int,
        x: Int = 0xFFFF,
        y: Int = 0xFFFF,
        eventTimeMs: Long,
    ): ByteArray {
        val b = Le.buffer(13)
        b.put(1) // revision
        b.put(kind.toByte())
        b.putShort(commandId.toShort())
        b.put(page.toByte())
        b.putShort(x.toShort())
        b.putShort(y.toShort())
        b.putInt(eventTimeMs.toInt())
        return b.array()
    }

    // ---------- 手机 -> 设备 ----------

    fun decodeNavigationSnapshot(payload: ByteArray): NavSnapshotData? {
        if (payload.size < 53 || (payload[0].toInt() and 0xFF) != 1) return null
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        b.get() // revision
        return NavSnapshotData(
            state = b.get().toInt() and 0xFF,
            network = b.get().toInt() and 0xFF,
            displayPage = b.get().toInt() and 0xFF,
            maneuver = b.get().toInt() and 0xFF,
            traffic = b.get().toInt() and 0xFF,
            flags = b.short.toInt() and 0xFFFF,
            routeToken = b.int.toLong() and 0xFFFFFFFFL,
            routeGeneration = b.int.toLong() and 0xFFFFFFFFL,
            maneuverId = b.int.toLong() and 0xFFFFFFFFL,
            distanceToManeuverM = b.int.toLong() and 0xFFFFFFFFL,
            remainingDistanceM = b.int.toLong() and 0xFFFFFFFFL,
            remainingDurationS = b.int.toLong() and 0xFFFFFFFFL,
            routeProgressM = b.int.toLong() and 0xFFFFFFFFL,
            totalDistanceM = b.int.toLong() and 0xFFFFFFFFL,
            speedDeciKph = b.short.toInt() and 0xFFFF,
            speedLimitKph = b.short.toInt() and 0xFFFF,
            headingCdeg = b.short.toInt() and 0xFFFF,
            accuracyDm = b.short.toInt() and 0xFFFF,
            crossTrackDm = b.short.toInt() and 0xFFFF,
            roundaboutExit = b.get().toInt() and 0xFF,
            roadName = readLengthString(b, 63),
            instruction = readLengthString(b, 95),
        )
    }

    private fun readLengthString(b: ByteBuffer, maxBytes: Int): String {
        if (!b.hasRemaining()) return ""
        val len = b.get().toInt() and 0xFF
        if (len == 0 || len > maxBytes || b.remaining() < len) return ""
        val bytes = ByteArray(len)
        b.get(bytes)
        return runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
    }

    fun decodeRouteGeometry(payload: ByteArray): RouteGeometryData? {
        if (payload.size < 25 || (payload[0].toInt() and 0xFF) != 1) return null
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        b.get() // revision
        val coordinateSystem = b.get().toInt() and 0xFF
        if (coordinateSystem != 2) return null // GCJ-02
        val routeToken = b.int.toLong() and 0xFFFFFFFFL
        val generation = b.int.toLong() and 0xFFFFFFFFL
        val chunkIndex = b.short.toInt() and 0xFFFF
        val chunkCount = b.short.toInt() and 0xFFFF
        val firstPointIndex = b.short.toInt() and 0xFFFF
        val totalPointCount = b.short.toInt() and 0xFFFF
        val originLat = b.int
        val originLon = b.int
        if (totalPointCount !in 1..24 || chunkCount < 1 || chunkIndex >= chunkCount) return null
        val pointCount = b.get().toInt() and 0xFF
        if (pointCount !in 1..24) return null
        // 协议 6.5：首点是 i32 绝对 GCJ-02 坐标（4 字节小端），不是 varint；
        // 后续点才是相对前一点的 ZigZag varint 增量。
        var lat = b.int
        var lon = b.int
        val points = ArrayList<Int>(pointCount * 2)
        points += lat; points += lon
        repeat(pointCount - 1) {
            lat = (readSvarintOr(b) ?: return null).let { lat + it }
            lon = (readSvarintOr(b) ?: return null).let { lon + it }
            points += lat; points += lon
        }
        if (firstPointIndex + pointCount > totalPointCount) return null
        return RouteGeometryData(
            routeToken = routeToken,
            generation = generation,
            chunkIndex = chunkIndex,
            chunkCount = chunkCount,
            firstPointIndex = firstPointIndex,
            totalPointCount = totalPointCount,
            originLatE6 = originLat,
            originLonE6 = originLon,
            pointsE6 = points.toIntArray(),
        )
    }

    private fun readSvarintOr(b: ByteBuffer): Int? = Le.readSvarint(b)

    fun decodeTrafficDeviation(payload: ByteArray): TrafficDeviationData? {
        if (payload.size < 21 || (payload[0].toInt() and 0xFF) != 1) return null
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        b.get() // revision
        val routeToken = b.int.toLong() and 0xFFFFFFFFL
        val generation = b.int.toLong() and 0xFFFFFFFFL
        val flags = b.short.toInt() and 0xFFFF
        b.int // observed_at_ms
        b.int // remaining_duration_s
        b.short // cross_track_dm
        val segmentCount = b.get().toInt() and 0xFF
        if (segmentCount > 64) return null
        repeat(segmentCount * 7) { if (b.hasRemaining()) b.get() else return null }
        return TrafficDeviationData(routeToken = routeToken, generation = generation, flags = flags)
    }

    fun decodeMediaState(payload: ByteArray): MediaStateData? {
        if (payload.size < 9 || (payload[0].toInt() and 0xFF) != 1) return null
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        b.get() // revision
        val flags = b.get().toInt() and 0xFF
        val trackToken = b.int.toLong() and 0xFFFFFFFFL
        val positionS = b.short.toInt() and 0xFFFF
        val durationS = b.short.toInt() and 0xFFFF
        val source = readLengthString(b, 31)
        val title = readLengthString(b, 63)
        val artist = readLengthString(b, 47)
        return MediaStateData(
            flags = flags,
            trackToken = trackToken,
            positionS = positionS,
            durationS = durationS,
            sourceName = source,
            trackTitle = title,
            artistName = artist,
        )
    }

    fun decodeMapScene(payload: ByteArray): MapSceneData? {
        if (payload.size < 14 || (payload[0].toInt() and 0xFF) != 1) return null
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        b.get() // revision
        if ((b.get().toInt() and 0xFF) != 2) return null // GCJ-02
        val sceneRevision = b.int.toLong() and 0xFFFFFFFFL
        val originLat = b.int
        val originLon = b.int
        val radiusM = b.short.toInt() and 0xFFFF
        val roadCount = b.get().toInt() and 0xFF
        val buildingCount = b.get().toInt() and 0xFF
        if (roadCount > 24 || buildingCount > 16) return null

        fun readShape(minPoints: Int): IntArray? {
            val count = b.get().toInt() and 0xFF
            if (count < minPoints) return null
            val pts = ArrayList<Int>(count * 2)
            repeat(count) {
                // 协议 6.7：每个点是独立相对 view_origin 的偏移，
                // 不是相对前一点的增量；解码时直接转成绝对坐标。
                val dLat = Le.readSvarint(b) ?: return null
                val dLon = Le.readSvarint(b) ?: return null
                // 偏移必须在 origin ±100000 E6 安全界内
                if (dLat < -100000 || dLat > 100000 || dLon < -100000 || dLon > 100000) return null
                pts += originLat + dLat; pts += originLon + dLon
            }
            return pts.toIntArray()
        }

        val roads = ArrayList<MapShape>(roadCount)
        repeat(roadCount) {
            val roadClass = b.get().toInt() and 0xFF
            val pts = readShape(2) ?: return null
            roads += MapShape(roadClass, pts)
        }
        val buildings = ArrayList<MapShape>(buildingCount)
        repeat(buildingCount) {
            val buildingClass = b.get().toInt() and 0xFF
            val pts = readShape(3) ?: return null
            buildings += MapShape(buildingClass, pts)
        }
        return MapSceneData(
            sceneRevision = sceneRevision,
            originLatE6 = originLat,
            originLonE6 = originLon,
            radiusM = radiusM,
            roads = roads,
            buildings = buildings,
        )
    }
}

// ---------- 数据类 ----------

data class ConnectionStatusData(
    val role: Int,
    val state: Int,
    val minimumVersion: Int,
    val maximumVersion: Int,
    val capabilities: Int,
    val sessionId: Long,
    val maxFrameSize: Int,
    val heartbeatIntervalMs: Int,
)

data class NavSnapshotData(
    val state: Int,             // 0 Idle .. 5 Arrived
    val network: Int,           // 0 Offline .. 2 Online
    val displayPage: Int,       // 0 Navigation, 1 Speed, 2 Compass, 3 Music
    val maneuver: Int,          // 0..12 见 ManeuverType
    val traffic: Int,
    val flags: Int,
    val routeToken: Long,
    val routeGeneration: Long,
    val maneuverId: Long,
    val distanceToManeuverM: Long,
    val remainingDistanceM: Long,
    val remainingDurationS: Long,
    val routeProgressM: Long,
    val totalDistanceM: Long,
    val speedDeciKph: Int,
    val speedLimitKph: Int,
    val headingCdeg: Int,
    val accuracyDm: Int,
    val crossTrackDm: Int,
    val roundaboutExit: Int,
    val roadName: String,
    val instruction: String,
) {
    val hasDestination: Boolean get() = flags and 0x0001 != 0
    val hasFix: Boolean get() = flags and 0x0002 != 0
    val gnssStale: Boolean get() = flags and 0x0004 != 0
    val offRoute: Boolean get() = flags and 0x0008 != 0
    val hasNextManeuver: Boolean get() = flags and 0x0010 != 0
    val routeRequestInFlight: Boolean get() = flags and 0x0020 != 0
    val hasRouteView: Boolean get() = flags and 0x0080 != 0
    val speedKph: Float get() = speedDeciKph / 10f
    val headingDeg: Float get() = headingCdeg / 100f
}

data class RouteGeometryData(
    val routeToken: Long,
    val generation: Long,
    val chunkIndex: Int,
    val chunkCount: Int,
    val firstPointIndex: Int,
    val totalPointCount: Int,
    val originLatE6: Int,
    val originLonE6: Int,
    /** 绝对 GCJ-02 E6，[lat0, lon0, lat1, lon1, ...]。 */
    val pointsE6: IntArray,
)

data class TrafficDeviationData(
    val routeToken: Long,
    val generation: Long,
    val flags: Int,
) {
    val trafficChanged: Boolean get() = flags and 0x0001 != 0
    val offRoute: Boolean get() = flags and 0x0002 != 0
    val rerouting: Boolean get() = flags and 0x0004 != 0
    val routeInvalidated: Boolean get() = flags and 0x0008 != 0
}

data class MediaStateData(
    val flags: Int,
    val trackToken: Long,
    val positionS: Int,
    val durationS: Int,
    val sourceName: String,
    val trackTitle: String,
    val artistName: String,
) {
    val connected: Boolean get() = flags and 0x01 != 0
    val playing: Boolean get() = flags and 0x02 != 0
}

data class MapShape(
    val shapeClass: Int,
    /** 相对 origin 的 E6 偏移 [lat0, lon0, ...]。 */
    val pointsE6: IntArray,
)

data class MapSceneData(
    val sceneRevision: Long,
    val originLatE6: Int,
    val originLonE6: Int,
    val radiusM: Int,
    val roads: List<MapShape>,
    val buildings: List<MapShape>,
)
