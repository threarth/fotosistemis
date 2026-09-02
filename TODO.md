# Lavoro in corso

Stato al 31 agosto 2026.

## Da verificare sul dispositivo — prima di ogni altra cosa

**Niente di tutto questo è mai stato eseguito su un telefono.** Compila e i
test passano, ma lo schema e la riorganizzazione toccano i dati e non li ha
visti girare nessuno.

Il telefono nuovo è un **Honor Magic8 Lite, Android 16 (API 36)**: `targetSdk`
combacia, non c'è niente da cambiare nel progetto. Nessuna scheda SD, un solo
volume. L'app **non è installata**, e non lo è mai stata: il database verrà
creato da zero, nessuna migrazione girerà. Non c'è nulla da esportare prima.

Ordine consigliato:

1. Installa, apri la schermata principale, **lascia finire la
   riconciliazione**: popola l'inventario, senza il quale il resto non trova
   nulla.
2. **Cartelle → Cartelle di origine**: metti `Pictures/storage-1`. È lì che
   Phone Clone ha messo l'archivio già ordinato.
3. **Cartelle → Riconosci foto già ordinate**, e controlla che i numeri per
   categoria corrispondano a quello che ti aspetti. Guarda le foto prima di
   confermare.
5. Il modello globale `{anno}-{etichetta}` non è mai stato provato. Le
   destinazioni migrate da versioni precedenti usano `{anno}`: per cambiarlo
   vanno aperte a mano.

Due misure in più, da prendere nella stessa sessione. Sono cinque minuti, e
la riorganizzazione del filesystem dipende interamente da come vanno:

4. **Spostare una foto cambia `DATE_MODIFIED`?** Spostane due e confronta il
   valore prima e dopo. Se cambia, la deriva delle date è reale e l'ordine
   delle operazioni non è negoziabile.
5. **Rinominare funziona in scoped storage?** Non serve una prova a mano: apri
   la riorganizzazione su una categoria piccola, attiva la data nel nome e
   applica. Se `DISPLAY_NAME` non passa su MagicOS lo dice il messaggio
   d'errore.

## Fase 3 — funzionalità richieste, non ancora scritte

- [ ] **Anteprima con checkbox prima di Applica.** Una sezione per le foto
      che vanno in eliminazione, una per quelle che si spostano, ogni voce
      con una casella per escluderla. Escludere annulla la transazione per
      quella foto e la riporta a *mantenuta*.
      Metà del lavoro è fatta: `PhotoPreviewActivity` esiste ed è stata
      scritta per essere riusata qui.
- [ ] **Rinomina delle cartelle** con riallineamento del database.
      Assorbita dalla riorganizzazione del filesystem, sezione qui sotto:
      è lo stesso motore, perché per MediaStore rinominare una cartella
      significa riscrivere il percorso di ogni foto che contiene, e la
      cartella vuota può restare.
- [ ] **Controlla integrità del database.** Verifica che le foto stiano dove
      l'ultimo percorso registrato dice, e ripara: le righe il cui `media_id`
      non risolve più vanno riagganciate tramite il riconoscimento a cascata.
- [ ] **Leggere l'EXIF direttamente**, con `ExifInterface`, invece di fidarsi
      di `datetaken`. MediaStore restituisce `NULL` anche su file che l'EXIF
      ce l'hanno: sulle WhatsApp fino a fine 2024 l'ora vera è dentro il file,
      e recuperarla trasformerebbe qualche migliaio di `_000000__` nell'orario
      giusto. Il timbro si aggiornerebbe da solo, perché l'EXIF batte il nome
      file nell'ordine di fiducia.
- [ ] **Backup automatico**, almeno settimanale.
- [ ] **Cartella madre** che precompila il percorso di una nuova
      destinazione.
- [ ] **Percorso precompilato** con il modello scelto.
- [ ] **Pagina di aiuto.** Serve più di quanto sembri: cinque comportamenti
      non si deducono guardando l'app, in particolare che *Da eliminare* non
      elimina, e che senza svuotare da Google Foto la copia nel cloud resta.
