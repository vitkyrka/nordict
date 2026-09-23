# Nordict

An Android app with a learner-friendly interface to Swedish and Danish
dictionaries.  Major dictionaries of both these languages have official,
free-of-cost apps, but Nordict has unique features which may be helpful for
non-native speakers.

Nordict also has varying degrees of support for various Catalan, Spanish,
French and Portuguese dictionaries.

It builds upon ideas previously implemented in
[Bildkortsappen](https://github.com/vitkyrka/bildkortsappen) and
[Ordboken](https://github.com/vitkyrka/ordboken).

## Supported dictionaries

### Swedish

- [Svensk ordbok utgiven av Svenska Akademien](https://svenska.se/so/) (SO).
  [Official
  app](https://play.google.com/store/apps/details?id=se.svenskaakademien.so16).
  Note that the official app works offline but Nordict requires an Internet
  connection.

- [Svensk-Dansk Ordbog](https://sdo.dsl.dk/) (SDO).  [Official
  app](https://play.google.com/store/apps/details?id=dk.dsl.ordnet.sdo).

### Danish

- [Den Danske Ordbog](https://ordnet.dk/ddo) (DDO).  [Official
  app](https://play.google.com/store/apps/details?id=dk.dsl.ordnet.ddo).

### Spanish

- [Diccionario de la lengua española](https://dle.rae.es/) (DLE).

- [Diccionario del estudiante](https://www.rae.es/diccionario-estudiante) (EST).

- [Collins Spanish-English dictionary](https://www.collinsdictionary.com/dictionary/spanish-english) (COLSPAN).

### Catalan

- [DIDAC](https://www.diccionari.cat/didac) (DIDAC).

- [Gran Diccionari de la Llengua Catalana](https://www.diccionari.cat/) (GDLC).

- [Diccionari català-castellà](https://www.diccionari.cat/) (CA-ES).

- [Diccionari català-anglès](https://www.diccionari.cat/) (CA-EN).

### Portuguese

- [Linguee (Portuguese<->English)](https://www.linguee.pt/) (LINGPT).

- [Infopédia](https://www.infopedia.pt/) (INFOPEDIA).

### French

- [Wiktionnaire](https://fr.wiktionary.org/) (WFR).

- [Dico en ligne Le Robert](https://dictionnaire.lerobert.com/) (ROB).

- [Collins French-English dictionary](https://www.collinsdictionary.com/dictionary/french-english) (COLFREN).

## Features

* **All words are hyperlinks (all).** Any word in the definition can
  be clicked to either jump directly to its definition (if there is a unique,
  perfect match) or to put it in the search box to save some typing.

* **AnkiDroid integration (all).** [AnkiDroid](https://github.com/ankidroid/Anki-Android)
  flashcards can be created directly from the app:
  * The different meanings for the headword are split so that each flashcard
    only contains one meaning.
  * The example sentences from the dictionary entry are automatically parsed and
    added to the card.
  * Images from Google Image Search (or from the dictionary entry itself if
    present there) can also be added to the card.
  * Javascript is used in the card to display a random subset of the examples
    and images on the front.
  * The pronunciation audio is also included and works offline and plays
    automatically in the back of the flashcard.

* **Phonetic transcription (DDO, SDO, SO, WFR, ROB, INFOPEDIA, diccionari.cat).**
  The official DDO website includes a phonetic transcription for each word in
  a [simplified version of the
  IPA](https://ordnet.dk/ddo/artiklernes-opbygning/udtale), but this is omitted
  in the official Android app.  Nordict displays these, as well as the
  pronunciation guides of the other transcribed dictionaries.

* **Gender highlighting (Spanish, Catalan, French, Portuguese).**  Masculine
  and feminine grammar markers are highlighted with different backgrounds in
  an attempt to make them more memorable.  (Swedish/Danish neuter aids are
  covered separately below.)

* **Proper navigation (all).**  The official SO app already gets this
  right, but in the DDO app it is not possible to return to a word with the
  back button after navigating to another word via a hyperlink.  This is fixed
  in Nordict.

* **Better order of information (WFR).** The mobile Wiktionnaire site has several
  flaws which make it hard to use: for example, in words with more than one
  meaning, there is no overview so one is forced to scroll throw a large amount
  of content (such as translations to dozens of languages) to jump between meanings.
  These problems are fixed in Nordict: translations are stripped, each part of
  speech gets its own headed entry on one page, and the etymology is shown
  below the definitions instead of above them.

* **Smooth inter-dictionary navigation (all, e.g. SDO to DDO).**  The official
  SDO app shows a dialog whenever a link from SDO to DDO is clicked, but this
  is not present in Nordict.

* **Inflected forms expansion (DDO, SDO, SO).**  The swung dashes/tildes in the
  inflected forms are replaced with the word which they represent.  The idea is
  that seeing the forms fully spelled out will help in remembering them.

* **Gender memorization aids (SO).**  The headword in neuter nouns is prefixed
  with the indefinite article, and different declined forms are highlighted in
  neuter and common gender nouns, in order to aid in applying [the techniques
  described by Olle Kjellin](https://bit.ly/EN-ETT-in-Swedish).

* **Pronunciation auto-play (DDO, SO, WFR, ROB, COLSPAN, COLFREN, LINGPT,
  INFOPEDIA).**  The recorded pronunciations can be configured to automatically
  play when the entry is opened.

## License

See [LICENSE](LICENSE).

The flags are from [FlagKit](https://github.com/madebybowtie/FlagKit), by way
of [Shusshu/android-flags](https://github.com/Shusshu/android-flags).  The
other icons are [Material Design
icons](https://github.com/google/material-design-icons).
