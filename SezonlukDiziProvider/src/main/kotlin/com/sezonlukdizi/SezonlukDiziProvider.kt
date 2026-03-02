package com.sezonlukdizi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

class SezonlukDiziProvider : MainAPI() {
    override var mainUrl = "https://sezonlukdizi.cc"
    override var name = "SezonlukDizi"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val defaultHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    // ===================== HomePage =====================

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenen Bölümler",
        "$mainUrl/" to "Popüler Diziler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = defaultHeaders).document

        val homePageLists = mutableListOf<HomePageList>()

        // Son eklenen bölümler (featured cards at top)
        val latestEpisodes = doc.select("a.ui.medium.image.rounded").mapNotNull { element ->
            element.toSearchResult()
        }
        if (latestEpisodes.isNotEmpty()) {
            homePageLists.add(HomePageList("Son Eklenen Bölümler", latestEpisodes))
        }

        // Popüler diziler section
        val popularShows = doc.select("div.ui.five.column.stackable.grid a.column, div.ui.four.column.stackable.grid a.column").mapNotNull { element ->
            element.toSearchResult()
        }
        if (popularShows.isNotEmpty()) {
            homePageLists.add(HomePageList("Popüler Diziler", popularShows))
        }

        // Dizi takvimi (calendar)
        val calendarShows = doc.select("a.ui.image.label.dizilabel.golge").mapNotNull { element ->
            val title = element.text().trim()
            val href = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
            val showUrl = href.replace("/bolumler/", "/diziler/")
                .replace(Regex("/\\d+-sezon.*"), ".html")
            newTvSeriesSearchResponse(title, showUrl) {
                // No poster from calendar items
            }
        }
        if (calendarShows.isNotEmpty()) {
            homePageLists.add(HomePageList("Dizi Takvimi", calendarShows))
        }