- [ ] **Ricerca duplicati.** Lo schema è già pronto: `size_bytes`,
      `date_taken`, `width`, `height` bastano per una query. `content_hash`
      esiste ed è vuoto, da riempire solo sulle candidate incerte.

## Riorganizzazione del filesystem — piano approvato, non ancora scritto

Il layout su disco è una proiezione del database, non uno stato da custodire:
avendo foto, percorsi, nomi e date in `photos`, la disposizione si ricalcola
quando serve. Da qui una voce di menu **Riorganizza sul filesystem**, che
mostra la situazione e permette, per ogni categoria, di rinominarla, di
scegliere fra file piatti e sottocartelle per anno, e di riscrivere i nomi
con un prefisso di data.

Il bisogno è concreto: la galleria di Android genera un album per cartella, e
le sottocartelle per anno moltiplicano gli album fino a rendere l'archivio
ingestibile. Appiattendo però si perde l'ordine, perché il nome originale
ordina per dispositivo e non per tempo — `PXL_2024…` finisce prima di
`Screenshot_2018…`. Il prefisso restituisce l'ordine che la cartella dava.

### Prerequisiti

L'adozione delle foto già ordinate deve essere girata sul telefono: se il
database non rispecchia il disco, la riorganizzazione lavora su una mappa
sbagliata. Servono anche le due misure ai punti 6 e 7 in cima a questo file.

### File toccati

| file | modifica |
| --- | --- |
| `core/model/CaptureDateResolver.kt` | nuovo `Source.ESTIMATED`; `readOwnPrefix()` che legge il marcatore e distingue la tilde; ordine di qualità delle sorgenti |
| `core/data/Schema.kt` | **v6**: `original_display_name` su `photos`, con `ALTER TABLE ADD COLUMN` sotto `hasColumn()` come le migrazioni esistenti |
| `core/data/PhotoInventory.kt` | in `update()`, mai sostituire una data con una di qualità inferiore; scrivere `original_display_name` una volta sola, prima della prima rinomina |
| `core/reorg/FileNamer.kt` *(nuovo)* | `strip()` e `apply()` del marcatore, contatore per i pari-secondo, troncamento del gambo oltre 255 byte |
| `core/reorg/Reorganizer.kt` *(nuovo)* | stato attuale più scelte per categoria, in uscita la lista degli spostamenti con percorso **e** nome. Nessun I/O |
| `app/MediaStorePhotoSource.kt` | `DISPLAY_NAME` accanto a `RELATIVE_PATH`, nella stessa `update` |
| `app/BatchMover.kt` | `createWriteRequest` a blocchi: il binder non regge migliaia di URI in una chiamata sola |
| `app/ReorganizeActivity.kt` *(nuovo)* | schermata, layout e stringhe |
| test | `FileNamerTest`, `ReorganizerTest`, più casi in `CaptureDateResolverTest` per marcatore, tilde e non regressione |

L'enum è salvato per `.name` e riletto con `firstOrNull { it.name == ... }`:
aggiungere un valore non invalida le righe esistenti.

### La schermata

Una riga per categoria — nome, numero di foto, disposizione attuale, percorso
d'esempio — e per ciascuna rinomina, piatto o per anno, prefisso sì o no.

Prima di applicare, un riepilogo: quante foto si spostano, quante si
rinominano, la ripartizione per `date_source` riusando `describeSources`,
quante prendono la tilde, e i grappoli. Un grappolo è un gruppo di foto che
condividono lo stesso giorno in `FILE_TIMESTAMP`: quattrocento foto con la
stessa data non sono una giornata di scatti, sono un'importazione, e vanno
riconosciute come tale prima di scriverne la data nel nome. Poi *Guarda le
foto*, poi *Applica*.

### Quello che il piano non fa

Non indovina le date sbagliate: le congela e le segnala. Correggerle — a mano,
o deducendole dall'anno della cartella, che è pur sempre un'affermazione umana
— è lavoro successivo.

## Com'è fatto l'archivio, misurato il 31 agosto 2026

