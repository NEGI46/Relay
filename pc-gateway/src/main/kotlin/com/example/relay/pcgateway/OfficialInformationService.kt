package com.example.relay.pcgateway

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
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
)

@Serializable
data class OfficialSource(val title: String, val organization: String, val url: String)

@Serializable private data class JmaWarning(
    val reportDatetime: String = "",
    val headlineText: String = "",
    val areaTypes: List<JmaAreaType> = emptyList(),
)
@Serializable private data class JmaAreaType(val areas: List<JmaArea> = emptyList())
@Serializable private data class JmaArea(val code: String, val warnings: List<JmaAreaWarning> = emptyList())
@Serializable private data class JmaAreaWarning(val code: String? = null, val status: String)

class OfficialInformationService(private val cachePath: Path) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var lastCheckedAt = 0L
    @Volatile private var lastResponse: OfficialInformationResponse? = null

    @Synchronized
    fun current(now: Long = System.currentTimeMillis()): OfficialInformationResponse {
        if (now - lastCheckedAt < 5 * 60_000) return requireNotNull(lastResponse)
        lastCheckedAt = now
        val live = runCatching { fetchWarningJson() }.getOrNull()
        if (live != null) {
            cachePath.parent?.let(Files::createDirectories)
            runCatching { Files.writeString(cachePath, live) }
        }
        val raw = live ?: runCatching { Files.readString(cachePath) }.getOrNull()
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
        ).also { lastResponse = it }
    }

    private fun fetchWarningJson(): String {
        val request = HttpRequest.newBuilder(URI(JMA_WARNING_URL))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", "Relay/1.0 disaster-response-pilot")
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        require(response.statusCode() == 200) { "JMA warning HTTP ${response.statusCode()}" }
        return response.body()
    }

    private companion object {
        const val FUCHU_TOWN_CODE = "3430200"
        const val JMA_WARNING_URL = "https://www.jma.go.jp/bosai/warning/data/warning/340000.json"
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
