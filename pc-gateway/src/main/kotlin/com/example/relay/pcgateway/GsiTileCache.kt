package com.example.relay.pcgateway

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlinx.serialization.Serializable

@Serializable
data class OfflineMapStatus(
    val municipality: String = "広島県安芸郡府中町",
    val state: String,
    val cachedTiles: Int,
    val expectedTiles: Int,
    val minZoom: Int = MIN_TILE_ZOOM,
    val maxNativeZoom: Int = MAX_TILE_ZOOM,
    val maxZoom: Int = MAX_DISPLAY_ZOOM,
    val lastError: String? = null,
    val attribution: String = "国土地理院（地理院タイル・標準地図）",
)

/** Small, bounded cache for the Fuchu Town pilot area (zoom 13–15). */
class GsiTileCache(private val root: Path) : AutoCloseable {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "relay-gsi-cache").apply { isDaemon = true }
    }
    @Volatile private var preparing = false
    @Volatile private var lastError: String? = null
    private val targets = buildTargets()
    private val targetSet = targets.toHashSet()

    init { Files.createDirectories(root) }

    fun status(): OfflineMapStatus {
        val cachedTiles = cachedTileCount()
        return OfflineMapStatus(
            state = when {
                preparing -> "preparing"
                cachedTiles >= targets.size -> "ready"
                cachedTiles > 0 -> "partial"
                else -> "not_ready"
            },
            cachedTiles = cachedTiles,
            expectedTiles = targets.size,
            lastError = lastError,
        )
    }

    fun prepare(): Boolean = synchronized(this) {
        if (preparing) return false
        preparing = true
        lastError = null
        executor.submit {
            try {
                targets.forEach { (z, x, y) ->
                    if (!Files.isRegularFile(tilePath(z, x, y))) fetchAndCache(z, x, y)
                }
            } catch (error: Exception) {
                lastError = error.message?.take(200) ?: "tile_download_failed"
            } finally {
                preparing = false
            }
        }
        true
    }

    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (Triple(z, x, y) !in targetSet) return null
        val path = tilePath(z, x, y)
        if (Files.isRegularFile(path)) return runCatching { Files.readAllBytes(path) }.getOrNull()
        return runCatching { fetchAndCache(z, x, y) }.getOrNull()
    }

    private fun fetchAndCache(z: Int, x: Int, y: Int): ByteArray {
        val request = HttpRequest.newBuilder(
            URI("https://cyberjapandata.gsi.go.jp/xyz/std/$z/$x/$y.png"),
        ).timeout(Duration.ofSeconds(12)).header("User-Agent", "Relay/1.0 disaster-response-pilot").build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        require(response.statusCode() == 200) { "GSI tile HTTP ${response.statusCode()}" }
        val bytes = response.body()
        require(bytes.size in 100..1_000_000) { "invalid GSI tile size" }
        val path = tilePath(z, x, y)
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".tile-", ".tmp")
        try {
            Files.write(temporary, bytes)
            runCatching {
                Files.move(
                    temporary,
                    path,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return bytes
    }

    private fun tilePath(z: Int, x: Int, y: Int): Path = root.resolve(z.toString()).resolve(x.toString()).resolve("$y.png")

    private fun cachedTileCount(): Int = targets.count { (z, x, y) -> Files.isRegularFile(tilePath(z, x, y)) }

    private fun buildTargets(): List<Triple<Int, Int, Int>> = buildList {
        for (zoom in MIN_TILE_ZOOM..MAX_TILE_ZOOM) {
            val xMin = longitudeToTileX(FUCHU_WEST, zoom)
            val xMax = longitudeToTileX(FUCHU_EAST, zoom)
            val yMin = latitudeToTileY(FUCHU_NORTH, zoom)
            val yMax = latitudeToTileY(FUCHU_SOUTH, zoom)
            for (x in xMin..xMax) for (y in yMin..yMax) add(Triple(zoom, x, y))
        }
    }

    override fun close() { executor.shutdownNow() }

    private companion object {
        const val FUCHU_WEST = 132.45
        const val FUCHU_EAST = 132.55
        const val FUCHU_SOUTH = 34.36
        const val FUCHU_NORTH = 34.43

        fun longitudeToTileX(longitude: Double, zoom: Int): Int =
            floor((longitude + 180.0) / 360.0 * (1 shl zoom)).toInt()

        fun latitudeToTileY(latitude: Double, zoom: Int): Int {
            val radians = latitude * PI / 180.0
            return floor((1.0 - ln(tan(radians) + 1.0 / kotlin.math.cos(radians)) / PI) / 2.0 * (1 shl zoom)).toInt()
        }
    }
}

private const val MIN_TILE_ZOOM = 13
private const val MAX_TILE_ZOOM = 15

/**
 * The browser can overzoom the highest-resolution cached tiles without performing
 * any network requests. This keeps labels and rescue markers usable at close range
 * while the bounded offline cache remains small enough for the pilot deployment.
 */
private const val MAX_DISPLAY_ZOOM = 18
