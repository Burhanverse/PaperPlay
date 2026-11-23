package io.max.paperplay.core.sync

import io.max.paperplay.core.library.newUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

actual fun createLanSync(env: LanEnv): LanSync = JavaLanSync(env)

private const val IDENTITY_KEY = "lan_identity_key"
private const val PASSWORD_KEY = "lan_password"

/** Thrown on the server while checking a link; the reason goes back to the other side and the link closes. */
private class AuthFail(val why: LanFailure) : Exception(why.name)

private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
private fun b64d(s: String): ByteArray = Base64.getDecoder().decode(s)
private fun hex(b: ByteArray): String = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
private fun failureOf(name: String?): LanFailure = LanFailure.values().firstOrNull { it.name == name } ?: LanFailure.Protocol

private fun parseObj(bytes: ByteArray): JsonObject =
    wireJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: throw IOException("bad message")

private fun announceHash(id: String, name: String, port: Int): String = hex(sha256("pp-lan|$id|$name|$port".encodeToByteArray())).take(16)

/** The device's IPv4 addresses on real interfaces. */
private fun localIPv4(): List<String> = try {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        .mapNotNull { it.hostAddress }
        .distinct().sorted()
} catch (e: Exception) {
    emptyList()
}

private fun broadcastAddresses(): List<InetAddress> = try {
    val list = ArrayList<InetAddress>()
    for (i in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
        if (!i.isUp || i.isLoopback) continue
        for (a in i.interfaceAddresses) a.broadcast?.let { list += it }
    }
    list.add(InetAddress.getByName("255.255.255.255"))
    list.distinct()
} catch (e: Exception) {
    emptyList()
}

private fun randomDigits(n: Int): String {
    val r = SecureRandom()
    return buildString { repeat(n) { append(r.nextInt(10)) } }
}

private const val MAX_COVER = 4_000_000

internal class JavaLanSync(private val env: LanEnv) : LanSync {
    private val lib = env.library
    private val io = CoroutineScope(SupervisorJob(env.scope.coroutineContext.job) + Dispatchers.IO)
    private val lock = Any()
    private val identityPriv: ByteArray
    private val identityPub: ByteArray
    private val deviceId: String = lib.pref("device_id") ?: newUuid().also { lib.setPref("device_id", it) }
    private val guard = PairingGuard(env.clock, ::randomDigits)
    private val book = AddressBook({ lib.pref("lan_addresses") }, { lib.setPref("lan_addresses", it) }, env.clock)
    private val store = SnapshotStore(lib)