Letto dal telefono con `adb`, in sola lettura. Serve perché quasi ogni scelta
sui nomi e sulle date dipende da questi numeri, e a memoria non si ricostruiscono.

**24.166 immagini indicizzate da MediaStore**, di cui **16.091 (67%) senza
`datetaken`**. Non è un difetto del telefono: sono quasi esattamente le foto di
WhatsApp.

| albero | file | data+ora nel nome | solo data | niente |
| --- | --- | --- | --- | --- |
| `Pictures` | 2.016 | 92% | 8% | 5 file |
| `DCIM` | 6.334 | 98% | 1% | 12 file |
| `Download` | 99 | 96% | — | 4 file |
| **WhatsApp Images** | **15.609** | **0%** | **100%** | — |

`DCIM/Camera` da solo ne tiene 5.032, mai riviste: è lì che sta il lavoro.

**Phone Clone ha diviso l'archivio in `Pictures/storage-0` e
`Pictures/storage-1`**, che erano i due volumi del telefono vecchio.
`storage-1` è l'archivio ordinato — 649 foto, 10 categorie, tutte nella forma
`categoria/anno-categoria`. `storage-0` sono 1.350 foto in cartelle a evento
senza livello anno: non sono adottabili, vanno riviste una a una.

**Le foto WhatsApp hanno perso l'EXIF verso fine 2024**, ma quelle precedenti
ce l'hanno ancora, con l'ora esatta. Verificato su dieci foto sparse su otto
anni. **MediaStore però restituisce `datetaken=NULL` anche per quelle**, quindi
l'app cade sul nome file e ottiene il giorno giusto con ora `00:00:00`.

**Tutte le WhatsApp hanno `date_modified` del 31 luglio 2026**, dentro una
finestra di dieci ore: è il trasferimento. Se il nome file si perdesse, quelle
15.609 foto diventerebbero tutte di quel pomeriggio. Il timbro esiste per
rendere esplicita quella dipendenza invece che accidentale.

## Fase 4 — desktop

Applicazione **Swing**, non webapp: chiamando `core` direttamente sparisce lo
strato HTTP più la serializzazione JSON che servirebbe a un browser.

- [ ] `JdbcDatabase` implementa `Database` (~80 righe). Unica dipendenza
      nuova: `org.xerial:sqlite-jdbc`.
- [ ] `FileSystemSource` implementa `PhotoSource` (~150 righe). Nessun
      MediaStore da interrogare: cammina le directory e legge l'EXIF.
- [ ] Interfaccia Swing con tastiera (frecce invece degli swipe) e striscia
      di miniature, che sul telefono non sta.
- [ ] **Cache delle miniature**, generata durante la scansione. Su Android
      MediaStore le fornisce già; sul desktop no, e decodificare un JPEG da
      6 MB per mostrarlo costa decine di millisecondi. I JPEG contengono una
      miniatura EXIF estraibile quasi gratis.

## Fase 5 — video

- [ ] Porta `FrameExtractor`: `durationMillis`, `frameAt(posizione)`.
      Android la implementa con `MediaMetadataRetriever`, senza dipendenze;
      il desktop invocando `ffmpeg` come processo esterno.
- [ ] Anteprima per "fotoizzazione": n fotogrammi a t0, t1/n … tn/n disposti
      a matrice, con pulsante *approfondisci* che scorre a densità maggiore.
      Non serve alcun motore video: è estrazione di fotogrammi, non
      riproduzione.
- [ ] `media_type` e `duration_millis` sono già nello schema e valgono
      sempre `image`: nessuna migrazione sarà necessaria.

## Decisioni prese, da non rimettere in discussione

Sono costate ragionamento o misure, e il motivo conta quanto la conclusione.

**Ogni foto resta sul proprio volume.** Una foto sulla SD archiviata in
Famiglia finisce in `SD:Pictures/Famiglia`, una interna in
`Pictures/Famiglia`. MediaStore non sposta un file fra volumi con un
`update`: attraversarli significherebbe copiare ogni byte e assegnare alla
foto un identificatore nuovo. Le cartelle duplicate sui due volumi non sono
un difetto ma due archivi paralleli.

