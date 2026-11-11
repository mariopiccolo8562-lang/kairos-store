# kairos-store
Kairos Store - store per le mie app Android

## Store aperto (come IBO Player)

Lo store è **aperto**: chi installa Kairos Store vede subito tutte le app e preme **Installa** (o **Scarica** dal browser), e il download parte. Niente codice del venditore all'avvio, niente «Richiedi autorizzazione», nessun blocco dopo codici sbagliati, per nessuno (titolare o agenti).

Per le app Kairos non cambia nulla: il cliente apre l'app, manda la **foto del codice** che vede sulla TV e il titolare (o l'agente) lo attiva dal pannello Kairos Control.

- **Cosa resta**: elenco delle app (da `docs/apps.json`, modificabile dal *Pannello Kairos Store*), pulsante **App consigliata per questo dispositivo** (regole dal pannello Kairos Control → *Riepilogo store*), pulsante **AGGIORNA** e aggiornamento automatico dello store.
- **Conteggi**: la pagina avvisa il pannello quando lo store si apre e quando parte un download (`/api/store/hello` e `/api/store/download` su `panel-kairos.com`). Servono solo al titolare per vedere quanti store ci sono e quanti scaricano (pannello → **Riepilogo store**); se il pannello non risponde, lo store scarica lo stesso.
- **Store già installati (versioni fino alla 1.0.129)**: la pagina dello store la scaricano da GitHub Pages a ogni apertura, quindi vedono subito quella nuova, senza codice; la pagina spegne anche il blocco dei download che l'APK vecchio ricordava. Se per qualche motivo l'APK vecchio mostra la sua copia interna della pagina (store senza internet verso GitHub), il pannello approva all'istante ogni richiesta e accetta qualunque codice. In più lo store si aggiorna da solo alla versione nuova alla prima apertura (controllo delle release su GitHub).
- Il blocco `"approval"` in `apps.json` non serve più: se c'è, viene ignorato.

> I link degli APK e i codici Downloader scritti in `apps.json` sono pubblici (il repository è pubblico): con lo store aperto è voluto.

### Download solo con codice (11 novembre 2026)

Il titolare può accendere **«Download solo con codice»** nel pannello Kairos Control (Riepilogo store). Da quel momento la pagina, prima di ogni download, chiede il via libera al pannello (`/api/store/download`): senza un codice valido su questo dispositivo esce la finestra **«🔑 Codice del rivenditore»** con un tastierino usabile col telecomando (frecce e OK), coi tasti numerici o col dito; Indietro e ANNULLA la chiudono. Il codice (6 cifre, generato dal titolare o da un agente col permesso) vale una volta, su quel dispositivo, per tutte le app e per la durata scelta dal titolare (30 minuti, 2 ore o 24 ore). Spento, il pannello risponde subito di sì e tutto è come prima. All'apertura `/api/store/hello` (o `/api/store/config` nel browser) dice se serve il codice; se il pannello non risponde lo store scarica lo stesso, tranne quando l'ultima risposta diceva che serve il codice. Il codice protegge lo store, non i file: i link diretti degli APK restano (servono agli aggiornamenti).

### Sicurezza dei download nell'APK (11 novembre 2026)

- **Solo APK Kairos firmati**: un'app Kairos (Pro `com.kairos.player`, TV, Premium, Lite, XC, Plus, Nova, Store) si installa solo se l'APK scaricato è firmato con la sua chiave (impronte SHA-256 in `KairosSign.java`, lette dalle release pubblicate). Un APK con il pacchetto di un'app Kairos ma un'altra firma non si installa («Installazione bloccata: questo file non è firmato da Kairos»). Le app degli altri non si controllano. **Se una chiave cambia, prima va aggiornata l'impronta qui.**
- **https prima di http**: un link http (anche un reindirizzamento) si prova prima in https; se non risponde o dà errore si torna a http da soli (`StoreLinks.secure`, `apkUrls`).

## Indirizzi dei download

L'APK dello store prende la pagina, il suo aggiornamento e le app Kairos solo da `https://store-k.download` (costante `DL_BASE` in `MainActivity.java`) e, se non risponde bene, dalle riserve con gli stessi percorsi (`/store/`, `/get/store.json`, `/get/<app>.apk`): `DL_BASE2` (variabile `KAIROS_DL_BASE2` del repository, vuota finché non c'è un secondo dominio) e i domini che `/get/store.json` annuncia in `mirrors` (la lista `DL_HOSTS` del pannello: lo store li ricorda e li usa dall'apertura dopo). Niente pannello né GitHub negli aggiornamenti e nei download (dal 10 novembre 2026); per la pagina restano GitHub Pages (variabile `STORE_URL`, se c'è) e alla fine la copia dentro l'APK. Nella pagina i link `/get/…` delle app Kairos sono relativi quando la pagina arriva dal dominio dei download (o da una sua riserva), di `https://store-k.download` quando arriva dal pannello, da GitHub Pages o dalla copia nell'APK.

**App consigliata (11 novembre 2026)**: il riquadro mostra la scheda del dispositivo (modello, processore, RAM totale e libera, memoria interna, Android, schermo e HDR, scheda video, H.265 e 4K) e sotto le app Kairos dalla più leggera, con ✓ se il dispositivo ha abbastanza memoria e ✗ se ne serve di più. La memoria richiesta da ogni app e l'app per le chiavette deboli (di serie Kairos-XC) si cambiano nel pannello Kairos Control (Download store → App consigliata). Processore, memoria interna, H.265/4K e HDR li legge l'APK (`DeviceSpecs.java`, `KairosStore.deviceSpecs()`, dalla 1.0.134); gli store più vecchi mostrano modello, RAM, Android, core, schermo e scheda video.

## Pannello (worker/)

Il **PANNELLO KAIROS STORE** (`https://kairos-store.<account>.workers.dev`) è il Worker Cloudflare in `worker/index.js`.
Ogni modifica a quella cartella viene pubblicata da sola da GitHub Actions (*Deploy pannello (Worker)*), che usa i
segreti del repository `CLOUDFLARE_API_TOKEN` e `CLOUDFLARE_ACCOUNT_ID`. Da ora il codice si cambia qui, non in
*Edit code* su Cloudflare: una modifica fatta lì verrebbe sovrascritta al deploy successivo.
