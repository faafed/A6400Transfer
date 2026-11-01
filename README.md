# A6400 Transfer

App Android per scaricare le foto dalla Sony Alpha 6400 via Wi-Fi (la stessa modalità "Invia a smartphone" di Imaging Edge Mobile). Le foto finiscono nella Galleria, cartella **Sony A6400**.

## 1. Ottenere l'APK (una volta sola)

1. Su github.com crea un repository **privato** (es. `A6400Transfer`).
2. Carica tutto il contenuto di questa cartella (*Add file > Upload files*), cartelle comprese. Controlla che esista `.github/workflows/build-apk.yml`: se la cartella nascosta `.github` non è stata caricata, crea il file a mano con *Add file > Create new file* e incolla il contenuto.
3. Apri la scheda **Actions > Build APK**. Dopo 3-5 minuti scarica l'artifact **A6400Transfer-apk** (uno zip con `app-debug.apk`).
4. Copia l'APK sul telefono e installalo (Android chiederà di consentire l'installazione da fonti sconosciute).

Se la build fallisce, apri il passaggio rosso in Actions e copia l'errore.

## 2. Usare l'app

1. Fotocamera: tasto Riproduzione, poi tasto **Invia** (o Menu > Rete > Invia a smartphone) e scegli le foto. Premi il tasto **Cestino** per vedere SSID e password.
2. App: inserisci SSID e password, tocca **Connetti** e conferma la richiesta di Android. In alternativa, se sei già connesso al Wi-Fi della fotocamera, tocca **Sono già connesso**.
3. Tocca una foto per ingrandirla (pizzica o doppio tap per lo zoom, scorri per le altre). Seleziona con il cerchio in alto a destra della miniatura, poi **Scarica**. Dal menu **⋯** trovi Seleziona nuove/tutte, Aggiorna elenco e Fine trasferimento.
4. Quando hai finito scegli **Fine trasferimento** dal menu ⋯: la fotocamera esce dalla modalità di invio.

## Limiti

- **RAW (ARW)**: via Wi-Fi la A6400 non li fornisce. Con RAW+JPEG scarichi il JPEG; con solo RAW ricevi un JPEG convertito. Per i RAW originali servono cavo USB o lettore di schede.
- Il protocollo è ricostruito dal lavoro di reverse engineering di ImagingEdge4Linux (provato su A6400). Questa app non è stata ancora provata su una fotocamera reale.