**"Da eliminare" non elimina.** Raduna le foto in
`Pictures/_FotoSistemis_DaEliminare`, che si svuota da Google Foto: eliminare
in locale lascerebbe intatta la copia già caricata, e nessuna API pubblica
può rimuoverla. Effetto collaterale positivo: archiviare e cestinare sono la
stessa operazione, quindi un lotto misto costa un solo consenso.

**I tag stanno nel database, non nel nome del file.** Aggiungerne uno resta
un `UPDATE` invece di una rinomina che disturberebbe i servizi di backup. La
scrittura in XMP `dc:subject` resta possibile come esportazione separata: è
il punto standard, ma la prima scrittura su un JPEG che non ha già un
pacchetto XMP riscrive l'intero file.

**Due livelli di cartelle, `categoria/anno`, e niente di più profondo.**
L'anno è l'unica dimensione mai ambigua. Tutto ciò che è più fine si
sovrappone, e forzarlo in una gerarchia produce scelte arbitrarie che a
distanza di anni non si ricordano: quello è materiale da tag.

**La riconciliazione è completa, non incrementale.** Solo confrontare tutto
rivela ciò che è stato cancellato altrove, e avendo confrontato tutto non
resta nulla che una sincronizzazione incrementale possa aggiungere.

**Il `media_id` non è un'identità.** È una scorciatoia riscrivibile. Il
riconoscimento è a cascata — `media_id`, poi dimensione con data e nome, poi
dimensione con data se la candidata è una sola — e con più candidate rinuncia
invece di indovinare, perché indovinare sposterebbe i tag di una foto su
un'altra.

**Il nome del file porta la data, su tutte le foto.** Appiattendo dieci anni
in una cartella sola l'ordine alfabetico segue il prefisso del dispositivo —
`DSC_`, `IMG_`, `PXL_`, `Screenshot_` — e raggruppa per marca di telefono
invece che per tempo. Un prefisso uniforme `_yyyymmdd-hhmmss_` restituisce
l'ordine cronologico. Uniforme e non solo dove manca: una regola con
eccezioni non è verificabile a colpo d'occhio, e la ridondanza su un nome che
la data già ce l'aveva costa meno del dubbio su quali file siano stati
toccati. L'anno va davanti perché l'ordinamento confronta da sinistra: in
`ggmmyyyy` comanda il giorno del mese, che non significa niente.

**Il marcatore è `__yyyyMMdd_HHmmss[-N]__`, e i due underscore sono il punto.**
Con uno solo il marcatore sarebbe indistinguibile dai nomi che le fotocamere
producono davvero: `20200805_113940_01_saved.jpg` verrebbe letto come nostro, e
toglierlo lascerebbe `01_saved.jpg`. Sul telefono ce ne sono 154 così, e zero
che contengano `__`. Il separatore fra data e ora è invece lo stesso che usano
loro, `_`, perché con uno diverso i nomi si separerebbero a quel carattere e
ogni foto timbrata di una giornata finirebbe prima di ogni foto nativa della
stessa giornata invece di prendere il suo posto. Il contatore `-N` sta dentro
i delimitatori e serve solo quando due foto nella stessa cartella avrebbero
data, ora e nome identici.

**Il `+` marca una data che è un ripiego.** `__+yyyyMMdd_HHmmss__` significa
che la data viene da `FILE_TIMESTAMP`, cioè dalla data di modifica del file:
descrive quando il file è stato scritto su questo telefono, non quando la
fotografia è stata presa. Il `+` vale 43 e le cifre partono da 48, quindi
quelle foto si radunano in cima alla cartella, dove si trovano. Sul telefono
sono una ventina su 24.000: quasi tutto ha o l'EXIF o la data nel nome.

**Scrivere la data nel nome la mette al sicuro.** Il prefisso viene
riconosciuto da `parseFileName`, quindi una foto prefissata risale da
`FILE_TIMESTAMP` a una sorgente stabile: la data smette di dipendere dal
filesystem e sopravvive a copie, backup e cambi di telefono.