        return newHomePageResponse(homePageLists)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title").ifBlank { this.selectFirst("img")?.attr("alt") }?.trim()
            ?: return null
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("src")
        )

        // Normalize URL: episode URLs -> show page URL
        val showUrl = if (href.contains(Regex("/\\d+-sezon"))) {
            val slug = href.substringAfter("$mainUrl/").substringBefore("/")
            "$mainUrl/diziler/$slug.html"
        } else {
            href
        }

        return newTvSeriesSearchResponse(title, showUrl) {
            this.posterUrl = posterUrl
        }
    }

    // ===================== Search =====================

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/diziler.asp?adi=${query.replace(" ", "+")}"
        val doc = app.get(searchUrl, headers = defaultHeaders).document

        return doc.select("a.column").mapNotNull { element ->
            val title = element.selectFirst("img")?.attr("alt")
                ?: element.attr("title")
                ?: return@mapNotNull null
            val href = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
            val posterUrl = fixUrlNull(
                element.selectFirst("img")?.attr("data-src")
                    ?: element.selectFirst("img")?.attr("src")
            )
            newTvSeriesSearchResponse(title.trim(), href) {
                this.posterUrl = posterUrl
            }
        }
    }

    // ===================== Load (Show Detail) =====================

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = defaultHeaders).document

        val title = doc.selectFirst("h1.header, h2.header")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore(" izle")?.trim()
            ?: "Bilinmeyen Dizi"

        val posterUrl = fixUrlNull(
            doc.selectFirst("div.ui.small.image img, img.ui.image")?.attr("data-src")
                ?: doc.selectFirst("div.ui.small.image img, img.ui.image")?.attr("src")
        )

        val description = doc.selectFirst("div.ui.segment p, div.description")?.text()?.trim()

        val tags = doc.select("a.ui.label").map { it.text().trim() }.filter { it.isNotBlank() }

        val year = doc.selectFirst("div.extra, span.year")?.text()
            ?.let { Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val rating = doc.selectFirst("div.ui.label.yellow, span.rating")?.text()
            ?.let { Regex("([\\d.]+)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
            ?.let { (it * 1000).toInt() } // CloudStream uses rating * 1000

        // Get episodes from the bolumler page
        val slug = url.substringAfterLast("/").substringBefore(".html")
        val episodesUrl = "$mainUrl/bolumler/$slug.html"
        val episodesDoc = try {
            app.get(episodesUrl, headers = defaultHeaders).document
        } catch (e: Exception) {
            doc // Fallback to main page
        }

        val episodes = mutableListOf<Episode>()
        episodesDoc.select("table.ui.table tbody tr, div.ui.segment a").forEach { row ->
            // Try table rows first
            val link = row.selectFirst("a[href]") ?: if (row.tagName() == "a") row else return@forEach
            val epHref = fixUrlNull(link.attr("href")) ?: return@forEach
            val epTitle = link.text().trim().ifBlank { return@forEach }

            // Parse season and episode from URL like /dizi-adi/2-sezon-6-bolum.html
            val seasonMatch = Regex("(\\d+)-sezon").find(epHref)
            val episodeMatch = Regex("(\\d+)-bolum").find(epHref)

            val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull()
            val ep = episodeMatch?.groupValues?.get(1)?.toIntOrNull()

            episodes.add(
                newEpisode(epHref) {
                    this.name = epTitle
                    this.season = season
                    this.episode = ep
                }
            )
        }

        // If no episodes found via table, try alternative selectors
        if (episodes.isEmpty()) {
            episodesDoc.select("a[href*='-sezon-'], a[href*='-bolum']").forEach { link ->
                val epHref = fixUrlNull(link.attr("href")) ?: return@forEach
                val epTitle = link.text().trim().ifBlank { return@forEach }

                val seasonMatch = Regex("(\\d+)-sezon").find(epHref)
                val episodeMatch = Regex("(\\d+)-bolum").find(epHref)

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epTitle
                        this.season = seasonMatch?.groupValues?.get(1)?.toIntOrNull()
                        this.episode = episodeMatch?.groupValues?.get(1)?.toIntOrNull()
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = tags
            this.year = year
            this.rating = rating
        }
    }

    // ===================== LoadLinks (Video Extraction) =====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = defaultHeaders).document

        // 1. Try to find direct m3u8/mp4 sources in page scripts
        val pageHtml = doc.html()
        extractDirectLinks(pageHtml, data, callback, subtitleCallback)

        // 2. Try to find iframes
        doc.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val iframeUrl = fixUrlNull(iframe.attr("src").ifBlank { iframe.attr("data-src") })
                ?: return@forEach
            loadIframeLinks(iframeUrl, data, callback, subtitleCallback)
        }

        // 3. Check alternative sources in #alternatif dropdown or similar
        val altLinks = doc.select("#alternatif a[href], div.alternatives a[href], a.item[data-href]")
        for (altLink in altLinks) {
            val altUrl = fixUrlNull(
                altLink.attr("data-href").ifBlank { altLink.attr("href") }
            ) ?: continue

            try {
                val altDoc = app.get(altUrl, headers = defaultHeaders).document
                val altHtml = altDoc.html()
                extractDirectLinks(altHtml, altUrl, callback, subtitleCallback)

                altDoc.select("iframe[src], iframe[data-src]").forEach { iframe ->
                    val iframeUrl = fixUrlNull(iframe.attr("src").ifBlank { iframe.attr("data-src") })
                        ?: return@forEach
                    loadIframeLinks(iframeUrl, altUrl, callback, subtitleCallback)
                }
            } catch (_: Exception) {
                // Skip failing alternatives
            }
        }

        // 4. Check for AJAX-loaded player (some pages load player via JS)
        val ajaxPatterns = listOf(
            Regex("""loadPlayer\(['"]([^'"]+)['"]\)"""),
            Regex("""playerUrl\s*[:=]\s*['"]([^'"]+)['"]"""),
            Regex("""data-video\s*=\s*['"]([^'"]+)['"]"""),
        )
        for (pattern in ajaxPatterns) {
            pattern.findAll(pageHtml).forEach { match ->
                val playerUrl = fixUrlNull(match.groupValues[1]) ?: return@forEach
                loadIframeLinks(playerUrl, data, callback, subtitleCallback)
            }
        }

        return true
    }

    // ===================== Direct Link Extraction =====================

    private fun extractDirectLinks(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        // Find m3u8 links
        val m3u8Pattern = Regex("""['"]?(https?://[^'"<>\s]+\.m3u8[^'"<>\s]*)['"]?""")
        m3u8Pattern.findAll(html).forEach { match ->
            val m3u8Url = match.groupValues[1]
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name - HLS",
                    url = m3u8Url,
                    referer = referer,
                    quality = Qualities.Unknown.value,
                    type = INFER_TYPE,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer,
                        "Origin" to mainUrl
                    )
                )
            )
        }

        // Find mp4 links
        val mp4Pattern = Regex("""['"]?(https?://[^'"<>\s]+\.mp4[^'"<>\s]*)['"]?""")
        mp4Pattern.findAll(html).forEach { match ->
            val mp4Url = match.groupValues[1]
            if (!mp4Url.contains("logo") && !mp4Url.contains("banner")) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name - MP4",
                        url = mp4Url,
                        referer = referer,
                        quality = Qualities.Unknown.value,
                        type = INFER_TYPE,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to referer
                        )
                    )
                )
            }
        }

        // Find subtitle tracks (VTT/SRT)
        val subtitlePattern = Regex(
            """(?:src|file|url)\s*[:=]\s*['"]?(https?://[^'"<>\s]+\.(?:vtt|srt)[^'"<>\s]*)['"]?"""
        )
        subtitlePattern.findAll(html).forEach { match ->
            val subUrl = match.groupValues[1]
            // Detect language label
            val langLabel = when {
                html.substringBefore(subUrl).takeLast(100).contains("Türkçe", ignoreCase = true) -> "Türkçe"
                html.substringBefore(subUrl).takeLast(100).contains("Turkish", ignoreCase = true) -> "Türkçe"
                html.substringBefore(subUrl).takeLast(100).contains("English", ignoreCase = true) -> "English"
                else -> "Türkçe" // Default to Turkish for this site
            }
            subtitleCallback.invoke(SubtitleFile(langLabel, subUrl))
        }

        // VideoJS text tracks
        val trackPattern = Regex(
            """<track[^>]+src\s*=\s*['"]([^'"]+)['"][^>]*>"""
        )
        trackPattern.findAll(html).forEach { match ->
            val trackUrl = fixUrlNull(match.groupValues[1]) ?: return@forEach
            val labelMatch = Regex("""label\s*=\s*['"]([^'"]+)['"]""").find(match.value)
            val label = labelMatch?.groupValues?.get(1) ?: "Türkçe"
            subtitleCallback.invoke(SubtitleFile(label, trackUrl))
        }
    }

    // ===================== Iframe Link Loading =====================

    private suspend fun loadIframeLinks(
        iframeUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            // Try known extractors first
            if (iframeUrl.contains("rapidrame") ||
                iframeUrl.contains("closeload") ||
                iframeUrl.contains("faselhd") ||
                iframeUrl.contains("vidmoly") ||
                iframeUrl.contains("filemoon")
            ) {
                loadExtractor(iframeUrl, referer, subtitleCallback, callback)
                return
            }

            // For unknown iframes, fetch and extract
            val iframeDoc = app.get(
                iframeUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                ),
                allowRedirects = true
            )

            extractDirectLinks(iframeDoc.text, iframeUrl, callback, subtitleCallback)

            // Check for nested iframes
            val nestedDoc = Jsoup.parse(iframeDoc.text)
            nestedDoc.select("iframe[src]").forEach { nested ->
                val nestedUrl = fixUrlNull(nested.attr("src")) ?: return@forEach
                try {
                    val nestedResponse = app.get(
                        nestedUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to iframeUrl
                        )
                    )
                    extractDirectLinks(nestedResponse.text, nestedUrl, callback, subtitleCallback)
                } catch (_: Exception) { }
            }

            // Check for packed/encoded JS
            val packedPattern = Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\)""", RegexOption.DOT_MATCHES_ALL)
            packedPattern.findAll(iframeDoc.text).forEach { match ->
                try {
                    val unpacked = getAndUnpack(match.value)
                    extractDirectLinks(unpacked, iframeUrl, callback, subtitleCallback)
                } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            // Try loadExtractor as last resort
            try {
                loadExtractor(iframeUrl, referer, subtitleCallback, callback)
            } catch (_: Exception) { }
        }
    }
}
