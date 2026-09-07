# Piano: la verita' e la proposta

Stato: **implementato** (schema v12, tabella `proposals`). Scritto il 7 settembre 2026, dopo lo schema v11; approvato e applicato lo stesso giorno con le tre raccomandazioni qui sotto. Sostituisce `PIANO-SPOOL.md`.

Una deviazione dal testo: le funzioni `migrateToVersion10` e `migrateToVersion11` sono state **tolte**, non svuotate. La v12 riconosce da sola una base v10/v11 (dalla colonna `pending`) e la converte; una base v9 o precedente salta direttamente a v12. Tenere due migrazioni che avrebbero aggiunto colonne per poi toglierle nella stessa apertura non aveva senso.
Sostituisce il modello di `PIANO-SPOOL.md` (che resta come registro).

## Il problema

Con `pending` la riga di `photo_state` dice due cose con le stesse colonne:
finche' `pending = 1`, `status` e' cio' che si vuole fare; dopo, e' cio'
che e' avvenuto. Una decisione nuova sovrascrive la vecchia, e per non
perderla e' servito `previous_status` (v11), e poi una regola speciale per
Tieni, e poi un caso limite documentato. Ogni toppa e' corretta e ognuna
esiste perche' il modello mette intenzione e fatto nella stessa casella.

## Il modello

Due cose, in due posti, con nomi che dicono cosa sono.

**`photo_state` — la verita'.** Cosa e' gia' avvenuto a una foto.
`status` non mente mai sul filesystem:

| `status`      | significa                                              |
|---------------|--------------------------------------------------------|
| (nessuna riga)| mai vista                                               |
| `kept`        | guardata e lasciata dov'era; nessun file toccato        |
| `categorized` | sta nella cartella della categoria `destination_id`     |
| `trashed`     | sta nel cestino dell'app, o consegnata a quello di Android |

**`proposals` — la proposta.** Cosa si vuole fare, non ancora fatto.
Una riga per foto al massimo, e solo finche' e' in sospeso.

| colonna          | contenuto                                             |
|------------------|-------------------------------------------------------|
| `photo_id`       | chiave; una proposta per foto                          |
| `action`         | `file` / `trash` / `restore`                           |
| `destination_id` | la categoria, solo per `file`                          |
| `proposed_at`    | quando; ordina la Coda                                 |

Applica esegue la proposta e la trasforma in verita'. Scarta la cancella.
Non c'e' un "prima" da ricordare perche' la verita' non e' mai stata
toccata.

### Cosa NON va nella proposta, e perche'

- **Il percorso di destinazione.** Si ricalcola all'esecuzione dalla
  categoria (`MovePlanner`), come oggi: se nel frattempo la categoria e'
  stata spostata, la foto va dove la categoria punta ora. Salvarlo
  congelerebbe una scelta gia' cambiata.
- **Il nome stampato.** Stessa ragione; lo produce `FileNamer` al momento.
- **L'origine per il ripristino.** E' gia' in `photo_paths` (kind
  `original`), scritta alla prima decisione. Copiarla farebbe due verita'.
- **Lo stato precedente.** Non serve piu': la verita' resta in
  `photo_state` finche' la proposta non viene eseguita.
- **Il nome del file al momento della proposta.** Lo ha `photos`.

Quindi la proposta e' esattamente `(foto, azione, categoria, quando)`.
Tutto il resto e' derivabile, e derivarlo e' piu' sicuro che copiarlo.

### Azioni, non stati

`action` non riusa `ReviewStatus`: una proposta e' un verbo (archivia,
butta, riporta a casa), una verita' e' uno stato. Oggi il ripristino e'
"`kept` in sospeso", che si capisce solo sapendo la convenzione.
Nuovo enum `Proposal.Action { FILE, TRASH, RESTORE }` in `core/model`.
L'esito di ogni azione, una volta eseguita, e' fissato in un posto solo:
`FILE -> categorized`, `TRASH -> trashed`, `RESTORE -> kept`.

## Cosa significa Tieni

