package eu.amadeos.samplebooks;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Downloads the books listed in books.tsv into books/&lt;language&gt;/&lt;name&gt;.epub and checks them.
 *
 * <pre>
 * ./gradlew fetchBooks                download the missing books, check all, write books.sha256
 * ./gradlew fetchBooks -Prefresh      download all books again
 * ./gradlew checkBooks                only check the files against books.tsv and books.sha256 (no network)
 * </pre>
 *
 * A Project Gutenberg book given as "auto" is looked up by author and title in Gutenberg's own search, and the
 * number found is written back into books.tsv, so the next run downloads the same book. Each download must be an
 * EPUB in the listed language whose title matches the listed one. Busy servers are tried again; a book that still
 * fails is reported at the end, and the books fetched so far are kept.
 */
public final class FetchBooks {
    static final String AGENT = "amadeos-sample-books/1.0 (+https://github.com/amadeos-eu/amadeos-sample-books)";

    /** One line of books.tsv. */
    record Book(int line, String lang, String name, String author, String title, String source, String ref) {
        Path file(Path root) {
            return root.resolve("books").resolve(lang).resolve(name + ".epub");
        }

        Book withRef(String newRef) {
            return new Book(line, lang, name, author, title, source, newRef);
        }
    }

    /** Title and language from an EPUB's package document. */
    record Metadata(String title, String language) {
    }

