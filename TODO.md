# Lavoro in corso

Stato al 7 settembre 2026.

## Misurato sul dispositivo, il 4 e 5 settembre 2026

Honor Magic8 Lite, Android 16 (API 36): `targetSdk` combacia, niente da
cambiare nel progetto. Nessuna scheda SD. Collegato via **debug wireless**, che
e' l'unica strada rimasta: le porte USB del PC si sono rotte.

**Spostare una foto non cambia `DATE_MODIFIED`.** La deriva delle date che
temevamo non avviene su questo telefono. Le difese restano — non costano
niente e valgono su altri dispositivi — ma vanno lette come assicurazione, non
come rimedio a un guasto osservato.

**`DATE_TAKEN` non e' scrivibile**, ne' dove ha un valore ne' dove e' vuoto:
MediaProvider ignora la scrittura in silenzio. Anche `date_modified` e
`inferred_date` sono in sola lettura.

**Ma la data si corregge lo stesso**, per un'altra strada: cambiando l'ora di
modifica **del file** e riscansionando, Android ricalcola `inferred_date` e,
quando quella data concorda con il nome del file, scrive pure `datetaken`. Il
comando di riscansione e'
`content call --uri content://media --method scan_file --arg <percorso>`, e su
una cartella intera vale per tutto cio' che contiene.

**Le due gallerie non leggono le stesse cose.** Quella di serie di MagicOS
ricava la data dall'EXIF del file; Aves legge MediaStore. Percio' una
correzione fatta nell'indice si vede in Aves e non nella galleria di serie, e
viceversa. Per le verifiche vale Aves.

**Fatto:** le 15.653 foto WhatsApp hanno ora la loro data vera. Per le 8.374
che l'EXIF ce l'hanno e' stata usata l'ora esatta dello scatto letta dal file;
per le altre il giorno preso dal nome, a mezzogiorno — lontano dai confini di
giornata, perche' il `touch` sbaglia di un'ora sulle date in ora legale e
Android aggiunge qualche minuto di scarto suo.

**Da rifare quando arrivano foto nuove:** WhatsApp continua a riceverne, e le
nuove nascono con `date_modified` corretta, quindi il problema non si ripresenta.
Resterebbe solo dopo un altro trasferimento di telefono.

## Da rivedere: lo spool delle decisioni (schema v10, 7 settembre 2026)

**Scritto e compilato, mai eseguito su un telefono.** La migrazione tocca 1.859
decisioni vere e nessuno l'ha ancora vista girare.

### Il problema che risolve

Decidere scriveva due cose in due posti con due vite diverse: la decisione in
`photo_state`, subito e definitiva, e lo spostamento in una lista in memoria.
`ReviewSession.load()` svuotava quella lista, e ogni ricarica passa di li'.
Restava la decisione senza la mossa: la foto marcata, il file fermo. E' l'origine
delle "arretrate" che l'archivio si porta dietro da settimane, attribuite per
giorni a consensi negati.

### La forma

Colonna `pending` su `photo_state`. `status` dice **cosa e' stato deciso**,
`pending` **se e' gia' avvenuto**. Finche' vale 1 non c'e' nulla di definitivo:
scartare cancella quelle righe e il database torna com'era.

Non tutte le decisioni nascono in sospeso. Non lo sono: mantenere una foto dov'e',
catalogare una cartella nella categoria in cui gia' si trova, riagganciare un
estraneo alla categoria che lo ospita, registrare un ripristino gia' applicato.
Quattro punti, marcati `pending = false`.

### Cosa controllare per primo

1. **La migrazione** (`Schema.migrateToVersion10`, `classifyOwedWork`).
   Classifica ogni decisione esistente in fatta o dovuta. Il caso insidioso e'
   la foto WhatsApp: non lascia mai la sua cartella, quindi "dove sta" non puo'
   dire se il lavoro e' stato fatto — lo dice solo il registro della copia.
   Senza quella esclusione la migrazione rimetterebbe in coda ogni originale
   gia' copiato, e il primo Applica ne farebbe una seconda copia. L'esclusione
   c'e'; va verificata sui numeri veri.