`kept` vuol dire "guardata e lasciata dov'e'": e' cio' che toglie una
foto dalla lista da smistare senza spostarla. E' una verita' sulla
revisione, non sul file.

Con il modello nuovo Tieni ha una regola sola: **cancella la proposta,
e se la foto non aveva una verita' le scrive `kept`.** Non sovrascrive
mai una verita': una foto in Famiglia tenuta resta `categorized`
Famiglia (oggi diventa `kept` e la categoria si perde dal record). Nel
cestino Tieni non esiste, il gesto e' Ripristina, come oggi.

Annullabile solo quando ha scritto `kept`; quando ha solo cancellato una
proposta non c'e' nulla da restituire, e Annulla non lo raggiunge.

## Coda e spool con la proposta

Oggi `ReviewSession` tiene tre cose da mantenere allineate: la mappa degli
stati, la lista `pendingMoves`, e l'elenco `decidedHere` per Annulla. La
lista delle mosse e' una copia in memoria dello spool, ricostruita ad ogni
caricamento e aggiornata a mano a ogni decisione (`queueMove`, `dequeue`).

Con la tabella delle proposte lo spool e' un oggetto con un nome, e la
sessione ne tiene lo specchio per il perimetro caricato:

- `truths: Map<Long, StoredState>` — `photo_state`;
- `proposals: Map<Long, Proposal>` — `proposals`, nel perimetro;
- `decidedHere: List<Long>` — resta, serve solo ad Annulla.

**`pendingMoves` sparisce.** Le mosse si derivano quando servono:
`queuedMoves = proposals.values.mapNotNull { MovePlanner.plan(it, ...) }`
e `pendingCount = proposals.size`. Non c'e' piu' nulla da tenere in
sincronia, e "non allineate" non puo' piu' accadere per costruzione:
una mossa esiste se e solo se esiste la proposta.

