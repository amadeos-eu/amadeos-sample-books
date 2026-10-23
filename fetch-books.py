#!/usr/bin/env python3
"""Downloads the books listed in books.tsv into books/<language>/<name>.epub and checks them.

    ./fetch-books.py            download the books that are missing, check all, write books.sha256
    ./fetch-books.py --refresh  download all books again
    ./fetch-books.py --check    only check the files against books.tsv and books.sha256 (no network)

A Project Gutenberg book given as "auto" is looked up by author, title and language (gutendex.com),
and the number found is written back into books.tsv, so the next run downloads the same book.
Each download must be an EPUB whose language is the one listed and whose title matches the listed one.
"""
import hashlib
import json
import re
import sys
import time
import unicodedata
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
LIST = ROOT / "books.tsv"
SUMS = ROOT / "books.sha256"
BOOKS = ROOT / "books"
AGENT = "amadeos-sample-books/1.0 (+https://github.com/datazuul/amadeos-sample-books)"
DC = "{http://purl.org/dc/elements/1.1/}"


def entries():
    """The books of books.tsv as dicts, with the line number for writing back."""
    result = []
    for number, line in enumerate(LIST.read_text(encoding="utf-8").splitlines()):
        if not line.strip() or line.startswith("#"):
            continue
        lang, name, author, title, source, ref = line.split("\t")
        result.append(dict(line=number, lang=lang, name=name, author=author, title=title, source=source, ref=ref))
    return result


def path_of(book):
    return BOOKS / book["lang"] / (book["name"] + ".epub")


def fold(text):
    """Lower case without accents and punctuation, for comparing titles."""
    text = unicodedata.normalize("NFD", text)
    text = "".join(c for c in text if not unicodedata.combining(c)).lower()
    return re.sub(r"[^a-z0-9]+", " ", text).strip()


def get(url):
    request = urllib.request.Request(url, headers={"User-Agent": AGENT})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def lookup(book):
    """The most downloaded Gutenberg book with an EPUB for the author, title and language."""
    last_name = book["author"].split()[-1]
    query = book["title"] if book["author"].lower().startswith("anon") else f"{last_name} {book['title']}"
    url = "https://gutendex.com/books/?" + urllib.parse.urlencode({"search": query, "languages": book["lang"]})
    results = json.loads(get(url))["results"]
    for r in results:
        if any(k.startswith("application/epub+zip") for k in r["formats"]):
            print(f"  lookup '{query}' ({book['lang']}): #{r['id']} {r['title']!r}")
            return str(r["id"])
    raise SystemExit(f"No Gutenberg EPUB found for {query!r} in {book['lang']}; candidates: "
                     + ", ".join(f"#{r['id']} {r['title']!r}" for r in results[:5]))


def write_back(book, ref):
    lines = LIST.read_text(encoding="utf-8").splitlines()
    fields = lines[book["line"]].split("\t")
    fields[5] = ref
    lines[book["line"]] = "\t".join(fields)
    LIST.write_text("\n".join(lines) + "\n", encoding="utf-8")


def url_of(book):
    if book["source"] == "standardebooks":
        return book["ref"] + "?source=download"
    if book["source"] == "gutenberg":
        number = book["ref"]
        return f"https://www.gutenberg.org/cache/epub/{number}/pg{number}-images-3.epub"
    raise SystemExit(f"Unknown source {book['source']!r} for {book['name']}")


def metadata(file):
    """Title and language from the EPUB's package document."""
    with zipfile.ZipFile(file) as z:
        container = ET.fromstring(z.read("META-INF/container.xml"))
        opf_path = next(e.get("full-path") for e in container.iter() if e.tag.endswith("rootfile"))
        opf = ET.fromstring(z.read(opf_path))
    title = next((e.text or "" for e in opf.iter(DC + "title")), "")
    language = next((e.text or "" for e in opf.iter(DC + "language")), "")
    return title.strip(), language.strip()


def check(book):
    """Problems with the downloaded file of a book, or an empty list."""
    file = path_of(book)
    if not file.is_file():
        return [f"{file.relative_to(ROOT)} is missing"]
    try:
        title, language = metadata(file)
    except (zipfile.BadZipFile, KeyError, StopIteration, ET.ParseError) as e:
        return [f"{file.relative_to(ROOT)} is no EPUB: {e}"]
    problems = []
    if not language.lower().startswith(book["lang"]):
        problems.append(f"{file.relative_to(ROOT)}: language {language!r}, expected {book['lang']!r}")
    expected = {w for w in fold(book["title"]).split() if len(w) >= 4}
    if expected and not expected & set(fold(title).split()):
        problems.append(f"{file.relative_to(ROOT)}: title {title!r} does not match {book['title']!r}")
    return problems


def sha256(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()


def main(args):
    books = entries()
    if "--check" not in args:
        refresh = "--refresh" in args
        for book in books:
            file = path_of(book)
            if file.is_file() and not refresh and book["ref"] != "auto":
                continue
            if book["source"] == "gutenberg" and book["ref"] == "auto":
                book["ref"] = lookup(book)
                write_back(book, book["ref"])
            url = url_of(book)
            print(f"  {book['lang']}  {book['name']}  <- {url}")
            data = get(url)
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(data)
            time.sleep(2)  # be gentle with the servers
        SUMS.write_text("".join(f"{sha256(path_of(b))}  {path_of(b).relative_to(ROOT)}\n"
                                for b in books if path_of(b).is_file()), encoding="utf-8")

    problems = []
    for book in books:
        problems += check(book)
        if path_of(book).is_file():
            title, language = metadata(path_of(book))
            print(f"{book['lang']}  {path_of(book).stat().st_size // 1024:6d} KB  {title}  [{language}]")
    listed = {str(path_of(b).relative_to(ROOT)) for b in books}
    present = {str(p.relative_to(ROOT)) for p in BOOKS.rglob("*.epub")} if BOOKS.is_dir() else set()
    problems += [f"{p} is not listed in books.tsv" for p in sorted(present - listed)]
    sums = {}
    if SUMS.is_file():
        for line in SUMS.read_text(encoding="utf-8").splitlines():
            digest, name = line.split(maxsplit=1)
            sums[name] = digest
    for name in sorted(listed & present):
        if sums.get(name) != sha256(ROOT / name):
            problems.append(f"{name} does not match books.sha256")
    for p in problems:
        print("PROBLEM:", p, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
