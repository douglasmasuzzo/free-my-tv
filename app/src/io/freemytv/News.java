package io.freemytv;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * The day's headlines for the home screen: G1, CNN Brasil, O Globo, Fox News and Band News, grouped by the topics the
 * home row mixes (Brazilian daily life, politics, economy, technology). RSS only, no account and no key.
 *
 * Kept free of Android types (a File for the cache) so tests/NewsTest can run it on a plain JVM.
 */
final class News {
    private News() {}

    /** A headline, as one card on the home screen. */
    static final class Story {
        String source, topic, title, link;
        long time;   // epoch millis, 0 when the feed gave none

        Story(String source, String topic, String title, String link, long time) {
            this.source = source;
            this.topic = topic;
            this.title = title;
            this.link = link;
            this.time = time;
        }
    }

    /** One RSS feed: the source's name (as shown), its address and the topic its stories belong to. */
    static final class Feed {
        final String source, url, topic;

        Feed(String source, String url, String topic) {
            this.source = source;
            this.url = url;
            this.topic = topic;
        }
    }

    static final String COTIDIANO = "cotidiano", POLITICA = "política", ECONOMIA = "economia", TECNOLOGIA = "tecnologia";

    /** The feeds, in the order their topics are mixed on the row. Band News blocks plain readers, so Google News
     *  carries its headlines (the card still says Band News and opens the article's address). */
    static final Feed[] FEEDS = {
        new Feed("G1", "https://g1.globo.com/rss/g1/", COTIDIANO),
        new Feed("G1", "https://g1.globo.com/rss/g1/politica/", POLITICA),
        new Feed("G1", "https://g1.globo.com/rss/g1/economia/", ECONOMIA),
        new Feed("G1", "https://g1.globo.com/rss/g1/tecnologia/", TECNOLOGIA),
        new Feed("CNN Brasil", "https://www.cnnbrasil.com.br/feed/", COTIDIANO),
        new Feed("O Globo", "https://oglobo.globo.com/rss/oglobo", COTIDIANO),
        new Feed("Fox News", "https://moxie.foxnews.com/google-publisher/latest.xml", COTIDIANO),
        new Feed("Fox News", "https://moxie.foxnews.com/google-publisher/tech.xml", TECNOLOGIA),
        new Feed("Band News", "https://news.google.com/rss/search?q=site%3Abandnewstv.uol.com.br&hl=pt-BR&gl=BR&ceid=BR:pt-419", COTIDIANO),
        new Feed("Band News", "https://news.google.com/rss/search?q=site%3Abandnewstv.uol.com.br+tecnologia&hl=pt-BR&gl=BR&ceid=BR:pt-419", TECNOLOGIA),
    };

    /** Topics in the order the row rotates them: one from each before a second from any. */
    static final String[] TOPICS = {COTIDIANO, POLITICA, ECONOMIA, TECNOLOGIA};

    static final long CACHE_MS = 20 * 60_000L;   // a feed's headlines change by the minute; 20 is plenty for a TV

    /** The headlines to show: the cache while it is fresh, a fresh read otherwise (falling back to a stale cache). */
    static List<Story> load(File cache, long now) {
        List<Story> saved = readCache(cache);
        if (!saved.isEmpty() && now - newest(saved) >= 0 && now - newest(saved) < CACHE_MS) return saved;
        List<Story> fresh = fetch();
        if (!fresh.isEmpty()) {
            writeCache(cache, fresh);
            return fresh;
        }
        return saved;
    }

    private static long newest(List<Story> stories) {
        long t = 0;
        for (Story s : stories) t = Math.max(t, s.time);
        return t;
    }

    /** The technology stories in the same cached list (the home screen's ticker and card), newest first. */
    static List<Story> tech(File cache, long now) {
        List<Story> out = new ArrayList<>();
        for (Story s : load(cache, now)) if (TECNOLOGIA.equals(s.topic)) out.add(s);
        return out;
    }

