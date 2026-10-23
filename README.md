# amadeos-sample-books

Public-domain classics for [Books](https://github.com/datazuul/amade-books), the e-book reader of amadeOS:
fifteen books, three in each language of the desktop. The Debian package `amadeos-sample-books` installs them
to `/usr/share/amadeos/books`, where Books shows them on the shelf *Included*, next to the reader's own books
in `~/Books`.

| Language | Books | Source |
|---|---|---|
| English | Alice’s Adventures in Wonderland, Pride and Prejudice, The Time Machine | Standard Ebooks (CC0) |
| German | Die Verwandlung, Faust I, Kinder- und Hausmärchen | Project Gutenberg |
| French | Le tour du monde en quatre-vingts jours, Candide, Les misérables (tome I) | Project Gutenberg |
| Italian | Le avventure di Pinocchio, Novelle rusticane, Inferno | Project Gutenberg |
| Spanish | Don Quijote, Rimas, Lazarillo de Tormes | Project Gutenberg |

Every author died before 1955, so the texts are in the public domain in the EU and the USA. The Project
Gutenberg books keep the Project Gutenberg License in their text and are given away free of charge, as it
allows. *Le Petit Prince* is not included: it is still protected in France.

## How it works

The books are in the repository, so the package builds without a network and every change to a book is a
commit you can review.

- `books.tsv` lists the books: language, file name, author, title, source and the Gutenberg number or
  Standard Ebooks URL.
- `./fetch-books.py` downloads the missing books into `books/<language>/`, checks that each one is an EPUB in
  the listed language with the listed title, and writes `books.sha256`. A Gutenberg number given as `auto` is
  looked up by author, title and language and written back into `books.tsv`. `--refresh` downloads all books
  again, `--check` only checks the files.
- `./gradlew deb` checks the books against `books.tsv` and `books.sha256` and builds
  `build/distributions/amadeos-sample-books_*_all.deb`; `./gradlew installToAmadeOs` copies it into
  `../amadeos/config/packages.chroot/` for the amadeOS image.

## Adding or changing a book

1. Add a line to `books.tsv` (a Gutenberg book can start as `auto`).
2. Run `./fetch-books.py`, or push the change: the workflow *Fetch books* runs the script on GitHub and
   commits the downloaded books and the checksums to the same branch.
3. Look at the titles the script printed, then open a pull request.

## Licence

The scripts and build files are licensed under the [EUPL-1.2](LICENSE). The books are in the public domain;
see [packaging/copyright](packaging/copyright).
