package com.layarkaca

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addQuality
import com.lagradost.cloudstream3.addSub
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class LayarKaca : MainAPI() {

    override var mainUrl = "https://tv.lk21official.love"
    private var seriesUrl = "https://series.lk21.de"

    override var name = "LayarKaca"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage =
            mainPageOf(
                    "$mainUrl/populer/page/" to "Film Terplopuler",
                    "$mainUrl/rating/page/" to "Film Berdasarkan IMDb Rating",
                    "$mainUrl/most-commented/page/" to "Film Dengan Komentar Terbanyak",
                    "$mainUrl/latest/page/" to "Film Upload Terbaru",
                    "$seriesUrl/country/south-korea/page/" to "Drama Korea",
                    "$seriesUrl/country/china/page/" to "Series China",
                    "$seriesUrl/series/west/page/" to "Series West",
                    "$seriesUrl/populer/page/" to "Series Terpopuler",
                    "$seriesUrl/latest-series/page/" to "Series Terbaru",
            )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("li.slider article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private suspend fun getProperLink(url: String): String? {
        // Redirect check logic seems outdated for the new domain structure, 
        // assuming direct links for now or simplified check
        return url
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.poster-title")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val episode = this.selectFirst("span.episode")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        
        return if (episode != null) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
            }
        } else {
            val quality = this.select("span.label").text().trim()
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val mainUrl = "https://series.lk21.de"
        val encodedQuery = withContext(Dispatchers.IO) {
            URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        }
        val document =
                app.get("$mainUrl/search.php?s=$query#gsc.tab=0&gsc.q=$encodedQuery&gsc.page=1")
                        .document
        return document.select("li.slider article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixUrl = getProperLink(url)
        val document = app.get(fixUrl ?: return null).document

        // Header extraction
        val title = document.selectFirst("div.movie-info > h1")?.text()?.replace("Nonton ", "")?.replace(" Sub Indo di Lk21", "")?.trim() 
                ?: document.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = fixUrlNull(document.selectFirst("div.movie-info picture img")?.attr("src") 
                ?: document.selectFirst("div.poster img")?.attr("src"))
        val tags = document.select("div.tag-list span.tag a").map { it.text() }

        val year = document.select("div.meta-info div.detail p").find { it.text().contains("Release", true) }
                ?.text()?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
                ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val description = document.select("div.synopsis").text().trim()
        val trailer = document.selectFirst("a.yt-lightbox")?.attr("href")
        
        // Rating extraction
        val rating = document.select("div.info-tag i.fa-star").first()?.parent()?.text()?.trim()

        // Recommendations
        val recommendations = document.select("div.related-content ul.video-list li a").map {
            val recName = it.selectFirst("span.video-title")?.text()?.trim() ?: ""
            val recHref = fixUrl(it.attr("href"))
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("src"))
            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
            }
        }

        val isSeries = url.contains("series") || document.select("div.episode-list").isNotEmpty() || document.select("ul.episode-list-wrapper").isNotEmpty() || title.contains("Season", true)
        // Note: verify series logic. Assuming 'series' in URL or episode list presence. 
        // We can also check extracting episodes first.
        
        // Episodes
        // Needs verifying on Series page. Assuming standard list if present.
        // If no episode list found, treat as movie.
        
        // For now, simple check:
        val episodes = document.select("div.episode-list a, ul.episode-list-wrapper a").map { 
             val href = fixUrl(it.attr("href"))
             val name = it.text().trim()
             val episode = name.filter { c -> c.isDigit() }.toIntOrNull()
             newEpisode(href) {
                 this.name = name
                 this.episode = episode
             }
        }

        if (episodes.isNotEmpty() || isSeries) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        document.select("ul#player-list li a").map { fixUrl(it.attr("href")) }.forEach {
            loadExtractor(it, data, subtitleCallback, callback)
        }

        return true
    }


}