    /** Reads every feed at once (each with its own timeout) and mixes the newest stories by topic. */
    static List<Story> fetch() {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(5, FEEDS.length));
        try {
            List<Future<List<Story>>> futures = new ArrayList<>(FEEDS.length);
            for (final Feed f : FEEDS) {
                futures.add(pool.submit(new Callable<List<Story>>() {
                    @Override public List<Story> call() {
                        return read(f);
                    }
                }));
            }
            List<Story> all = new ArrayList<>();
            for (Future<List<Story>> future : futures) {
                try {
                    all.addAll(future.get(12, TimeUnit.SECONDS));
                } catch (Exception ignored) {
                    // one feed down: the others still fill the row
                }
            }
            return select(all, 12);
        } finally {
            pool.shutdownNow();
        }
    }

    /** One feed's stories, parsed; empty when it can't be read (no network, a 403, a timeout). */
    static List<Story> read(Feed feed) {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(feed.url).openConnection();
            http.setConnectTimeout(8_000);
            http.setReadTimeout(8_000);
            http.setInstanceFollowRedirects(true);
            http.setRequestProperty("User-Agent", "FreeMyTV/1.3 (Android TV)");
            http.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.5");
            http.setRequestProperty("Accept-Encoding", "gzip");
            int code = http.getResponseCode();
            if (code < 200 || code >= 300) return Collections.emptyList();
            InputStream in = http.getInputStream();
            if ("gzip".equalsIgnoreCase(http.getContentEncoding())) in = new GZIPInputStream(in);
            String xml = new String(bytes(in), StandardCharsets.UTF_8);
            return parse(xml, feed.source, feed.topic);
        } catch (IOException | RuntimeException e) {
            return Collections.emptyList();
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private static byte[] bytes(InputStream in) throws IOException {
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static final Pattern ITEM = Pattern.compile("<item(?:\\s[^>]*)?>(.*?)</item>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE = Pattern.compile("<title(?:\\s[^>]*)?>(.*?)</title>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK = Pattern.compile("<link(?:\\s[^>]*)?>(.*?)</link>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile("<pubDate(?:\\s[^>]*)?>(.*?)</pubDate>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** The stories in an RSS document, oldest dropped when there are more than `max` (the row shows the newest). */
    static List<Story> parse(String xml, String source, String topic) {
        List<Story> out = new ArrayList<>();
        Matcher item = ITEM.matcher(xml == null ? "" : xml);
        while (item.find()) {
            String body = item.group(1);
            String title = text(TITLE, body);
            if (title.isEmpty()) continue;
            String link = text(LINK, body);
            long time = date(text(DATE, body));
            title = clean(title, source);
            if (title.isEmpty()) continue;
            out.add(new Story(source, topic, title, link, time));
        }
        return out;
    }

    private static String text(Pattern p, String body) {
        Matcher m = p.matcher(body);
        return m.find() ? m.group(1) : "";
    }

    /** CDATA, HTML entities and the whitespace a feed leaves around a title. */
    static String clean(String raw, String source) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("<![CDATA[")) {
            s = s.substring(9);
            if (s.endsWith("]]>")) s = s.substring(0, s.length() - 3);
        }
        s = s.replace("<![CDATA[", "").replace("]]>", "");
        s = unescape(s).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        // Google News appends " - BandNews" to every headline: the card already names the source.
        String compact = source.replaceAll("\\s+", "");
        if (s.endsWith(" - " + source)) s = s.substring(0, s.length() - source.length() - 3).trim();
        else if (s.toLowerCase(Locale.ROOT).endsWith(" - " + compact.toLowerCase(Locale.ROOT)))
            s = s.substring(0, s.length() - compact.length() - 3).trim();
        return s;
    }

    private static final Pattern ENTITY = Pattern.compile("&#([xX]?[0-9a-fA-F]+);");

    /** Twice: feeds sometimes encode a quote twice ("&amp;#8216;"), and one pass would leave "&#8216;" on screen. */
    private static String unescape(String s) {
        return unescapeOnce(unescapeOnce(s));
    }

    private static String unescapeOnce(String s) {
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&nbsp;", " ").replace("&amp;", "&");
        Matcher m = ENTITY.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String code = m.group(1);
            int at;
            boolean hex = code.startsWith("x") || code.startsWith("X");
            try {
                at = Integer.parseInt(hex ? code.substring(1) : code, hex ? 16 : 10);
            } catch (NumberFormatException e) {
                at = -1;
            }
            boolean valid = at >= 0 && at <= 0x10FFFF && (at < 0xD800 || at > 0xDFFF);
            m.appendReplacement(out, Matcher.quoteReplacement(valid ? new String(Character.toChars(at)) : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** RFC 822's date ("Tue, 31 Oct 2026 09:15:00 GMT"), or 0 when it can't be read. */
    static long date(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return 0;
        String[] formats = {
            "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm Z",
            "dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yy HH:mm:ss zzz",
        };
        for (String format : formats) {
            try {
                return new java.text.SimpleDateFormat(format, Locale.US).parse(s).getTime();
            } catch (java.text.ParseException ignored) {
                // try the next shape
            }
        }
        return 0;
    }

    /**
     * The newest stories, one topic at a time in rotation, so a single busy source doesn't fill the row. Repeated
     * headlines (the same story in two G1 sections) appear once.
     */
    static List<Story> select(List<Story> stories, int max) {
        List<Story> sorted = new ArrayList<>(stories);
        Collections.sort(sorted, (a, b) -> Long.compare(b.time, a.time));
        Map<String, List<Story>> byTopic = new HashMap<>();
        for (String t : TOPICS) byTopic.put(t, new ArrayList<Story>());
        List<Story> other = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Story s : sorted) {
            String key = s.title.toLowerCase(Locale.ROOT);
            if (s.title.isEmpty() || !seen.add(key)) continue;
            List<Story> list = byTopic.get(s.topic);
            (list != null ? list : other).add(s);
        }
        List<Story> out = new ArrayList<>();
        int[] at = new int[TOPICS.length];
        while (out.size() < max) {
            boolean added = false;
            for (int i = 0; i < TOPICS.length && out.size() < max; i++) {
                List<Story> list = byTopic.get(TOPICS[i]);
                if (at[i] < list.size()) {
                    out.add(list.get(at[i]++));
                    added = true;
                }
            }
            if (!added) break;
        }
        for (Story s : other) {
            if (out.size() >= max) break;
            out.add(s);
        }
        return out;
    }

    /** A source's brand colour, for its card on the home screen (dark enough for white text). */
    static int color(String source) {
        if ("G1".equals(source)) return 0xFFC4170C;
        if ("CNN Brasil".equals(source)) return 0xFFB00020;
        if ("O Globo".equals(source)) return 0xFF0B5AA5;
        if ("Fox News".equals(source)) return 0xFF0C2340;
        if ("Band News".equals(source)) return 0xFF1B3F8B;
        return 0xFF3A3A3C;
    }

    // MARK: - Cache (a tab-separated file: one story per line)

    static List<Story> readCache(File file) {
        List<Story> out = new ArrayList<>();
        if (file == null || !file.isFile()) return out;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length < 5) continue;
                try {
                    out.add(new Story(unescapeField(f[0]), unescapeField(f[1]), unescapeField(f[3]), unescapeField(f[4]), Long.parseLong(f[2])));
                } catch (NumberFormatException ignored) {
                    // a damaged line: skip it, the rest still show
                }
            }
        } catch (IOException ignored) {
            // no cache yet
        }
        return out;
    }

    static void writeCache(File file, List<Story> stories) {
        if (file == null) return;
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer out = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (Story s : stories) {
                out.write(escapeField(s.source) + "\t" + escapeField(s.topic) + "\t" + s.time + "\t"
                        + escapeField(s.title) + "\t" + escapeField(s.link) + "\n");
            }
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            tmp.renameTo(file);
        }
    }

    private static String escapeField(String s) {
        return (s == null ? "" : s).replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescapeField(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                out.append(n == 't' ? '\t' : n == 'n' ? '\n' : n == 'r' ? '\r' : n);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
