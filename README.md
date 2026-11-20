# Ketten Live Wallpaper — Organic Hardware

Een interactieve, 100% native Kotlin **3D ASCII Live Wallpaper** voor Android, ontworpen voor AMOLED-smartphonescreens.

<p align="center">
  <img src="docs/ketten-demo.gif" alt="Ketten Live Wallpaper live demo op Galaxy S25 Ultra" width="320" />
</p>

---

## Wat doet het?

- **3D `KETTEN`-logo met diepte-sway** — Een volumetrisch 3D ASCII-logo op sub-pixelniveau dat reageert op het kantelen van je telefoon, het swipen tussen homescreens en aanrakingen (inclusief periodieke lichtglans en tik-schokgolf).
- **Gyroscoop & homescreen-parallax** — De voorgrondranken, verre achtergrondranken en wortels verschuiven in meerdere dieptelagen mee via de hardware-zwaartekrachtsensor (`TYPE_GRAVITY`) en homescreen-swipe (`onOffsetsChanged`).
- **PCB × Mycelium wortelstelsel** — Onderin groeit een combinatie van organische wortels en 45°-printplaatsporen met oplichtende soldeerpads rondom een centrale USB-C power-nexus (`[=o=]`).
- **Interactieve touch-effecten** — Tikken of slepen veroorzaakt windrimpelingen door bladeren en ranken, schudt gloeiende sporen los en stuurt bioluminescente energiepulsen omhoog door de stengels.
- **24-uurs *Spectral Recipe*** — De kleuren verschuiven automatisch met het tijdstip van de dag tussen *Ochtend (480nm)*, *Dag (Full-Spec)*, *Avond (660nm)* en *Nacht (Biolum)* — of kies handmatig een vast spectrum in de app.
- **Oplaad- & batterijvisualisatie** — Zodra de telefoon aan de lader hangt, pulseren er continu energiepulsen vanuit de onderkant de plant in.
- **Rustig midden voor iconen & widgets** — Langs de randen gedetailleerde flora en bloeiende toppen (`· o * ¤`), in het midden ruimte voor je apps en een subtiele regel onder `── ORGANIC // HARDWARE ──` met **tijd · datum · batterij%**.
- **3D cilindrische stammen & dieptelagen** — Voorgrondstammen met cilindrische licht-/schaduwshading, gebogen organische schorsnerven, warm rim-light richting de lampjes en 3 atmosferische dieptelagen (achtergrond, middenplan, voorgrond).
- **Organische dagcyclus (06:00–06:00)** — Rustige, open begroeiing in de ochtend (`06:00–09:00`), geleidelijke groei overdag (`09:00–18:00`), weelderige avondpiek (`18:00–23:00`) en vloeiende nachtafbouw (`23:00–06:00`) zonder sprong om middernacht.
- **Instellingenscherm met live preview** — Direct toegankelijk vanuit de app én via de *Instellingen*-knop in Android's wallpaper-kiezer (`android:settingsActivity`): animatiesnelheid (`0.2x–2.5x`), aantal bloom-lampjes (`0–24`), toggles voor wind/lampjes/groei en een 24-uurs tijd-simulatieslider.
- **AMOLED-vriendelijk** — Puur `#000000` achtergrond, adaptieve FPS (20 FPS rust / 30 FPS actief / 15 FPS batterijbesparing), bitmap-caching en pixel-shift tegen inbranden.

---

## Download & Installatie

1. **Download de APK** vanaf de [Releases-pagina](https://github.com/ketten-dev/nlkettenlivewallpaper/releases/latest) (`ketten-live-wallpaper-v1.1.0.apk`).
2. **Installeer de APK** op je Android-telefoon (sta eenmalig installeren van onbekende apps toe voor je browser of bestandsbeheerder als Android daarom vraagt).
3. **Open `Ketten Live Wallpaper`** vanuit je app-overzicht:
   - Tik en sleep op het scherm om de animatie direct uit te proberen.
   - Kies eventueel een kleurmodus met de knop **Spectrum**.
   - Tik op **Achtergrond instellen** → kies **Startscherm** (of **Start- en vergrendelscherm**).

*(Minimale Android-versie: Android 15 / API 35+)*

---

## Zelf bouwen vanuit broncode

```bash
git clone https://github.com/ketten-dev/nlkettenlivewallpaper.git
cd nlkettenlivewallpaper
./gradlew :app:assembleDebug
```

De gebouwde APK verschijnt in `app/build/outputs/apk/debug/app-debug.apk`.
