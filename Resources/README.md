# Resources

Sorgenti grafiche e gli strumenti per ricavarne le risorse dell'app. Niente
di questa cartella entra nell'APK.

- `splash.png` — l'artwork originale (1254×1254). Copiato tale e quale in
  `app/src/main/res/drawable-nodpi/splash.png` per la splash all'avvio.
- `IconMaker.java` — ricava da `splash.png` il livello in primo piano
  dell'icona adattiva (`mipmap-*/ic_launcher_foreground.png`) e un'anteprima
  di come i launcher la mascherano. Lo sfondo a gradiente e' in
  `drawable/ic_launcher_sky.xml`, il livello monocromatico in
  `drawable/ic_launcher_monochrome.xml`.

## Rigenerare l'icona

Serve solo una JVM (va bene quella di Android Studio). Da questa cartella:

```
java IconMaker.java splash.png probe
```

stampa i colori lungo i bordi, per trovare dove inizia l'artwork. Poi:

```
java IconMaker.java splash.png make ../app/src/main/res 160 160 934 70 62 A3DFFD,C6EDFD preview.png
```

Gli argomenti dopo `make`: cartella `res` di destinazione; `x y lato` del
ritaglio quadrato in pixel dell'originale (dentro il bordo dell'artwork);
larghezza in pixel della fascia sfumata; quanti dp dei 108 del canvas occupa
l'artwork (62 tiene sole e griglia nel cerchio sicuro di 66 dp); i due colori
del cielo, in alto e in basso, gli stessi di `ic_launcher_sky.xml`; il file
dell'anteprima.

Controlla `preview.png` prima di compilare: cerchio a sinistra, squircle a
destra, nessuna cucitura fra artwork e sfondo.
