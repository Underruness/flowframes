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
./gradlew testDebugUnitTest        # 48 tests JVM (moteur RIFE vs référence Python, YUV, plan, nommage)
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
| 48 tests unitaires JVM | ✅ |
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

`android/keystore.properties` + `app/keystore/flowframes.jks` contiennent une clé auto-signée
locale. **Ils ne sont pas versionnés** (voir `.gitignore`) : un secret de signature n'a rien à faire
dans un dépôt public. Sans ces fichiers, `assembleRelease` produit une APK signée avec la clé de
debug — elle fonctionne, mais Android refusera de l'installer par-dessus une APK signée avec une
autre clé (erreur `INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Pour conserver la mise à jour par-dessus
l'application déjà installée, gardez la même keystore d'une reconstruction à l'autre.

## Performances

Coût des étapes non liées au modèle, pour une image 1280×720 (mesures JVM sur la VM de
développement x86, `FF_BENCH=1 ./gradlew testDebugUnitTest --tests "*PerfProbeTest"`) :

| Étape | Avant | Après |
|---|---|---|
| Décodage 1080p → 720p (YUV + rotation + redimensionnement) | 51 ms | 29 ms |
| Détection doublons / changements de plan | 1,8 ms (2 passes) | 1,1 ms (1 passe) |
| Sortie interpolée (planar → YUV encodeur) | 9,5 ms | 8,2 ms |
| Allocation par pixel dans le redimensionnement bilinéaire | 1 `IntArray`/pixel | aucune |

Ce qui a changé :

* Le décodage fait conversion YUV, rotation et redimensionnement en **une seule passe** qui ne
touche que les pixels de destination, au lieu de trois passes pleine résolution avec deux tableaux
intermédiaires alloués par image.
* Les plans YUV sont copiés en bloc vers des tableaux réutilisés : `ByteBuffer.get(index)` coûtait
~6 ns par accès et il y en avait six par pixel.
* Les images interpolées sont écrites **directement en YUV** pour l'encodeur, sans repasser par une
image ARGB.
* Les deux métriques de dé-duplication sont calculées en une passe.
* L'inférence conserve deux cœurs pour les codecs (`cores - 2`, plafonné à 6). La 1.1.0 avait
tenté de tout prendre : c'était une régression, voir la section 1.3.0.

**L'inférence RIFE reste le poste dominant** : ~800 ms/image à 720p sur cette VM x86 (sans
XNNPACK, qui n'existe pas dans le jar desktop), quelques centaines de ms sur un SoC mobile où
XNNPACK est actif. Chaque tâche journalise désormais le temps réel par étage
(`Temps par étage: décodage … · inférence … · encodage …`) : c'est la mesure à lire avant de
chercher ailleurs.

Pistes écartées, avec la raison :

* **Quantification INT8** : rejetée par la mesure. `tools/quantize_rife.py` quantifie le modèle en
  QDQ et compare au FP32 ; le PSNR tombe à 12,6 dB au pire des cas pour un seuil fixé à 33 dB.
  RIFE (GridNet, nombreuses concaténations) ne supporte pas l'int8 sans entraînement spécifique.
* **NNAPI** : l'option existe dans les réglages, mais NNAPI est déprécié depuis Android 15 et son
  gain est très variable selon le pilote du constructeur. Ce n'est pas activé par défaut.

### Version 1.2.0 — mesure sur appareil et formes figées

La 1.1.0 restait lente sur un Snapdragon 8 Elite : 379 ms d'inférence par image à 576×576, avec
97 % du temps total dans le modèle (décodage 2,8 s et encodage 12 s pour 8 minutes de calcul).
Le profil par opérateur d'ONNX Runtime (`tools/profile_ops.py`) explique où part ce temps :

| Opérateur | Part du temps | Nœuds |
|---|---|---|
| `GridSample` (warping RIFE) | ~43 % | 14 |
| `Conv` | ~25 % | 42 |
| `ConvTranspose`, `Resize`, `Transpose`, `Slice`, `Concat` | ~25 % | 99 |
| Reorder (conversions de layout) | ~5 % | 264 événements |

`GridSample` n'a pas d'implémentation rapide sur CPU et n'existe pas dans XNNPACK : c'est le
plafond structurel de cette architecture de modèle, et aucune option de compilation ne le
contourne. Ce qui a donc été corrigé :

* **Dimensions figées.** `OrtSession.SessionOptions.setSymbolicDimensionValue("height"|"width", n)`
  existe bien dans l'API Java 1.19.2 (contrairement à ce qu'indiquait la version précédente de ce
  fichier : c'est `addFreeDimensionOverride` qui manque). Le moteur reçoit désormais la taille de
  traitement à sa construction et fige `height`/`width`. Deux tests vérifient que la sortie reste
  **bit-identique** au graphe dynamique, y compris à une résolution non multiple de 32.
* **Graphe plié.** Avec des formes connues, ORT replie les ~130 nœuds de calcul de formes :
  412 → 255 nœuds, soit −15 % de temps par image mesurés ici (`tools/compare_variants.py`),
  sortie identique au bit près.
* **Banc d'essai embarqué.** Les réglages exposent « Run engine benchmark » : six configurations
  (CPU ou XNNPACK, threads automatiques ou tous les cœurs, opérateurs séquentiels ou parallèles,
  formes figées ou dynamiques, plus NNAPI si l'option est active) sont chronométrées sur
  l'appareil, avec le profil par opérateur, et le rapport est copiable. C'est la seule façon de
  savoir ce qui gagne réellement sur un SoC donné.
* **Logs détaillés.** Chaque tâche journalise l'appareil (modèle, cœur, RAM, statut thermique),
  le fournisseur d'exécution, les formes, le temps par étage, la répartition en pourcentage et les
  percentiles de latence d'inférence (min/médiane/p95/max), avec boutons « Copy » et « Share ».
  Les percentiles révèlent le throttling qu'une moyenne masque.
* **Interface en anglais**, y compris les messages du pipeline, la notification et les erreurs.

Pistes examinées et écartées, chiffres à l'appui :

* **FP16 / INT8** : la quantification INT8 tombe à 12,6 dB de PSNR, inutilisable. Le FP16 n'a pas
  d'intérêt démontrable ici : ORT n'en tire parti que si XNNPACK prend le nœud, et `GridSample`
  reste de toute façon sur le CPU.
* **ONNX Runtime 1.22** : mesuré ~12 % plus rapide que la version utilisée sur les mêmes modèles
  en Python x86, mais aucune preuve sur ARM ; le gain n'est pas garanti et la version est restée
  en 1.19.2.
* **Réécriture de `GridSample` en `GatherND`** : quatre collectes par pixel au lieu d'un warp
  vectorisé, plus de trafic mémoire — pas de raison d'être plus rapide, non implémenté.

Le vrai levier restant est le modèle : à qualité comparable, un réseau plus léger (RIFE-lite)
coûterait plusieurs fois moins cher. C'est un changement de modèle, pas d'optimisation.

### Version 1.3.0 — la régression de threading, et des réglages mesurés

Constat de l'utilisateur : le débit est passé de ~1,20 à ~0,90 image source/s entre la 1.0.0 et la
1.1.0, et NNAPI aggrave encore les choses. Le diff explique la régression : la 1.0.0 appelait
`defaultThreads() = (cores - 2).coerceAtMost(6)`, la 1.1.0 l'a remplacé par `cores` (8 threads),
sur l'hypothèse que les codecs matériels dorment et qu'il suffisait de tout donner au modèle.
L'hypothèse est fausse sur une tâche longue.

Banc d'essai embarqué sur HONOR AAP-AN00 (Snapdragon 8 Elite, 8 cœurs, Android 17), canevas
1280×736, 3 runs par configuration :

| Configuration | ms/image | Écart |
|---|---|---|
| CPU seul, 8 threads, formes figées | **814,5** | référence |
| XNNPACK, 4 threads, formes figées | 857,8 | +5 % |
| XNNPACK, 8 threads, formes figées | 885,0 | +9 % |
| XNNPACK, 8 threads, formes dynamiques | 909,7 | +12 % |
| NNAPI + XNNPACK, 8 threads | 5491,8 | ×6,7 |

Trois enseignements, tous contraires à ce que supposaient les réglages précédents :

* **XNNPACK perd** sur ce graphe (885 contre 814). `GridSample`, `Div` et `Transpose` ne sont pas
  couverts par XNNPACK : il ne fait que payer des conversions de layout. Le moteur utilise donc le
  chemin CPU (MLAS) par défaut, et XNNPACK est devenu une option mesurable.
* **NNAPI est une catastrophe** (5,5 s/image, ×6,7). Ce n'est plus une simple option « au cas où » :
  l'interface le déconseille explicitement.
* **Plus de threads n'est pas plus rapide**, même dans un test court (4T bat 8T). Le nombre de
  threads est donc revenu à `cores - 2` (6 ici), ce qui laisse deux cœurs au décodeur, à
  l'encodeur et à la conversion de pixels.

Le banc d'essai ne pouvait pas voir la régression, parce qu'il mesure des images isolées sur
quelques secondes : ni la contention avec les codecs ni le throttling thermique n'y apparaissent.
Il mesure maintenant **aussi** une passe soutenue de 16 inférences sur le gagnant et rapporte la
dérivée entre les premières et les dernières (`drift +X%`), avec le statut thermique avant/après.
Chaque configuration est également exprimée en **ms/MPx** et en images source/s prévues, pour
pouvoir comparer un canevas donné avec celui d'une tâche réelle ; le bouton « Apply best » écrit la
configuration gagnante dans les réglages.

Autres ajouts de journalisation, tous copiables depuis les réglages :

* configuration réelle du moteur (Runtime, modèle, formes, threads, fournisseur, mode d'exécution) ;
* une ligne `Progress:` par minute avec débit instantané, latence d'inférence de la dernière minute,
  statut thermique et occupation du tas ;
* `drift` dans les percentiles d'inférence (min/médiane/p95/p99/max), calculé entre les 64
  premières et les 64 dernières mesures ;
* comptabilité exacte des images (`décodées · paires interpolées · coupes · doublons · écrites`) ;
* une ligne `Diagnosis:` qui nomme le poste dominant et donne la conséquence chiffrée (par exemple
  le gain attendu en passant à 480p), et signale un throttling supérieur à 10 %.

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
