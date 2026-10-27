# FlowFrames Android

Réimplémentation native Android du pipeline de **[FlowFrames](https://github.com/n00mkrad/flowframes)** :
interpolation d'images vidéo par IA (RIFE), 100 % sur l'appareil, sans PC et sans internet.

L'application d'origine est un programme Windows (C#/.NET + binaires NCNN/CUDA). Ce module
`android/` ne réutilise pas ce code : il refait le même pipeline avec les API Android.

```
vidéo source ──► MediaCodec (décodage matériel) ──► RIFE 4.9 / ONNX Runtime
              ──► MediaCodec (encodage matériel) ──► MediaMuxer (MP4 + audio copié)
```

## Installation

L'APK signé est fourni avec le module : `android/app/build/outputs/apk/release/app-release.apk`
(copie de distribution : `artifacts/FlowFrames-Android-1.0.0.apk`).

1. Copiez l'APK sur le téléphone (ou `adb install -r app-release.apk`).
2. Autorisez l'installation depuis une source inconnue si Android le demande.
3. L'application est signée avec une clé auto-signée (voir « Signature » plus bas), il n'y a donc
   pas de mise à jour par le Play Store.

Prérequis : Android 8.0 (API 26) ou plus récent, processeur ARM 64 bits.
L'APK fourni ne contient que `arm64-v8a` (tous les téléphones depuis ~2016) ; pour un émulateur
x86_64 ou un appareil 32 bits, voir « Compiler soi-même ».

## Utilisation

1. **Ajouter** une ou plusieurs vidéos (sélecteur système, aucun accès complet au stockage requis).
2. Régler le facteur (`x2`, `x3`, `x4`, `x6`, `x8`) et éventuellement la résolution de traitement.
3. **Démarrer** : le traitement tourne dans un service de premier plan, l'écran peut être éteint.
4. Le résultat est écrit dans le dossier choisi (ou dans `Android/data/com.flowframes.android/files/Movies/FlowFrames`).

Le journal intégré affiche chaque étape (modèle, décodeur, images traitées, audio, sortie).

## Fonctionnalités (par rapport à FlowFrames Desktop)

| Fonction FlowFrames | Android | Remarque |
|---|---|---|
| Interpolation RIFE (NCNN/CUDA) | ✅ RIFE 4.9 via ONNX Runtime | XNNPACK (CPU) ou NNAPI |
| Facteur 2x / 3x / 4x / 8x | ✅ 2 → 8, phases quelconques | modèle à timestep variable |
| Qualité « ensemble » | ✅ réglage Rapide / Haute qualité | deux modèles embarqués |
| Résolution maximale | ✅ 480p → source | limite la RAM et le temps |
| Dé-duplication (animation 2D) | ✅ avec seuil réglable | basée sur la différence de luminance |
| Correction des changements de plan | ✅ avec sensibilité réglable | évite les images de morphing |
| Boucle parfaite | ✅ | interpole la dernière vers la première image |
| Conserver l'audio | ✅ copie sans réencodage | piste AAC/Opus selon le conteneur source |
| Choix du codec / débit | ✅ H.264 / HEVC, 3 profils de débit | repli automatique sur H.264 |
| Limite de FPS en sortie | ✅ | réduit le facteur automatiquement |
| Modèle de nom de fichier | ✅ `{name} {factor} {fps} {codec} {date}` | |
| File d'attente par lot | ✅ | vidéos traitées l'une après l'autre |
| Export GIF / séquence PNG | ❌ | MediaMuxer ne produit que du MP4 |
| Sous-titres, métadonnées MKV | ❌ | non supportés par MediaMuxer |
| Lecteur temps réel | ❌ | hors périmètre de cette version |
| DAIN / FLAVR / XFVI | ❌ | nécessitent des runtimes Python/CUDA |

## Réglages importants

* **Résolution de traitement** : RIFE est gourmand. Sur mobile, 720p est le bon compromis.
  1080p demande beaucoup de RAM (plusieurs centaines de Mo par tenseur) et peut échouer sur un
  appareil limité ; l'application affiche alors un message explicite.
* **Qualité du modèle** : « Haute qualité » (ensemble) réduit les artefacts sur les mouvements
  rapides mais double environ le temps de calcul.
* **NNAPI** : accélération par le processeur neuronal quand le constructeur l'expose. Les gains
  varient énormément d'un appareil à l'autre ; en cas de plantage, laisser désactivé.
* **Threads CPU** : automatique par défaut (nombre de cœurs - 2). Utile à réduire si le
  téléphone chauffe.

## Architecture

```
app/src/main/java/com/flowframes/android/
├── core/                  code pur JVM (testable sans appareil)
│   ├── RifeEngine.kt      session ONNX Runtime + tenseurs planaires RGB
│   ├── FrameMath.kt       ARGB ↔ plans flottants, redimensionnement, rotation, métriques
│   ├── YuvConverter.kt    YUV 4:2:0 ↔ ARGB (BT.601/BT.709, limité/pleine plage)
│   ├── InterpPlan.kt      facteur, phases, dé-duplication, changements de plan
│   └── Naming.kt          modèle de nom de fichier
├── video/
│   ├── VideoInfo.kt       métadonnées + espace colorimétrique
│   ├── VideoDecoder.kt    MediaExtractor + MediaCodec → ARGB (rotation/redimensionnement)
│   └── VideoEncoder.kt    MediaCodec + MediaMuxer, copie de la piste audio
├── job/
│   ├── Interpolator.kt    orchestration du pipeline complet
│   ├── InterpolationService.kt  service de premier plan (file d'attente, notification)
│   ├── Settings.kt        réglages persistés
│   ├── JobStore.kt        état partagé UI ↔ service
│   └── ModelStore.kt      extraction des modèles depuis les assets
├── ui/                    écrans Compose (file d'attente, réglages, journal)
└── MainActivity.kt        sélecteurs SAF, permissions
```

### Modèles

Deux exports ONNX de **RIFE 4.9** (licence MIT), embarqués dans l'APK et copiés dans le stockage
interne au premier lancement :

| Fichier | Rôle | Taille |
|---|---|---|
| `rife49_ensemble_False_scale_1_sim.onnx` | mode rapide | 21 Mo |
| `rife49_ensemble_True_scale_1_sim.onnx` | mode haute qualité (ensemble) | 21 Mo |

Entrées : `img0`, `img1` (`[1,3,H,W]`, RGB planaire dans [0,1]) et `timestep` (`[1]`).
Sortie : image interpolée `[1,3,H,W]`. Le graphe gère des dimensions quelconques
(padding interne multiple de 32 puis recadrage), ce qui a été vérifié jusqu'en 4K.

## Compiler soi-même

Prérequis : JDK 17, Android SDK (plateforme 34, build-tools 34.0.0).

```bash
cd android
echo "sdk.dir=/chemin/vers/android-sdk" > local.properties
./gradlew assembleRelease          # APK arm64-v8a signé
./gradlew assembleRelease -PallAbis=true   # + armeabi-v7a et x86_64 (émulateurs)
```

Sortie : `app/build/outputs/apk/release/app-release.apk`.

### Tests

```bash
./gradlew testDebugUnitTest        # 35 tests JVM (moteur RIFE vs référence Python, YUV, plan, nommage)
./gradlew connectedDebugAndroidTest # tests instrumentés (pipeline complet + UI) sur appareil/émulateur
```

Le test `RifeEngineTest` compare la sortie du moteur Kotlin avec des tenseurs de référence
produits par l'implémentation Python/ONNX Runtime (`app/src/test/resources/rife/`) : il garantit
que l'ordre des plans, l'ordre des images et la sémantique du timestep sont corrects.

Le test `PipelineEndToEndTest` fabrique une vidéo de test sur l'appareil, la fait passer dans le
pipeline complet (décodage matériel → RIFE → encodage matériel → MP4) et vérifie que la sortie
est lisible, à la bonne résolution, avec deux fois plus d'images et la même durée.
Il est paramétrable pour les appareils lents :

```bash
adb shell am instrument -w \
  -e width 32 -e height 32 -e frames 4 \
  -e class com.flowframes.android.PipelineEndToEndTest \
  com.flowframes.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

### État de la validation

| Vérification | Résultat |
|---|---|
| Compilation (debug + release, lint vital) | ✅ |
| 35 tests unitaires JVM | ✅ |
| Sortie du moteur RIFE identique à la référence Python (deux modèles, 3 phases) | ✅ |
| APK signé, installé et démarré sur Android 12 (émulateur x86_64) | ✅ |
| Encodage MP4 de bout en bout par `VideoEncoder` (images réelles) | ✅ |
| Lecture des métadonnées, ouverture du décodeur, chargement du modèle ONNX + XNNPACK | ✅ |
| Pipeline complet décodage → RIFE → encodage → mux sur Android 12 | ✅ test instrumenté passé |
| Interface Compose (rendu, navigation) | ⚠️ test écrit, exécution non confirmée |

Le test `PipelineEndToEndTest` a été exécuté sur un émulateur Android 12 : à partir d'une vidéo
de 4 images, la sortie fait 7 images en 60 fps, relue correctement par `VideoInfo`, avec un MP4
valide écrit sur le disque. L'émulateur tournait sans accélération matérielle (KVM inaccessible),
l'inférence RIFE y est donc des centaines de fois plus lente qu'en réel (≈ 40 s par image à 32x32
contre quelques dizaines de ms sur un téléphone).

C'est ce test qui a permis de trouver un vrai bug : `VideoEncoder.finishVideo` pouvait bloquer
plusieurs minutes à la fin de chaque tâche quand l'encodeur ne signalait pas sa fin de flux.
La boucle est désormais bornée dans le temps.

Le test d'UI (`UiSmokeTest`) est fourni mais n'a pas pu être exécuté jusqu'au bout dans
l'environnement de développement ; à lancer sur un appareil :

```bash
./gradlew connectedDebugAndroidTest
```

### Signature

`android/keystore.properties` + `app/keystore/flowframes.jks` contiennent une clé **auto-signée
de test** (mot de passe `flowframes`) destinée à l'installation manuelle. Elle est versionnée
volontairement pour que les reconstructions s'installent par-dessus l'application existante.
Remplacez-la par votre propre clé pour une diffusion publique.

## Limites connues

* Sortie **MP4 uniquement** (H.264 ou HEVC), pas de GIF/PNG/MKV.
* Les codecs décodables sont ceux exposés par `MediaCodec` sur l'appareil (H.264, HEVC, VP8/VP9
  et parfois AV1) ; un format exotique sera refusé avec un message.
* Vidéo à fréquence d'images variable : la cadence est reconstruite à partir des horodatages,
  ce qui peut décaler légèrement les cas extrêmes.
* L'interpolation de RIFE est purement 2D : pas de vecteurs de mouvement 3D ni d'audio.
* Sur un appareil sans encodeur HEVC, la sortie repasse automatiquement en H.264.

## Crédits et licences

* **FlowFrames** (n00mkrad) — concept et pipeline d'origine, licence GPL.
* **RIFE / Practical-RIFE** (hzwer et al.) — réseau d'interpolation, licence MIT.
* Exports ONNX utilisés : `yuvraj108c/rife-onnx` et `ChimairrA/rife49_ensemble_False_scale_1_sim`
  (Hugging Face, licence MIT), conversions du modèle RIFE 4.9 de `hzwer/ECCV2022-RIFE`.
* **ONNX Runtime** (Microsoft, licence MIT) pour l'inférence.
