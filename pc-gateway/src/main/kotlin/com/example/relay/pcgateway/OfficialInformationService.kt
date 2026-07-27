package com.example.relay.pcgateway

import com.example.relay.pcgateway.official.OfficialInfoFormat
import com.example.relay.pcgateway.official.OfficialInfoProvenance
import com.example.relay.pcgateway.official.OfficialInfoRetrieval
import com.example.relay.pcgateway.official.OfficialInfoVerification
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class OfficialInformationResponse(
    val municipality: String = "広島県安芸郡府中町",
    val checkedAtEpochMillis: Long,
    val usedCachedWarning: Boolean,
    val urgent: Boolean,
    val warningHeadline: String,
    val warningStatuses: List<String>,
    val sources: List<OfficialSource> = officialSources,
    /** Where the warning content came from and how far it was verified (never beyond TLS). */
    val provenance: OfficialInfoProvenance,
)

@Serializable
data class OfficialSource(val title: String, val organization: String, val url: String)

/** Sidecar persisted next to the cached document so cache replays keep the original fetch time. */
@Serializable private data class OfficialCacheMetadata(
    val fetchedAtEpochMillis: Long,
    val contentSha256Hex: String,
)

@Serializable private data class JmaWarning(
    val reportDatetime: String = "",
    val headlineText: String = "",
    val areaTypes: List<JmaAreaType> = emptyList(),
)
@Serializable private data class JmaAreaType(val areas: List<JmaArea> = emptyList())
@Serializable private data class JmaArea(val code: String, val warnings: List<JmaAreaWarning> = emptyList())
@Serializable private data class JmaAreaWarning(val code: String? = null, val status: String)

class OfficialInformationService(
    private val cachePath: Path,
    /** Injectable for tests; production default performs the real HTTPS fetch. */
    private val fetcher: () -> String = defaultFetcher(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var lastCheckedAt = 0L
    @Volatile private var lastResponse: OfficialInformationResponse? = null

    @Synchronized
    fun current(now: Long = System.currentTimeMillis()): OfficialInformationResponse {
        if (now - lastCheckedAt < 5 * 60_000) return requireNotNull(lastResponse)
        lastCheckedAt = now
        val live = runCatching { fetcher() }.getOrNull()
        if (live != null) {
            cachePath.parent?.let(Files::createDirectories)
            runCatching {
                Files.writeString(cachePath, live)
                Files.writeString(
                    metadataPath(),
                    json.encodeToString(
                        OfficialCacheMetadata.serializer(),
                        OfficialCacheMetadata(now, sha256Hex(live)),
                    ),
                )
            }
        }
        val cached = if (live == null) runCatching { Files.readString(cachePath) }.getOrNull() else null
        val raw = live ?: cached
        val provenance = provenanceFor(now, live, cached)
        val parsed = raw?.let { runCatching { json.decodeFromString<JmaWarning>(it) }.getOrNull() }
        val fuchu = parsed?.areaTypes?.asSequence()?.flatMap { it.areas.asSequence() }
            ?.firstOrNull { it.code == FUCHU_TOWN_CODE }
        val statuses = fuchu?.warnings.orEmpty().map { warning ->
            listOfNotNull(warning.code?.let { "コード$it" }, warning.status).joinToString(": ")
        }
        val urgent = fuchu?.warnings.orEmpty().any { warning ->
            warning.status.contains("発表") && !warning.status.contains("なし")
        }
        return OfficialInformationResponse(
            checkedAtEpochMillis = now,
            usedCachedWarning = live == null && raw != null,
            urgent = urgent,
            warningHeadline = parsed?.headlineText?.ifBlank { null } ?: "気象庁の警報情報を取得できませんでした",
            warningStatuses = statuses,
            provenance = provenance,
        ).also { lastResponse = it }
    }

    /**
     * Honest provenance: a live fetch is at most TLS-verified (JMA content is unsigned), a
     * cache replay is explicitly CACHED_UNVERIFIED, and nothing available is UNVERIFIED.
     */
    private fun provenanceFor(now: Long, live: String?, cached: String?): OfficialInfoProvenance {
        val metadata = if (live == null && cached != null) readMetadata(cached) else null
        return when {
            live != null -> OfficialInfoProvenance(
                sourceUrl = JMA_WARNING_URL,
                format = OfficialInfoFormat.JMA_BOSAI_JSON,
                retrieval = OfficialInfoRetrieval.LIVE_FETCH,
                verification = OfficialInfoVerification.TRANSPORT_TLS_ONLY,
                checkedAtEpochMillis = now,
                fetchedAtEpochMillis = now,
                contentSha256Hex = sha256Hex(live),
            )
            cached != null -> OfficialInfoProvenance(
                sourceUrl = JMA_WARNING_URL,
                format = OfficialInfoFormat.JMA_BOSAI_JSON,
                retrieval = OfficialInfoRetrieval.LOCAL_CACHE,
                verification = OfficialInfoVerification.CACHED_UNVERIFIED,
                checkedAtEpochMillis = now,
                fetchedAtEpochMillis = metadata?.fetchedAtEpochMillis,
                contentSha256Hex = sha256Hex(cached),
            )
            else -> OfficialInfoProvenance(
                sourceUrl = JMA_WARNING_URL,
                format = OfficialInfoFormat.JMA_BOSAI_JSON,
                retrieval = OfficialInfoRetrieval.UNAVAILABLE,
                verification = OfficialInfoVerification.UNVERIFIED,
                checkedAtEpochMillis = now,
            )
        }
    }

    /** Metadata is trusted only when its digest matches the cached document actually read. */
    private fun readMetadata(cached: String): OfficialCacheMetadata? = runCatching {
        json.decodeFromString<OfficialCacheMetadata>(Files.readString(metadataPath()))
    }.getOrNull()?.takeIf { it.contentSha256Hex == sha256Hex(cached) }

    private fun metadataPath(): Path = cachePath.resolveSibling(cachePath.fileName.toString() + ".meta.json")

    private companion object {
        const val FUCHU_TOWN_CODE = "3430200"
        const val JMA_WARNING_URL = "https://www.jma.go.jp/bosai/warning/data/warning/340000.json"

        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val REQUEST_TIMEOUT_SECONDS = 10L
        private const val HTTP_OK = 200

        fun sha256Hex(content: String): String =
            MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun defaultFetcher(): () -> String {
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build()
            return {
                val request = HttpRequest.newBuilder(URI(JMA_WARNING_URL))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                    .header("User-Agent", "Relay/1.0 disaster-response-pilot")
                    .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
                require(response.statusCode() == HTTP_OK) { "JMA warning HTTP ${response.statusCode()}" }
                response.body()
            }
        }
    }
}

private val officialSources = listOf(
    OfficialSource(
        "府中町 防災・消防・救急",
        "府中町",
        "https://www.town.fuchu.hiroshima.jp/life/1/6/",
    ),
    OfficialSource(
        "府中町 指定緊急避難場所・指定避難所",
        "府中町",
        "https://www.town.fuchu.hiroshima.jp/site/kikikannrika/2030.html",
    ),
    OfficialSource(
        "広島県 防災Web",
        "広島県",
        "https://www.bousai.pref.hiroshima.jp/",
    ),
    OfficialSource(
        "警報・注意報",
        "気象庁",
        "https://www.jma.go.jp/bosai/warning/#area_type=class20s&area_code=3430200",
    ),
)