    private val _listening = MutableStateFlow(false)
    private val _port = MutableStateFlow(lib.pref("lan_port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: lanPort)
    private val _name = MutableStateFlow(lib.pref("lan_name")?.takeIf { it.isNotBlank() } ?: env.defaultName)
    private val _addresses = MutableStateFlow(emptyList<String>())
    private val _connected = MutableStateFlow(emptyList<LanDevice>())
    private val _paired = MutableStateFlow(loadPaired())
    private val _discovered = MutableStateFlow(emptyList<DiscoveredDevice>())
    private val _pin = MutableStateFlow<PairingPin?>(null)
    private val _passwordSet = MutableStateFlow(false)
    private val _onlyPaired = MutableStateFlow(lib.pref("lan_require_pairing") == "true")
    private val _tokenMode = MutableStateFlow(lib.pref("lan_token_mode") == "true")
    private val _discovery = MutableStateFlow(lib.pref("lan_discovery") != "false")
    private val _scanning = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _messages = MutableSharedFlow<LanMessage>(extraBufferCapacity = 32)
    private val _connecting = MutableStateFlow<List<String>>(emptyList())
    private val _remoteNow = MutableStateFlow<Map<String, RemoteNow>>(emptyMap())
    private var autoJob: Job? = null

    // Devices the person disconnected by hand: never dialled, and their dials are refused, until the person connects them again.
    private val _held = MutableStateFlow(lib.pref("lan_held")?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty())
    // Devices that said goodbye to this one: not dialled by the rounds below until they dial in or this one connects them.
    private val byed = ConcurrentHashMap.newKeySet<String>()
    override val held: StateFlow<Set<String>> get() = _held

    private fun setHeld(id: String, on: Boolean) {
        _held.update { if (on) it + id else it - id }
        lib.setPref("lan_held", _held.value.joinToString(","))
    }

    private fun autoBlocked(id: String) = id in _held.value || id in byed

    override val listening: StateFlow<Boolean> get() = _listening
    override val port: StateFlow<Int> get() = _port
    override val deviceName: StateFlow<String> get() = _name
    override val localAddresses: StateFlow<List<String>> get() = _addresses
    override val connected: StateFlow<List<LanDevice>> get() = _connected
    override val saved: StateFlow<List<KnownAddress>> get() = book.entries
    override val paired: StateFlow<List<PairedDevice>> get() = _paired
    override val discovered: StateFlow<List<DiscoveredDevice>> get() = _discovered
    override val pin: StateFlow<PairingPin?> get() = _pin
    override val passwordSet: StateFlow<Boolean> get() = _passwordSet
    override val onlyPaired: StateFlow<Boolean> get() = _onlyPaired
    override val tokenMode: StateFlow<Boolean> get() = _tokenMode
    override val discoveryOn: StateFlow<Boolean> get() = _discovery
    override val scanning: StateFlow<Boolean> get() = _scanning
    override val lastError: StateFlow<String?> get() = _error
    override val messages: SharedFlow<LanMessage> get() = _messages
    override val connecting: StateFlow<List<String>> get() = _connecting
    override val remoteNow: StateFlow<Map<String, RemoteNow>> get() = _remoteNow

    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private var udp: DatagramSocket? = null
    private var udpJobs: List<Job> = emptyList()
    private val peers = ConcurrentHashMap<String, Peer>()
    private val autoDialing = ConcurrentHashMap.newKeySet<String>()
    private val pending = AtomicInteger(0)

    init {
        val saved = env.secrets.get(IDENTITY_KEY)?.let { runCatching { b64d(it) }.getOrNull() }?.takeIf { it.size == 32 }
        val priv = saved ?: X25519.newPrivateKey().also { env.secrets.put(IDENTITY_KEY, b64(it)) }
        identityPriv = priv
        identityPub = X25519.publicKey(priv)
        guard.setPassword(env.secrets.get(PASSWORD_KEY))
        _passwordSet.value = guard.hasPassword()
        _addresses.value = localIPv4()
        if (lib.pref("lan_on") == "1") io.launch { start() }
    }

    // Paired devices

    private fun loadPaired(): List<PairedDevice> {
        val raw = lib.pref("lan_paired") ?: return emptyList()
        return runCatching {
            (wireJson.parseToJsonElement(raw) as JsonArray).map {
                val o = it as JsonObject
                PairedDevice(o.reqStr("id"), o.reqStr("name"), o.reqStr("key"), o.lng("at") ?: 0)
            }
        }.getOrDefault(emptyList())
    }

    private fun savePaired(list: List<PairedDevice>) {
        _paired.value = list
        lib.setPref(
            "lan_paired",
            buildJsonArray {
                for (p in list) add(buildJsonObject { put("id", p.id); put("name", p.name); put("key", p.publicKey); put("at", p.pairedAt) })
            }.toString(),
        )
    }

    private fun addPaired(id: String, name: String, key: ByteArray) = synchronized(lock) {
        savePaired(_paired.value.filter { it.id != id } + PairedDevice(id, name, b64(key), env.clock()))
    }

    private fun isPaired(id: String) = _paired.value.any { it.id == id }

    private inline fun <T> bk(block: AddressBook.() -> T): T = synchronized(lock) { book.block() }

    // Start and stop

    override suspend fun start() {
        withContext(Dispatchers.IO) {
            if (_listening.value) return@withContext
            val ss = try {
                ServerSocket().also { it.reuseAddress = true; it.bind(InetSocketAddress(_port.value)) }
            } catch (e: IOException) {
                _error.value = "Port ${_port.value} is in use"
                return@withContext
            }
            _error.value = null
            server = ss
            _listening.value = true
            lib.setPref("lan_on", "1")
            _addresses.value = localIPv4()
            acceptJob = io.launch { acceptLoop(ss) }
            autoJob?.cancel()
            autoJob = io.launch { autoConnect() }
            if (_discovery.value) startDiscovery()
        }
    }

    override suspend fun stop() {
        withContext(Dispatchers.IO) {
            lib.setPref("lan_on", "0")
            stopDiscovery()
            acceptJob?.cancel(); acceptJob = null
            autoJob?.cancel(); autoJob = null
            runCatching { server?.close() }
            server = null
            for (p in peers.values.toList()) p.close()
            _listening.value = false
            io.coroutineContext.cancelChildren()
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try { ss.accept() } catch (e: IOException) { break }
            io.launch { serve(s) }
        }
    }

    // Server side

    private fun serve(s: Socket) {
        if (pending.incrementAndGet() > 8) { pending.decrementAndGet(); runCatching { s.close() }; return }
        var peer: Peer? = null
        try {
            s.soTimeout = 10_000
            s.tcpNoDelay = true
            val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            val hs = serverHandshake(inp, out)
            val ch = hs.channel
            val first = parseObj(ch.receive())
            val source = (s.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "?"
            if (first.str("t") != "auth") throw IOException("bad message")
            if (first.str("m") == "probe") {
                ch.send(
                    buildJsonObject {
                        put("t", "probe-ok"); put("id", deviceId); put("name", _name.value); put("desktop", env.isDesktop); put("port", _port.value)
                    }.toString().encodeToByteArray(),
                )
            } else {
                try {
                    peer = authenticate(first, hs, source, s)
                } catch (f: AuthFail) {
                    ch.send(buildJsonObject { put("t", "auth-fail"); put("why", f.why.name) }.toString().encodeToByteArray())
                }
            }
        } catch (e: Exception) {
            // A broken or hostile link: dropped without detail.
        } finally {
            pending.decrementAndGet()
        }
        val p = peer
        if (p == null) { runCatching { s.close() }; return }
        register(p)
        runLoop(p)
    }

    private fun authenticate(a: JsonObject, hs: Handshake, source: String, s: Socket): Peer {
        val id = a.reqStr("id")
        val name = a.reqStr("name").take(64)
        val desktop = a.flag("desktop") ?: false
        val spub = b64d(a.reqStr("spub"))
        val theirProof = b64d(a.reqStr("proof"))
        val method = a.reqStr("m")
        val t = hs.transcript
        if (spub.size != 32 || id == deviceId || id.length > 100) throw AuthFail(LanFailure.Protocol)
        val serverProof: ByteArray
        var newPair = false
        var viaToken = false
        when (method) {
            "key" -> {
                val p = _paired.value.firstOrNull { it.id == id } ?: throw AuthFail(LanFailure.NotPaired)
                if (id in _held.value) throw AuthFail(LanFailure.NotReady)
                if (!b64d(p.publicKey).contentEquals(spub)) throw AuthFail(LanFailure.BadProof)
                val dh = X25519.shared(identityPriv, spub)
                if (!constantEq(proof(dh, "key", "client", t, spub), theirProof)) throw AuthFail(LanFailure.BadProof)
                serverProof = proof(dh, "key", "server", t, identityPub)
            }
            "code" -> {
                if (_onlyPaired.value) throw AuthFail(LanFailure.NotPaired)
                var matched: String? = null
                val r = guard.attempt(source) { secret ->
                    val ok = constantEq(proof(secret.encodeToByteArray(), "code", "client", t, spub), theirProof)
                    if (ok) matched = secret
                    ok
                }
                if (r == CodeAttempt.Locked) throw AuthFail(LanFailure.LockedOut)
                if (r == CodeAttempt.Wrong) throw AuthFail(LanFailure.WrongCode)
                _pin.value = guard.currentPin()
                serverProof = proof(matched!!.encodeToByteArray(), "code", "server", t, identityPub)
                newPair = true
            }
            "token" -> {
                if (!_tokenMode.value || _onlyPaired.value) throw AuthFail(LanFailure.NotPaired)
                val token = env.botToken()?.takeIf { it.isNotEmpty() }?.encodeToByteArray() ?: throw AuthFail(LanFailure.NotPaired)
                if (!constantEq(proof(token, "token", "client", t, spub), theirProof)) throw AuthFail(LanFailure.BadProof)
                serverProof = proof(token, "token", "server", t, identityPub)
                viaToken = true
            }
            else -> throw AuthFail(LanFailure.Protocol)
        }
        val port = a.lng("port")?.toInt()?.takeIf { it in 1..65535 } ?: lanPort
        hs.channel.send(
            buildJsonObject {
                put("t", "auth-ok"); put("id", deviceId); put("name", _name.value); put("desktop", env.isDesktop)
                put("spub", b64(identityPub)); put("proof", b64(serverProof)); put("port", _port.value)
                put("addrs", buildJsonArray { for (x in _addresses.value) add(x) })
            }.toString().encodeToByteArray(),
        )
        if (newPair) addPaired(id, name, spub)
        if (!viaToken) {
            bk { learn(source, port, id, name) }
            for (x in a.arr("addrs").orEmpty().take(8)) {
                val h = (x as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                if (h != source && parseAddress(h) != null) bk { learn(h, port, id, name, AddressState.Trying) }
            }
        }
        s.soTimeout = 0
        return Peer(LanDevice(id, name, desktop, source, port, !viaToken, env.clock()), hs.channel, s)
    }

    // Peers

    private inner class Peer(val info: LanDevice, val ch: SecureChannel, val socket: Socket) {
        val waiting = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        fun send(o: JsonObject) = ch.send(o.toString().encodeToByteArray())
        fun close() { runCatching { socket.close() } }
    }

    private fun register(p: Peer) {
        // The other device dialled in on purpose: it wants the link back.
        byed.remove(p.info.id)
        peers.put(p.info.id, p)?.close()
        publishConnected()
        // A new link exchanges song data at once (what one device lacks, the other fills in), then now and then while it lasts.
        io.launch {
            delay(3_000)
            while (peers[p.info.id] === p) {
                runCatching { syncNow(p.info.id) }
                delay(10 * 60_000L)
            }
        }
    }

    private fun publishConnected() {
        _connected.value = peers.values.map { it.info }.sortedBy { it.name }
        _remoteNow.update { m -> m.filterKeys { it in peers.keys } }
    }

    private fun runLoop(p: Peer) {
        try {
            p.socket.soTimeout = 0
            p.socket.keepAlive = true
            while (true) {
                val m = parseObj(p.ch.receive())
                handle(p, m)
            }
        } catch (e: Exception) {
            // Link closed or damaged.
        } finally {
            peers.remove(p.info.id, p)
            for (w in p.waiting.values) w.completeExceptionally(IOException("closed"))
            p.close()
            publishConnected()
        }
    }

    private fun handle(p: Peer, m: JsonObject) {
        when (m.str("t")) {
            "sync" -> {
                val rid = m.str("rid") ?: return
                try {
                    val res = store.apply(SnapshotCodec.decode(m.obj("data") ?: throw IllegalArgumentException("no data")))
                    askCovers(p, res)
                    p.send(
                        buildJsonObject {
                            put("t", "sync-reply"); put("rid", rid); put("changed", res.songsChanged + res.playlistsChanged)
                            put("data", SnapshotCodec.encode(store.build()))
                        },
                    )
                } catch (e: Exception) {
                    // Any failure is answered, so the other side does not wait out its timeout and the link stays up.
                    p.send(buildJsonObject { put("t", "sync-reply"); put("rid", rid); put("error", true) })
                }
            }
            "sync-reply" -> m.str("rid")?.let { p.waiting.remove(it)?.complete(m) }
            "data" -> {
                val rid = m.str("rid") ?: return
                val answer = runCatching { env.data?.answer(p.info.id, m.obj("q") ?: JsonObject(emptyMap())) }.getOrNull()
                p.send(buildJsonObject { put("t", "data-reply"); put("rid", rid); if (answer != null) put("a", answer) else put("error", true) })
            }
            "data-reply" -> m.str("rid")?.let { p.waiting.remove(it)?.complete(m) }
            // Index pause, resume and "read this chat" requests, and "new index posts are here".
            "indexctl", "indexnudge" -> if (p.info.paired) _messages.tryEmit(LanMessage(p.info.id, m.str("t")!!, m.toString()))
            "coverreq" -> serveCovers(p, m.arr("ids")?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty())
            "cover" -> {
                val id = m.str("id")
                val at = (m["at"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                val bytes = m.str("b64")?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
                if (id != null && at != null && bytes != null && bytes.size <= MAX_COVER) runCatching { env.saveCover(id, at, bytes, m.str("curl")) }
            }
            "nowplaying" -> {
                _remoteNow.update { it + (p.info.id to RemoteNow(
                    m.str("id"), m.str("title"), m.str("artist"), m.flag("playing") == true,
                    (m["pos"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                    (m["dur"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                    System.currentTimeMillis(), m.str("out"), m.str("kind"),
                )) }
                _messages.tryEmit(LanMessage(p.info.id, "nowplaying", m.toString()))
            }
            "queue" -> _messages.tryEmit(LanMessage(p.info.id, "queue", m.toString()))
            "bye" -> byed.add(p.info.id)
            // Only a paired device may drive playback; a device that linked with the token alone may not.
            "control" -> if (p.info.paired) _messages.tryEmit(LanMessage(p.info.id, "control", m.toString()))
            else -> Unit
        }
    }

    // Client side

    private fun dial(host: String, port: Int, method: String, code: String?, expectId: String?): ConnectResult {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), 3_000)
        } catch (e: Exception) {
            runCatching { s.close() }
            return ConnectResult(false, LanFailure.Unreachable)
        }
        var peer: Peer? = null
        var failure = LanFailure.Protocol
        try {
            s.soTimeout = 10_000
            s.tcpNoDelay = true
            val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            val hs = clientHandshake(inp, out)
            val t = hs.transcript
            val secret: ByteArray
            val label: String
            var knownKey: ByteArray? = null
            when (method) {
                "key" -> {
                    val p = _paired.value.firstOrNull { it.id == expectId }
                    if (p == null) { failure = LanFailure.NotPaired; throw AuthFail(failure) }
                    knownKey = b64d(p.publicKey)
                    secret = X25519.shared(identityPriv, knownKey)
                    label = "key"
                }
                "code" -> { secret = (code ?: "").encodeToByteArray(); label = "code" }
                else -> {
                    val tok = env.botToken()?.takeIf { it.isNotEmpty() }
                    if (tok == null) { failure = LanFailure.NotPaired; throw AuthFail(failure) }
                    secret = tok.encodeToByteArray(); label = "token"
                }
            }
            hs.channel.send(
                buildJsonObject {
                    put("t", "auth"); put("m", method); put("id", deviceId); put("name", _name.value); put("desktop", env.isDesktop)
                    put("spub", b64(identityPub)); put("port", _port.value)
                    put("addrs", buildJsonArray { for (x in _addresses.value) add(x) })
                    put("proof", b64(proof(secret, label, "client", t, identityPub)))
                }.toString().encodeToByteArray(),
            )
            val reply = parseObj(hs.channel.receive())
            if (reply.str("t") != "auth-ok") {
                failure = failureOf(reply.str("why"))
                throw AuthFail(failure)
            }
            val sid = reply.reqStr("id")
            val sname = reply.reqStr("name").take(64)
            val spub = b64d(reply.reqStr("spub"))
            failure = LanFailure.BadProof
            if (spub.size != 32 || sid == deviceId) { failure = LanFailure.Protocol; throw AuthFail(failure) }
            if (method == "key" && (sid != expectId || !spub.contentEquals(knownKey))) throw AuthFail(failure)
            if (!constantEq(proof(secret, label, "server", t, spub), b64d(reply.reqStr("proof")))) throw AuthFail(failure)
            val sport = reply.lng("port")?.toInt()?.takeIf { it in 1..65535 } ?: port
            if (method == "code") addPaired(sid, sname, spub)
            val viaToken = method == "token"
            if (!viaToken) {
                bk { learn(host, port, sid, sname) }
                for (x in reply.arr("addrs").orEmpty().take(8)) {
                    val h = (x as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                    if (h != host && parseAddress(h) != null) bk { learn(h, sport, sid, sname, AddressState.Trying) }
                }
            }
            s.soTimeout = 0
            peer = Peer(LanDevice(sid, sname, reply.flag("desktop") ?: false, host, port, !viaToken, env.clock()), hs.channel, s)
        } catch (e: AuthFail) {
            return ConnectResult(false, e.why)
        } catch (e: Exception) {
            return ConnectResult(false, failure)
        } finally {
            if (peer == null) runCatching { s.close() }
        }
        val p = peer!!
        register(p)
        io.launch { runLoop(p) }
        return ConnectResult(true, null, p.info)
    }

    private fun result(key: String, r: ConnectResult): ConnectResult {
        val state = when {
            r.ok -> AddressState.Connected
            r.failure == LanFailure.Unreachable -> AddressState.Unreachable
            else -> AddressState.Refused
        }
        bk { record(key, state) }
        return r
    }

    override suspend fun connectWithCode(host: String, port: Int, code: String): ConnectResult = withContext(Dispatchers.IO) {
        val a = parseAddress("$host:$port") ?: return@withContext ConnectResult(false, LanFailure.Unreachable)
        result(a.key, dial(a.host, a.port, "code", code.trim(), null))
    }

    override suspend fun connectWithToken(host: String, port: Int): ConnectResult = withContext(Dispatchers.IO) {
        val a = parseAddress("$host:$port") ?: return@withContext ConnectResult(false, LanFailure.Unreachable)
        result(a.key, dial(a.host, a.port, "token", null, null))
    }

    override suspend fun connect(deviceId: String): ConnectResult = withContext(Dispatchers.IO) {
        peers[deviceId]?.let { return@withContext ConnectResult(true, null, it.info) }
        // Asked for, by the person or by a round that already skipped the held ones.
        if (deviceId in _held.value) setHeld(deviceId, false)
        byed.remove(deviceId)
        val label = _paired.value.firstOrNull { it.id == deviceId }?.name ?: deviceId
        _connecting.update { it + label }
        try { dialPaired(deviceId) } finally { _connecting.update { it - label } }
    }

    private fun dialPaired(deviceId: String): ConnectResult {
        // Where the device was just heard comes first, then every address saved for it, newest first.
        val tries = (_discovered.value.filter { it.id == deviceId }.map { it.host to it.port } +
            bk { forDevice(deviceId) }.map { it.host to it.port }).distinct()
        if (tries.isEmpty()) return ConnectResult(false, LanFailure.Unreachable)
        var last = ConnectResult(false, LanFailure.Unreachable)
        for ((h, p) in tries) {
            var r = result("$h:$p", dial(h, p, "key", null, deviceId))
            if (!r.ok && r.failure == LanFailure.NotPaired && _tokenMode.value) r = result("$h:$p", dial(h, p, "token", null, null))
            if (r.ok) return r
            if (last.failure == LanFailure.Unreachable || r.failure != LanFailure.Unreachable) last = r
        }
        return last
    }

    override suspend fun disconnect(deviceId: String) {
        // Remembered, or the rounds that link paired devices would dial it again within seconds.
        setHeld(deviceId, true)
        peers[deviceId]?.let { p ->
            // Tells the other device, so it does not dial back either.
            runCatching { p.send(buildJsonObject { put("t", "bye") }) }
            p.close()
        }
    }

    // Sync

    override suspend fun syncNow(deviceId: String?): SyncResult = withContext(Dispatchers.IO) {
        val targets = if (deviceId == null) peers.values.toList() else listOfNotNull(peers[deviceId])
        if (targets.isEmpty()) return@withContext SyncResult(false, LanFailure.NotReady)
        var songs = 0
        var lists = 0
        var other = 0
        var failure: LanFailure? = null
        for (p in targets) {
            // Rows and index posts first, so the song state below also covers the songs that just arrived.
            env.data?.let { data ->
                runCatching { data.pull(p.info.id) { q -> ask(p, q) } }.onSuccess { songs += it }
            }
            val rid = newUuid()
            val waiter = CompletableDeferred<JsonObject>()
            p.waiting[rid] = waiter
            try {
                p.send(buildJsonObject { put("t", "sync"); put("rid", rid); put("data", SnapshotCodec.encode(store.build())) })
                val reply = withTimeoutOrNull(60_000) { waiter.await() }
                if (reply == null || reply.flag("error") == true) { failure = failure ?: LanFailure.Protocol; continue }
                val res = store.apply(SnapshotCodec.decode(reply.obj("data") ?: throw IllegalArgumentException("no data")))
                askCovers(p, res)
                songs += res.songsChanged; lists += res.playlistsChanged
                other += (reply["changed"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
            } catch (e: IOException) {
                failure = failure ?: LanFailure.Protocol
            } catch (e: IllegalArgumentException) {
                failure = failure ?: LanFailure.Protocol
            } finally {
                p.waiting.remove(rid)
            }
        }
        SyncResult(failure == null, failure, songs, lists, other)
    }

    /** Sends a data request to [p] and waits for its answer; throws when the link fails or the other side could not answer. */
    private suspend fun ask(p: Peer, q: JsonObject): JsonObject {
        val rid = newUuid()
        val waiter = CompletableDeferred<JsonObject>()
        p.waiting[rid] = waiter
        try {
            p.send(buildJsonObject { put("t", "data"); put("rid", rid); put("q", q) })
            val reply = withTimeoutOrNull(120_000) { waiter.await() } ?: throw IOException("no answer")
            if (reply.flag("error") == true) throw IOException("the other device could not answer")
            return reply.obj("a") ?: throw IOException("empty answer")
        } finally {
            p.waiting.remove(rid)
        }
    }

    override suspend fun sendMessage(deviceId: String?, body: JsonObject): Boolean = withContext(Dispatchers.IO) {
        val targets = if (deviceId == null) peers.values.toList() else listOfNotNull(peers[deviceId])
        targets.count { p -> runCatching { p.send(body) }.isSuccess } > 0
    }

    /** Sends the hand-chosen covers [ids] asked for, one message each, skipping any that are missing or too big. */
    private fun serveCovers(p: Peer, ids: List<String>) {
        io.launch {
            for (id in ids.take(200)) {
                val cover = runCatching { env.coverBytes(id) }.getOrNull() ?: continue
                if (cover.bytes.size > MAX_COVER) continue
                runCatching {
                    p.send(
                        buildJsonObject {
                            put("t", "cover"); put("id", id); put("at", cover.at); put("curl", cover.sourceUrl)
                            put("b64", java.util.Base64.getEncoder().encodeToString(cover.bytes))
                        },
                    )
                }
            }
        }
    }

    /** Asks [p] for the covers [res] says it has and this device lacks (hand-chosen ones are in front), 200 songs to a request. */
    private fun askCovers(p: Peer, res: ApplyResult) {
        for (part in res.wantCovers.chunked(200)) {
            runCatching { p.send(buildJsonObject { put("t", "coverreq"); put("ids", buildJsonArray { for (u in part) add(u) }) }) }
        }
    }

    override suspend fun unpair(deviceId: String) {
        withContext(Dispatchers.IO) {
            synchronized(lock) { savePaired(_paired.value.filter { it.id != deviceId }) }
            if (deviceId in _held.value) setHeld(deviceId, false)
            peers[deviceId]?.close()
        }
    }

    override suspend fun unpairAll() {
        withContext(Dispatchers.IO) {
            synchronized(lock) { savePaired(emptyList()) }
            for (p in peers.values.toList()) p.close()
        }
    }

    // Pairing

    override suspend fun showPin(): PairingPin {
        val p = guard.newPin()
        _pin.value = p
        io.launch {
            delay((p.expiresAt - env.clock()).coerceAtLeast(0) + 50)
            if (_pin.value == p) _pin.value = guard.currentPin()
        }
        return p
    }

    override suspend fun hidePin() {
        guard.clearPin()
        _pin.value = null
    }

    override suspend fun setPassword(password: String?) {
        val p = password?.takeIf { it.length >= 6 }
        if (p == null) env.secrets.remove(PASSWORD_KEY) else env.secrets.put(PASSWORD_KEY, p)
        guard.setPassword(p)
        _passwordSet.value = p != null
    }

    override suspend fun resetCode() {
        guard.reset()
        env.secrets.remove(PASSWORD_KEY)
        _pin.value = null
        _passwordSet.value = false
    }

    // Addresses and settings

    override suspend fun addAddress(text: String, label: String?, deviceId: String?): Boolean = bk { add(text, label, deviceId) }

    override suspend fun editAddress(oldKey: String, text: String): Boolean = bk { edit(oldKey, text) }

    override suspend fun removeAddress(key: String) = bk { remove(key) }

    override suspend fun setDeviceName(name: String) {
        val n = name.trim().take(64).ifEmpty { env.defaultName }
        _name.value = n
        lib.setPref("lan_name", n)
    }

    override suspend fun setPort(port: Int) {
        if (port !in 1024..65535 || port == _port.value) return
        val wasOn = _listening.value
        if (wasOn) stop()
        _port.value = port
        lib.setPref("lan_port", port.toString())
        if (wasOn) start()
    }

    override suspend fun setOnlyPaired(on: Boolean) {
        _onlyPaired.value = on
        lib.setPref("lan_require_pairing", on.toString())
    }

    override suspend fun setTokenMode(on: Boolean) {
        _tokenMode.value = on
        lib.setPref("lan_token_mode", on.toString())
    }

    override suspend fun setDiscovery(on: Boolean) {
        _discovery.value = on
        lib.setPref("lan_discovery", on.toString())
        withContext(Dispatchers.IO) {
            if (!on) stopDiscovery() else if (_listening.value) startDiscovery()
            Unit
        }
    }

    // Discovery

    private fun startDiscovery() {
        if (udp != null) return
        val sock = try {
            DatagramSocket(null as java.net.SocketAddress?).also { it.reuseAddress = true; it.broadcast = true; it.bind(InetSocketAddress(lanDiscoveryPort)) }
        } catch (e: Exception) {
            _error.value = "Discovery port is in use"
            return
        }
        udp = sock
        val listen = io.launch {
            val buf = ByteArray(1024)
            while (!sock.isClosed) {
                val pk = DatagramPacket(buf, buf.size)
                try { sock.receive(pk) } catch (e: Exception) { break }
                runCatching { onAnnounce(pk) }
            }
        }
        val announce = io.launch {
            // Every 5 s for the first minute, then every 30 s: each round lists the network interfaces and broadcasts, all day.
            // A device that shows up is answered at once (onAnnounce), so finding each other stays quick.
            val started = env.clock()
            while (isActive && !sock.isClosed) {
                _addresses.value = localIPv4()
                runCatching { announceOnce(sock) }
                delay(if (env.clock() - started < 60_000L) 5_000L else 30_000L)
            }
        }
        udpJobs = listOf(listen, announce)
    }

    private fun stopDiscovery() {
        udpJobs.forEach { it.cancel() }
        udpJobs = emptyList()
        runCatching { udp?.close() }
        udp = null
    }

    private fun announceOnce(sock: DatagramSocket) {
        val port = _port.value
        val name = _name.value
        val msg = buildJsonObject {
            put("app", "paperplay"); put("v", 1); put("id", deviceId); put("name", name); put("port", port)
            put("desktop", env.isDesktop); put("hash", announceHash(deviceId, name, port))
        }.toString().encodeToByteArray()
        for (a in broadcastAddresses()) runCatching { sock.send(DatagramPacket(msg, msg.size, a, lanDiscoveryPort)) }
    }

    private fun onAnnounce(pk: DatagramPacket) {
        val o = parseObj(pk.data.copyOfRange(pk.offset, pk.offset + pk.length))
        if (o.str("app") != "paperplay") return
        val id = o.reqStr("id")
        val name = o.reqStr("name").take(64)
        val port = o.reqLng("port").toInt()
        if (id == deviceId || port !in 1..65535 || o.str("hash") != announceHash(id, name, port)) return
        val host = pk.address?.hostAddress ?: return
        // A device not heard lately gets our announcement back at once, since we only announce every 30 s after the first minute.
        if (_discovered.value.none { it.id == id }) udp?.let { s -> io.launch { runCatching { announceOnce(s) } } }
        addDiscovered(DiscoveredDevice(id, name, o.flag("desktop") ?: false, host, port, env.clock(), isPaired(id)))
    }

    private fun addDiscovered(d: DiscoveredDevice) {
        val cutoff = env.clock() - 3 * 60_000
        val known = bk { forDevice(d.id).isNotEmpty() } || d.paired
        // Announcing is not trying: the address is only marked as seen, so it is not shown as "Trying now" for ever.
        if (known) bk { learn(d.host, d.port, d.id, d.name, AddressState.Seen) }
        _discovered.value = (listOf(d) + _discovered.value.filter { it.id != d.id && it.lastSeen >= cutoff }).sortedByDescending { it.lastSeen }
        // A paired device that shows up, at its old address or a new one, is linked at once instead of at the next 20 second round.
        if (d.paired && _listening.value && !peers.containsKey(d.id) && !autoBlocked(d.id) && autoDialing.add(d.id)) {
            io.launch {
                try {
                    // Both sides see each other's announcement; a short random wait and a second look keep double dials rare.
                    delay((150..900).random().toLong())
                    if (!peers.containsKey(d.id) && !autoBlocked(d.id) && _connecting.value.isEmpty()) runCatching { connect(d.id) }
                } finally { autoDialing.remove(d.id) }
            }
        }
    }

    private fun probe(host: String, port: Int): DiscoveredDevice? {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), 600)
            s.soTimeout = 3_000
            val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            val hs = clientHandshake(inp, out)
            hs.channel.send(buildJsonObject { put("t", "auth"); put("m", "probe") }.toString().encodeToByteArray())
            val r = parseObj(hs.channel.receive())
            if (r.str("t") != "probe-ok") return null
            val id = r.reqStr("id")
            if (id == deviceId) return null
            return DiscoveredDevice(
                id, r.reqStr("name").take(64), r.flag("desktop") ?: false, host,
                r.lng("port")?.toInt()?.takeIf { it in 1..65535 } ?: port, env.clock(), isPaired(id),
            )
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { s.close() }
        }
    }

    override suspend fun scanNetwork(): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        _scanning.value = true
        try {
            val mine = localIPv4()
            _addresses.value = mine
            val port = _port.value
            val targets = mine.flatMap { ip -> val pre = ip.substringBeforeLast('.'); (1..254).map { "$pre.$it" } }.distinct().filter { it !in mine }
            val gate = Semaphore(48)
            val found = coroutineScope {
                targets.map { h -> async { gate.withPermit { probe(h, port) } } }.awaitAll().filterNotNull()
            }
            found.forEach { addDiscovered(it) }
            found
        } finally {
            _scanning.value = false
        }
    }

    // Stubs for queue and now playing share

    override suspend fun sendQueue(deviceId: String, uniqueIds: List<String>, index: Int): Boolean = withContext(Dispatchers.IO) {
        val p = peers[deviceId] ?: return@withContext false
        runCatching {
            p.send(buildJsonObject { put("t", "queue"); put("index", index); put("ids", buildJsonArray { for (u in uniqueIds) add(u) }) })
        }.isSuccess
    }

    override suspend fun sendControl(deviceId: String, action: String, uids: List<String>): Boolean = withContext(Dispatchers.IO) {
        val p = peers[deviceId] ?: return@withContext false
        runCatching {
            p.send(buildJsonObject {
                put("t", "control"); put("action", action)
                if (uids.isNotEmpty()) put("uids", buildJsonArray { for (u in uids) add(u) })
            })
        }.isSuccess
    }

    /** Dials every paired device that is not linked, now and then, so devices find each other without a tap. */
    private suspend fun autoConnect() {
        delay(2_000)
        // A device that keeps failing (switched off, or on another network) is dialled less and less often, up to every
        // 5 minutes, instead of every 20 seconds all day. A success starts over; a device heard on the network is dialled by
        // discovery itself, apart from this.
        val fails = HashMap<String, Int>()
        val nextAt = HashMap<String, Long>()
        while (_listening.value) {
            val now = System.currentTimeMillis()
            for (d in _paired.value) {
                if (peers.containsKey(d.id)) { fails.remove(d.id); nextAt.remove(d.id); continue }
                if (now < (nextAt[d.id] ?: 0L) || autoBlocked(d.id) || _connecting.value.isNotEmpty()) continue
                // Both sides may dial at once; a short random wait and a second look keep that rare.
                delay((200..2_500).random().toLong())
                if (peers.containsKey(d.id) || autoBlocked(d.id)) continue
                val ok = runCatching { connect(d.id).ok }.getOrDefault(false)
                if (ok) { fails.remove(d.id); nextAt.remove(d.id) } else {
                    val n = (fails[d.id] ?: 0) + 1
                    fails[d.id] = n
                    nextAt[d.id] = System.currentTimeMillis() + minOf(20_000L shl minOf(n - 1, 4), 300_000L)
                }
            }
            delay(20_000)
        }
    }

    override suspend fun sendNowPlaying(deviceId: String, uniqueId: String?, positionMs: Long, playing: Boolean, title: String?, artist: String?, durationMs: Long, outName: String?, outKind: String?): Boolean =
        withContext(Dispatchers.IO) {
            val p = peers[deviceId] ?: return@withContext false
            runCatching {
                p.send(
                    buildJsonObject {
                        put("t", "nowplaying"); put("id", uniqueId); put("pos", positionMs); put("dur", durationMs); put("playing", playing); put("title", title); put("artist", artist); put("out", outName); put("kind", outKind)
                    },
                )
            }.isSuccess
        }
}
