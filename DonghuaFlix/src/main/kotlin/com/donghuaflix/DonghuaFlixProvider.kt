package com.donghuaflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

class DonghuaFlixProvider : MainAPI() {

    override var mainUrl = "https://donghuaflix.pages.dev"
    override var name = "DonghuaFlix"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes =
        setOf(TvType.Anime, TvType.AnimeMovie, TvType.AsianDrama, TvType.Movie, TvType.TvSeries)

    // ── Datos ────────────────────────────────────────────────────────────
    // La web desplegada no sirve /data (la SPA responde HTML), así que el
    // proveedor lee los JSON directamente del repositorio, con jsDelivr de
    // espejo y la propia web como tercera opción futura.
    private val dataBases = listOf(
        "https://raw.githubusercontent.com/Aneitor33/donghuaflix/main/public/data",
        "https://cdn.jsdelivr.net/gh/Aneitor33/donghuaflix@main/public/data",
        "https://donghuaflix.pages.dev/data",
    )

    @Volatile
    private var activeBase: String? = null

    private data class Catalog(val id: String, val label: String, val indexFile: String)
    private data class Entry(
        val id: String,
        val slug: String,
        val title: String,
        val poster: String?,
        val type: String?,
    )

    private data class Index(
        val catalog: Catalog,
        val entries: List<Entry>,
        val imageBase: String,
        val detailsBase: String,
        val sourceBase: String?,
    )

    private val indexCache = ConcurrentHashMap<String, Index>()
    private val shardCache = ConcurrentHashMap<String, ConcurrentHashMap<Int, Any>>()
    private val shardMiss = ConcurrentHashMap<String, ConcurrentHashMap<Int, Boolean>>()

    private val pageSize = 30
    private val maxShards = 120
    private val shardBatch = 4
    private val maxCachedShards = 40

    // ── Utilidades ───────────────────────────────────────────────────────

    private suspend fun getText(url: String): String? = try {
        app.get(url).text
    } catch (_: Exception) {
        null
    }

    private fun parseJson(text: String): Any? = try {
        JSONTokener(text).nextValue()
    } catch (_: Exception) {
        null
    }

    /** Contenido de `path` relativo a /data, probando bases en orden. */
    private suspend fun getData(path: String): String? {
        activeBase?.let { base ->
            getText("$base/$path")?.let { return it }
        }
        for (base in dataBases) {
            val text = getText("$base/$path") ?: continue
            activeBase = base
            return text
        }
        return null
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()

    private fun absolutize(url: String, imageBase: String): String = when {
        url.isBlank() -> ""
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> imageBase.trimEnd('/') + "/" + url.trimStart('/')
    }

    private fun firstText(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.optString(k)
            if (v.isNotBlank()) return v
        }
        return ""
    }

    private fun firstIntOrNull(o: JSONObject, vararg keys: String): Int? {
        for (k in keys) {
            if (!o.has(k) || o.isNull(k)) continue
            val v = o.optInt(k, -1)
            if (v > 0) return v
        }
        return null
    }

    // ── Catálogos e índices ──────────────────────────────────────────────

