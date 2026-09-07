# Piano: le decisioni non ancora eseguite

Stato: **proposta, non implementata.** Da approvare prima di scrivere codice.

## Il problema

Decidere scrive due cose in due posti con due vite diverse:

- la **decisione** va subito in `photo_state`, nel database, e resta;
- lo **spostamento** entra in una lista in memoria e aspetta Applica.

Fra i due momenti l'archivio e' incoerente per costruzione: il database
dice che la foto sta in Famiglia mentre il file e' altrove. E se l'app
viene chiusa, la meta' in memoria sparisce e resta una decisione che non
si eseguira' mai da sola. E' l'origine delle "arretrate".

## Cosa deve valere dopo

1. Prima di Applica, nel database non c'e' nulla di **definitivo**.
2. Le decisioni prese sopravvivono alla chiusura dell'app.
3. Scartare la coda riporta esattamente allo stato precedente.
4. Ogni schermata che chiede "cosa e' stato deciso?" ottiene la stessa
   risposta, che il lavoro sia stato eseguito o no.

## La forma scelta

Una colonna sola su `photo_state`: **`pending`**, 1 finche' il file non si e'
mosso, 0 dopo.

- `status` dice **cosa hai deciso**: `categorized` con la sua categoria,
  oppure `trashed`.
- `pending` dice **se e' gia' avvenuto**.

Finche' `pending` vale 1 non c'e' niente di definitivo: scartare cancella
quelle righe e il database torna esattamente com'era prima. Applicare le
porta a 0, e da quel momento descrivono un fatto.

Non `applied_at`: un nome che si legge come una data e si usa come un flag
obbliga a ricordare una convenzione. Il momento dell'esecuzione non si
perde comunque — e' gia' in `photo_paths`, che registra ogni spostamento
con la sua ora.

Storia delle decisioni: **non si fa**. La storia dei percorsi la copre quasi
tutta, perche' una categoria e' una cartella. Resterebbe fuori solo una
decisione cambiata prima di applicare, che non ha mai toccato il
filesystem, e non vale una tabella in piu' scritta a ogni scorrimento.

## Due conseguenze che semplificano

**Cambiare periodo o cartella non deve piu' chiedere "applica o scarta".**
Oggi lo chiede perche' la coda vive nella sessione e la sessione muore al
ricaricamento. Con lo spool le decisioni sono fatti nel database, non
appartengono alla vista: si decide a settembre, si passa ad agosto, si
decide ancora, si applica alla fine. Quel dialogo era il prezzo di un
difetto, non una tutela.

Ne segue che **Applica diventa globale**: applica tutto il pendente,
ovunque sia stato deciso. Perche' non sorprenda, l'anteprima a card resta
obbligatoria — si vede cosa si sta per scrivere — e la Coda elenca sempre
tutto il pendente.

**Non serve registrare quale periodo e cartella erano scelti.** La domanda
"quali di queste ho deciso mentre guardavo qui" si risponde gia' oggi
incrociando il pendente con le foto attualmente caricate, che sono la vista.
Una colonna in piu' direbbe la stessa cosa e potrebbe diventare falsa; una
selezione registrata invecchia, una foto no.

## Due strade

### A. Tabella separata `pending_changes`

Una tabella nuova: `photo_id`, `status`, `destination_id`, `target_path`,
`new_display_name`, `recorded_at`. `photo_state` torna a significare
soltanto **cio' che e' avvenuto**.

*Pro:* separazione netta, `photo_state` non mente mai.
*Contro:* ogni interrogazione su "cosa e' deciso" diventa l'unione di due
tabelle. Sono almeno dieci punti, elencati sotto, e ognuno e' un posto
dove dimenticarsene.

### B. Colonna `applied_at` su `photo_state` — **consigliata**

Una colonna sola. `NULL` significa deciso e non ancora eseguito; valorizzata
significa fatto. La riga esiste da subito ma **non e' definitiva**, ed e' lo
spool a tutti gli effetti.

