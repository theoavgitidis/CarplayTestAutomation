package com.example.adb_connection.data.ssh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import com.example.adb_connection.data.debug.DebugLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlin.math.min

data class DiscoveredMac(val host: String, val id: String)

interface MacDiscoveryRepository {
    suspend fun discover(): Result<DiscoveredMac>
}

class AndroidMacDiscoveryRepository(
    context: Context,
    private val networkProvider: SshNetworkProvider
) : MacDiscoveryRepository {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override suspend fun discover(): Result<DiscoveredMac> = runCatching {
        DiscoveryDiagnostics.startAttempt()
        logDiscovery("discovery requested")
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "Mac discovery requires Android 13 or later; use the cached or manual Mac IP."
        }
        val network = requireNotNull(networkProvider.networkFor(SshTarget.MAC)) {
            "No active Ethernet connection detected. Enable Ethernet tethering and connect the Mac."
        }
        val endpoint = try {
            withTimeout(DISCOVERY_TIMEOUT_MS) { browseService(network) }
        } catch (e: TimeoutCancellationException) {
            logDiscovery("FAILED: discovery timeout")
            throw IllegalStateException("Timed out browsing for $SERVICE_TYPE on Ethernet.", e)
        }
        logDiscovery("resolved ${endpoint.serviceName}: ${endpoint.host}:${endpoint.port} TXT=${endpoint.txt}")
        val identity = readIdentity(network, endpoint.host, endpoint.port)
        require(identity.equals(endpoint.id, ignoreCase = true)) {
            "TraceMate identity mismatch: DNS-SD id ${endpoint.id} does not match identity endpoint id $identity."
        }
        logDiscovery("identity JSON validated for $identity at ${endpoint.host}:${endpoint.port}")
        logDiscovery("SSH target=${endpoint.host}:22")
        DiscoveredMac(endpoint.host, identity)
    }.onFailure { error ->
        logDiscovery("FAILED: ${error.message ?: error::class.simpleName}")
        error.stackTrace.take(12).forEach { logDiscovery("stack: $it") }
    }

    private suspend fun browseService(network: Network): MdnsEndpoint = withContext(Dispatchers.IO) {
        val linkProperties = requireNotNull(connectivityManager.getLinkProperties(network)) {
            "Ethernet link properties are unavailable."
        }
        val interfaceName = requireNotNull(linkProperties.interfaceName) {
            "Ethernet interface name is unavailable."
        }
        val networkInterface = requireNotNull(NetworkInterface.getByName(interfaceName)) {
            "Ethernet interface $interfaceName is unavailable."
        }
        val addresses = linkProperties.linkAddresses.joinToString { it.address.hostAddress ?: "unknown" }
        logDiscovery("starting: type=$SERVICE_TYPE network=$network interface=$interfaceName addresses=$addresses")

        val records = MdnsRecords()
        val resolved = MulticastSocket(null).use { socket -> coroutineScope {
            socket.reuseAddress = true
            network.bindSocket(socket)
            socket.bind(InetSocketAddress(MDNS_PORT))
            socket.networkInterface = networkInterface
            socket.timeToLive = 255
            socket.joinGroup(InetSocketAddress(MDNS_GROUP, MDNS_PORT), networkInterface)
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            logDiscovery("socket bound=${socket.localSocketAddress} reuseAddress=${socket.reuseAddress} group=$MDNS_GROUP:$MDNS_PORT interface=$interfaceName joined=true ttl=${socket.timeToLive}")

            val receiverReady = CompletableDeferred<Unit>()
            val endpoint = CompletableDeferred<MdnsEndpoint>()
            val receiver = launch(Dispatchers.IO) {
                receiverReady.complete(Unit)
                logDiscovery("receive loop started")
                try {
                    val buffer = ByteArray(MAX_PACKET_SIZE)
                    val packet = DatagramPacket(buffer, buffer.size)
                    while (isActive && !endpoint.isCompleted) {
                        packet.length = buffer.size
                        try {
                            socket.receive(packet)
                        } catch (_: java.net.SocketTimeoutException) {
                            continue
                        }
                        logDnsDatagram(packet)
                        parseDnsMessage(packet.data, packet.length, records)
                        records.endpointOrNull()?.let { endpoint.complete(it) }
                    }
                } catch (e: Exception) {
                    if (isActive) logDiscovery("receive loop exception: ${e::class.simpleName}: ${e.message}")
                } finally {
                    logDiscovery("receive loop stopped")
                }
            }
            receiverReady.await()
            try {
                repeat(QUERY_ATTEMPTS) { attempt ->
                    if (endpoint.isCompleted) return@repeat
                    val query = buildPtrQuery(SERVICE_FQDN)
                    socket.send(DatagramPacket(query, query.size, MDNS_GROUP, MDNS_PORT))
                    logDiscovery("PTR query sent (${attempt + 1}/$QUERY_ATTEMPTS): $SERVICE_FQDN")
                    withTimeoutOrNull(QUERY_INTERVAL_MS) { endpoint.await() }
                }
                delay(RESPONSE_GRACE_MS)
                if (endpoint.isCompleted) endpoint.await() else null
            } finally {
                receiver.cancelAndJoin()
            }
        } }
        if (resolved != null) return@withContext resolved
        val reason = records.rejectionReason()
        logDiscovery("FAILED: no complete TraceMate DNS-SD response received; $reason")
        throw IllegalStateException("TraceMate DNS-SD response was incomplete: $reason")
    }

    private suspend fun readIdentity(network: Network, host: String, port: Int): String = withContext(Dispatchers.IO) {
        logDiscovery("identity endpoint=$host:$port connecting")
        network.socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(host, port), IDENTITY_TIMEOUT_MS)
            socket.soTimeout = IDENTITY_TIMEOUT_MS
            val response = socket.getInputStream().bufferedReader().readLine().orEmpty()
            val id = ID_REGEX.find(response)?.groupValues?.get(1)
            val protocolVersion = PROTOCOL_VERSION_REGEX.find(response)?.groupValues?.get(1)?.toIntOrNull()
            require(!id.isNullOrBlank()) { "TraceMate identity endpoint returned an invalid response." }
            require(protocolVersion == TRACE_MATE_PROTOCOL_VERSION) {
                "TraceMate identity endpoint returned unsupported protocol version."
            }
            logDiscovery("identity JSON valid id=$id protocolVersion=$protocolVersion")
            id
        }
    }

    private fun logDiscovery(message: String) {
        val text = "TraceMate mDNS $message"
        DebugLogger.log(text)
        DiscoveryDiagnostics.log(text)
    }

    private fun logDnsDatagram(packet: DatagramPacket) {
        if (packet.length < DNS_HEADER_SIZE) {
            logDiscovery("received short UDP datagram from ${packet.address.hostAddress}:${packet.port} length=${packet.length}")
            return
        }
        val flags = readU16(packet.data, 2).toString(16).padStart(4, '0')
        logDiscovery(
            "received UDP datagram from ${packet.address.hostAddress}:${packet.port} length=${packet.length} " +
                "id=0x${readU16(packet.data, 0).toString(16).padStart(4, '0')} flags=0x$flags " +
                "qd=${readU16(packet.data, 4)} an=${readU16(packet.data, 6)} ns=${readU16(packet.data, 8)} " +
                "ar=${readU16(packet.data, 10)} payloadHex=${packet.data.copyOfRange(0, min(packet.length, MAX_DIAGNOSTIC_PACKET_BYTES)).joinToString("") { "%02x".format(it) }}"
        )
    }

    private fun buildPtrQuery(name: String): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        writeDnsName(name)
        write(byteArrayOf(0, 12, 0, 1))
    }.toByteArray()

    private fun parseDnsMessage(data: ByteArray, length: Int, records: MdnsRecords) {
        if (length < DNS_HEADER_SIZE) return
        val questionCount = readU16(data, 4)
        val answerCount = readU16(data, 6)
        val authorityCount = readU16(data, 8)
        val additionalCount = readU16(data, 10)
        val recordCount = answerCount + authorityCount + additionalCount
        var offset = DNS_HEADER_SIZE
        repeat(questionCount) {
            offset = skipDnsName(data, offset, length) + 4
            if (offset > length) return
        }
        val parsed = mutableListOf<DnsRecord>()
        repeat(recordCount) { recordIndex ->
            val section = when {
                recordIndex < answerCount -> "ANSWER"
                recordIndex < answerCount + authorityCount -> "AUTHORITY"
                else -> "ADDITIONAL"
            }
            val nameResult = readDnsName(data, offset, length) ?: return
            offset = nameResult.nextOffset
            if (offset + 10 > length) return
            val type = readU16(data, offset)
            val recordClass = readU16(data, offset + 2) and DNS_CLASS_MASK
            val cacheFlush = readU16(data, offset + 2) and DNS_CACHE_FLUSH_MASK != 0
            val ttl = ((data[offset + 4].toLong() and 0xff) shl 24) or
                ((data[offset + 5].toLong() and 0xff) shl 16) or
                ((data[offset + 6].toLong() and 0xff) shl 8) or (data[offset + 7].toLong() and 0xff)
            val dataLength = readU16(data, offset + 8)
            val rdataStart = offset + 10
            val rdataEnd = rdataStart + dataLength
            if (rdataEnd > length) return
            parsed += DnsRecord(section, nameResult.name, type, recordClass, cacheFlush, ttl, rdataStart, rdataEnd)
            offset = rdataEnd
        }
        // Process PTRs first so valid Additional records can occur in any DNS record order.
        parsed.sortedBy { if (it.type == TYPE_PTR) 0 else 1 }.forEach { record ->
            val section = record.section
            val name = record.name
            if (record.recordClass != DNS_CLASS_IN) {
                logDiscovery("$section ignored owner=$name type=${record.type} class=${record.recordClass} flush=${record.cacheFlush} ttl=${record.ttl}")
                return@forEach
            }
            when (record.type) {
                TYPE_PTR -> if (name.equals(SERVICE_FQDN.trimEnd('.'), ignoreCase = true)) {
                    readDnsName(data, record.rdataStart, length)?.name?.let {
                        records.serviceName = it
                        logDiscovery("$section PTR owner=$name value=$it")
                    }
                }
                TYPE_SRV -> if (record.rdataEnd - record.rdataStart >= 6) {
                    val target = readDnsName(data, record.rdataStart + 6, length)
                    if (target != null) {
                        records.srvs[name] = Srv(target.name, readU16(data, record.rdataStart + 4))
                        logDiscovery("$section SRV owner=$name target=${target.name} port=${records.srvs[name]?.port} flush=${record.cacheFlush} ttl=${record.ttl}")
                    }
                }
                TYPE_TXT -> {
                    records.txtByOwner[name] = parseTxt(data, record.rdataStart, record.rdataEnd)
                    logDiscovery("$section TXT owner=$name entries=${records.txtByOwner[name]?.keys}")
                }
                TYPE_A -> if (record.rdataEnd - record.rdataStart == 4) {
                    records.addresses[name] = InetAddress.getByAddress(data.copyOfRange(record.rdataStart, record.rdataEnd))
                    logDiscovery("$section A owner=$name address=${records.addresses[name]?.hostAddress}")
                }
                else -> logDiscovery("$section skipped owner=$name type=${record.type} class=${record.recordClass} flush=${record.cacheFlush} ttl=${record.ttl}")
            }
        }
    }

    private fun parseTxt(data: ByteArray, start: Int, end: Int): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        var offset = start
        while (offset < end) {
            val length = data[offset].toInt() and 0xff
            offset++
            if (offset + length > end) break
            val entry = data.copyOfRange(offset, offset + length).toString(Charsets.UTF_8)
            entries[entry.substringBefore('=').lowercase()] = entry.substringAfter('=', "")
            offset += length
        }
        return entries
    }

    private fun ByteArrayOutputStream.writeDnsName(name: String) {
        name.trimEnd('.').split('.').forEach { label ->
            write(label.length)
            write(label.toByteArray(Charsets.UTF_8))
        }
        write(0)
    }

    private fun readDnsName(data: ByteArray, start: Int, length: Int): DnsName? {
        val labels = mutableListOf<String>()
        var offset = start
        var nextOffset = start
        var jumped = false
        var jumps = 0
        while (offset < length && jumps++ < MAX_DNS_POINTER_JUMPS) {
            val size = data[offset].toInt() and 0xff
            when {
                size == 0 -> return DnsName(labels.joinToString("."), if (jumped) nextOffset else offset + 1)
                size and 0xc0 == 0xc0 -> {
                    if (offset + 1 >= length) return null
                    if (!jumped) nextOffset = offset + 2
                    offset = ((size and 0x3f) shl 8) or (data[offset + 1].toInt() and 0xff)
                    jumped = true
                }
                size and 0xc0 != 0 || offset + 1 + size > length -> return null
                else -> {
                    labels += data.copyOfRange(offset + 1, offset + 1 + size).toString(Charsets.UTF_8)
                    offset += size + 1
                }
            }
        }
        return null
    }

    private fun skipDnsName(data: ByteArray, start: Int, length: Int): Int =
        readDnsName(data, start, length)?.nextOffset ?: length

    private fun readU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private data class DnsName(val name: String, val nextOffset: Int)

    private data class DnsRecord(
        val section: String, val name: String, val type: Int, val recordClass: Int,
        val cacheFlush: Boolean, val ttl: Long, val rdataStart: Int, val rdataEnd: Int
    )

    private data class Srv(val target: String, val port: Int)

    private data class MdnsEndpoint(val serviceName: String, val host: String, val port: Int, val txt: Map<String, String>) {
        val id: String get() = txt.getValue("id")
    }

    private class MdnsRecords {
        var serviceName: String? = null
        val srvs = mutableMapOf<String, Srv>()
        val txtByOwner = mutableMapOf<String, Map<String, String>>()
        val addresses = mutableMapOf<String, InetAddress>()

        fun endpointOrNull(): MdnsEndpoint? {
            val service = serviceName?.takeIf {
                it.trimEnd('.').endsWith(SERVICE_FQDN.trimEnd('.'), ignoreCase = true)
            } ?: return null
            val srv = srvs.entries.firstOrNull { it.key.equals(service, ignoreCase = true) }?.value ?: return null
            val host = srv.target
            val address = addresses.entries.firstOrNull { it.key.equals(host, ignoreCase = true) }
                ?.value as? Inet4Address ?: return null
            val txt = txtByOwner.entries.firstOrNull { it.key.equals(service, ignoreCase = true) }?.value ?: emptyMap()
            val identity = !txt["id"].isNullOrBlank() && txt["protocol"] == TRACE_MATE_PROTOCOL_VERSION.toString()
            if (!identity || address.isLoopbackAddress) return null
            return MdnsEndpoint(service, address.hostAddress ?: return null, srv.port, txt)
        }

        fun rejectionReason(): String = when {
            serviceName == null -> "PTR service instance missing"
            srvs.entries.none { it.key.equals(serviceName, ignoreCase = true) } -> "matching SRV missing"
            txtByOwner.entries.none { it.key.equals(serviceName, ignoreCase = true) } -> "matching TXT missing"
            else -> {
                val txt = txtByOwner.entries.firstOrNull { it.key.equals(serviceName, ignoreCase = true) }?.value.orEmpty()
                when {
            txt["id"].isNullOrBlank() -> "TXT id missing"
            txt["protocol"] == null -> "TXT protocol missing"
            txt["protocol"] != TRACE_MATE_PROTOCOL_VERSION.toString() -> "TXT protocol unsupported: ${txt["protocol"]}"
                    addresses.entries.none { it.key.equals(srvs.entries.first { it.key.equals(serviceName, ignoreCase = true) }.value.target, ignoreCase = true) && it.value is Inet4Address } -> "SRV target IPv4 A record missing"
            else -> "candidate was incomplete"
                }
            }
        }
    }

    private companion object {
        const val SERVICE_TYPE = "MAC_AGENT_DISCOVERY_SERVICE_PLACEHOLDER"
        const val SERVICE_FQDN = "MAC_AGENT_DISCOVERY_FQDN_PLACEHOLDER"
        const val MDNS_PORT = 5353
        val MDNS_GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
        const val TYPE_A = 1
        const val TYPE_PTR = 12
        const val TYPE_TXT = 16
        const val TYPE_SRV = 33
        const val DNS_CLASS_IN = 1
        const val DNS_CLASS_MASK = 0x7fff
        const val DNS_CACHE_FLUSH_MASK = 0x8000
        const val DNS_HEADER_SIZE = 12
        const val MAX_PACKET_SIZE = 9000
        const val MAX_DIAGNOSTIC_PACKET_BYTES = 4096
        const val MAX_DNS_POINTER_JUMPS = 32
        const val QUERY_ATTEMPTS = 3
        const val QUERY_INTERVAL_MS = 1_500L
        const val RECEIVE_TIMEOUT_MS = 500
        const val RESPONSE_GRACE_MS = 500L
        const val IDENTITY_TIMEOUT_MS = 5_000
        const val DISCOVERY_TIMEOUT_MS = 10_000L
        val ID_REGEX = Regex("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        val PROTOCOL_VERSION_REGEX = Regex("\\\"protocolVersion\\\"\\s*:\\s*(\\d+)")
        const val TRACE_MATE_PROTOCOL_VERSION = 1
    }
}