2. **`markCarriedOut`** scrive tre fatti in una transazione: nuovo percorso,
   riga nella storia, `pending = 0`. Il parametro `relocated = false` serve al
   ramo della copia, dove l'originale non si e' mosso e l'inventario non deve
   dire il contrario. Se la scrittura fallisce dopo che il file si e' mosso,
   `BatchMover` conta la mossa come fallita: l'archivio la deve ancora, e la
   rioffre.
3. **La portata dello scarto.** Nella schermata principale riguarda la coda
   della sessione (periodo × cartelle, vedi sotto); nella Coda c'e' anche
   "Annulla TUTTE". Un pulsante che ne mostra dodici non deve poterne cancellare
   quattrocento.
4. **`ReviewSession.load`** svuota la coda e la ricostruisce dallo spool a ogni
   caricamento: il database e' l'unica verita', la memoria non ne tiene una
   seconda copia. Ricalcola la destinazione invece di averla salvata: se la
   categoria e' stata spostata fra il decidere e l'applicare, la foto va dove la
   categoria punta adesso. Il test `ReviewSessionTest` copre i tre stati.
5. **Il dialogo "applica o scarta" al cambio filtro non c'e' piu'.** Le decisioni
   non appartengono alla vista: si decide a settembre, si passa ad agosto, si
   applica alla fine. Controllare che nessun percorso perda ancora lavoro.
6. **Il backup** esporta anche `pending`. Un file scritto prima della v10 non
   ce l'ha: al ripristino si rifa' la classificazione della migrazione, invece
   di dare tutto per fatto.

### Applicare in parti (scritto il 7 settembre 2026, non ancora provato)

- `Applica (n)` nella schermata principale = tutto il dovuto per **il periodo e
  le cartelle scelte**, filtro di stato escluso. Il filtro escluso di proposito:
  con "non viste" le foto appena decise spariscono dallo schermo, e una coda
  letta dallo schermo direbbe zero dopo un pomeriggio di lavoro.
- La Coda mostra tutto il dovuto, di ogni cartella e giorno, con "Applica
  tutto" e "Annulla TUTTE". Ora include anche i **ripristini** dovuti (foto nel
  cestino con "riporta indietro" deciso), sotto "da spostare"; prima li
  cancellava con "Annulla TUTTE" senza averli mai mostrati.
- **Annulla** nella schermata principale torna indietro solo sulle decisioni
  di **questa** sessione, nell'ordine in cui sono state prese. Le decisioni di
  giorni fa sono in coda ma non si annullano una alla volta da li': per quelle
  c'e' la Coda.
- Il contatore nel cassetto, `Coda (n)`, conta il dovuto (`loadOwedWork`).
  Prima sommava le foto fuori posto, che sono lavoro gia' fatto, e non
  contava le archiviazioni dovute.

### Cosa ha cambiato la revisione del 7 settembre

- **`MovePlanner`** (core/review): l'unico posto che dice dove va una foto e
  con che nome. Prima quattro schermate rispondevano ognuna a modo suo, e il
  timbro `__data__` lo metteva solo la schermata principale: Coda, Griglia e
  il controllo "fuori posto" spostavano senza timbro. Ora tutte passano di li'.
- `load` ricostruiva la coda **prima** di sapere le origini: un ripristino
  dovuto non veniva mai rimesso in coda. Corretto l'ordine.
- La coda in memoria non veniva mai riallineata allo spool: dopo un Applica
  dalla Coda, la principale teneva ancora le mosse gia' fatte, e un secondo
  Applica avrebbe ricopiato le foto WhatsApp. Ora si svuota a ogni load.
- La Coda non offriva mai al cestino di Android le foto WhatsApp decise da
  eliminare (`forSystemBin`): restavano dovute per sempre. Ora le offre.
- `SystemBinHandover`: "Annulla" nel dialogo non chiamava `onFinished`, e la
  Griglia restava aperta senza piu' niente da mostrare.
- Backup: esporta anche `photo_paths.display_name` (senza, un ripristino dal
  cestino non ritrova il nome originale), `photos.date_suspect`, le stelle e
  gli "ignora" dei controlli. `restoreTable` inserisce solo le colonne presenti
  nel file, cosi' le mancanti prendono il default dello schema invece di NULL.
