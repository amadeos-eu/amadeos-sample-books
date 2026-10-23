# amadeos-sample-books

Public-domain classics for [Books](https://github.com/datazuul/amade-books), the e-book reader of amadeOS:
fifteen books, three in each language of the desktop. The Debian package `amadeos-sample-books` installs them
to `/usr/share/amadeos/books`, where Books shows them on the shelf *Included with amadeOS*, next to the reader's own books
in `~/Books`.

| Language | Books | Source |
|---|---|---|
| English | Alice’s Adventures in Wonderland, Pride and Prejudice, The Time Machine | Standard Ebooks (CC0) |
| German | Die Verwandlung, Faust I, Kinder- und Hausmärchen | Project Gutenberg |
| French | Le tour du monde en quatre-vingts jours, Candide, Les misérables (tome I) | Project Gutenberg |
| Italian | Le avventure di Pinocchio, I promessi sposi, Inferno | Project Gutenberg |
| Spanish | Don Quijote, Rimas, Lazarillo de Tormes | Project Gutenberg |

Every author died before 1955, so the texts are in the public domain in the EU and the USA. The Project
Gutenberg books keep the Project Gutenberg License in their text and are given away free of charge, as it
allows. *Le Petit Prince* is not included: it is still protected in France.

## How it works

The books are in the repository, so the package builds without a network and every change to a book is a
commit you can review.

- `books.tsv` lists the books: language, file name, author, title, source and the Gutenberg number or
  Standard Ebooks URL.
- `./gradlew fetchBooks` downloads the missing books into `books/<language>/`, checks that each one is an EPUB
  in the listed language with the listed title, and writes `books.sha256`. A Gutenberg number given as `auto` is
  looked up in Gutenberg's own search by author and title, checked for its language and written back into
  `books.tsv`. Busy servers are tried again, and a book that still fails is reported at the end while the
  others are kept, so the next run only fetches what is missing. `-Prefresh` downloads all books
  again; `./gradlew checkBooks` only checks the files, without a network. The program is
  `src/main/java/eu/amadeos/samplebooks/FetchBooks.java`, plain Java without libraries.
- `./gradlew deb` checks the books against `books.tsv` and `books.sha256` and builds
  `build/distributions/amadeos-sample-books_*_all.deb`; `./gradlew installToAmadeOs` copies it into
  `../amadeos/config/packages.chroot/` for the amadeOS image.

## Adding or changing a book

1. Add a line to `books.tsv` (a Gutenberg book can start as `auto`).
2. Run `./gradlew fetchBooks` and look at the titles it prints.
3. Commit `books/`, `books.sha256` and `books.tsv`, then open a pull request.

Where the book sites cannot be reached, the workflow *Fetch books* can be started by hand on a branch; it
runs the same program on GitHub and commits the result to that branch.

## Licence

The program and build files are licensed under the [EUPL-1.2](LICENSE). The books are in the public domain;
see [packaging/copyright](packaging/copyright).
