# MirrorGazeTest1

**MirrorGazeTest1** er en Android-app som kombinerer en 5-knapp staveapp med head pose estimation for oeystyring. Basert paa [Test11MirrorPartnerEyes](https://github.com/eyvindbogstrand/Test11mirrorpartnereyes).

Appen er designet for personer som ikke kan snakke (ALS, CP, afasi, etc.) og trenger et gratis alternativ til dyre oeystyringssystemer.

## Hva er dette?

- **Staveapp**: Skriv med 5 knapper -- to klikk per bokstav (foerst gruppe, deretter posisjon)
- **Oeystyring**: Beveg hodet for aa velge knapper automatisk (dwell-time: 500ms)
- **TTS**: Appen leser opp det du skriver (norsk)
- **Kalibrering**: 3-stegs kalibrering for aa tilpasse seg din unike hodebevegelse

## Teknologi

- **MediaPipe Face Landmarker**: Gratis, lokalt paa enheten
- **Head Pose Estimation**: Nesens offset fra ansiktssenter
- **CameraX**: Moderne Android kamera-API
- **EMA-glatting**: Exponential Moving Average for jevne bevegelser

## Bygge-instruksjoner

### Steg 1: Last ned MediaPipe-modellen

Last ned `face_landmarker.task` fra denne lenken:
```
https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
```

Legg filen i: `app/src/main/assets/face_landmarker.task`
(Opprett `assets`-mappen hvis den ikke finnes.)

### Steg 2: Aapne i Android Studio

1. Aapn prosjektmappen i Android Studio
2. La Gradle synkronisere
3. Koble til Android-enhet (API 24+ / Android 7.0+)
4. Trykk **Run**

### Steg 3: Kjoer appen

1. Godta kamera-tillatelse
2. **Kalibrering** starter automatisk:
   - Se rett frem i 3 sekunder
   - Se til venstre i 2 sekunder
   - Se opp i 2 sekunder
3. Etter kalibrering: Beveg hodet for aa styre den roede prikken
4. Hold blikket over en knapp i 0.5 sekund = automatisk "klikk"

## Stave-system (5 knapper)

Knappene er plassert i et **speilvendt** 3-2 rutenett:

```
+--------+--------+--------+
|   3    |   2    |   1    |
| KLMNO  | FGHIJ  | ABCDE  |
+--------+--------+--------+
|   5    |   4    |
| VY AE  | PRSTU  |
| OE AA  |        |
+--------+--------+
```

*(Merk: Knappeplasseringen er speilvendt slik at oeystyringen fungerer korrekt med frontkamera.)*

### 2-klikk system

Foerste klikk velger **gruppe**, andre klikk velger **posisjon** i gruppen:

| Kombinasjon | Bokstav |
|-------------|---------|
| 11 | a | 12 | b | 13 | c | 14 | d | 15 | e |
| 21 | f | 22 | g | 23 | h | 24 | i | 25 | j |
| 31 | k | 32 | l | 33 | m | 34 | n | 35 | o |
| 41 | p | 42 | r | 43 | s | 44 | t | 45 | u |
| 51 | v | 52 | y | 53 | ae | 54 | oe | 55 | aa |

### Langt trykk (snarvei)

Hold inne en knapp for aa skrive bokstaven direkte:

| Knapp | Bokstav |
|-------|---------|
| 1 | a |
| 2 | g |
| 3 | m |
| 4 | t |
| 5 | aa |

### Andre knapper

- **NYTT ORD** (`spaceBar`): Les opp teksten og legg til mellomrom
- **SLETT TEKST** (`deleteBar`): Tøm all tekst og stopp tale

### Eksempel: Skrive "hei"
- **H**: Klikk 2 (gruppe) -> Klikk 3 (posisjon) = **h**
- **E**: Klikk 1 (gruppe) -> Klikk 5 (posisjon) = **e**
- **I**: Klikk 2 (gruppe) -> Klikk 4 (posisjon) = **i**

## Tilpasning

### Oekt sensitivitet
I `GazeTracker.kt`:
```kotlin
val gazeSensitivityX = 3.0f  // Hoeyere = mer sensitiv
val gazeSensitivityY = 4.0f
```

### Endre dwell-time
I `MainActivity.kt`:
```kotlin
val DWELL_MS = 500L  // Millisekunder foer "klikk"
```

## Feilsoeking

| Problem | Loesning |
|---------|----------|
| "Kunne ikke initialisere" | Sjekk at `face_landmarker.task` er i `app/src/main/assets/` |
| Kamera svart | Godta kamera-tillatelse. Restart app. |
| Dot beveger seg ikke | Sjekk lysforhold -- godt lys hjelper |
| For treig respons | Oek `gazeSensitivityX/Y` i GazeTracker.kt |

## Lisens

Dette prosjektet er fritt tilgjengelig for aa hjelpe personer med kommunikasjonsvansker.

---

**Utviklet for**: oeystyring.no