- Tolti `retainFailedMoves` (chi applica ricarica, e lo spool rioffre cio' che
  e' fallito), il parametro inutile di `loadPendingTrash`, e 39 stringhe senza
  riferimenti — fra cui `cleanup_*`, che descrivevano la copia nel cestino
  dell'app che oggi non si fa piu'.

### Un limite da decidere (non risolto, per scelta)

Una decisione nuova che sostituisce una gia' eseguita — ripristinare dal
cestino, riarchiviare altrove, buttare una foto gia' catalogata — riscrive la
riga con `pending = 1`. Se poi si **scarta**, la riga viene cancellata e con
lei la decisione precedente: la foto torna "non vista" invece che "eliminata"
o "in Famiglia". Una colonna `previous_status` lo sistemerebbe, ma cambia il
significato dello scarto e va discussa prima, come da regola.

### Cosa non e' stato fatto

- **Storia delle decisioni**: scartata di proposito. La storia dei percorsi la
  copre quasi tutta, perche' una categoria e' una cartella.
- Il piano completo, con le alternative valutate, e' in `PIANO-SPOOL.md`.

## Fase 3 — funzionalità richieste, non ancora scritte

**Niente di questa fase si scrive prima di aver fatto il giro di controllo sul
dispositivo, in cima a questo file.** Le verifiche che contiene decidono come
vanno scritte piu' di una di queste voci, e scriverle prima significa doverle
rifare.

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
- [ ] **Avanzamento della riconciliazione.** Fatto a meta': la riga di stato
      ora dice cosa sta facendo e un avviso riporta il risultato. Manca un
      avanzamento vero — su 24.000 foto resta un'attesa lunga con un numero
      solo all'inizio e uno alla fine.
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
- [ ] **Ricerca duplicati, di due tipi diversi.**

      *Copie identiche* — lo schema è già pronto: `size_bytes`, `date_taken`,
      `width`, `height` bastano per una query, e `content_hash` esiste ed è
      vuoto, da riempire solo sulle candidate incerte.

      *Copie ricompresse* — la foto scattata col telefono e poi mandata su
      WhatsApp: stessa immagine, byte diversi, dimensione diversa, hash
      diverso. Nessun confronto esatto la prende. Il timbro nel nome porta
      ora `_from_whatsapp`, quindi la ricerca può partire di lì: per ogni
      categoria, prendere le foto marcate come venute da WhatsApp e cercare
      le candidate **fra le altre della stessa categoria**, che è un insieme
      piccolo. Il confronto vero richiede un'impronta percettiva (tipo pHash)
      che riconosca la stessa immagine ridimensionata: fattibile senza
      dipendenze, ma è un lavoro a sé.

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
congelati al primo avvistamento. *Misurato il 4 settembre: su questo telefono
spostare un file non cambia `DATE_MODIFIED`, quindi la deriva non avviene.* La
regola resta come assicurazione — non costa niente, e su un altro dispositivo
il presupposto potrebbe non valere.

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

**L'app non scrive mai l'EXIF, lo legge soltanto.** Scrivere un tag su un JPEG
che non ha gia' un blocco EXIF significa riscrivere l'intero file: cambia la
dimensione, che e' uno dei segnali con cui una foto viene riconosciuta e con
cui si verifica di non toccarne una sbagliata; azzera `DATE_MODIFIED`; fa
ricaricare il file a Google Foto e a ogni servizio di backup; e
un'interruzione a meta' puo' rovinare la fotografia. Sarebbe l'unica
operazione dell'app capace di distruggerne una: oggi non sa nemmeno
cancellarle.

Sull'archivio vero non servirebbe comunque. Delle 15.609 foto WhatsApp, 8.869
sono anteriori a ottobre 2024 e l'EXIF **ce l'hanno gia', con l'ora vera**: e'
MediaStore a non indicizzarlo, quindi li' il lavoro e' leggere, non scrivere.
Le 6.739 successive non hanno EXIF e il nome da' solo il giorno: scriverlo
significherebbe mettere un `00:00:00` inventato nel campo che ogni altra app
tratta come verita'. Nel nome del file quello stesso `000000` e' tollerabile,
perche' un nome e' dichiaratamente un'etichetta; nell'EXIF sarebbe
un'affermazione falsa che sopravvive all'app.

Resta un caso in cui scriverlo avrebbe senso: quando la data la afferma
l'utente. Se un giorno esistera' "correggi la data di queste foto", scrivere
l'EXIF registrerebbe una decisione umana invece di inventare un dato — la
riscrittura del file resterebbe da valutare, ma il contenuto sarebbe onesto.

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
