# Lavoro in corso

Stato al 6 agosto 2026.

## Da verificare sul dispositivo — prima di ogni altra cosa

**Nove commit non sono mai stati eseguiti su un telefono.** Sono stati
compilati e i test passano, ma l'estrazione in `core`, lo schema v5 e la sua
migrazione toccano i dati e non li ha visti girare nessuno.

Ordine consigliato:

1. **Esporta i dati** dalla versione installata, prima di aggiornare
   (Cartelle → Esporta dati). Sarà un file di formato 1, quindi non
   reimportabile dalla nuova versione, ma resta leggibile a mano.
2. Installa, apri la schermata principale, **lascia finire la
   riconciliazione**: popola l'inventario, senza il quale il resto non trova
   nulla.
3. Verifica che le foto già archiviate mostrino ancora il loro stato: è la
   prova che la migrazione v4 → v5 ha riagganciato le righe ai nuovi id.
4. **Cartelle → Riconosci foto già ordinate**, e controlla che i numeri per
   categoria corrispondano a quello che ti aspetti. Guarda le foto prima di
   confermare.
5. Il modello globale `{anno}-{etichetta}` non è mai stato provato. Le
   destinazioni migrate da versioni precedenti usano `{anno}`: per cambiarlo
   vanno aperte a mano.

## Fase 3 — funzionalità richieste, non ancora scritte

- [ ] **Anteprima con checkbox prima di Applica.** Una sezione per le foto
      che vanno in eliminazione, una per quelle che si spostano, ogni voce
      con una casella per escluderla. Escludere annulla la transazione per
      quella foto e la riporta a *mantenuta*.
      Metà del lavoro è fatta: `PhotoPreviewActivity` esiste ed è stata
      scritta per essere riusata qui.
- [ ] **Rinomina delle cartelle** con riallineamento del database. Deve
      aggiornare i percorsi nelle righe, rinominare sul disco, e mostrare
      un'anteprima prima di agire. MediaStore non ha una rinomina di
      cartella: significa spostare ogni foto contenuta, veloce e con un solo
      consenso, ma la cartella vuota può restare.
- [ ] **Controlla integrità del database.** Verifica che le foto stiano dove
      l'ultimo percorso registrato dice, e ripara: le righe il cui `media_id`
      non risolve più vanno riagganciate tramite il riconoscimento a cascata.
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
