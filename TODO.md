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

## Da rivedere: le proposte (schema v12, 7 settembre 2026)

**Scritto e compilato, mai eseguito su un telefono.** La migrazione tocca 1.859
decisioni vere e nessuno l'ha ancora vista girare. Passa dalla v9 (il telefono)
o dalla v11 (l'APK consegnato lo stesso giorno, se e' stato installato).

### Il problema che risolve

Decidere scriveva due cose in due posti con due vite diverse: la decisione in
`photo_state`, subito e definitiva, e lo spostamento in una lista in memoria.
Restava la decisione senza la mossa: la foto marcata, il file fermo. E' l'origine
delle "arretrate". La v10 aveva risposto con una colonna `pending` sulla stessa
riga, e la v11 con `previous_status` per non perdere la decisione sostituita:
due colonne e una regola speciale per Tieni, per dire una cosa sola.

### La forma

Due tabelle con due significati. `photo_state` dice **cio' che e' avvenuto**
(tenuta, in categoria, nel cestino) e non contiene mai nulla di sospeso.
`proposals` dice **cio' che e' stato chiesto e non ancora fatto**: un'azione
(`file`, `trash`, `restore`), la categoria se serve, quando. Al piu' una
proposta per foto; l'ultima sostituisce la precedente. Eseguire una proposta
scrive l'esito in `photo_state` e la cancella; scartarla la cancella e basta,
e la foto torna a essere quel che l'archivio diceva: non vista, o la decisione
gia' eseguita. Non c'e' nulla da "restituire" perche' nulla era stato tolto.

La coda in memoria e' derivata: le proposte nella portata della sessione,
pianificate da `MovePlanner.plan`. Non esiste piu' una lista da tenere allineata.

Cinque punti scrivono la verita' senza passare da una proposta, perche' il
file e' gia' dove deve stare: `markCarriedOut` (dopo una mossa), la copia di
una foto inamovibile (`recordCopy`), le foto riconosciute nelle cartelle di
destinazione (`recordAll`), le estranee gia' nella cartella giusta
(`fileStrangers`), e il cestino di Android (`recordSystemBin`).

**Tieni** ritira la proposta, se c'e', e scrive `kept` solo se della foto non si
sa nulla. Non sovrascrive mai una verita': una foto in Famiglia riarchiviata in
Viaggi e poi tenuta e' in Famiglia. E' annullabile solo quando ha scritto
`kept`.

### La migrazione

`migrateToVersion12`: se `photo_state` ha la colonna `pending`, le righe con
`pending = 1` diventano proposte (azione dallo status, data da `updated_at`);
quelle con una `previous_status` tornano alla precedente, le altre spariscono.
Poi `photo_state` viene ricostruita con le sole quattro colonne. Sempre, da
qualunque versione, `proposeUnfinishedWork`: ogni verita' il cui file non e'
dove la verita' dice diventa una proposta — catalogata ma fuori dalla cartella,
eliminata ma fuori dal cestino, tenuta ma dentro il cestino (esclusioni: le
copie WhatsApp in categoria e le consegne al cestino di Android). Idempotente.

La v13 (7 settembre, sera) ha tolto l'esclusione "copiata nel cestino app":
sul telefono c'erano 7 WhatsApp del 5 settembre "eliminate" sulla carta,
copiate nel cestino app, cestino poi svuotato, originali ancora nella cartella
WhatsApp. Per una WhatsApp la copia nel cestino era la strada sbagliata; ora
tornano in Coda e vanno al cestino Android col consenso. `migrateToVersion13`
rilancia `proposeUnfinishedWork`.

Sempre la v13 confronta i percorsi senza badare alle maiuscole. Motivo: la
memoria condivisa e' un mount FUSE che risolve `Pictures` e `pictures` alla
stessa directory (verificato sul telefono: stesso inode), e l'indice media ha
registrato tre copie fatte l'11 agosto come `pictures/Famiglia/` benche' l'app
avesse chiesto `Pictures/Famiglia/`. Prima SQL `LIKE` piegava il caso ma `=`
e `GROUP BY` no, e in Kotlin ogni confronto faceva a modo suo: l'albero della
Sorgente mostrava due nodi per una cartella. Ora `relative_path` e
`photo_paths.path` sono `COLLATE NOCASE` (tabelle ricostruite dalla v13,
testo invariato), in Kotlin passa tutto da `FolderPath.sameFolder`/`key`, e le
selezioni MediaStore usano `LIKE ... ESCAPE`. `StorageCaseProbe` fa `stat` di
`Pictures` e `pictures` all'avvio e avvisa una volta sola se gli inode
differiscono, e dichiara anche quando non e' riuscita a rispondere: un
controllo fallito non deve sembrare passato. Non cambia comportamento.

