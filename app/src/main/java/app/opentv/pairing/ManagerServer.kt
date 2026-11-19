/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import android.content.Context
import android.util.Log
import app.opentv.config.ConfigUpdater
import app.opentv.core.AppSettings
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.StreamKind
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.parser.displayTitle
import app.opentv.data.model.SourceKind
import app.opentv.data.model.shownName
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.SourceRepository
import app.opentv.recording.SmbClient
import app.opentv.recording.SmbConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Serves a "manage your channels from your phone or laptop" web app off this television.
 *
 * ## Same ethos as [PairingServer]
 *
 * There is no cloud. The TV runs a tiny HTTP server on the local network for as long as the manage
 * screen is open; a phone or laptop on the same wifi opens a real page — with a keyboard and mouse
 * instead of a D-pad — and browses, hides, favourites, renames and reorders channels. Every change
 * writes straight to this device's database. It works with the internet unplugged.
 *
 * ## Access
 *
 * The page is open to anyone on the local network: the address is simply
 * `http://<tv-ip>:<port>/`, with no token. What remains:
 *
 * - Binds to the LAN, and only while the manage screen is open.
 * - The first free port of [PORTS] is used (9090, 1234, 8080, 8888); only if all four are busy
 *   does the system pick one.
 * - Requests from non-private addresses are refused outright.
 * - Request bodies are capped ([MAX_BODY_BYTES]).
 * - Unlike pairing (which closes after the first submit) this keeps serving while the screen is
 *   open, and only closes itself after [IDLE_TIMEOUT_MINUTES] with no requests, or on [stop].
 */
class ManagerServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val catalog: CatalogRepository,
    private val settings: AppSettings,
    private val sourceRepository: SourceRepository,
) {

    data class Session(
        /** What the QR code encodes, e.g. `http://192.168.0.14:9090/` */
        val url: String,
        val host: String,
        val port: Int,
    ) {
        /** The address to type if the QR will not scan. */
        val shortUrl: String get() = "$host:$port"
    }

    sealed interface State {
        data object Idle : State
        data class Listening(val session: Session) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var lastActivityMillis = 0L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun start() {
        if (running) return
        val host = lanAddress()
        if (host == null) {
            _state.value = State.Failed(
                "This device doesn't appear to be on a network, so a phone or laptop can't reach it.",
            )
            return
        }

        val socket = openSocket()
        if (socket == null) {
            _state.value = State.Failed("Could not start the channel manager on this device.")
            return
        }

        serverSocket = socket
        running = true
        lastActivityMillis = System.currentTimeMillis()

        _state.value = State.Listening(
            Session(
                url = "http://$host:${socket.localPort}/",
                host = host,
                port = socket.localPort,
            ),
        )

        // A plain thread rather than a coroutine: ServerSocket.accept() blocks uninterruptibly,
        // and the only reliable way to stop it is to close the socket.
        thread(name = "opentv-manager", isDaemon = true) {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break // socket closed, which is how we stop
                }
                runCatching { handle(client) }
                    .onFailure { Log.w(TAG, "Manager request failed", it) }
                runCatching { client.close() }
            }
        }

        // Idle watchdog: keep serving while the screen is open, but do not leave a socket on the
        // user's network for hours if they wander off. Closes after a long unused stretch.
        scope.launch(Dispatchers.Default) {
            while (running) {
                delay(TimeUnit.MINUTES.toMillis(1))
                val idleFor = System.currentTimeMillis() - lastActivityMillis
                if (running && idleFor > TimeUnit.MINUTES.toMillis(IDLE_TIMEOUT_MINUTES)) {
                    stop()
                    _state.value = State.Failed(
                        "The channel manager closed after a while unused. Reopen it when you need it.",
                    )
                    break
                }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        if (_state.value is State.Listening) _state.value = State.Idle
    }

    // ---- Request handling ----------------------------------------------------------------------

    private fun handle(client: Socket) {
        client.soTimeout = SOCKET_TIMEOUT_MILLIS

        if (!PairingHttp.isPrivateAddress(client.inetAddress?.hostAddress)) {
            client.getOutputStream().write(
                PairingHttp.httpResponse("403 Forbidden", "text/plain", "Local network only."),
            )
            return
        }

        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val (method, path, query) = PairingHttp.parseRequestLine(requestLine) ?: return

        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            PairingHttp.parseHeader(line)?.let { (name, value) -> headers[name] = value }
        }

        val output = client.getOutputStream()

        // Any request counts as activity; the idle watchdog measures from here.
        lastActivityMillis = System.currentTimeMillis()

        val body = if (method == "POST") readBody(reader, headers) else ""

        when {
            method == "GET" && path == "/" ->
                output.write(PairingHttp.httpResponse("200 OK", "text/html", ManagerPage.page()))

            method == "GET" && path == "/meta" ->
                output.write(PairingHttp.httpResponse("200 OK", "application/json", metaJson()))

            method == "GET" && path == "/channels" ->
                output.write(PairingHttp.httpResponse("200 OK", "application/json", channelsJson(query)))

            method == "POST" && path == "/channel" -> handleChannelPatch(output, body)

            method == "POST" && path == "/reorder" -> handleReorder(output, body)

            method == "POST" && path == "/bulk" -> handleBulk(output, body)

            method == "GET" && path == "/vod/meta" ->
                output.write(PairingHttp.httpResponse("200 OK", "application/json", vodMetaJson(query)))

            method == "GET" && path == "/vod/items" ->
                output.write(PairingHttp.httpResponse("200 OK", "application/json", vodItemsJson(query)))

            method == "POST" && path == "/vod/item" -> handleVodItem(output, body)

            method == "POST" && path == "/vod/bulk" -> handleVodBulk(output, body)

            method == "GET" && path == "/recording" ->
                output.write(PairingHttp.httpResponse("200 OK", "application/json", recordingJson()))

            method == "POST" && path == "/recording" -> handleRecording(output, body)

            method == "POST" && path == "/recording/test" -> handleRecordingTest(output, body)

            method == "POST" && path == "/source" -> handleAddSource(output, body)

            method == "POST" && path == "/source/test" -> handleTestSource(output, body)

            method == "GET" && path == "/conf" ->
                writeJson(output, ConfDto.serializer(), ConfDto(url = ConfigUpdater.getConfUrl(context)))

            method == "POST" && path == "/conf" -> handleConf(output, body)

            else -> output.write(PairingHttp.httpResponse("404 Not Found", "text/plain", "Not found."))
        }
    }

    private fun readBody(reader: BufferedReader, headers: Map<String, String>): String {
        val contentLength = (headers["content-length"]?.toIntOrNull() ?: 0)
            .coerceIn(0, MAX_BODY_BYTES)
        if (contentLength <= 0) return ""
        val buffer = CharArray(contentLength)
        var total = 0
        while (total < contentLength) {
            val read = reader.read(buffer, total, contentLength - total)
            if (read < 0) break
            total += read
        }
        return String(buffer, 0, total)
    }

    // ---- Routes --------------------------------------------------------------------------------

    /** `{ sources:[{id,name}], categories:[{id,label,sourceId,count,hidden}] }` */
    private fun metaJson(): String = runBlocking {
        val sources = catalog.enabledSources()
        val sourceIds = sources.map { it.id }.toSet()
        val counts = catalog.categoryChannelCounts()
            .associateBy({ it.sourceId to it.categoryId }, { it.count })
        val hiddenCounts = catalog.hiddenChannelCountsByCategory()
        // The TV's category rail FOLDS provider categories whose names differ only by language
        // prefix or codec ("FR| PRIME RAW" + "IT| PRIME RAW" = one "PRIME" entry; see
        // ChannelsViewModel.foldCategories). Show the same entries here, so hiding "PRIME" on the
        // phone hides exactly what hiding "PRIME" on the TV hides. Folded per source, so two
        // providers stay separate.
        val folded = LinkedHashMap<Pair<Long, String>, Pair<String, MutableList<String>>>()
        catalog.liveCategories()
            .filter { it.sourceId in sourceIds }
            .forEach { cat ->
                val normalised = ChannelNameNormalizer.normalize(cat.name)
                val key = cat.sourceId to normalised.groupKey.ifEmpty { cat.id }
                folded.getOrPut(key) { normalised.baseName to mutableListOf() }.second += cat.id
            }
        // Only groups that actually hold channels — an empty one is noise on a phone, and a count
        // includes hidden rows so it stays > 0 for a group whose channels are all hidden (which is
        // exactly what you'd browse to fix).
        val categories = folded.mapNotNull { (key, value) ->
            val sourceId = key.first
            val ids = value.second
            val count = ids.sumOf { counts[sourceId to it] ?: 0 }
            if (count <= 0) null
            else CategoryDto(
                id = ids.first(),
                label = value.first,
                sourceId = sourceId,
                count = count,
                hidden = ids.sumOf { hiddenCounts[sourceId to it] ?: 0 },
                ids = ids,
            )
        }
        json.encodeToString(
            MetaDto.serializer(),
            MetaDto(sources = sources.map { SourceDto(it.id, it.name) }, categories = categories),
        )
    }

    /** `GET /channels?cat=<id>[&source=<id>]` → the category's channels, hidden included. */
    private fun channelsJson(query: Map<String, String>): String = runBlocking {
        val serializer = ListSerializer(ChannelDto.serializer())
        // `cats` is a JSON array of provider category ids (a folded rail entry spans several);
        // `cat` is the single-id form.
        val ids = query["cats"]
            ?.let { raw -> runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrNull() }
            ?: listOfNotNull(query["cat"])
        val wanted = ids.filter { it.isNotBlank() }
        if (wanted.isEmpty()) return@runBlocking json.encodeToString(serializer, emptyList())
        val source = query["source"]?.toLongOrNull()
        val rows = wanted
            .flatMap { catalog.channelsInCategoryForManager(source, it) }
            .distinctBy { it.id }
            .map { it.toDto() }
        json.encodeToString(serializer, rows)
    }

    /** `POST /channel {id, hidden?|favourite?|name?}` → apply the given field(s). */
    private fun handleChannelPatch(output: OutputStream, body: String) {
        val patch = runCatching { json.decodeFromString(ChannelPatch.serializer(), body) }.getOrNull()
        if (patch == null || patch.id <= 0L) {
            output.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "Bad request."))
            return
        }
        // Unknown ids simply update zero rows — harmless, so no existence check.
        runBlocking {
            patch.hidden?.let { catalog.setChannelHidden(patch.id, it) }
            patch.favourite?.let { catalog.setChannelFavourite(patch.id, it) }
            // A blank name clears the rename back to the provider's own (see setChannelCustomName).
            patch.name?.let { catalog.setChannelCustomName(patch.id, it) }
        }
        writeOk(output)
    }

    /** `POST /reorder {ids:[orderedChannelIds]}` → assign sortIndex by position. */
    private fun handleReorder(output: OutputStream, body: String) {
        val req = runCatching { json.decodeFromString(ReorderReq.serializer(), body) }.getOrNull()
        if (req == null) {
            output.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "Bad request."))
            return
        }
        runBlocking {
            req.ids.forEachIndexed { index, id -> if (id > 0L) catalog.setChannelSortIndex(id, index) }
        }
        writeOk(output)
    }

    // ---- Bulk actions ---------------------------------------------------------------------------

    /**
     * `POST /bulk {cat, source?, hidden?|favourite?}` → apply the given field(s) to **every** channel
     * of one category: show all / hide all / favourite all / unfavourite all. Each is a single SQL
     * statement (see [CatalogRepository.setCategoryHidden]), so it is instant even for a category of
     * thousands. Serves both the category list and the channel list. Replies with how many changed.
     */
    private fun handleBulk(output: OutputStream, body: String) {
        val req = runCatching { json.decodeFromString(BulkReq.serializer(), body) }.getOrNull()
        val ids = (req?.cats.orEmpty().ifEmpty { listOfNotNull(req?.cat) }).filter { it.isNotBlank() }
        if (req == null || ids.isEmpty() || (req.hidden == null && req.favourite == null)) {
            output.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "Bad request."))
            return
        }
        val changed = runBlocking {
            var n = 0
            for (id in ids) {
                req.hidden?.let { n += catalog.setCategoryHidden(req.source, id, it) }
                req.favourite?.let { n += catalog.setCategoryFavourite(req.source, id, it) }
            }
            n
        }
        writeJson(output, BulkResultDto.serializer(), BulkResultDto(ok = true, changed = changed))
    }

    // ---- VOD: hide / show movies and series -----------------------------------------------------

    private fun vodKind(raw: String?): StreamKind? = when (raw) {
        "movie" -> StreamKind.MOVIE
        "series" -> StreamKind.SERIES
        else -> null
    }

    /** `GET /vod/meta?kind=movie|series` → same shape as `/meta`, for the VOD categories. */
    private fun vodMetaJson(query: Map<String, String>): String = runBlocking {
        val kind = vodKind(query["kind"])
            ?: return@runBlocking json.encodeToString(MetaDto.serializer(), MetaDto(emptyList(), emptyList()))
        val sources = catalog.enabledSources()
        val sourceIds = sources.map { it.id }.toSet()
        val counts = catalog.vodCategoryCounts(kind).associateBy({ it.sourceId to it.categoryId }, { it.count })
        val hiddenCounts = catalog.vodHiddenCounts(kind).associateBy({ it.sourceId to it.categoryId }, { it.count })
        val categories = catalog.vodCategories(kind)
            .filter { it.sourceId in sourceIds }
            .mapNotNull { cat ->
                val count = counts[cat.sourceId to cat.id] ?: 0
                if (count <= 0) null
                else CategoryDto(
                    id = cat.id,
                    label = cat.name,
                    sourceId = cat.sourceId,
                    count = count,
                    hidden = hiddenCounts[cat.sourceId to cat.id] ?: 0,
                )
            }
        json.encodeToString(
            MetaDto.serializer(),
            MetaDto(sources = sources.map { SourceDto(it.id, it.name) }, categories = categories),
        )
    }

    /** `GET /vod/items?kind=movie|series&cat=<id>[&source=<id>]` → the category's titles, hidden included. */
    private fun vodItemsJson(query: Map<String, String>): String = runBlocking {
        val serializer = ListSerializer(VodItemDto.serializer())
        val kind = vodKind(query["kind"])
        val cat = query["cat"]
        if (kind == null || cat.isNullOrBlank()) return@runBlocking json.encodeToString(serializer, emptyList())
        val source = query["source"]?.toLongOrNull()
        val rows = when (kind) {
            StreamKind.MOVIE -> catalog.moviesInCategoryForManager(source, cat)
                .map { VodItemDto(it.id, it.displayTitle, it.posterUrl, it.year, it.hidden) }
            StreamKind.SERIES -> catalog.seriesInCategoryForManager(source, cat)
                .map { VodItemDto(it.id, it.displayTitle, it.posterUrl, it.year, it.hidden) }
            StreamKind.LIVE -> emptyList()
        }
        json.encodeToString(serializer, rows)
    }

    /** `POST /vod/item {kind, id, hidden}` → hide or show one movie or series. */
    private fun handleVodItem(output: OutputStream, body: String) {
        val req = runCatching { json.decodeFromString(VodItemReq.serializer(), body) }.getOrNull()
        val kind = vodKind(req?.kind)
        if (req == null || kind == null || req.id <= 0L) {
            output.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "Bad request."))
            return
        }
        runBlocking {
            when (kind) {
                StreamKind.MOVIE -> catalog.setMovieHidden(req.id, req.hidden)
                StreamKind.SERIES -> catalog.setSeriesHidden(req.id, req.hidden)
                StreamKind.LIVE -> Unit
            }
        }
        writeOk(output)
    }

    /** `POST /vod/bulk {kind, cat, source?, hidden}` → hide or show every title of a category. */
    private fun handleVodBulk(output: OutputStream, body: String) {
        val req = runCatching { json.decodeFromString(VodBulkReq.serializer(), body) }.getOrNull()
        val kind = vodKind(req?.kind)
        if (req == null || kind == null || req.cat.isBlank() || req.hidden == null) {
            output.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "Bad request."))
            return
        }
        val changed = runBlocking {
            when (kind) {
                StreamKind.MOVIE -> catalog.setMovieCategoryHidden(req.source, req.cat, req.hidden)
                StreamKind.SERIES -> catalog.setSeriesCategoryHidden(req.source, req.cat, req.hidden)
                StreamKind.LIVE -> 0
            }
        }
        writeJson(output, BulkResultDto.serializer(), BulkResultDto(ok = true, changed = changed))
    }

    private fun writeOk(output: OutputStream) {
        output.write(
            PairingHttp.httpResponse("200 OK", "application/json", json.encodeToString(OkDto.serializer(), OkDto())),
        )
    }

    /** `GET /recording` → the current recording target and NAS settings. The password is never sent
     *  back to the browser — only whether one is set. */
    private fun recordingJson(): String = json.encodeToString(
        RecordingDto.serializer(),
        RecordingDto(
            target = settings.recordingTarget.value.name,
            host = settings.smbHost.value,
            share = settings.smbShare.value,
            folder = settings.smbFolder.value,
            user = settings.smbUser.value,
            hasPassword = settings.smbPassword.value.isNotEmpty(),
        ),
    )

    /** `POST /recording {host,share,folder,user,password?}` → save the NAS details and switch
     *  recordings to it. A blank/absent password keeps the one already stored, so the fields can be
     *  filled without re-typing an existing password. */
    private fun handleRecording(output: OutputStream, body: String) {
        val req = runCatching { json.decodeFromString(RecordingReq.serializer(), body) }.getOrNull()
        if (req == null) {
            output.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "Bad request."))
            return
        }
        val password = req.password?.takeIf { it.isNotEmpty() } ?: settings.smbPassword.value
        settings.setSmbConfig(req.host.trim(), req.share.trim(), req.folder.trim(), req.user.trim(), password)
        settings.setRecordingTarget(AppSettings.RecordingTarget.SMB)
        writeOk(output)
    }

    /** `POST /recording/test {host,share,folder,user,password?}` → actually open the SMB share and
     *  report whether it worked. Blank fields fall back to what's stored, so the browser can test
     *  the saved settings without re-sending the password. Runs on this handler thread (already off
     *  the main thread), which is where a blocking network probe belongs. */
    private fun handleRecordingTest(output: OutputStream, body: String) {
        val req = runCatching { json.decodeFromString(RecordingReq.serializer(), body) }.getOrNull()
            ?: RecordingReq()
        val cfg = SmbConfig(
            host = req.host.trim().ifBlank { settings.smbHost.value },
            share = req.share.trim().ifBlank { settings.smbShare.value },
            folder = req.folder.trim().ifBlank { settings.smbFolder.value },
            username = req.user.trim().ifBlank { settings.smbUser.value },
            password = req.password?.takeIf { it.isNotEmpty() } ?: settings.smbPassword.value,
        )
        val result = runCatching { SmbClient.test(cfg) }
        val dto = if (result.isSuccess) {
            TestDto(ok = true, error = null)
        } else {
            val e = result.exceptionOrNull()
            TestDto(ok = false, error = e?.message ?: e?.javaClass?.simpleName ?: "Connection failed")
        }
        output.write(PairingHttp.httpResponse("200 OK", "application/json", json.encodeToString(TestDto.serializer(), dto)))
    }

    // ---- conf.json -----------------------------------------------------------------------------

    /**
     * `POST /conf {url}` → remember the address of a `conf.json` and apply it right away.
     * A blank url forgets the address. The same file is re-checked at every app launch
     * (see [ConfigUpdater]); a failed download leaves the existing providers untouched.
     */
    private fun handleConf(output: OutputStream, body: String) {
        val req = runCatching { json.decodeFromString(ConfReq.serializer(), body) }.getOrNull()
        if (req == null) {
            output.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "Bad request."))
            return
        }

        val raw = req.url.trim()
        if (raw.isEmpty()) {
            ConfigUpdater.saveConfUrl(context, null)
            writeJson(output, ConfResultDto.serializer(), ConfResultDto(ok = true))
            return
        }

        val url = PairingHttp.cleanHttpUrl(raw)
        if (url == null) {
            writeJson(
                output,
                ConfResultDto.serializer(),
                ConfResultDto(ok = false, error = "The address must start with http:// or https://."),
            )
            return
        }

        ConfigUpdater.saveConfUrl(context, url)
        val result = runBlocking {
            ConfigUpdater.refresh(
                context = context,
                existing = catalog.enabledSources(),
                force = true,
                saveSource = { source ->
                    val id = sourceRepository.save(source)
                    val saved = sourceRepository.byId(id)
                    saved != null &&
                        catalog.syncLive(saved, System.currentTimeMillis()) is CatalogRepository.SyncResult.Success
                },
            )
        }
        val dto = when (result) {
            is ConfigUpdater.Result.Updated -> ConfResultDto(ok = true, updated = result.count)
            is ConfigUpdater.Result.Unchanged -> ConfResultDto(ok = true)
            is ConfigUpdater.Result.Failed -> ConfResultDto(ok = false, error = result.reason)
        }
        writeJson(output, ConfResultDto.serializer(), dto)
    }

    // ---- Add a provider ------------------------------------------------------------------------

    /**
     * `POST /source {kind,name,url,username?,password?,mac?}` → save the provider and pull its live
     * channels, so the catalogue fills without anyone typing a server address on the TV remote.
     * Mirrors the on-device Add-source screen: save, then load live channels first.
     */
    private fun handleAddSource(output: OutputStream, body: String) {
        val draft = runCatching { json.decodeFromString(SourceReq.serializer(), body) }.getOrNull()?.toDraftOrNull()
        if (draft == null) {
            writeJson(output, AddResultDto.serializer(), AddResultDto(ok = false, error = "Fill in the required fields for this provider type."))
            return
        }
        val result = runBlocking {
            val id = sourceRepository.save(draft)
            val saved = sourceRepository.byId(id)
                ?: return@runBlocking AddResultDto(ok = false, error = "Could not save the source.")
            when (val r = catalog.syncLive(saved, System.currentTimeMillis())) {
                is CatalogRepository.SyncResult.Success -> AddResultDto(ok = true, channels = r.channelCount)
                is CatalogRepository.SyncResult.Failed -> AddResultDto(ok = false, error = r.reason)
            }
        }
        writeJson(output, AddResultDto.serializer(), result)
    }

    /** `POST /source/test {…}` → validate the provider details without saving (handshake / auth). */
    private fun handleTestSource(output: OutputStream, body: String) {
        val draft = runCatching { json.decodeFromString(SourceReq.serializer(), body) }.getOrNull()?.toDraftOrNull()
        if (draft == null) {
            writeJson(output, TestResultDto.serializer(), TestResultDto(ok = false, error = "Fill in the required fields for this provider type."))
            return
        }
        val result = runBlocking { sourceRepository.test(draft) }
        val dto = if (result.isSuccess) {
            TestResultDto(ok = true, message = result.getOrNull())
        } else {
            TestResultDto(ok = false, error = result.exceptionOrNull()?.message ?: "Test failed.")
        }
        writeJson(output, TestResultDto.serializer(), dto)
    }

    /** Turn a web request into a [Source] draft, or null if a field required for its kind is missing. */
    private fun SourceReq.toDraftOrNull(): Source? {
        val k = runCatching { SourceKind.valueOf(kind.trim().uppercase()) }.getOrNull() ?: return null
        if (url.isBlank()) return null
        when (k) {
            SourceKind.XTREAM -> if (username.isNullOrBlank() || password.isNullOrBlank()) return null
            SourceKind.STALKER -> if (mac.isNullOrBlank()) return null
            SourceKind.M3U -> Unit
        }
        return Source(
            name = name.trim().ifBlank { if (k == SourceKind.M3U) "Playlist" else "Provider" },
            kind = k,
            url = url.trim(),
            username = username?.takeIf { it.isNotBlank() },
            password = password?.takeIf { it.isNotBlank() },
            macAddress = mac?.takeIf { it.isNotBlank() },
            userAgent = Source.DEFAULT_USER_AGENT,
        )
    }

    private fun <T> writeJson(output: OutputStream, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        output.write(PairingHttp.httpResponse("200 OK", "application/json", json.encodeToString(serializer, value)))
    }

    private fun Channel.toDto() = ChannelDto(
        id = id,
        name = shownName,
        original = displayName,
        hidden = hidden,
        favourite = favourite,
        sortIndex = sortIndex,
        logo = logoUrl,
        number = number,
    )

    // ---- Port selection ------------------------------------------------------------------------

    /**
     * Opens the first free port of [PORTS]; only when all of them are taken does the system pick
     * one (port 0). The address shown on the TV is built from the port actually bound.
     */
    private fun openSocket(): ServerSocket? {
        for (port in PORTS) {
            val candidate = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Port $port unavailable", e)
                null
            }
            if (candidate != null) return candidate
        }
        return try {
            ServerSocket(0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open a socket", e)
            null
        }
    }

    // ---- Finding our own address ---------------------------------------------------------------

    /**
     * The device's address on the LAN.
     *
     * Deliberately the same enumeration as [PairingServer.lanAddress] and [app.opentv.sync.SyncServer]
     * (duplicated, not shared, to keep the servers independent): asks the interfaces rather than
     * ConnectivityManager, because TV boxes are frequently on ethernet and the wifi-shaped API
     * returns nothing useful when the box is wired.
     */
    private fun lanAddress(): String? {
        val candidates = mutableListOf<InetAddress>()
        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in nic.inetAddresses) {
                    if (address.isLoopbackAddress) continue
                    if (address.hostAddress?.contains(':') == true) continue // IPv4 only
                    if (PairingHttp.isPrivateAddress(address.hostAddress)) candidates += address
                }
            }
        }.onFailure { Log.w(TAG, "Could not enumerate interfaces", it) }

        // Prefer a real private range over a 169.254 self-assigned address, which means DHCP
        // failed and nothing will be able to reach us anyway.
        return candidates
            .sortedBy { it.hostAddress?.startsWith("169.254") == true }
            .firstOrNull()
            ?.hostAddress
    }

    private companion object {
        const val TAG = "ManagerServer"

        /** Tried in this order; the first free one wins, then the system picks. */
        val PORTS = intArrayOf(9090, 1234, 8080, 8888)
        const val SOCKET_TIMEOUT_MILLIS = 15_000
        const val IDLE_TIMEOUT_MINUTES = 30L

        // Generous next to pairing's 16 KB: a /reorder body carries every channel id in a
        // category, which can be a few thousand on a big provider.
        const val MAX_BODY_BYTES = 256 * 1024
    }
}

