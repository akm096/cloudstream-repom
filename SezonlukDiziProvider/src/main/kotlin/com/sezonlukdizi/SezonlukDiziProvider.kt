package com.sezonlukdizi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

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
        val popularShows = doc.select(
            "div.ui.five.column.stackable.grid a.column, div.ui.four.column.stackable.grid a.column"
        ).mapNotNull { element ->
            element.toSearchResult()
        }
        if (popularShows.isNotEmpty()) {
            homePageLists.add(HomePageList("Popüler Diziler", popularShows))
        }

        // Dizi takvimi (calendar)
        val calendarShows = doc.select("a.ui.image.label.dizilabel.golge").mapNotNull { element ->
            val title = element.text().trim()
            val href = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
            newTvSeriesSearchResponse(title, href)
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

        // Normalize episode URLs -> show page URL
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
            val title = (element.selectFirst("img")?.attr("alt")
                ?: element.attr("title")).trim()
            if (title.isBlank()) return@mapNotNull null
            val href = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
            val posterUrl = fixUrlNull(
                element.selectFirst("img")?.attr("data-src")
                    ?: element.selectFirst("img")?.attr("src")
            )
            newTvSeriesSearchResponse(title, href) {
                this.posterUrl = posterUrl
            }
        }
    }

    // ===================== Load (Show Detail) =====================

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = defaultHeaders).document

        val title = doc.selectFirst("h1.header, h2.header")?.text()?.trim()
            ?: doc.title().substringBefore(" izle").trim()

        val posterUrl = fixUrlNull(
            doc.selectFirst("div.ui.small.image img, img.ui.image")?.attr("data-src")
                ?: doc.selectFirst("div.ui.small.image img, img.ui.image")?.attr("src")
        )

        val description = doc.selectFirst("div.ui.segment p, div.description")?.text()?.trim()

        val tags = doc.select("a.ui.label").map { it.text().trim() }.filter { it.isNotBlank() }

        val year = doc.selectFirst("div.extra, span.year")?.text()
            ?.let { Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        // Get episodes from the bolumler page
        val slug = url.substringAfterLast("/").substringBefore(".html")
        val episodesUrl = "$mainUrl/bolumler/$slug.html"
        val episodesDoc = try {
            app.get(episodesUrl, headers = defaultHeaders).document
        } catch (e: Exception) {
            doc
        }

        val episodes = mutableListOf<Episode>()

        // Try table rows first
        episodesDoc.select("table.ui.table tbody tr").forEach { row ->
            val link = row.selectFirst("a[href]") ?: return@forEach
            val epHref = fixUrlNull(link.attr("href")) ?: return@forEach
            val epTitle = link.text().trim().ifBlank { return@forEach }

            val season = Regex("(\\d+)-sezon").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
            val ep = Regex("(\\d+)-bolum").find(epHref)?.groupValues?.get(1)?.toIntOrNull()

            episodes.add(newEpisode(epHref) {
                this.name = epTitle
                this.season = season
                this.episode = ep
            })
        }

        // Fallback: links with season/episode in URL
        if (episodes.isEmpty()) {
            episodesDoc.select("a[href*='-sezon-'], a[href*='-bolum']").forEach { link ->
                val epHref = fixUrlNull(link.attr("href")) ?: return@forEach
                val epTitle = link.text().trim().ifBlank { return@forEach }
                val season = Regex("(\\d+)-sezon").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                val ep = Regex("(\\d+)-bolum").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode(epHref) {
                    this.name = epTitle
                    this.season = season
                    this.episode = ep
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = tags
            this.year = year
        }
    }

    // ===================== LoadLinks =====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = defaultHeaders)
        val doc = response.document
        val pageHtml = response.text

        // 1. Direct m3u8/mp4 from page source
        extractDirectLinks(pageHtml, data, callback, subtitleCallback)

        // 2. Check iframes
        doc.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val iframeUrl = fixUrlNull(
                iframe.attr("src").ifBlank { iframe.attr("data-src") }
            ) ?: return@forEach
            loadIframeLinks(iframeUrl, data, callback, subtitleCallback)
        }

        // 3. Alternative source buttons
        doc.select("#alternatif a[href], div.alternatives a[href], a.item[data-href]")
            .forEach { altLink ->
                val altUrl = fixUrlNull(
                    altLink.attr("data-href").ifBlank { altLink.attr("href") }
                ) ?: return@forEach
                try {
                    val altResponse = app.get(altUrl, headers = defaultHeaders)
                    extractDirectLinks(altResponse.text, altUrl, callback, subtitleCallback)
                    altResponse.document.select("iframe[src], iframe[data-src]").forEach { iframe ->
                        val iframeUrl = fixUrlNull(
                            iframe.attr("src").ifBlank { iframe.attr("data-src") }
                        ) ?: return@forEach
                        loadIframeLinks(iframeUrl, altUrl, callback, subtitleCallback)
                    }
                } catch (_: Exception) { }
            }

        return true
    }

    // ==================== Helpers ====================

    private suspend fun extractDirectLinks(
        html: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        // m3u8
        Regex("""['"]?(https?://[^'"<>\s]+\.m3u8[^'"<>\s]*)['"]?""")
            .findAll(html).forEach { match ->
                val m3u8Url = match.groupValues[1]
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name HLS",
                        url = m3u8Url,
                        type = INFER_TYPE
                    ) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
                    }
                )
            }

        // mp4
        Regex("""['"]?(https?://[^'"<>\s]+\.mp4[^'"<>\s]*)['"]?""")
            .findAll(html).forEach { match ->
                val mp4Url = match.groupValues[1]
                if (!mp4Url.contains("logo") && !mp4Url.contains("banner")) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name MP4",
                            url = mp4Url,
                            type = INFER_TYPE
                        ) {
                            this.referer = referer
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
                        }
                    )
                }
            }

        // VTT/SRT subtitles
        Regex("""['"]?(https?://[^'"<>\s]+\.(?:vtt|srt)[^'"<>\s]*)['"]?""")
            .findAll(html).forEach { match ->
                subtitleCallback.invoke(SubtitleFile("Türkçe", match.groupValues[1]))
            }

        // <track> elements
        Regex("""<track[^>]+src=['"]([^'"]+)['"][^>]*>""")
            .findAll(html).forEach { match ->
                val trackUrl = fixUrlNull(match.groupValues[1]) ?: return@forEach
                val label = Regex("""label=['"]([^'"]+)['"]""")
                    .find(match.value)?.groupValues?.get(1) ?: "Türkçe"
                subtitleCallback.invoke(SubtitleFile(label, trackUrl))
            }
    }

    private suspend fun loadIframeLinks(
        iframeUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        // Try built-in extractors first
        try {
            loadExtractor(iframeUrl, referer, subtitleCallback, callback)
            return
        } catch (_: Exception) { }

        // Manual fetch fallback
        try {
            val iframeHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to referer,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            val iframeResponse = app.get(iframeUrl, headers = iframeHeaders, allowRedirects = true)
            val html = iframeResponse.text

            extractDirectLinks(html, iframeUrl, callback, subtitleCallback)

            // Nested iframes
            Jsoup.parse(html).select("iframe[src]").forEach { nested ->
                val nestedUrl = fixUrlNull(nested.attr("src")) ?: return@forEach
                try {
                    val nestedHtml = app.get(
                        nestedUrl,
                        headers = mapOf("User-Agent" to USER_AGENT, "Referer" to iframeUrl)
                    ).text
                    extractDirectLinks(nestedHtml, nestedUrl, callback, subtitleCallback)
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }
}