I periodi contano anche le foto andate via. Prima la tendina dei periodi
nasceva dalle sole foto presenti nelle cartelle scelte, e un mese finito —
originali WhatsApp consegnati al cestino di Android, foto DCIM tutte
catalogate — spariva invece di restare in verde (i WhatsApp 2018 del 6
settembre). `PhotoInventory.loadDeparted` legge le foto con una verita' che
non stanno piu' nella cartella d'origine (prima `original` in `photo_paths`)
o mancano dal telefono; `PeriodTally` (core, con test) le somma alle presenti
sotto la cartella da cui vengono, come decise, contandone una sola volta chi
si e' solo spostata dentro le cartelle. Un mese svuotato legge `n/n`.

La Coda mostra anche le proposte che non si possono eseguire (categoria
cancellata, origine sconosciuta, foto gia' al suo posto), con il motivo: si
annullano con la lista in cui stanno, non si eseguono mai.

Il backup esporta `proposals`. Un file v10/v11 (righe con `pending`) viene
convertito all'import allo stesso modo; un file pre-v10 passa da
`proposeUnfinishedWork`.

### Provato sul telefono, 7 settembre 2026

Tutto il giro fatto con la v13 installata:

- Migrazione: 14 proposte dalla v12, tutte le `pending` v11 (WhatsApp da
  eliminare mai consegnate); con la v13 le 7 del 5 settembre in Coda,
  consegnate al cestino di Android.
- Sorgente: un solo `Pictures/Famiglia`, nessun avviso sulle maiuscole.
- Sorgente WhatsApp: 2018-07..10 in verde, 23/34/34/7. Il conto fatto sulla
  copia del DB diceva 23/35/34/6 perche' contava in UTC; l'app conta in ora
  locale, e i nomi `IMG-20180901-WA0007` e `IMG-20181001-WA0016` le danno
  ragione.
- Scarta dalla principale e dalla Coda, Tieni su una foto in categoria,
  backup v11 reimportato: come previsto.

### Cosa non e' stato fatto

- **Storia delle decisioni**: scartata di proposito. La storia dei percorsi la
  copre quasi tutta, perche' una categoria e' una cartella.
- Il piano completo e' in `PIANO-PROPOSTE.md`; `PIANO-SPOOL.md` resta come
  registro delle alternative valutate per la v10.

## Fase 3 — funzionalità richieste

Rivista voce per voce contro il codice il 7 settembre 2026: quasi tutto era
gia' stato scritto, spesso in una forma diversa da quella immaginata qui.
Restano aperte due voci in fondo.

- [x] **Anteprima prima di Applica.** Fatta come `QueueActivity`: le due
      meta' (da eliminare, da spostare), ogni foto visibile, *Annulla* sulle
      scelte e *Annulla tutte*. Niente caselle: annullare riporta la foto
      alla decisione di prima, che e' cio' che la casella avrebbe fatto.
- [x] **Rinomina delle cartelle.** Assorbita da `ReorganizeActivity`, come
      previsto: si rinomina la categoria, e il motore riscrive percorso e
      nome di ogni foto.
- [x] **Controlla integrità del database.** Coperta in due pezzi: i
      controlli *non catalogate in categoria* e *catalogate fuori posto*
      (`CheckActivity`) verificano che le foto stiano dove l'inventario
      dice; il riaggancio dei `media_id` morti lo fa gia' la riconciliazione
      a cascata, forzabile con *Ricostruisci l'inventario*. Un pulsante
      unico "controlla e ripara" non e' stato fatto di proposito: ogni
      controllo deve dire dove guarda e mostrare le foto prima di toccarle,
      e un pulsante unico lo nasconderebbe.
- [x] **Avanzamento della riconciliazione.** `ScanProgress`: barra
      determinata dove il totale e' noto, che gira dove non lo e'.
- ~~**Leggere l'EXIF direttamente**~~ Cancellata il 7 settembre 2026: la
      premessa era sbagliata. Verificato sul telefono leggendo le intestazioni
      dei file: le WhatsApp fino al 2023 hanno l'EXIF e l'indice ne ha gia'
      la data (6.870 foto, `date_source = EXIF`); quelle da meta' 2024 in poi
      non hanno nessun segmento EXIF — WhatsApp le spoglia dei metadati prima
      di salvarle (6.976 foto, taglio netto a luglio 2024). Non c'e' niente
      da leggere: per le recenti l'unica data e' quella nel nome, il giorno
      di ricezione, che e' quella gia' usata.
- [x] **Backup automatico.** `FotosistemisBackupAgent`, con l'interruttore
      *Backup su account Google*: e' Android a decidere quando, di solito
      una volta al giorno. Resta da ripulire `backup_rules.xml`, che e'
      ancora il file di esempio commentato — funziona (senza regole entra
      tutto), ma non dice niente.
- [x] **Cartella madre** — `settings.destinationRoot`, pulsante in
      `DestinationsActivity`.
- [x] **Percorso precompilato** — una nuova categoria parte da
      `destinationRoot + "/"`, con l'esempio del modello sotto.
- [x] **Pagina di aiuto.** `HelpActivity`, 7 settembre 2026: prima voce del
      menu, diciotto sezioni, tutto in `strings.xml`. Apre con le tre cose
      che non si deducono dall'app: non cancella mai, le decisioni stanno
      nell'inventario, niente rete.
- [x] **Ricerca duplicati, copie identiche.** `DuplicateFinder` +
      `DuplicatesActivity`: prima le dimensioni, poi i byte solo dove
      coincidono, impronte calcolate a lotti di 40 in sottofondo.
- [ ] **Ricerca duplicati, copie ricompresse.**

      *Copie ricompresse* — la foto scattata col telefono e poi mandata su
      WhatsApp: stessa immagine, byte diversi, dimensione diversa, hash
      diverso. Nessun confronto esatto la prende. La provenienza da WhatsApp
      si ricava dal percorso d'origine in `photo_paths` (nessun marcatore
      `_from_whatsapp` nel nome: non e' mai stato scritto), quindi la
      ricerca può partire di lì: per ogni categoria, prendere le foto venute
      da WhatsApp e cercare le candidate **fra le altre della stessa
      categoria**, che è un insieme piccolo. Il confronto vero richiede un'impronta percettiva (tipo pHash)
      che riconosca la stessa immagine ridimensionata: fattibile senza
      dipendenze, ma è un lavoro a sé.

## Riorganizzazione del filesystem — scritta

Il piano approvato qui e' stato eseguito: `core/reorg/FileNamer.kt`,
`core/reorg/Reorganizer.kt`, `Source.ESTIMATED`, `DISPLAY_NAME` scritto nella
stessa `update` del percorso, `createWriteRequest` a blocchi
(`MAX_FILES_PER_CONSENT`), `ReorganizeActivity`, `FileNamerTest` e
`ReorganizerTest`. Il comportamento e' documentato in *Decisioni prese*.

Unica differenza dal piano: la colonna `original_display_name` non e' mai
stata aggiunta. Il nome originale si recupera togliendo il timbro
(`CaptureDateResolver.stripStamp`) e, per il ripristino, dalla storia dei
percorsi in `photo_paths`, che tiene anche il nome.

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

**Le foto WhatsApp hanno perso l'EXIF a luglio 2024**, ma quelle precedenti
ce l'hanno ancora, con l'ora esatta. *Corretto il 7 settembre 2026 leggendo
le intestazioni di tutti i file: il taglio e' netto a luglio 2024, non "fine
2024".* Allora MediaStore restituiva `datetaken=NULL` anche per quelle e l'app
cadeva sul nome file; dal 4 settembre l'inventario legge l'EXIF da solo e le
6.870 foto fino al 2023 hanno la loro ora vera (`date_source = EXIF`).

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

Sull'archivio vero non servirebbe comunque. Delle foto WhatsApp, quelle fino
a giugno 2024 l'EXIF **ce l'hanno gia', con l'ora vera**, e dal 4 settembre
l'inventario lo legge da solo (6.870 con `date_source = EXIF`). Le 6.976
successive non hanno EXIF e il nome da' solo il giorno: scriverlo
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