// ---- JSON DTOs ---------------------------------------------------------------------------------
// kotlinx.serialization handles the escaping that matters here: channel names carry quotes, `|`
// and unicode, and hand-rolled string concatenation is exactly where that goes wrong.

@Serializable private data class MetaDto(val sources: List<SourceDto>, val categories: List<CategoryDto>)

@Serializable private data class SourceDto(val id: Long, val name: String)

@Serializable private data class CategoryDto(
    val id: String,
    val label: String,
    val sourceId: Long,
    val count: Int,
    val hidden: Int = 0,
    /** The provider category ids folded into this entry (the TV rail folds codec/language copies). */
    val ids: List<String> = emptyList(),
)

@Serializable private data class ChannelDto(
    val id: Long,
    val name: String,
    val original: String,
    val hidden: Boolean,
    val favourite: Boolean,
    val sortIndex: Int,
    val logo: String?,
    val number: Int?,
)

@Serializable private data class OkDto(val ok: Boolean = true)

@Serializable private data class ChannelPatch(
    val id: Long,
    val hidden: Boolean? = null,
    val favourite: Boolean? = null,
    val name: String? = null,
)

@Serializable private data class ReorderReq(val ids: List<Long>)

@Serializable private data class BulkReq(
    val cat: String = "",
    val cats: List<String> = emptyList(),
    val source: Long? = null,
    val hidden: Boolean? = null,
    val favourite: Boolean? = null,
)