    private fun parseMaster(text: String): List<Catalog> {
        val root = parseJson(text) as? JSONObject ?: return emptyList()
        val arr = root.optJSONArray("catalogs") ?: return emptyList()
        val out = ArrayList<Catalog>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val file = o.optString("index")
            if (id.isBlank() || file.isBlank()) continue
            out.add(Catalog(id, o.optString("label", id), file))
        }
        return out
    }

    private fun parseIndex(catalog: Catalog, text: String): Index? {
        val root = parseJson(text) as? JSONObject ?: return null
        val arr = root.optJSONArray("series") ?: return null
        val imageBase = root.optString("imageBase")
            .ifBlank { "https://image.tmdb.org/t/p/w300" }
        val meta = root.optJSONObject("meta")
        val entries = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val slug = o.optString("s").ifBlank { o.optString("i") }
            val title = o.optString("t").ifBlank { slug }
            if (slug.isBlank()) continue
            val rawPoster = o.optString("p").ifBlank { null }
            entries.add(
                Entry(
                    id = o.optString("i").ifBlank { slug },
                    slug = slug,
                    title = title,
                    poster = rawPoster?.let { absolutize(it, imageBase) },
                    type = o.optString("ty").ifBlank { null },
                )
            )
        }
        if (entries.isEmpty()) return null
        return Index(
            catalog = catalog,
            entries = entries,
            imageBase = imageBase,
            detailsBase = root.optString("detailsBase")
                .ifBlank { "catalog-${catalog.id}-details" },
            sourceBase = meta?.optString("source")?.ifBlank { null },
        )
    }

    private suspend fun catalogs(): List<Index> {
        val masterText = getData("catalog-index.json")
            ?: return indexCache.values.toList() // resiliencia: usar caché si no hay red
        val result = ArrayList<Index>()
        for (cat in parseMaster(masterText)) {
            val cached = indexCache[cat.id]
            if (cached != null) {
                result.add(cached)
                continue
            }
            val text = getData(cat.indexFile) ?: continue
            val idx = parseIndex(cat, text) ?: continue
            indexCache[cat.id] = idx
            result.add(idx)
        }
        return result
    }

    // ── Tipos y tarjetas ─────────────────────────────────────────────────

    private fun typeOf(e: Entry, idx: Index): TvType {
        val t = (e.type ?: "").lowercase()
        val c = idx.catalog.id
        return when {
            "movie" in t || "film" in t -> TvType.Movie
            "dorama" in t || "drama" in t -> TvType.AsianDrama
            "donghua" in t || "anime" in t -> TvType.Anime
            c == "youtubefilms" -> TvType.Movie
            c == "doramas" || c == "dramasyt" -> TvType.AsianDrama
            c == "pelispedia" -> TvType.Movie
            else -> TvType.TvSeries
        }
    }

    private fun card(e: Entry, idx: Index): SearchResponse =
        newMovieSearchResponse(e.title, "$mainUrl/dfx/${idx.catalog.id}/${e.slug}", typeOf(e, idx)) {
            posterUrl = e.poster
        }

    // ── Home ─────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "donghualife" to "Donghua Life",
        "donghuasub" to "DonghuaSub",
        "doramas" to "Doramas",
        "dramasyt" to "Dramas & Series YouTube",
        "youtubefilms" to "YouTube Films",
        "pelispedia" to "PelisPedia",
    )

    // Si la compilación falla por la firma, usa: request: MainPageRequest?
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val idx = catalogs().firstOrNull { it.catalog.id == request.data }
            ?: throw ErrorLoadingException("Catálogo no disponible: ${request.data}")
        val from = (page - 1).coerceAtLeast(0) * pageSize
        val items = idx.entries.drop(from).take(pageSize).map { card(it, idx) }
        if (items.isEmpty() && page == 1) {
            throw ErrorLoadingException("Catálogo vacío: ${idx.catalog.label}")
        }
        return newHomePageResponse(
            HomePageList(idx.catalog.label, items),
            hasNext = from + pageSize < idx.entries.size,
        )
    }

    // ── Búsqueda (híbrida) ───────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = fold(query.trim())
        if (q.isBlank()) return emptyList()
        val all = catalogs()
        // 1) Índices ligeros de DonghuaFlix
        val primary = all.flatMap { idx ->
            idx.entries.asSequence()
                .filter { q in fold(it.title) || q in fold(it.slug) }
                .take(40)
                .map { card(it, idx) }
                .toList()
        }.distinctBy { it.url }
        if (primary.isNotEmpty()) return primary.take(60)
        // 2) Red de seguridad: búsqueda HTML en las fuentes originales
        return sourceSearch(all, query)
    }

    private suspend fun sourceSearch(all: List<Index>, query: String): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        for (idx in all) {
            if (out.size >= 40) break
            val base = idx.sourceBase ?: continue
            val doc = try {
                app.get("$base/?s=" + URLEncoder.encode(query, "UTF-8")).document
            } catch (_: Exception) {
                continue
            }
            val selectors =
                "article a[href], .bsx a[href], .post-title a[href], .listupd a[href], .result a[href]"
            for (a in doc.select(selectors)) {
                val href = a.absUrl("href")
                val title = a.attr("title").ifBlank { a.text().trim() }
                if (!href.startsWith("http") || title.isBlank()) continue
                if (href.trimEnd('/') == base.trimEnd('/')) continue
                if (out.containsKey(href)) continue
                out[href] = newMovieSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = a.selectFirst("img")?.let {
                        it.absUrl("data-src").ifBlank { it.absUrl("src") }
                    }?.takeIf { it.startsWith("http") }
                }
                if (out.size >= 40) break
            }
        }
        return out.values.toList()
    }

    // ── Ficha (detalle) ──────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val marker = "/dfx/"
        if (!url.contains(marker)) return loadFromSource(url)
        val path = url.substringAfter(marker).substringBefore('?').substringBefore('#')
        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val catId = parts[0]
        val slug = parts.subList(1, parts.size).joinToString("/")
        val idx = catalogs().firstOrNull { it.catalog.id == catId } ?: return null
        val entry = idx.entries.firstOrNull { it.slug == slug || it.id == slug } ?: return null

        // 1) Detalle publicado en los shards del repositorio
        findDetail(idx, slug)?.let { return buildFromDetail(it, entry, idx, url) }

        // 2) Híbrido: consultar la página de origen
        sourcePage(idx, slug)?.let { return loadFromSource(it) }

        // 3) Tarjeta mínima con lo que sabemos del índice
        return newMovieLoadResponse(entry.title, url, typeOf(entry, idx), url) {
            posterUrl = entry.poster
        }
    }

    private suspend fun findDetail(idx: Index, slug: String): JSONObject? = coroutineScope {
        // Candidatos directos, por si algún catálogo publica un json por serie
        if ('/' !in slug) {
            getData("${idx.detailsBase}/$slug.json")?.let { text ->
                (parseJson(text) as? JSONObject)?.let { return@coroutineScope it }
            }
        }
        val cache = shardCache.getOrPut(idx.catalog.id) { ConcurrentHashMap() }
        val miss = shardMiss.getOrPut(idx.catalog.id) { ConcurrentHashMap() }
        var found: JSONObject? = null
        var missStreak = 0
        var n = 0
        while (n < maxShards && missStreak < 2 && found == null) {
            val upper = minOf(n + shardBatch, maxShards)
            val results = (n until upper).map { i ->
                async { i to loadShard(idx, i, cache, miss) }
            }.awaitAll()
            if (results.all { it.second == null }) missStreak++ else missStreak = 0
            for ((_, root) in results) {
                root ?: continue
                detailIn(root, slug)?.let {
                    found = it
                    break
                }
            }
            n += shardBatch
        }
        found
    }

    private suspend fun loadShard(
        idx: Index,
        i: Int,
        cache: ConcurrentHashMap<Int, Any>,
        miss: ConcurrentHashMap<Int, Boolean>,
    ): Any? {
        cache[i]?.let { return it }
        if (miss.containsKey(i)) return null
        val text = getData("${idx.detailsBase}/shard-$i.json")
        if (text == null) {
            miss[i] = true
            return null
        }
        val root = parseJson(text)
        if (root == null) {
            miss[i] = true
            return null
        }
        if (cache.size < maxCachedShards) cache[i] = root
        return root
    }

    private val arrayKeys = listOf("series", "items", "entries", "data")

    private fun detailIn(root: Any, slug: String): JSONObject? = when (root) {
        is JSONObject -> {
            root.optJSONObject(slug)
                ?: root.optJSONObject("details")?.optJSONObject(slug)
                ?: arrayKeys.firstNotNullOfOrNull { key ->
                    root.optJSONArray(key)?.let { entryIn(it, slug) }
                }
        }
        is JSONArray -> entryIn(root, slug)
        else -> null
    }

    private fun entryIn(arr: JSONArray, slug: String): JSONObject? {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("i") == slug || o.optString("s") == slug ||
                o.optString("id") == slug || o.optString("slug") == slug
            ) return o
        }
        return null
    }

    private suspend fun buildFromDetail(
        d: JSONObject,
        entry: Entry,
        idx: Index,
        url: String,
    ): LoadResponse {
        val title = firstText(d, "title", "t", "name").ifBlank { entry.title }
        val rawPoster = firstText(d, "poster", "p", "image", "img", "cover")
        val poster = if (rawPoster.isBlank()) entry.poster ?: ""
        else absolutize(rawPoster, idx.imageBase)
        val plot = firstText(d, "description", "synopsis", "plot", "desc", "overview")
        val year = firstIntOrNull(d, "year", "y")
        val genres = (d.optJSONArray("genres") ?: d.optJSONArray("g"))?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
        }.orEmpty()
        val type = typeOf(entry, idx)
        val episodes = episodesIn(d)

        return if (episodes.isNotEmpty() && type != TvType.Movie) {
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
            }
        } else {
            val direct = firstText(d, "url", "u", "link", "video", "src")
            val dataUrl = when {
                direct.startsWith("http") -> direct
                episodes.isNotEmpty() -> episodes.first().data
                else -> url
            }
            newMovieLoadResponse(title, url, type, dataUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
            }
        }
    }

    private fun episodesIn(d: JSONObject): List<Episode> {
        val arr = d.optJSONArray("episodes") ?: d.optJSONArray("eps")
            ?: d.optJSONArray("items") ?: d.optJSONArray("chapters")
            ?: return emptyList()
        val out = ArrayList<Episode>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val data = firstText(o, "url", "u", "link", "href", "src", "file")
            if (data.isBlank()) continue
            val num = firstIntOrNull(o, "number", "n", "num", "episode", "ep") ?: (i + 1)
            val name = firstText(o, "title", "t", "name", "label").ifBlank { "Episodio $num" }
            out.add(newEpisode(data) {
                this.name = name
                this.episode = num
            })
            if (out.size >= 600) break
        }
        return out.sortedBy { it.episode }
    }

    // ── Consulta de fuentes (híbrido) ────────────────────────────────────

    private suspend fun sourcePage(idx: Index, slug: String): String? {
        val base = idx.sourceBase ?: return null
        for (p in listOf("/serie/$slug/", "/series/$slug/", "/$slug/", "/pelicula/$slug/")) {
            val candidate = base.trimEnd('/') + p
            val text = getText(candidate) ?: continue
            if (text.length > 2000 && !text.contains("404", ignoreCase = true)) return candidate
        }
        return null
    }

    private suspend fun loadFromSource(url: String): LoadResponse? {
        val doc = try {
            app.get(url).document
        } catch (_: Exception) {
            return null
        }
        val title = doc.selectFirst("h1.entry-title, h1[itemprop=name], header h1, h1")
            ?.text()?.trim()?.ifBlank { null }
            ?: doc.title()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.startsWith("http") }
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")

        val anchors = doc.select(
            "a[href*='/episodio/'], a[href*='/episode/'], " +
                    ".episodios a[href], .eplister a[href], " +
                    ".listing-chapters a[href], .episodes a[href]"
        ).distinctBy { it.absUrl("href") }

        val eps = anchors.mapIndexedNotNull { i, a ->
            val href = a.absUrl("href")
            if (!href.startsWith("http")) return@mapIndexedNotNull null
            val label = a.text().trim()
            newEpisode(href) {
                name = label.ifBlank { "Episodio ${i + 1}" }
            }
        }

        return if (eps.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // ── Reproducción ─────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!data.startsWith("http")) return false
        var emitted = 0

        // Medio directo: emitir sin descargar nada
        if (isDirectMedia(data)) {
            emit(data, data, callback)
            return true
        }

        // 1) Extractores registrados de CloudStream (YouTube, etc.)
        runCatching {
            loadExtractor(data, null, subtitleCallback) { link ->
                emitted++
                callback(link)
            }
        }

        // 2) Raspado del reproductor embebido
        emitted += scrape(data, 0, subtitleCallback, callback)
        return emitted > 0
    }

    private fun isDirectMedia(u: String): Boolean =
        u.contains(".m3u8", true) || u.contains(".mp4", true) || u.contains(".mpd", true)

    private suspend fun scrape(
        pageUrl: String,
        depth: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        if (depth > 1) return 0
        val doc = try {
            app.get(pageUrl).document
        } catch (_: Exception) {
            return 0
        }
        var count = 0

        val media = linkedSetOf<String>()
        doc.select("video[src], video source[src], audio[src], source[src]").forEach { el ->
            el.absUrl("src").takeIf { isDirectMedia(it) }?.let(media::add)
        }

        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.absUrl("src")
            when {
                src.contains("youtube.com/embed/") || src.contains("youtu.be/") ->
                    src.substringAfterLast("/").substringBefore('?').substringBefore('&')
                        .takeIf { it.isNotBlank() }
                        ?.let { media.add("https://www.youtube.com/watch?v=$it") }

                isDirectMedia(src) -> media.add(src)

                depth == 0 && (src.contains("player", true) || src.contains("embed", true)) ->
                    count += scrape(src, depth + 1, subtitleCallback, callback)
            }
        }

        val html = doc.html()
        listOf(
            Regex(
                """["']((?:https?:)?//[^"'<>\s]+?\.(?:m3u8|mp4|mpd))(?:[?][^"'<>\s]*)?["']""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """file\s*:\s*["']([^"']+\.(?:m3u8|mp4|mpd)[^"']*)["']""",
                RegexOption.IGNORE_CASE
            ),
        ).forEach { rx ->
            rx.findAll(html).forEach { m ->
                val u = m.groupValues[1].replace("&amp;", "&")
                val abs = if (u.startsWith("//")) "https:$u" else u
                if (abs.startsWith("http")) media.add(abs)
            }
        }

        media.forEach { u ->
            emit(u, pageUrl, callback)
            count++
        }
        return count
    }

    private suspend fun emit(u: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val type = when {
            u.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            u.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        val quality = when (
            Regex("""(2160|1080|720|480|360|240)p""").find(u)?.groupValues?.get(1)?.toIntOrNull()
        ) {
            2160 -> Qualities.P2160.value
            1080 -> Qualities.P1080.value
            720 -> Qualities.P720.value
            480 -> Qualities.P480.value
            360 -> Qualities.P360.value
            240 -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
        callback(newExtractorLink(name, name, u, type) {
            this.referer = referer
            this.quality = quality
        })
    }
}
