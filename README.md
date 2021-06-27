# Nordict

An Android app with a learner-friendly interface to Swedish and Danish
dictionaries.  Major dictionaries of both these languages have official,
free-of-cost apps, but Nordict has unique features which may be helpful for
non-native speakers.

It builds upon ideas previously implemented in
[Bildkortsappen](https://github.com/vitkyrka/bildkortsappen) and
[Ordboken](https://github.com/vitkyrka/ordboken).

## Supported dictionaries

- [Svensk ordbok utgiven av Svenska Akademien](https://svenska.se/so/) (SO).
  [Official
  app](https://play.google.com/store/apps/details?id=se.svenskaakademien.so16).
  Note that the official app works offline but Nordict requires an Internet
  connection.

- [Den Danske Ordbog](https://ordnet.dk/ddo) (DDO).  [Official
  app](https://play.google.com/store/apps/details?id=dk.dsl.ordnet.ddo).

- [Svensk-Dansk Ordbog](https://sdo.dsl.dk/) (SDO).  [Official
  app](https://play.google.com/store/apps/details?id=dk.dsl.ordnet.sdo).

## Features

* **All words are hyperlinks (DDO, SDO, SO).** Any word in the definition can
  be clicked to either jump directly to its definition (if there is a unique,
  perfect match) or to put it in the search box to save some typing.

* **AnkiDroid integration (DDO, SO).** Flashcards can be created directly from
  the app.  Nordict splits the different meanings for the headword, so that
  each card only contains one meaning.  The example sentences from the
  dictionary entry are automatically parsed and additional example sentences
  (from [Korp](https://spraakbanken.gu.se/korp/)) or images (from Google Image
  Search) can be added.  Javascript is used in the card to display a random
  subset of the examples and images on the front and the formatted definition
  on the back.  The prounciation audio is also included and works offline in
  the flashcard.

* **Phonetic transcription (DDO).** The official DDO website includes a
  phonetic transcription for each word in a [simplified version of the
  IPA](https://ordnet.dk/ddo/artiklernes-opbygning/udtale), but this is omitted
  in the official Android app.  Nordict displays these.

* **Gender highlighting (DDO, SO).**  The vast majority of nouns in both
  Swedish and Danish are of the common gender, so Nordict highlights the
  entries for neuter nouns in an attempt to make them more memorable.

* **Proper navigation (DDO, SDO, SO).**  The official SO app already gets this
  right, but in the DDO app it is not possible to return to a word with the
  back button after navigating to another word via a hyperlink.  This is fixed
  in Nordict.

* **Smooth inter-dictionary navigation (SDO).**  The official SDO app shows a
  dialog whenever a link from SDO to DDO is clicked, but this is not present in
  Nordict.

* **Inflected forms expansion (DDO, SO).**  The swung dashes/tildes in the
  inflected forms are replaced with the word which they represent.  The idea is
  that seeing the forms fully spelled out will help in remembering them.

* **Gender memorization aids (SO).**  The headword in neuter nouns is prefixed
  with the indefinite article, and different declined forms are highlighted in
  neuter and common gender nouns, in order to aid in applying [the techniques
  described by Olle Kjellin](https://bit.ly/EN-ETT-in-Swedish).

* **Pronunciation auto-play (DDO, SO).**  The recorded pronunciations can be
  configured to automatically play when the entry is opened.

## License

See [LICENSE](LICENSE).

The flags are from [FlagKit](https://github.com/madebybowtie/FlagKit).  The
other icons are [Material Design
icons](https://github.com/google/material-design-icons).