@Serializable private data class BulkResultDto(val ok: Boolean, val changed: Int = 0)

@Serializable private data class VodItemDto(
    val id: Long,
    val name: String,
    val poster: String? = null,
    val year: Int? = null,
    val hidden: Boolean = false,
)

@Serializable private data class VodItemReq(val kind: String = "", val id: Long = 0, val hidden: Boolean = false)

@Serializable private data class VodBulkReq(
    val kind: String = "",
    val cat: String = "",
    val source: Long? = null,
    val hidden: Boolean? = null,
)

@Serializable private data class RecordingDto(
    val target: String,
    val host: String,
    val share: String,
    val folder: String,
    val user: String,
    val hasPassword: Boolean,
)

@Serializable private data class RecordingReq(
    val host: String = "",
    val share: String = "",
    val folder: String = "",
    val user: String = "",
    val password: String? = null,
)

@Serializable private data class TestDto(val ok: Boolean, val error: String?)

@Serializable private data class SourceReq(
    val kind: String = "xtream",
    val name: String = "",
    val url: String = "",
    val username: String? = null,
    val password: String? = null,
    val mac: String? = null,
)

@Serializable private data class AddResultDto(val ok: Boolean, val channels: Int = 0, val error: String? = null)

@Serializable private data class ConfReq(val url: String = "")

@Serializable private data class ConfDto(val url: String? = null)

@Serializable private data class ConfResultDto(val ok: Boolean, val updated: Int = 0, val error: String? = null)

@Serializable private data class TestResultDto(val ok: Boolean, val message: String? = null, val error: String? = null)
