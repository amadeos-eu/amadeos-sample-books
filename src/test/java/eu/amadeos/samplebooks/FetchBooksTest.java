package eu.amadeos.samplebooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FetchBooksTest {
    @TempDir
    Path root;

    private static void epub(Path file, String title, String language) throws IOException {
        Files.createDirectories(file.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            put(zip, "mimetype", "application/epub+zip");
            put(zip, "META-INF/container.xml", "<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'>"
                    + "<rootfiles><rootfile full-path='OEBPS/content.opf'/></rootfiles></container>");
            put(zip, "OEBPS/content.opf", "<package xmlns='http://www.idpf.org/2007/opf'>"
                    + "<metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>" + title + "</dc:title>"
                    + "<dc:language>" + language + "</dc:language></metadata></package>");
        }
    }

    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        OutputStream out = zip;
        out.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void list(String... lines) throws IOException {
        Files.write(root.resolve("books.tsv"), List.of(lines));
    }

    @Test
    void titlesMatchWithoutAccentsAndCase() {
        assertTrue(FetchBooks.titleMatches("Les misérables Tome I", "Les Misérables, Tome I: Fantine"));
        assertTrue(FetchBooks.titleMatches("Kinder- und Hausmärchen", "Kinder- und Hausmaerchen Band 1".replace("ae", "ä")));
        assertTrue(FetchBooks.titleMatches("Faust", "Faust: Der Tragödie erster Teil"));
        assertFalse(FetchBooks.titleMatches("Inferno", "Il Purgatorio"));
        assertEquals("alice s adventures in wonderland", FetchBooks.fold("Alice’s Adventures in Wonderland"));
    }

    @Test
    void urlsOfTheSources() {
        assertEquals("https://www.gutenberg.org/cache/epub/800/pg800-images-3.epub",
                FetchBooks.url(new FetchBooks.Book(0, "fr", "x", "Jules Verne", "Le tour", "gutenberg", "800")));
        assertEquals("https://standardebooks.org/ebooks/a/b/downloads/a_b.epub?source=download",
                FetchBooks.url(new FetchBooks.Book(0, "en", "x", "A", "B", "standardebooks",
                        "https://standardebooks.org/ebooks/a/b/downloads/a_b.epub")));
    }

    @Test
    void readsTheEntriesOfGutenbergsSearch() {
        String feed = """
                <feed xmlns="http://www.w3.org/2005/Atom">
                <id>https://www.gutenberg.org/ebooks/search.opds/?query=grimm</id>
                <entry><title>Sort Alphabetically by Title</title><id>https://www.gutenberg.org/ebooks/search.opds/?sort_order=title</id></entry>
                <entry><updated>2026-10-23</updated><title>Kinder- und Hausm&#228;rchen &amp; mehr</title>
                <content type="text">Jacob Grimm</content><id>https://www.gutenberg.org/ebooks/2591.opds</id></entry>
                <entry><title type="text">Grimms Märchen</title><id>https://www.gutenberg.org/ebooks/5314.opds</id></entry>
                </feed>""";
        var entries = FetchBooks.entries(feed);
        assertEquals(List.of("2591", "5314"), List.copyOf(entries.keySet()));
        assertEquals("Grimms Märchen", entries.get("5314"));
        assertTrue(entries.get("2591").endsWith("& mehr"));
    }

    @Test
    void checksLanguageTitleChecksumsAndStrayFiles() throws Exception {
        list("# comment", "de\tdie-verwandlung\tFranz Kafka\tDie Verwandlung\tgutenberg\t22367",
                "fr\tcandide\tVoltaire\tCandide\tgutenberg\t4650");
        FetchBooks fetch = new FetchBooks(root);
        assertEquals(2, fetch.books().size());
        assertEquals(List.of("books/de/die-verwandlung.epub is missing", "books/fr/candide.epub is missing"), fetch.check());

        epub(root.resolve("books/de/die-verwandlung.epub"), "Die Verwandlung", "de");
        epub(root.resolve("books/fr/candide.epub"), "Candide, ou l'optimisme", "en");
        epub(root.resolve("books/fr/extra.epub"), "Extra", "fr");
        Files.writeString(root.resolve("books.sha256"),
                FetchBooks.sha256(root.resolve("books/de/die-verwandlung.epub")) + "  books/de/die-verwandlung.epub\n");
        assertEquals(List.of("books/fr/candide.epub: language en, expected fr",
                "books/fr/extra.epub is not listed in books.tsv",
                "books/fr/candide.epub does not match books.sha256"), fetch.check());
    }
}