**Una data non si sostituisce mai con una di qualità inferiore.** La
riconciliazione riscrive `date_taken` e `date_source` a ogni giro, e non sono
congelati al primo avvistamento. Se spostare un file aggiorna `DATE_MODIFIED`,
una foto in `FILE_TIMESTAMP` si ridata al giorno dello spostamento, e alla
riorganizzazione successiva finisce nell'anno sbagliato — il cui spostamento
la ridata di nuovo. Uno strumento che mette ordine non deve spostare le foto
per effetto dei propri spostamenti.

**Prima il nome, poi la cartella, nella stessa `update`.** Scritta la data nel
nome la foto è immune alla deriva; spostarla prima la lascerebbe esposta per
tutta la durata del lotto. Un'unica scrittura per foto significa che non
esiste un istante in cui è già stata spostata ma non ancora battezzata.

**Un timbro già scritto non si tocca, salvo una sorgente migliore.** Una
riorganizzazione normale non porta informazione nuova, quindi non ha motivo di
riscrivere niente. Quando invece arriva una data da una sorgente che ne sa di
più — l'EXIF al posto del nome file — il timbro viene aggiornato. È la stessa
regola che impedisce alla data di andare alla deriva, applicata al nome
anziché al database.

**Le radici sono due cose diverse.** Quelle di **origine** sono molte e
limitano davvero cosa si revisiona: un telefono tiene foto in decine di
cartelle e quasi nessuna è un archivio. Quella di **destinazione** è una sola,
perché le foto arrivano da dove capita ma si mettono via in un posto solo.
Cambiarla propone di portarci sotto le categorie esistenti; i file però non si
muovono lì, li muove la riorganizzazione con la sua anteprima.

**La storia dei percorsi tiene anche il nome.** `photo_paths` registra ogni
spostamento e ogni rinomina come cartella più nome: MediaStore non ha un undo,
e quelle righe sono l'unica strada per tornare indietro. Prima il ripristino
rimetteva la foto nella cartella giusta lasciandole il nome nuovo, cioè
tornava indietro a metà.

**Prima di scrivere si verifica che la foto sia ancora quella.** Un `update`
raggiunge la foto tramite il suo id MediaStore, che e' una scorciatoia
riscrivibile: fra il momento in cui si costruisce un piano e quello in cui lo
si applica possono passare minuti, e una riscansione puo' avere dato quel
numero a un'altra foto. Nome e dimensione vengono riletti e confrontati; se non
combaciano la foto viene saltata e riportata fra le fallite, invece di
spostarne una sbagliata in silenzio. La data non entra nel confronto: l'app la
ricava dal nome file quando manca l'EXIF, quindi quella che ha in mano spesso
non e' quella che MediaStore restituirebbe.

**Il backup verso Google e' una scelta dell'utente, presa a ogni backup.**
`android:allowBackup` sta nel manifest e non si puo' cambiare a runtime, quindi
resta acceso e decide `FotosistemisBackupAgent`, che legge l'impostazione nel
momento in cui Android chiede i dati. Acceso di default: perdere il registro di
cosa e' gia' stato rivisto significa perdere il lavoro, perche' le foto sul
disco dicono dove sono ma non cosa si e' deciso su di loro. Nel backup entrano
database e preferenze; nessuna immagine, che non e' un file di questa app.

## Come si compila

Il progetto sta sul filesystem Windows perché Gradle non attraversi WSL.
Da WSL, `JAVA_HOME` va passato esplicitamente:

```
/mnt/c/Windows/System32/cmd.exe /c \
  "set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr&& \
   gradlew.bat assembleDebug :core:test --console=plain"
```

I test di `core` girano sulla JVM e non richiedono un dispositivo: 25 test,
la parte che conta è `PhotoMatcherTest`, dove sbagliare fa perdere lavoro
all'utente.

`adb` non è nel PATH:
`/mnt/c/Users/dan_g/AppData/Local/Android/Sdk/platform-tools/adb.exe`