*Pro:* le interrogazioni che chiedono "e' deciso?" restano quelle di oggi e
continuano a funzionare: nessuna dimenticanza possibile. Solo chi ha bisogno
di sapere se e' stato **eseguito** aggiunge una condizione, e sono pochi
punti ben identificati. Scartare = cancellare le righe con `applied_at IS
NULL`. La persistenza viene gratis.
*Contro:* la distinzione vive in una colonna invece che in una tabella;
meno evidente a chi legge lo schema per la prima volta.

## Cosa tocca (verificato, non ipotizzato)

Leggono le decisioni: `PhotoStateRepository`, `PhotoInventory`,
`MainActivity`, `QueueActivity`, `CheckActivity`, `DuplicatesActivity`,
`ReorganizeActivity`, `PlacementActivity`, `DestinationsActivity`,
`BackupRepository`.

Scrivono decisioni: `ReviewSession` (catalogare, eliminare, conservare,
annullare, scartare), `GridActivity`, `DuplicatesActivity`, `MainActivity`
(categoria da cartella).

## Cosa cambia, punto per punto (ricontrollato)

Con `pending` la maggior parte delle interrogazioni **non si tocca**: chi
chiede "e' deciso?" guarda l'esistenza della riga, e la riga c'e' da subito.
Cambiano solo quelle che hanno bisogno di sapere se e' stato **fatto**.

| interrogazione | oggi | dopo |
|---|---|---|
| filtro di stato, revisione | riga presente | invariato |
| riconosci gia' ordinate | riga presente | invariato |
| estranei in categoria | nessuna riga | invariato |
| doppioni, flag "catalogata" | riga presente | invariato |
| **coda: da eliminare in attesa** | stato + percorso + storia | `trashed AND pending` — piu' semplice e piu' vero |
| **catalogate fuori posto** | include le decise-non-mosse | solo `pending = 0`: una decisa non e' fuori posto, e' da fare |
| **riorganizza** | come sopra | solo `pending = 0` |
| **conteggi per categoria** | tutte | il fatto, con accanto il pendente |
| **backup / esporta** | esporta lo stato | deve esportare anche `pending`, o perde il lavoro non applicato |

Restano da fare con attenzione:

- **`BatchMover`** porta a 0 solo cio' che e' andato a buon fine. Una mossa
  fallita resta pendente, ed e' giusto: e' ancora da fare.
- **Percorso d'origine.** `record()` scrive anche la posizione originale, che
  serve a "conserva". Va scritta al momento della decisione, finche' la foto
  e' ancora a casa sua.
- **Scartare** cancella le righe con `pending = 1`. Le decisioni gia'
  eseguite non si toccano: quelle non sono in coda.
- **La coda in memoria** di `ReviewSession` diventa una vista sullo spool,
  non una seconda copia. Due liste che possono divergere sono il difetto che
  si sta togliendo.
- **Migrazione.** Ogni decisione esistente diventa `pending = 0` se la foto
  e' gia' dove la sua decisione implica, `pending = 1` altrimenti. Le
  seconde sono le arretrate di oggi, e per la prima volta si vedrebbero
  tutte in un posto solo.

## Ordine dei lavori

1. Schema v10 e migrazione, con i test.
2. `PhotoStateRepository`: scrivere in sospeso, marcare eseguito, scartare.
3. `ReviewSession`: la coda legge dallo spool.
4. `BatchMover`: marca eseguito solo cio' che e' andato a buon fine.
5. Le interrogazioni dei punti 2-6, una per una, con i test.
6. Migrazione delle arretrate e verifica sui numeri veri del telefono.

## Rischio

E' l'invariante centrale dell'app. La strada B lo riduce molto: cio' che
oggi funziona continua a funzionare, e si aggiunge solo la distinzione fra
deciso e fatto. La strada A e' piu' pulita e piu' pericolosa.