    private final Path root;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30)).build();

    FetchBooks(Path root) {
        this.root = root;
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("books.root", ".")).toAbsolutePath().normalize();
        List<String> options = Arrays.asList(args);
        FetchBooks fetch = new FetchBooks(root);
        List<String> problems = new ArrayList<>();
        if (!options.contains("--check")) problems.addAll(fetch.download(options.contains("--refresh")));
        problems.addAll(fetch.check());
        problems.forEach(p -> System.err.println("PROBLEM: " + p));
        if (!problems.isEmpty()) System.exit(1);
    }

    // ------------------------------------------------------------------ the list

    List<Book> books() throws IOException {
        List<String> lines = Files.readAllLines(root.resolve("books.tsv"));
        List<Book> books = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\t");
            if (f.length != 6) throw new IOException("books.tsv line " + (i + 1) + ": 6 tab-separated columns expected");
            books.add(new Book(i, f[0], f[1], f[2], f[3], f[4], f[5]));
        }
        return books;
    }

    private void writeBack(Book book) throws IOException {
        Path list = root.resolve("books.tsv");
        List<String> lines = new ArrayList<>(Files.readAllLines(list));
        String[] f = lines.get(book.line()).split("\t");
        f[5] = book.ref();
        lines.set(book.line(), String.join("\t", f));
        Files.write(list, lines);
    }

    // ------------------------------------------------------------------ downloading

    /** Downloads the missing books; a book that fails is reported and the others are still fetched. */
    List<String> download(boolean refresh) throws Exception {
        List<String> failures = new ArrayList<>();
        for (Book book : books()) {
            Path file = book.file(root);
            boolean auto = book.source().equals("gutenberg") && book.ref().equals("auto");
            if (Files.isRegularFile(file) && !refresh && !auto) continue;
            try {
                byte[] data;
                if (auto) {
                    Candidate found = lookup(book);
                    book = book.withRef(found.ref());
                    data = found.data();
                    writeBack(book);
                } else {
                    String url = url(book);
                    System.out.println("  " + book.lang() + "  " + book.name() + "  <- " + url);
                    data = get(url);
                }
                Files.createDirectories(file.getParent());
                Files.write(file, data);
            } catch (IOException e) {
                failures.add(book.lang() + "/" + book.name() + " not fetched: " + e.getMessage());
                System.err.println("  " + book.lang() + "  " + book.name() + "  FAILED: " + e.getMessage());
            }
            Thread.sleep(2000); // be gentle with the servers
        }
        StringBuilder sums = new StringBuilder();
        for (Book book : books()) {
            Path file = book.file(root);
            if (Files.isRegularFile(file)) sums.append(sha256(file)).append("  ").append(relative(file)).append('\n');
        }
        Files.writeString(root.resolve("books.sha256"), sums);
        return failures;
    }

    static String url(Book book) {
        return switch (book.source()) {
            case "standardebooks" -> book.ref() + "?source=download";
            case "gutenberg" -> "https://www.gutenberg.org/cache/epub/" + book.ref() + "/pg" + book.ref() + "-images-3.epub";
            default -> throw new IllegalArgumentException("Unknown source " + book.source() + " for " + book.name());
        };
    }

    /** GET with up to four attempts: busy servers (HTTP 429 and 5xx) and broken connections are tried again. */
    private byte[] get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", AGENT)
                .timeout(Duration.ofMinutes(3)).build();
        IOException last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            if (attempt > 1) Thread.sleep(5000L << (2 * (attempt - 2))); // 5 s, 20 s, 80 s
            try {
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status == 200) return response.body();
                last = new IOException(url + ": HTTP " + status);
                if (status != 429 && status < 500) throw last;
            } catch (IOException e) {
                if (e == last) throw e;
                last = new IOException(url + ": " + e, e);
            }
            System.err.println("    attempt " + attempt + ": " + last.getMessage());
        }
        throw last;
    }

    /** A Gutenberg book found by {@link #lookup}, already downloaded. */
    record Candidate(String ref, String title, byte[] data) {
    }

    /** An entry of Gutenberg's OPDS search: the ebook number and the title. */
    private static final Pattern ENTRY = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL);
    private static final Pattern ENTRY_TITLE = Pattern.compile("<title[^>]*>([^<]*)</title>");
    private static final Pattern ENTRY_ID = Pattern.compile("<id>[^<]*/ebooks/(\\d+)(?:\\.opds)?</id>");

    /**
     * Looks the book up in Project Gutenberg's own search (OPDS), narrower queries first. A result whose title matches
     * is downloaded and taken when the EPUB is in the book's language and its title matches too.
     */
    private Candidate lookup(Book book) throws IOException, InterruptedException {
        String[] names = book.author().split(" ");
        String lastName = book.author().toLowerCase(Locale.ROOT).startsWith("an") && names.length == 1 ? ""
                : names[names.length - 1];
        Set<String> queries = new LinkedHashSet<>();
        for (String title : titles(book.title())) {
            queries.add((lastName + " " + title).strip());
            for (String word : title.split("[^\\p{L}]+")) {
                if (word.length() >= 5) queries.add((lastName + " " + word).strip());
            }
            queries.add(title);
        }
        if (!lastName.isEmpty()) queries.add(lastName); // all books of the author, when no title query finds one
        Set<String> tried = new LinkedHashSet<>();
        List<String> seen = new ArrayList<>();
        Set<String> others = new LinkedHashSet<>();
        for (String query : queries) {
            String url = "https://www.gutenberg.org/ebooks/search.opds/?query="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8);
            String feed = new String(get(url), StandardCharsets.UTF_8);
            for (Map.Entry<String, String> entry : entries(feed).entrySet()) {
                String id = entry.getKey();
                String title = entry.getValue();
                if (!titleMatches(book.title(), title)) {
                    others.add("#" + id + " " + title);
                    continue;
                }
                if (!tried.add(id)) continue;
                if (tried.size() > 8) break;
                Candidate c = candidate(book, id, title);
                seen.add("#" + id + " " + title + (c == null ? " (rejected)" : ""));
                if (c != null) {
                    System.out.println("  " + book.lang() + "  " + book.name() + "  found '" + query + "': #" + id + " "
                            + title);
                    return c;
                }
            }
            Thread.sleep(1000);
        }
        throw new IOException("No Gutenberg book found for " + book.author() + ", " + book.title() + " (" + book.lang()
                + "); tried: " + (seen.isEmpty() ? "nothing matching" : String.join("; ", seen))
                + "; other results: " + (others.isEmpty() ? "none" : String.join("; ", others.stream().limit(25).toList())));
    }

    /** The ebook numbers and titles of an OPDS search result, in its order. */
    static Map<String, String> entries(String feed) {
        Map<String, String> entries = new LinkedHashMap<>();
        Matcher m = ENTRY.matcher(feed);
        while (m.find()) {
            Matcher t = ENTRY_TITLE.matcher(m.group(1));
            Matcher i = ENTRY_ID.matcher(m.group(1));
            if (t.find() && i.find()) entries.putIfAbsent(i.group(1), unescape(t.group(1)));
        }
        return entries;
    }

    /** The downloaded book if its EPUB is in the expected language and has a matching title, else null. */
    private Candidate candidate(Book book, String id, String title) throws IOException, InterruptedException {
        String url = url(book.withRef(id));
        System.out.println("  " + book.lang() + "  " + book.name() + "  trying #" + id + " " + title + "  <- " + url);
        byte[] data;
        try {
            data = get(url);
        } catch (IOException e) {
            System.err.println("    " + e.getMessage());
            return null;
        }
        Path temp = Files.createTempFile("book", ".epub");
        try {
            Files.write(temp, data);
            Metadata meta = metadata(temp);
            boolean ok = meta.language().toLowerCase(Locale.ROOT).startsWith(book.lang())
                    && titleMatches(book.title(), meta.title());
            System.out.println("    " + meta.title() + " [" + meta.language() + "]" + (ok ? "" : " rejected"));
            return ok ? new Candidate(id, meta.title(), data) : null;
        } catch (Exception e) {
            System.err.println("    no EPUB: " + e.getMessage());
            return null;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String unescape(String xml) {
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(xml);
        xml = m.replaceAll(r -> Matcher.quoteReplacement(Character.toString(
                Integer.parseInt(r.group(2), r.group(1).isEmpty() ? 10 : 16))));
        return xml.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    // ------------------------------------------------------------------ checking

    List<String> check() throws Exception {
        List<String> problems = new ArrayList<>();
        List<Book> books = books();
        Set<String> listed = new HashSet<>();
        for (Book book : books) {
            Path file = book.file(root);
            listed.add(relative(file));
            if (!Files.isRegularFile(file)) {
                problems.add(relative(file) + " is missing");
                continue;
            }
            Metadata m;
            try {
                m = metadata(file);
            } catch (Exception e) {
                problems.add(relative(file) + " is no EPUB: " + e.getMessage());
                continue;
            }
            System.out.printf("%s  %6d KB  %s  [%s]%n", book.lang(), Files.size(file) / 1024, m.title(), m.language());
            if (!m.language().toLowerCase(Locale.ROOT).startsWith(book.lang())) {
                problems.add(relative(file) + ": language " + m.language() + ", expected " + book.lang());
            }
            if (!titleMatches(book.title(), m.title())) {
                problems.add(relative(file) + ": title '" + m.title() + "' does not match '" + book.title() + "'");
            }
        }
        Path booksDir = root.resolve("books");
        if (Files.isDirectory(booksDir)) {
            try (Stream<Path> files = Files.walk(booksDir)) {
                files.filter(p -> p.toString().endsWith(".epub")).map(this::relative).sorted()
                        .filter(p -> !listed.contains(p)).forEach(p -> problems.add(p + " is not listed in books.tsv"));
            }
        }
        Map<String, String> sums = new LinkedHashMap<>();
        Path sumFile = root.resolve("books.sha256");
        if (Files.isRegularFile(sumFile)) {
            for (String line : Files.readAllLines(sumFile)) {
                String[] f = line.split("\\s+", 2);
                if (f.length == 2) sums.put(f[1], f[0]);
            }
        }
        for (Book book : books) {
            Path file = book.file(root);
            if (Files.isRegularFile(file) && !sha256(file).equals(sums.get(relative(file)))) {
                problems.add(relative(file) + " does not match books.sha256");
            }
        }
        return problems;
    }

    static Metadata metadata(Path file) throws Exception {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Document container = parse(zip, "META-INF/container.xml");
            String opfPath = ((Element) container.getElementsByTagNameNS("*", "rootfile").item(0)).getAttribute("full-path");
            Document opf = parse(zip, opfPath);
            return new Metadata(first(opf, "title"), first(opf, "language"));
        }
    }

    private static Document parse(ZipFile zip, String name) throws Exception {
        var entry = zip.getEntry(name);
        if (entry == null) throw new IOException(name + " is missing");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try (InputStream in = zip.getInputStream(entry)) {
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private static String first(Document opf, String dcName) {
        NodeList nodes = opf.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", dcName);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().strip();
    }

    /** The titles of a books.tsv title column: one title or several alternatives separated by " | ". */
    static List<String> titles(String column) {
        return Arrays.stream(column.split("\\|")).map(String::strip).filter(t -> !t.isEmpty()).toList();
    }

    /**
     * Whether a title shares a word of four or more letters with the expected one, or with one of its alternatives
     * (accents and case ignored).
     */
    static boolean titleMatches(String expected, String title) {
        Set<String> words = new HashSet<>(Arrays.asList(fold(title).split(" ")));
        for (String alternative : titles(expected)) {
            List<String> wanted = Arrays.stream(fold(alternative).split(" ")).filter(w -> w.length() >= 4).toList();
            if (wanted.isEmpty() || wanted.stream().anyMatch(words::contains)) return true;
        }
        return false;
    }

    /** Lower case without accents and punctuation. */
    static String fold(String text) {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return plain.replaceAll("[^a-z0-9]+", " ").strip();
    }

    static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String relative(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
