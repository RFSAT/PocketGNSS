# Publishing on Google Play

How to take PocketGNSS from the signed bundle to the public Play Store, including the closed test that Google requires
of some developer accounts. The store texts and declarations are in [play-listing.md](play-listing.md), the privacy
policy in [privacy-policy.md](privacy-policy.md).

## 1. Is a closed test required?

Google requires it of **personal** developer accounts created after 13 November 2023: before Production can be
used, a closed test must have had **at least 12 testers opted in for the last 14 consecutive days**
([Play Console Help: app testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465)).
Testers who opt out before 14 days do not count.

Google's page covers personal accounts only. If the app is published from an **organisation** account (RFSAT
Limited, verified with a D-U-N-S number), check the Dashboard: when "Production" can be selected, the closed test is
not required, although it is still a good idea. When the Dashboard shows "Apply for production", it is required.

Since it takes at least 14 days, start the closed test as soon as a release candidate exists. Development can go on
meanwhile: newer builds can be uploaded to the same closed track and the testers keep their opt-in.

## 2. Build and sign

The CI workflow builds the signed bundle: when the repository has the four Actions secrets `KEYSTORE_BASE64`,
`STORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` (the same names as RFSAT's other apps), each push produces the
artifacts `PocketGNSS-v<version>-play-bundle` (`PocketGNSS_v<version>_build<code>.aab`, for Play) and
`PocketGNSS-v<version>-apk`. The keystore exists only as a secret and is
decoded for the build and deleted afterwards; it is never in the repository (`.gitignore` excludes `*.jks` and
`*.keystore`).

* The version code follows from `appVersion` in `app/build.gradle.kts` (1.11.0 gives 11100); every upload needs a
  higher one.
* Play App Signing (the default for new apps) keeps the app signing key at Google; your keystore is the upload key.
  Keep a backup of it and its passwords somewhere safe outside the repository.
* Without the secrets (e.g. on a fork) CI still runs the tests and screenshots but builds no bundle; locally, Android
  Studio > Build > Generate Signed App Bundle does the same with the keystore file.

## 3. Set up the closed test

1. Play Console > the app > Test and release > Testing > Closed testing > Create track (or use "Alpha").
2. Testers: add an e-mail list (Google accounts) or a Google Group. A Google Group is easier to manage: testers join
   it themselves and you add the group once. Invite **15–20 people** so that 12 remain after the inevitable drop-outs.
3. Countries: all the countries the testers are in (at least those of the AgriBIT and ENACT partners).
4. Create a release on the track, upload the `.aab`, paste the release notes (section 6) and roll it out to the track.
5. Copy the **opt-in link** (Testers tab) and send it with the invitation (section 5).
6. Complete "App content" (privacy policy URL, data safety, foreground services, target audience) and the store
   listing first: the closed track needs them before it can be reviewed. Text for all of it is in play-listing.md.

## 4. During the 14 days

* Keep at least 12 testers opted in all the time (check Testers > number opted in).
* PocketGNSS has no analytics, so feedback comes by e-mail or a form: ask each tester for a short reply after the
  first week (what they used, what failed, phone model). Keep these replies: the production application asks for a
  summary.
* A useful spread of testers: farmers or advisers with the AgriBIT receiver, a few with dual-frequency phones only
  (phone RTK and PPP), someone outside Greece and Ireland (national heights, grids, languages), and a GIS user
  (imports and exports).
* What to try, so that the application can say the testers used all features:
  receiver over USB and Bluetooth; NTRIP relay; phone RTK and PPP with Galileo HAS; survey a point and stake it out;
  national height model; field boundary; guidance (A-B line, recorded curve, headland) with the coverage map;
  import and export (CSV, KML, shapefile, GeoJSON, ISO-XML on a tractor terminal if one is at hand); session replay;
  the app in the tester's own language.
* Upload fixes to the same track whenever needed; the opt-ins carry over.

## 5. Invitation text

> Subject: Test PocketGNSS before its release on Google Play
>
> Hello,
>
> We would like you to test PocketGNSS, the RTK GNSS app from RFSAT Limited, before it is published. It works with
> the AgriBIT receiver, other RTK receivers and the phone's own GNSS (RTK and Galileo HAS PPP).
>
> 1. Open this link on your Android phone and tap "Become a tester": OPT-IN LINK
> 2. Install PocketGNSS from the Play Store page the link opens.
> 3. Please stay in the test for at least 14 days (Google needs 12 testers for 14 days before publishing), use the
>    app in the field when you can, and send us a few lines after a week: phone model, what you used, what did not
>    work.
>
> The app sends nothing to RFSAT and has no tracking. Thank you!

> Θέμα: Δοκιμάστε το PocketGNSS πριν κυκλοφορήσει στο Google Play
>
> Καλησπέρα,
>
> Θα θέλαμε να δοκιμάσετε το PocketGNSS, την εφαρμογή RTK GNSS της RFSAT Limited, πριν δημοσιευτεί. Λειτουργεί με
> τον δέκτη AgriBIT, άλλους δέκτες RTK και το GNSS του ίδιου του τηλεφώνου (RTK και PPP με Galileo HAS).
>
> 1. Ανοίξτε αυτόν τον σύνδεσμο στο τηλέφωνο Android και πατήστε «Γίνετε δοκιμαστής»: ΣΥΝΔΕΣΜΟΣ
> 2. Εγκαταστήστε το PocketGNSS από τη σελίδα του Play Store που ανοίγει.
> 3. Μείνετε στη δοκιμή τουλάχιστον 14 ημέρες (η Google ζητά 12 δοκιμαστές για 14 ημέρες πριν από τη δημοσίευση),
>    χρησιμοποιήστε την εφαρμογή στο πεδίο όποτε μπορείτε και στείλτε μας λίγες γραμμές μετά από μία εβδομάδα:
>    μοντέλο τηλεφώνου, τι χρησιμοποιήσατε, τι δεν λειτούργησε.
>
> Η εφαρμογή δεν στέλνει τίποτα στη RFSAT και δεν παρακολουθεί τη χρήση. Ευχαριστούμε!

## 6. Release notes

Paste the block of the version you upload into the release's "Release notes" box; Play Console splits it by language
(the languages must also be added to the store listing, section 7). When the first upload is 1.12.0, a short
summary of 1.11.0 can go in front of it (each language under 500 characters in total).

### 1.12.0

```
<en-GB>
Guidance follows curves and headlands: record the first pass along a contour or bend and drive parallel curved passes, or follow passes round a field boundary inside it. Export fields, guidance lines and points as ISO-XML (ISOBUS TASKDATA) for tractor terminals and farm software. Replay can be paused. Choice buttons wrap neatly in every language.
</en-GB>
<el-GR>
Η καθοδήγηση ακολουθεί καμπύλες και κεφαλές: καταγράψτε το πρώτο πέρασμα κατά μήκος μιας ισοϋψούς ή στροφής και οδηγήστε παράλληλα καμπύλα περάσματα, ή ακολουθήστε περάσματα γύρω από το όριο του αγροτεμαχίου, μέσα σε αυτό. Εξαγωγή αγροτεμαχίων, γραμμών καθοδήγησης και σημείων σε ISO-XML (ISOBUS TASKDATA) για τερματικά τρακτέρ και λογισμικό εκμετάλλευσης. Η αναπαραγωγή μπορεί να γίνει παύση.
</el-GR>
<de-DE>
Die Spurführung folgt Kurven und dem Vorgewende: die erste Spur entlang einer Höhenlinie oder eines Bogens aufzeichnen und parallele Kurvenspuren fahren, oder Spuren entlang der Feldgrenze innerhalb des Feldes folgen. Felder, Spurlinien und Punkte als ISO-XML (ISOBUS TASKDATA) für Traktorterminals und Ackerschlagkarteien exportieren. Die Wiedergabe lässt sich anhalten.
</de-DE>
<fr-FR>
Le guidage suit les courbes et les tournières : enregistrez le premier passage le long d’une courbe de niveau ou d’un virage et suivez des passages courbes parallèles, ou des passages le long de la limite de la parcelle, à l’intérieur. Exportez parcelles, lignes de guidage et points en ISO-XML (ISOBUS TASKDATA) pour les terminaux de tracteur et les logiciels d’exploitation. La relecture peut être mise en pause.
</fr-FR>
<es-ES>
El guiado sigue curvas y cabeceras: registre la primera pasada a lo largo de una curva de nivel o una curva y conduzca pasadas curvas paralelas, o siga pasadas a lo largo del límite de la parcela, por dentro. Exporte parcelas, líneas de guiado y puntos en ISO-XML (ISOBUS TASKDATA) para terminales de tractor y programas de gestión agrícola. La reproducción se puede pausar.
</es-ES>
<it-IT>
La guida segue curve e capezzagne: registrate la prima passata lungo una curva di livello o una curva e percorrete passate curve parallele, oppure passate lungo il confine dell’appezzamento, all’interno. Esportate appezzamenti, linee di guida e punti in ISO-XML (ISOBUS TASKDATA) per i terminali dei trattori e i software gestionali. La riproduzione si può mettere in pausa.
</it-IT>
<pl-PL>
Prowadzenie po krzywych i uwrociach: nagraj pierwszy przejazd wzdłuż warstwicy lub łuku i jedź równoległymi przejazdami po krzywej albo przejazdami wzdłuż granicy pola, wewnątrz niego. Eksport pól, linii prowadzenia i punktów do ISO-XML (ISOBUS TASKDATA) dla terminali ciągników i programów do zarządzania gospodarstwem. Odtwarzanie można wstrzymać.
</pl-PL>
<pt-PT>
A orientação segue curvas e cabeceiras: registe a primeira passagem ao longo de uma curva de nível ou curva e conduza passagens curvas paralelas, ou siga passagens ao longo do limite da parcela, por dentro. Exporte parcelas, linhas de orientação e pontos em ISO-XML (ISOBUS TASKDATA) para terminais de tratores e software agrícola. A reprodução pode ser pausada.
</pt-PT>
```

### 1.11.0

```
<en-GB>
Phone PPP with Galileo HAS: positions to a few decimetres on dual-frequency phones with no base station and no subscription, using the free EU corrections (or IGS SSRA streams), given in ETRS89 within Europe. National heights: the official geoid models of 21 European countries, and EGM2008 elsewhere, on screen and in exports. A-B guidance paints a coverage map with overlaps and the field's gaps. Counts read correctly in every language.
</en-GB>
<el-GR>
PPP στο τηλέφωνο με Galileo HAS: θέσεις με ακρίβεια λίγων δεκατομέτρων σε τηλέφωνα δύο συχνοτήτων, χωρίς σταθμό βάσης και χωρίς συνδρομή, με τις δωρεάν διορθώσεις της ΕΕ (ή ροές IGS SSRA), σε ETRS89 εντός Ευρώπης. Εθνικά υψόμετρα: τα επίσημα μοντέλα γεωειδούς 21 ευρωπαϊκών χωρών και το EGM2008 αλλού, στην οθόνη και στις εξαγωγές. Η καθοδήγηση A-B σχεδιάζει χάρτη κάλυψης με επικαλύψεις και κενά. Σωστοί πληθυντικοί σε όλες τις γλώσσες.
</el-GR>
<de-DE>
PPP auf dem Telefon mit Galileo HAS: Positionen auf wenige Dezimeter mit Zweifrequenz-Telefonen, ohne Basisstation und ohne Abo, mit den kostenlosen EU-Korrekturen (oder IGS-SSRA-Strömen), in Europa in ETRS89. Amtliche Höhen: die offiziellen Geoidmodelle von 21 europäischen Ländern und sonst EGM2008, auf dem Bildschirm und in Exporten. Die A-B-Spurführung zeichnet eine Bearbeitungskarte mit Überlappungen und Lücken im Feld. Zahlen sind in allen Sprachen korrekt gebeugt.
</de-DE>
<fr-FR>
PPP sur le téléphone avec Galileo HAS : positions à quelques décimètres sur les téléphones bifréquences, sans station de base ni abonnement, avec les corrections gratuites de l’UE (ou les flux IGS SSRA), en ETRS89 en Europe. Altitudes nationales : les modèles de géoïde officiels de 21 pays européens, et EGM2008 ailleurs, à l’écran et dans les exports. Le guidage A-B dessine une carte de couverture avec recouvrements et manques. Les nombres s’accordent dans toutes les langues.
</fr-FR>
<es-ES>
PPP en el teléfono con Galileo HAS: posiciones a pocos decímetros en teléfonos de doble frecuencia, sin estación base ni suscripción, con las correcciones gratuitas de la UE (o flujos IGS SSRA), en ETRS89 dentro de Europa. Alturas nacionales: los modelos de geoide oficiales de 21 países europeos y EGM2008 en el resto, en pantalla y en las exportaciones. El guiado A-B pinta un mapa de cobertura con solapes y huecos de la parcela. Plurales correctos en todos los idiomas.
</es-ES>
<it-IT>
PPP sul telefono con Galileo HAS: posizioni a pochi decimetri sui telefoni a doppia frequenza, senza stazione base né abbonamento, con le correzioni gratuite dell’UE (o i flussi IGS SSRA), in ETRS89 in Europa. Quote nazionali: i modelli di geoide ufficiali di 21 paesi europei ed EGM2008 altrove, sullo schermo e nelle esportazioni. La guida A-B disegna una mappa di copertura con sovrapposizioni e lacune. Plurali corretti in tutte le lingue.
</it-IT>
<pl-PL>
PPP w telefonie z Galileo HAS: pozycje z dokładnością kilku decymetrów na telefonach dwuczęstotliwościowych, bez stacji bazowej i bez abonamentu, z bezpłatnymi poprawkami UE (lub strumieniami IGS SSRA), w Europie w ETRS89. Wysokości krajowe: oficjalne modele geoidy 21 krajów europejskich, a gdzie indziej EGM2008, na ekranie i w eksporcie. Prowadzenie A-B rysuje mapę pokrycia z nakładkami i lukami pola. Poprawne formy liczebników we wszystkich językach.
</pl-PL>
<pt-PT>
PPP no telefone com Galileo HAS: posições a poucos decímetros em telefones de dupla frequência, sem estação base nem assinatura, com as correções gratuitas da UE (ou fluxos IGS SSRA), em ETRS89 na Europa. Altitudes nacionais: os modelos de geoide oficiais de 21 países europeus e o EGM2008 no resto, no ecrã e nas exportações. A orientação A-B pinta um mapa de cobertura com sobreposições e falhas da parcela. Plurais corretos em todas as línguas.
</pt-PT>
```

The English text is the 1.11.0 entry of [CHANGELOG.md](../CHANGELOG.md); every version's entry is written to fit
the 500-character limit.

## 7. Store listing in the app's languages

Add the seven translations in Play Console > Grow users > Store presence > Main store listing > Manage translations.
Short descriptions (80 characters at most):

| Language | Short description |
|---|---|
| en-GB | Manage RTK GNSS receivers: centimetre position, NTRIP, survey and stake-out. |
| el-GR | Διαχείριση δεκτών RTK GNSS: θέση εκατοστού, NTRIP, αποτύπωση και χάραξη. |
| de-DE | RTK-GNSS-Empfänger verwalten: Zentimeterposition, NTRIP, Aufmaß, Absteckung. |
| fr-FR | Gérez vos récepteurs GNSS RTK : position au cm, NTRIP, levé et implantation. |
| es-ES | Gestione receptores GNSS RTK: posición centimétrica, NTRIP, levantamiento. |
| it-IT | Gestite ricevitori GNSS RTK: posizione al centimetro, NTRIP, rilievo, picchetto. |
| pl-PL | Obsługa odbiorników RTK GNSS: pozycja co do cm, NTRIP, pomiar i tyczenie. |
| pt-PT | Gira recetores GNSS RTK: posição ao cm, NTRIP, levantamento e piquetagem. |

The full description (play-listing.md) can be added in English for all of them and translated with Play Console's
translation service, or by the partners; the app itself is already translated.

## 8. Applying for production

After 14 days with 12 or more testers: Dashboard > Apply for production. Google asks about the closed test (how
testers were recruited, whether they used all features, the feedback and how it was collected), about the app
(audience: farmers, agronomists, surveyors and researchers using RTK GNSS; value: centimetre positions, field work
and free Galileo HAS corrections from a phone; estimated installs) and about readiness (what changed after the test
and how readiness was judged). Review usually takes up to seven days. After that, publish the same release to
Production (staged rollout of 20 % first is a safe choice).

## 9. Translations

The seven translations were made with Claude and checked for placeholders, plural forms and terminology, not by native
speakers. Before the public release, ask one tester per language to read the main screens (Home, Live, Setup,
Tools > Survey point, Field boundary, Guidance) and report wording that is wrong or unnatural; fixes go into
`app/src/main/res/values-<language>/strings.xml`.
