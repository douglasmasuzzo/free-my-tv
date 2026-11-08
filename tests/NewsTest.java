package io.freemytv;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The news row: RSS parsing, the topic rotation, the cache and the source colours. */
public final class NewsTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    private static News.Story story(String source, String topic, String title, long time) {
        return new News.Story(source, topic, title, "https://example.com/" + title, time);
    }

    public static void main(String[] args) throws Exception {
        String rss = "<?xml version=\"1.0\"?><rss><channel><title>g1</title>"
                + "<item><title><![CDATA[Chuva em São Paulo &amp; trânsito]]></title>"
                + "<link>https://g1.globo.com/sp/noticia</link><pubDate>Tue, 31 Oct 2026 09:15:00 GMT</pubDate></item>"
                + "<item><title>  Política &lt;b&gt;hoje&lt;/b&gt;  </title><link><![CDATA[https://g1.globo.com/politica]]></link></item>"
                + "<item><title></title><link>x</link></item>"
                + "</channel></rss>";
        List<News.Story> parsed = News.parse(rss, "G1", News.COTIDIANO);
        check(parsed.size() == 2, "empty titles are dropped");
        check(parsed.get(0).title.equals("Chuva em São Paulo & trânsito"), "CDATA and entities");
        check(parsed.get(0).link.equals("https://g1.globo.com/sp/noticia"), "link");
        check(parsed.get(0).time > 0, "RFC 822 date");
        check(parsed.get(1).title.equals("Política hoje"), "tags and spacing");
        check(parsed.get(1).time == 0, "missing date");

        check(News.clean("Manchete - BandNews", "Band News").equals("Manchete"), "Google News source suffix");
        check(News.clean("Manchete - Band News", "Band News").equals("Manchete"), "source suffix with a space");
        check(News.clean("Manchete - Outro", "Band News").equals("Manchete - Outro"), "other suffix kept");
        check(News.clean("Governo &amp;#8216;fecha&amp;#8217; acordo", "G1").equals("Governo ‘fecha’ acordo"),
                "an entity encoded twice");
        check(News.clean("It&#8217;s &amp; more", "G1").equals("It’s & more"), "named and numeric entities together");
        check(News.clean("A &#x2014; B", "G1").equals("A — B"), "a hexadecimal entity");
        check(News.clean("S&#227;o Paulo", "G1").equals("São Paulo"), "a decimal letter");
        check(News.clean("&#xD800;", "G1").equals("&#xD800;"), "an invalid code point stays as it is");

        long now = 1_800_000_000_000L;
        List<News.Story> all = new ArrayList<>(Arrays.asList(
                story("G1", News.COTIDIANO, "Cotidiano 1", now - 1000),
                story("G1", News.POLITICA, "Política 1", now - 2000),
                story("G1", News.ECONOMIA, "Economia 1", now - 3000),
                story("G1", News.TECNOLOGIA, "Tecnologia 1", now - 4000),
                story("G1", News.COTIDIANO, "Cotidiano 2", now - 5000),
                story("CNN Brasil", News.POLITICA, "Política 2", now - 6000)));
        List<News.Story> selected = News.select(all, 4);
        check(selected.size() == 4, "the row's size");
        check(selected.get(0).topic.equals(News.COTIDIANO) && selected.get(1).topic.equals(News.POLITICA)
                && selected.get(2).topic.equals(News.ECONOMIA) && selected.get(3).topic.equals(News.TECNOLOGIA),
                "one topic at a time, in rotation");
        List<News.Story> more = News.select(all, 12);
        check(more.size() == 6, "everything fits");
        check(more.get(4).title.equals("Cotidiano 2"), "the rotation continues with the second cotidiano");
        check(News.select(Arrays.asList(
                story("G1", News.COTIDIANO, "Mesma", now), story("O Globo", News.COTIDIANO, "mesma", now - 1)), 12).size() == 1,
                "the same headline twice appears once");
        check(News.select(new ArrayList<News.Story>(), 12).isEmpty(), "no stories");
        check(News.select(all, 0).isEmpty(), "no room");

        check(News.color("G1") != News.color("Fox News"), "each source has its colour");
        check(News.color("desconhecida") == News.color("outra"), "unknown sources share one");

        File dir = Files.createTempDirectory("news-test").toFile();
        File cache = new File(dir, "news.tsv");
        List<News.Story> saved = Arrays.asList(
                new News.Story("G1", News.POLITICA, "Título\tcom tab\ne linha", "https://a?b=1&c=2", now),
                new News.Story("Fox News", News.TECNOLOGIA, "Back\\slash", "", 0));
        News.writeCache(cache, saved);
        List<News.Story> read = News.readCache(cache);
        check(read.size() == 2, "cache round trip");
        check(read.get(0).title.equals("Título\tcom tab\ne linha"), "tabs and newlines survive the cache");
        check(read.get(0).link.equals("https://a?b=1&c=2"), "link survives the cache");
        check(read.get(1).title.equals("Back\\slash"), "backslashes survive the cache");
        check(News.readCache(new File(dir, "missing.tsv")).isEmpty(), "no cache file");

        List<News.Story> mixed = Arrays.asList(
                story("G1", News.COTIDIANO, "Cotidiano", now - 1000),
                story("G1", News.TECNOLOGIA, "Tecnologia 1", now - 2000),
                story("Fox News", News.TECNOLOGIA, "Technology 2", now - 3000),
                story("G1", News.POLITICA, "Política", now - 4000));
        News.writeCache(cache, mixed);
        List<News.Story> tech = News.tech(cache, now);
        check(tech.size() == 2, "only technology stories");
        check(tech.get(0).title.equals("Tecnologia 1") && tech.get(1).title.equals("Technology 2"),
                "technology keeps the cache's order");
        cache.delete();
        dir.delete();

        // The cache is fresh while the newest story is younger than CACHE_MS (the fetch path needs no network here).
        check(News.CACHE_MS == 20 * 60_000L, "cache window");

        // The bottom row's two pages (R3): technology first, the quotes second, left/right walking between them.
        check(News.PAGES.length == 2 && News.PAGE_TECH.equals(News.PAGES[0]) && News.PAGE_QUOTES.equals(News.PAGES[1]),
                "technology first, the quotes second");
        check(News.page("").equals(News.PAGE_TECH), "an empty stored page is technology");
        check(News.page(News.PAGE_QUOTES).equals(News.PAGE_QUOTES), "the quotes page is kept");
        check(News.page("sepia").equals(News.PAGE_TECH), "an unknown page is technology");
        check(News.page(News.PAGE_TECH, true, true).equals(News.PAGE_TECH), "technology when both pages are ready");
        check(News.page(News.PAGE_QUOTES, true, true).equals(News.PAGE_QUOTES), "the quotes when they are asked for");
        check(News.page(News.PAGE_TECH, false, true).equals(News.PAGE_QUOTES), "no technology: the quotes' page");
        check(News.page(News.PAGE_QUOTES, true, false).equals(News.PAGE_TECH), "no quotes: technology's page");
        check(News.page(News.PAGE_TECH, false, false) == null && News.page(News.PAGE_QUOTES, false, false) == null,
                "nothing ready: no page at all");
        check(News.page(News.PAGE_TECH, false, true).equals(News.PAGE_QUOTES)
                && News.page(News.PAGE_TECH, true, true).equals(News.PAGE_TECH),
                "the fallback doesn't change the stored preference");
        check(News.step(News.PAGE_TECH, 1).equals(News.PAGE_QUOTES), "right from technology");
        check(News.step(News.PAGE_QUOTES, -1).equals(News.PAGE_TECH), "left from the quotes");
        check(News.step(News.PAGE_TECH, -1).equals(News.PAGE_QUOTES), "left from technology wraps to the quotes");
        check(News.step(News.PAGE_QUOTES, 1).equals(News.PAGE_TECH), "right from the quotes wraps to technology");
        check(News.step(News.PAGE_TECH, 0).equals(News.PAGE_TECH), "no direction: no change");

        System.out.println("News: " + assertions + " assertions passed");
    }
}