Cio' che vede l'utente sulla foto — "l'hai messa in Viaggi" — e' la
proposta se c'e', altrimenti la verita': `shownStatus(id) =
proposals[id]?.asStatus() ?: truths[id]?.status`. I lettori che vogliono
la verita' (controlli, cestino, riconoscimento) leggono `truths`.

Il conteggio della Coda in `MainActivity` e la schermata Coda leggono la
tabella `proposals` direttamente, come oggi leggono `loadOwedWork()`.

## Migrazione: schema v12

Sequenza, idempotente, in una transazione:

1. `CREATE TABLE IF NOT EXISTS proposals`.
2. Se `photo_state` ha ancora `pending`: per ogni riga `pending = 1`
   inserisci la proposta (`action` da `status`: `categorized -> file`,
   `trashed -> trash`, `kept -> restore`; `proposed_at = updated_at`),
   poi riporta la riga alla verita': `status = previous_status` se non
   nullo, altrimenti cancella la riga.
3. Ricostruisci `photo_state` senza `pending`, `previous_status`,
   `previous_destination_id` (crea, copia, elimina, rinomina: SQLite non
   toglie colonne su tutte le versioni di Android in uso).
4. `proposeUnfinishedWork`: le condizioni di `classifyOwedWork` (v10)
   riscritte per produrre proposte da righe di verita' il cui file non e'
   dove la verita' dice, e cancellare quelle righe. Copre le basi dati
   pre-v10 e i backup vecchi. Idempotente: su una base gia' coerente non
   trova nulla.

v10 e v11 non sono mai andate su un telefono (solo build di sviluppo).
**Proposta:** le loro migrazioni restano nel codice — l'APK v11 e' stato
consegnato oggi e potrebbe essere installato — ma `migrateToVersion10`
perde la classificazione, che passa al punto 4. Cosi' la logica di
"cosa e' ancora dovuto" esiste in un posto solo.

## Backup

- `COLUMNS` acquista `proposals`; `photo_state` perde le tre colonne.
- Import di un file v2 con `pending`: le righe con `pending = 1` vanno in
  `proposals` (con `previous_*` a riportare la verita'), le altre in
  `photo_state`. Import di un file pre-v10: `photo_state` intero, poi
  `proposeUnfinishedWork`. E' lo stesso punto 2 e 4 della migrazione,
  applicato al JSON invece che alla tabella: una funzione condivisa in
  `Schema`, chiamata da entrambi.

## File toccati

**core**

- `model/Proposal.kt` (nuovo): `Proposal(photoId, action, destinationId,
  proposedAt)`, `Action`, `Action.outcome(): ReviewStatus`.
- `data/Schema.kt`: v12, `CREATE_PROPOSALS`, `migrateToVersion12`,
  `proposeUnfinishedWork` (da `classifyOwedWork`), rebuild di
  `photo_state`; `migrateToVersion10` senza classificazione.
- `data/PhotoStateRepository.kt`: `StoredState` torna a
  `(photoId, status, destinationId)`; via `pending`, `previous_*`,
  `decisionToKeep`, `REVERT_SQL`, `Discarded`, `takeBack`.
  `record` scrive verita'; `markCarriedOut` / `recordSystemBin` scrivono
  l'esito della proposta e la cancellano.
- `data/ProposalRepository.kt` (nuovo): `loadAll`, `propose`,
  `proposeAll`, `withdraw(id)`, `withdrawAll(ids)`, `withdrawEvery()`,
  `loadOwed()` (join con `photos`, sostituisce `PhotoInventory.loadOwedWork`
  e `loadPendingTrash`).
- `data/PhotoInventory.kt`: via `loadOwedWork`, `loadPendingTrash`,
  `OwedWork`; le query "solo eseguite" perdono `pending = 0` (la verita'
  e' sempre eseguita).
- `review/MovePlanner.kt`: `forOwed` diventa `plan(photo, proposal,
  destination, pattern, origin)`.
- `review/ReviewSession.kt`: `truths` + `proposals`, via `pendingMoves`,
  `queueMove`, `dequeue`, `rebuildQueueFromSpool`, `keepAsBefore`;
  `queuedMoves` derivato; `shownStatus`; `keepCurrent` con la regola sola;
  `undoLastMove` = `withdraw` o, se aveva scritto `kept`, `forget`;
  `discardQueue` = `withdrawAll` nel perimetro; `commitRestores` sparisce
  (l'esito lo scrive `markCarriedOut`).
- test: `ReviewSessionTest`, `MovePlannerTest`, `StoredStateTest` (via),
  `ProposalTest` (nuovo: esiti, migrazione delle righe pending su stub),
  `SchemaTest`.

**app**

- `MainActivity.kt`: `load` carica anche le proposte; contatore Coda da
  `ProposalRepository`; `rememberDecision` e il filtro usano
  `shownStatus`; `undoFiling` con `forgetAll` (verita', invariato).
- `QueueActivity.kt`: legge `ProposalRepository.loadOwed()`; Annulla =
  `withdrawAll` / `withdrawEvery`.
- `GridActivity.kt`: `propose` / `withdraw` al posto di `record` /
  `forget`.
- `BatchMover.kt`: `move.action` al posto di `move.status` per la scelta
  cestino di Android.
- `CheckActivity.kt`, `DuplicatesActivity.kt`: leggono verita', invariati
  salvo il tipo.
- `BackupRepository.kt`: tabella `proposals`, import come sopra.
- `strings.xml`: testi di Scarta / Annulla tornano semplici ("le proposte
  vengono ritirate, le foto restano com'erano"), via "com'erano prima".
- `TODO.md`, `PIANO-SPOOL.md` (stato: superato da questo).

Ordine: core (modello, schema, repository, sessione, test verdi), poi app,
poi backup, poi build e APK. Un commit per blocco.

## Da decidere prima di iniziare

1. Tabella `proposals` separata (proposto) oppure due colonne
   `proposed_action` / `proposed_destination_id` su `photo_state`? La
   tabella evita di togliere il `NOT NULL` a `status` e da' allo spool un
   nome; le colonne evitano un join. Raccomando la tabella.
2. Tieni su una foto gia' in categoria: non tocca la verita' (proposto)
   oppure la riscrive `kept` come oggi?
3. Le migrazioni v10/v11 restano (proposto, per l'APK di oggi) oppure si
   tolgono e v12 parte da v9?
