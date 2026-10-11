package eu.kanade.tachiyomi.extension.de.onepiecetube

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.runWebView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Source
abstract class OnePieceTube : KeiSource() {

    private fun onePiece() = SManga.create().apply {
        url = "/manga/kapitel-mangaliste"
        title = "One Piece"
        thumbnail_url = "https://www.carlsen.de/sites/default/files/styles/sc_110_160/public/produkt/one-piece-2c-band-1.jpg?itok=26tLROc0"
        author = "Eiichiro Oda"
        status = SManga.ONGOING
    }

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(if (page == 1) listOf(onePiece()) else emptyList(), false)

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = MangasPage(
        if (page == 1 && "one piece".contains(query.trim(), ignoreCase = true)) listOf(onePiece()) else emptyList(),
        false,
    )

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? = if (url.host == baseUrl.toHttpUrl().host && url.encodedPath.startsWith("/manga/")) onePiece() else null

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchChapters) return SMangaUpdate(manga, chapters)

        // Experimental: determine the latest chapter from the listing, then generate URLs.
        // This deliberately does not pretend to verify each generated chapter's existence.
        val document = client.get("$baseUrl/manga/kapitel-mangaliste").asJsoup()
        val numbers = Regex("/manga/kapitel/(\\d{3,4})(?:/|\\\")")
            .findAll(document.outerHtml())
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it >= 1155 }
            .toList()
        val latest = numbers.maxOrNull()
            ?: Regex("Einträge\\s+(\\d{3,4})")
                .find(document.text())?.groupValues?.get(1)?.toIntOrNull()
            ?: error("Keine Kapitelnummer gefunden. Website-Struktur prüfen.")
        require(latest in 1155..2000) { "Unplausible Kapitelnummer $latest" }

        val result = (1155..latest).reversed().map { n ->
            SChapter.create().apply {
                url = "/manga/kapitel/$n/1"
                name = "Kapitel $n"
                chapter_number = n.toFloat()
            }
        }
        return SMangaUpdate(manga, result)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pageUrl = "$baseUrl${chapter.url}"
        val countJson = runWebView<String> {
            javaScriptEnabled = true
            domStorageEnabled = true
            onPageFinished {
                evaluateJs(PAGE_COUNT_JS) { value -> resolve(value) }
            }
            loadUrl(pageUrl)
        }
        val count = countJson.toIntOrNull()
            ?: error("Seitenzahl nicht erkannt: $countJson")
        require(count in 1..100) { "Unplausible Seitenzahl: $count" }
        val prefix = pageUrl.substringBeforeLast('/')
        return (1..count).map { number ->
            Page(index = number - 1, url = "$prefix/$number")
        }
    }

    override suspend fun getImageUrl(page: Page): String {
        val image = runWebView<String>(timeout = 30.seconds) {
            javaScriptEnabled = true
            domStorageEnabled = true
            blockImages = false
            // OnePiece-Tube inserts the reader image after navigation has finished.
            poll(500.milliseconds) {
                evaluateJs(IMAGE_URL_JS) { value ->
                    val url = runCatching {
                        Json.parseToJsonElement(value).jsonPrimitive.content
                    }.getOrNull()
                    if (url?.startsWith("https://") == true || url?.startsWith("http://") == true) {
                        resolve(url)
                    }
                }
            }
            loadUrl(page.url)
        }
        return image
    }

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .headers(headers.newBuilder().set("Referer", page.url).build())
        .build()

    companion object {
        private val PAGE_COUNT_JS = """
            (() => {
              const docs = [document];
              for (const f of document.querySelectorAll('iframe')) {
                try { if (f.contentDocument) docs.push(f.contentDocument); } catch(e) {}
              }
              const all = docs.flatMap(d => [...d.querySelectorAll('.page-item')]);
              const numbers = all.map(n => parseInt(n.textContent.trim(), 10)).filter(Number.isFinite);
              return numbers.length ? Math.max(...numbers) : 0;
            })()
        """.trimIndent()

        private val IMAGE_URL_JS = """
            (() => {
              const docs = [document];
              for (const frame of document.querySelectorAll('iframe')) {
                try { if (frame.contentDocument) docs.push(frame.contentDocument); } catch (e) {}
              }
              const images = docs.flatMap(doc => [...doc.images]);
              const preferred = images.filter(img => {
                const url = img.currentSrc || img.src || img.dataset.src || '';
                const width = img.naturalWidth || img.width || img.clientWidth || 0;
                const height = img.naturalHeight || img.height || img.clientHeight || 0;
                return /^https?:/.test(url) && width >= 300 && height >= 300 &&
                  !/logo|icon|avatar|favicon|banner|adserver/i.test(url);
              });
              const chosen = preferred.sort((a, b) =>
                (b.naturalWidth || b.width || 0) * (b.naturalHeight || b.height || 0) -
                (a.naturalWidth || a.width || 0) * (a.naturalHeight || a.height || 0)
              )[0];
              return chosen ? (chosen.currentSrc || chosen.src || chosen.dataset.src) : '';
            })()
        """.trimIndent()
    }
}
