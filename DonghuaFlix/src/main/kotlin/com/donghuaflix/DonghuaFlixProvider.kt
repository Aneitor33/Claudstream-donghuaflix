package com.donghuaflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
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
    // La web desplegada no sirve /data (la SPA responde HTML): el proveedor
    // lee los JSON del repositorio, con jsDelivr de espejo.
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

    /** Ficha (objeto series) + contenedor raíz (donde viven episodes/seasons). */
    private data class Detail(val series: JSONObject, val root: JSONObject)

    private val indexCache = ConcurrentHashMap<String, Index>()
    private val detailCache = ConcurrentHashMap<String, Detail>()

    private val pageSize = 30
    private val maxShards = 80
    private val shardWave = 16
    private val detailCacheMax = 20

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
            ?: return indexCache.values.toList()
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
        val primary = all.flatMap { idx ->
            idx.entries.asSequence()
                .filter { q in fold(it.title) || q in fold(it.slug) }
                .take(40)
                .map { card(it, idx) }
                .toList()
        }.distinctBy { it.url }
        if (primary.isNotEmpty()) return primary.take(60)
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

        findDetail(idx, slug)?.let { return buildFromDetail(it, entry, idx, url) }
        sourcePage(idx, slug)?.let { return loadFromSource(it) }

        return newMovieLoadResponse(entry.title, url, typeOf(entry, idx), url) {
            posterUrl = entry.poster
        }
    }

    private suspend fun findDetail(idx: Index, slug: String): Detail? {
        detailCache["${idx.catalog.id}/$slug"]?.let { return it }
        val found = scanDetail(idx, slug)
        if (found != null && detailCache.size < detailCacheMax) {
            detailCache["${idx.catalog.id}/$slug"] = found
        }
        return found
    }

    private suspend fun scanDetail(idx: Index, slug: String): Detail? = coroutineScope {
        // 1) Detalle individual: algunos catálogos publican <slug>.json cuya
        //    raíz es {series: {...}, seasons: [...], episodes: [...]}
        if ('/' !in slug) {
            getData("${idx.detailsBase}/$slug.json")?.let { text ->
                (parseJson(text) as? JSONObject)?.let { root ->
                    return@coroutineScope Detail(root.optJSONObject("series") ?: root, root)
                }
            }
        }
        // 2) Shards: la raíz de cada shard es
        //    {series: [...], seasons: [...], episodes: [...]}
        //    y los episodios/seasons se filtran por seriesId == slug.
        var result: Detail? = null
        var n = 0
        while (n < maxShards && result == null) {
            val upper = minOf(n + shardWave, maxShards)
            val deferreds = (n until upper).map {
                async { getData("${idx.detailsBase}/shard-$it.json") }
            }
            for (d in deferreds) {
                if (result != null) break
                val text = d.await()
                val root = text?.let { parseJson(it) as? JSONObject } ?: continue
                detailIn(root, slug)?.let { series ->
                    result = Detail(series, root)
                }
            }
            deferreds.forEach { it.cancel() }
            n = upper
        }
        result
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
        detail: Detail,
        entry: Entry,
        idx: Index,
        url: String,
    ): LoadResponse {
        val d = detail.series
        val root = detail.root
        val title = firstText(d, "title", "t", "name").ifBlank { entry.title }
        val rawPoster = firstText(d, "image", "poster", "p", "img", "cover")
        val poster = if (rawPoster.isBlank()) entry.poster ?: ""
        else absolutize(rawPoster, idx.imageBase)
        val plot = firstText(d, "synopsis", "sipnosis", "description", "plot", "desc", "overview")
        val year = firstIntOrNull(d, "year", "y")
        val genres = (d.optJSONArray("genres") ?: d.optJSONArray("g"))?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
        }.orEmpty()
        val type = typeOf(entry.copy(type = d.optString("type").ifBlank { null } ?: entry.type), idx)
        val episodes = episodesIn(root, entry.slug, d.optString("id").ifBlank { entry.slug })

        return if (episodes.isNotEmpty() && type != TvType.Movie) {
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
            }
        } else {
            val direct = firstText(root, "url", "u", "link", "video", "src")
            val servers = LinkedHashSet<String>()
            serverUrlsIn(root.optJSONArray("servers")).forEach(servers::add)
            serverUrlsIn(d.optJSONArray("servers")).forEach(servers::add)
            val dataUrl = when {
                direct.startsWith("http") -> direct
                servers.isNotEmpty() -> servers.joinToString("\n")
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

    /** Recoge las URL de un array "servers"/"sources" ({name,url}). */
    private fun serverUrlsIn(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            firstText(o, "url", "u", "link", "src", "href", "file")
                .ifBlank { null }?.let(out::add)
        }
        return out.toList()
    }

    private fun episodesIn(root: JSONObject, slug: String, seriesId: String): List<Episode> {
        val shardMode = root.optJSONArray("series") != null
        val seasons = HashMap<String, Int>()
        root.optJSONArray("seasons")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (shardMode) {
                    val sSeries = o.optString("seriesId")
                    if (sSeries != slug && sSeries != seriesId) continue
                }
                seasons[o.optString("id")] = o.optInt("number", 1)
            }
        }
        val arr = root.optJSONArray("episodes") ?: root.optJSONArray("eps")
            ?: root.optJSONArray("items") ?: root.optJSONArray("chapters")
            ?: return emptyList()
        val out = ArrayList<Episode>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (shardMode) {
                // En los shards los episodios se filtran por serie:
                // asi se evitan episodios "mezclados" de otras fichas.
                val epSeries = o.optString("seriesId")
                if (epSeries != slug && epSeries != seriesId) continue
            }
            val urls = LinkedHashSet<String>()
            firstText(o, "url", "u", "link", "href", "src", "file")
                .ifBlank { null }?.let(urls::add)
            serverUrlsIn(o.optJSONArray("servers")).forEach(urls::add)
            serverUrlsIn(o.optJSONArray("sources")).forEach(urls::add)
            if (urls.isEmpty()) continue
            val num = firstIntOrNull(o, "number", "n", "num", "episode", "ep") ?: (i + 1)
            val rawName = firstText(o, "title", "t", "name", "label")
            var name = rawName
                .replace(Regex("""\s*online\s*sub espa[ñn]ol.*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*sub espa[ñn]ol.*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+(completo|online|hd).*$""", RegexOption.IGNORE_CASE), "")
                .trim()
            if (name.isBlank() || name.length > 60) name = "Episodio $num"
            val season = o.optString("seasonId").ifBlank { null }?.let(seasons::get)
            out.add(newEpisode(urls.joinToString("\n")) {
                this.name = name
                this.episode = num
                if (season != null) this.season = season
            })
            if (out.size >= 600) break
        }
        return out.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
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
        var emitted = 0
        val urls = data.lines().map { it.trim() }.filter { it.startsWith("http") }.distinct()
        if (urls.isEmpty()) return false
        for (raw in urls) {
            emitted += resolve(raw, 0, subtitleCallback, callback)
        }
        return emitted > 0
    }

    /** Resuelve una URL (directa, embed o página) hasta conseguir enlaces. */
    private suspend fun resolve(
        u: String,
        depth: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val url = normalizeYoutube(u)
        if (isDirectMedia(url)) {
            emit(url, url, callback)
            return 1
        }
        if (depth > 2) return 0
        var emitted = 0
        when {
            isDownloadPath(url) -> emitted += extractDownloadPage(url, callback)
            "dailymotion.com" in url -> emitted += extractDailymotion(url, callback)
            "ok.ru" in url -> emitted += extractOkRu(url, callback)
            "vidhide" in url || "earn.vids" in url -> emitted += extractVidhide(url, callback)
            else -> {
                runCatching {
                    loadExtractor(url, null, subtitleCallback) { link ->
                        emitted++
                        callback(link)
                    }
                }
                emitted += scrape(url, depth, subtitleCallback, callback)
            }
        }
        return emitted
    }

    private fun normalizeYoutube(u: String): String = when {
        u.contains("youtube.com/embed/") ->
            "https://www.youtube.com/watch?v=" +
                    u.substringAfterLast("/").substringBefore('?').substringBefore('&')

        u.contains("youtu.be/") && !u.contains("watch?") ->
            "https://www.youtube.com/watch?v=" +
                    u.substringAfterLast("/").substringBefore('?').substringBefore('&')

        else -> u
    }

    private fun isDirectMedia(u: String): Boolean =
        u.contains(".m3u8", true) || u.contains(".mp4", true) || u.contains(".mpd", true)

    private fun isYoutubeUrl(u: String): Boolean =
        u.contains("youtube.com/embed/") || u.contains("youtu.be/")

    /** Hosts con extractor disponible en CloudStream o en este proveedor. */
    private val knownHostPatterns = listOf(
        "voe.sx", "streamtape", "mixdrop", "dood.", "doodstream", "filemoon",
        "streamwish", "supervideo", "vidmoly", "upstream.to", "mp4upload",
        "filelions", "luluvdo", "dailymotion", "ok.ru", "youtube", "youtu.be",
        "vimeo", "googlevideo", "sblongvu", "playlist.php", "player.php", "play.php",
        "vidhide", "earn.vids", "pelispedia", "streamhg",
        "ashleyhardat", "audinifer", "morencius", "voe.sx",
    )

    private fun isKnownHost(u: String): Boolean =
        knownHostPatterns.any { it in u }

    private suspend fun extractDailymotion(u: String, callback: (ExtractorLink) -> Unit): Int {
        val id = Regex("""[?&]video=([\w-]+)""").find(u)?.groupValues?.get(1)
            ?: u.removeSuffix("/").substringAfterLast("/")
        val meta = getText("https://www.dailymotion.com/player/metadata/video/$id") ?: return 0
        val qualities = (parseJson(meta) as? JSONObject)?.optJSONObject("qualities") ?: return 0
        var count = 0
        val keys = qualities.keys()
        while (keys.hasNext()) {
            val label = keys.next()
            val list = qualities.optJSONArray(label) ?: continue
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val url = o.optString("url")
                if (!url.startsWith("http")) continue
                if (!url.contains(".m3u8", true) && !url.contains(".mp4", true)) continue
                emit(url, "https://www.dailymotion.com", callback, label.toIntOrNull())
                count++
            }
        }
        return count
    }

    private suspend fun extractOkRu(u: String, callback: (ExtractorLink) -> Unit): Int {
        val id = u.removeSuffix("/").substringAfterLast("/")
        val page = getText("https://ok.ru/videoembed/$id") ?: return 0
        var count = 0

        Regex("""data-options="([^"]+)"""").find(page)?.let { m ->
            val unescaped = m.groupValues[1]
                .replace("&quot;", "\"").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
            val flashvars = (parseJson(unescaped) as? JSONObject)?.optString("flashvars")
            val metadata = flashvars?.let { (parseJson(it) as? JSONObject)?.optString("metadata") }
            val meta = metadata?.let { parseJson(it) as? JSONObject }
            meta?.optString("hlsManifestUrl")?.ifBlank { null }?.let {
                emit(it, "https://ok.ru", callback)
                count++
            }
            meta?.optJSONArray("videos")?.let { vids ->
                for (i in 0 until vids.length()) {
                    val v = vids.optJSONObject(i) ?: continue
                    val url = v.optString("url")
                    if (url.startsWith("http") && url.contains(".mp4", true)) {
                        emit(url, "https://ok.ru", callback)
                        count++
                    }
                }
            }
        }

        if (count == 0) {
            Regex(
                """https?://[^"'<>\s]+\.(?:mp4|m3u8)(?:\?[^"'<>\s]*)?""",
                RegexOption.IGNORE_CASE
            ).findAll(page).forEach { m ->
                emit(m.value, "https://ok.ru", callback)
                count++
            }
        }
        return count
    }

    /**
     * Raspado genérico de páginas de reproductor: medios directos, iframes,
     * enlaces a hosts conocidos (VOE, Streamtape, Mixdrop…) y URLs sueltas
     * en el HTML/JS. Los candidatos se resuelven recursivamente (límite de
     * profundidad) pasando por los extractores registrados de CloudStream.
     */
    /** Páginas de descarga de Streamwish/Earnvids/VidHide: el mp4 directo
     *  sale de un formulario con token (op=download1). */
    private suspend fun extractDownloadPage(u: String, callback: (ExtractorLink) -> Unit): Int {
        val page = getText(u) ?: return 0
        var count = grepMedia(page, u, callback)
        if (count > 0) return count

        val op = Regex("""name=["']op["'][^>]*value=["']([^"']+)""").find(page)?.groupValues?.get(1)
            ?: "download1"
        val token = Regex("""name=["']token["'][^>]*value=["']([^"']+)""").find(page)?.groupValues?.get(1)
        if (token == null) return 0

        return try {
            val html = app.post(u, data = mapOf("op" to op, "token" to token)).text
            var n = grepMedia(html, u, callback)
            Regex("""id=["']download_link["'][^>]*href=["']([^"']+)""").find(html)?.let { m ->
                val link = m.groupValues[1].replace("&amp;", "&")
                if (link.startsWith("http")) {
                    emit(link, u, callback)
                    n++
                }
            }
            n
        } catch (_: Exception) {
            0
        }
    }

    /** Busca m3u8/mp4 embebidos en un HTML (players tipo player.js). */
    private fun grepMedia(page: String, referer: String, callback: (ExtractorLink) -> Unit): Int {
        val found = LinkedHashSet<String>()
        listOf(
            Regex(
                """["']((?:https?:)?//[^"'<>\s]+?\.(?:m3u8|mp4)(?:\?[^"'<>\s]*)?)["']""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """file\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""",
                RegexOption.IGNORE_CASE
            ),
        ).forEach { rx ->
            rx.findAll(page).forEach { m ->
                val raw = m.groupValues[1].replace("&amp;", "&").replace("\\/", "/")
                when {
                    raw.startsWith("http") -> found.add(raw)
                    raw.startsWith("//") -> found.add("https:$raw")
                }
            }
        }
        var count = 0
        found.forEach { link ->
            emit(link, referer, callback)
            count++
        }
        return count
    }

    /** VidHide/earn.vids: el manifiesto HLS va embebido en la pagina del player. */
    private suspend fun extractVidhide(u: String, callback: (ExtractorLink) -> Unit): Int {
        val page = getText(u) ?: return 0
        return grepMedia(page, u, callback)
    }

    private fun isDownloadPath(u: String): Boolean =
        u.contains("/d/", true) || u.contains("/download/", true)

    private suspend fun scrape(
        pageUrl: String,
        depth: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        if (depth > 2) return 0
        val doc = try {
            app.get(pageUrl).document
        } catch (_: Exception) {
            return 0
        }
        var count = 0
        val media = linkedSetOf<String>()
        val candidates = linkedSetOf<String>()

        doc.select("video[src], video source[src], audio[src], source[src]").forEach { el ->
            el.absUrl("src").takeIf { isDirectMedia(it) }?.let(media::add)
        }

        doc.select("iframe[src], a[href]").forEach { el ->
            val link = el.absUrl(if (el.hasAttr("src")) "src" else "href")
            if (!link.startsWith("http")) return@forEach
            when {
                isDirectMedia(link) -> media.add(link)
                isYoutubeUrl(link) -> media.add(normalizeYoutube(link))
                depth < 2 && (isKnownHost(link) || isPlayerPath(link)) -> candidates.add(link)
            }
        }

        val html = doc.html()
        Regex("""https?://[^\s"'<>\\]+""").findAll(html).forEach { m ->
            val link = m.value
                .replace("\\/", "/")
                .trimEnd('"', '\'', ')', ']', '}', ';')
            if (!link.startsWith("http")) return@forEach
            when {
                isDirectMedia(link) -> media.add(link)
                isYoutubeUrl(link) -> media.add(normalizeYoutube(link))
                depth < 2 && isKnownHost(link) -> candidates.add(link)
            }
        }

        media.forEach { u ->
            emit(u, pageUrl, callback)
            count++
        }
        candidates.forEach { link ->
            count += resolve(link, depth + 1, subtitleCallback, callback)
        }
        return count
    }

    private fun isPlayerPath(u: String): Boolean =
        u.contains("/embed/", true) || u.contains("/e/", true) ||
                u.contains("player", true) || u.contains("/video/", true)

    private suspend fun emit(
        u: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        qualityHint: Int? = null,
    ) {
        val type = when {
            u.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            u.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        val quality = qualityHint ?: when (
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
