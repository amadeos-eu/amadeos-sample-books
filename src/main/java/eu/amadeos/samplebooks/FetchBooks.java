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
 * A Project Gutenberg book given as "auto" is looked up by author, title and language (gutendex.com), and the
 * number found is written back into books.tsv, so the next run downloads the same book. Each download must be an
 * EPUB in the listed language whose title matches the listed one.
 */
public final class FetchBooks {
    static final String AGENT = "amadeos-sample-books/1.0 (+https://github.com/datazuul/amadeos-sample-books)";

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
        if (!options.contains("--check")) fetch.download(options.contains("--refresh"));
        List<String> problems = fetch.check();
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

    void download(boolean refresh) throws Exception {
        List<Book> books = books();
        for (Book book : books) {
            Path file = book.file(root);
            boolean auto = book.source().equals("gutenberg") && book.ref().equals("auto");
            if (Files.isRegularFile(file) && !refresh && !auto) continue;
            if (auto) {
                book = book.withRef(lookup(book));
                writeBack(book);
            }
            String url = url(book);
            System.out.println("  " + book.lang() + "  " + book.name() + "  <- " + url);
            byte[] data = get(url);
            Files.createDirectories(file.getParent());
            Files.write(file, data);
            Thread.sleep(2000); // be gentle with the servers
        }
        StringBuilder sums = new StringBuilder();
        for (Book book : books()) {
            Path file = book.file(root);
            if (Files.isRegularFile(file)) sums.append(sha256(file)).append("  ").append(relative(file)).append('\n');
        }
        Files.writeString(root.resolve("books.sha256"), sums);
    }

    static String url(Book book) {
        return switch (book.source()) {
            case "standardebooks" -> book.ref() + "?source=download";
            case "gutenberg" -> "https://www.gutenberg.org/cache/epub/" + book.ref() + "/pg" + book.ref() + "-images-3.epub";
            default -> throw new IllegalArgumentException("Unknown source " + book.source() + " for " + book.name());
        };
    }

    private byte[] get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", AGENT)
                .timeout(Duration.ofMinutes(3)).build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new IOException(url + ": HTTP " + response.statusCode());
        return response.body();
    }

    private static final Pattern RESULT = Pattern.compile("\"id\":\\s*(\\d+),\\s*\"title\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** The most downloaded Gutenberg book for the author, title and language; tries narrower queries first. */
    private String lookup(Book book) throws IOException, InterruptedException {
        String[] names = book.author().split(" ");
        String lastName = book.author().toLowerCase(Locale.ROOT).startsWith("anon") ? "" : names[names.length - 1];
        Set<String> queries = new LinkedHashSet<>();
        queries.add((lastName + " " + book.title()).strip());
        queries.add(book.title());
        for (String word : book.title().split("[^\\p{L}]+")) {
            if (word.length() >= 5) queries.add((lastName + " " + word).strip());
        }
        List<String> seen = new ArrayList<>();
        for (String query : queries) {
            String url = "https://gutendex.com/books/?languages=" + book.lang() + "&search="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8);
            String json = new String(get(url), StandardCharsets.UTF_8);
            Matcher m = RESULT.matcher(json);
            while (m.find()) {
                String title = m.group(2).replace("\\\"", "\"");
                seen.add("#" + m.group(1) + " " + title);
                if (titleMatches(book.title(), title)) {
                    System.out.println("  lookup '" + query + "' (" + book.lang() + "): #" + m.group(1) + " " + title);
                    return m.group(1);
                }
            }
        }
        throw new IOException("No Gutenberg book found for " + book.author() + ", " + book.title() + " (" + book.lang()
                + "); seen: " + String.join("; ", seen.subList(0, Math.min(10, seen.size()))));
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

    /** Whether a title shares a word of five or more letters with the expected one (accents and case ignored). */
    static boolean titleMatches(String expected, String title) {
        Set<String> words = new HashSet<>(Arrays.asList(fold(title).split(" ")));
        List<String> wanted = Arrays.stream(fold(expected).split(" ")).filter(w -> w.length() >= 4).toList();
        return wanted.isEmpty() || wanted.stream().anyMatch(words::contains);
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
