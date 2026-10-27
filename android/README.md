# FlowFrames Android

Réimplémentation native Android du pipeline de **[FlowFrames](https://github.com/n00mkrad/flowframes)** :
interpolation d'images vidéo par IA (RIFE), 100 % sur l'appareil, sans PC et sans internet.

L'application d'origine est un programme Windows (C#/.NET + binaires NCNN/CUDA). Ce module
`android/` ne réutilise pas ce code : il refait le même pipeline avec les API Android.

```
vidéo source ──► MediaCodec (décodage matériel) ──► RIFE 4.9 / ncnn Vulkan (GPU)
                                                     └─ repli : ncnn CPU, puis ONNX Runtime CPU
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

### Version 1.4.0 — plusieurs inférences en parallèle

Objectif de l'utilisateur : 3 images source/s à x4 sur un Snapdragon 8 Elite. Après la 1.3.0 il
était à 1,40 image source/s, soit ~234 ms par inférence à 576×576 (mesure à 1920×1088 : 1473 ms
pour 2,09 MPx, ramené au canevas réel).

**Piste écartée par la mesure : partager le tronc entre les phases.** À x4, trois phases sont
demandées par paire. Si le pas de temps n'entrait dans le graphe qu'à la fin, une seule passe
suffirait pour les trois. L'analyse du graphe (`/tmp/ts2.py`, tracé des niveaux topologiques) montre
l'inverse : `timestep` est dilaté au niveau 19/237 puis entre dans **chacun** des quatre blocs
(niveaux 20, 78, 131, 184). Chaque phase paie donc la cascade complète ; aucune mutualisation n'est
possible sans changer le modèle.

**Le vrai gisement : le parallélisme.** Une inférence ne sature pas les cœurs. Sur l'appareil, 4, 6
et 8 threads donnent 857, 814 et 885 ms ; l'écart est dans le bruit, ce qui est la signature d'une
charge limitée par la latence mémoire : les threads attendent. Des inférences *indépendantes*, elles,
remplissent la machine. Mesuré à 576×576 avec `android/tools/concurrency_probe.py` (deux passes,
résultats reproductibles) :

| Configuration | ms par inférence | Débit |
|---|---|---|
| 1 session × 8 threads | 259 | 3,9 inf/s |
| 2 sessions × 4 threads | 147 | 6,8 inf/s |
| 3 sessions × 2 threads | 162 | 6,2 inf/s |
| 4 sessions × 2 threads | **125** | **8,0 inf/s** |

Soit **2,1× le débit, à sortie identique au bit près**. Le pipeline est donc devenu une chaîne à trois
étages :

1. le thread de tâche décode, calcule les métriques et alimente une fenêtre bornée de paires ;
2. un pool de sessions calcule les phases en parallèle (chacune sur son moteur ONNX Runtime) ;
3. l'encodeur consomme les résultats **dans l'ordre**, en attendant le future de chaque phase.

Aucune image n'est redistribuée ni recalculée : un test compare le pool de 3 sessions à un moteur
unique et exige une égalité **bit à bit** (delta 0f) sur les trois phases.

Points de conception qui comptent :

* **Ordre garanti** par une `OrderedWindow` (testée) et par `Future.get()` dans l'ordre de soumission.
  Une coupe franche vide la fenêtre avant d'écrire l'image de la coupe, sinon la sortie serait
désordonnée autour du plan.
* **Fenêtre bornée** : la mémoire est du tas Java (`FloatArray`), pas de la RAM. Le budget est
  `maxMemory() / 2` et la fenêtre tombe à 1 paire en 1080p (~130 Mo par paire). Le journal indique la
  taille retenue et la mémoire de tampons correspondante.
* **Mémoire par session** : chaque session charge sa copie du modèle (~21 Mo) et son arène. 3
  sessions ≈ 3× la mémoire du modèle, d'où le réglage exposé et le rappel du banc d'essai.
* **Arrêt propre** : si une inférence est encore en cours à l'annulation, les sessions ne sont pas
  fermées sous elle (crash natif) ; le processus récupère.

Réglages : « Parallel inferences » (0 = automatique → 3 sessions × 2 threads sur 8 cœurs, ce qui
laisse deux cœurs aux codecs). Le banc d'essai mesure désormais 1×, 2×, 3× et 4× en parallèle, plus
XNNPACK et les formes dynamiques, avec une passe soutenue ; « Apply best » écrit le gagnant.

Gain attendu sur ce téléphone : 1,4 → ~2,2-2,7 image source/s à x4 (prédiction, à confirmer par le
banc d'essai embarqué). 3 image source/s reste hors de portée à x4 sans toucher au modèle ; à x2, la
même machine dépasse déjà 2,8 image source/s et le parallélisme la place au-delà de 3.

Pistes suivantes, par ordre de rendement attendu :

* **fp16** : la qualité est acquise (aucune perte visible), mais ORT n'a de noyaux fp16 ARM que si
  le fournisseur les prend ; `GridSample` n'en a pas, donc le gain réel dépend des `Cast` insérés.
  À mesurer sur l'appareil avant d'être proposé.
* **Modèle plus léger** (RIFE-lite ou IFRNet) : le seul levier qui divise réellement le coût, au prix
  d'un réentraînement ou d'un autre choix de qualité.
* **GPU** : `rife-ncnn-vulkan` est l'implémentation rapide de référence, mais elle demande du code
  natif NDK et un modèle ncnn ; impossible à valider depuis cette VM, donc pas livré.

### Version 1.5.0 — RIFE sur le GPU (ncnn + Vulkan)

La 1.4.0 concluait que 3 images source/s à x4 restaient hors de portée **sur le CPU**. La 1.5.0 change
d'unité de calcul : RIFE tourne désormais sur le GPU du téléphone via **ncnn + Vulkan**, c'est-à-dire
l'implémentation que FlowFrames utilise sur PC (`rife-ncnn-vulkan`), compilée en natif (NDK) dans l'APK.

Pourquoi c'est le bon levier : sur le CPU, l'opérateur `GridSample` (le warp) n'a aucun noyau
optimisé dans ONNX Runtime et coûtait ~43 % de chaque inférence ; sur le GPU, tout le graphe,
warps compris, s'exécute en compute shaders fp16. Référence publique : RIFE 4.6 en ncnn atteint
5,5 inférences/s en 720p sur un Snapdragon 8 Gen 3
([hermitix](https://hermitix.com/2025/04/ncnn-rife-int8-quantization/)), soit ~17 inf/s une fois
ramené aux 544×544 d'une vidéo 540×540. L'objectif (3 images source/s à x4) en demande 9.

**Ce qui est livré**

* `app/src/main/cpp/` : moteur natif (`rife_core.cpp`) + pont JNI (`rife_jni.cpp`) + couche
  personnalisée `rife.Warp` de rife-ncnn-vulkan (licence MIT). Le shader de warp est modifié pour
  garder ses **coordonnées d'échantillonnage en fp32** même quand le réseau calcule en fp16 (au-delà
  de 512 px, le fp16 n'a qu'une précision de 0,25-0,5 px, ce qui flouterait le warp). Le chemin pack8,
  supprimé des versions récentes de ncnn, est retiré.
* ncnn 20261020 (`android-vulkan`, statique, Vulkan chargé à l'exécution via *simplevk*) : CMake le
  télécharge au premier build. Un téléphone sans pilote Vulkan charge quand même la bibliothèque.
* Modèles ncnn RIFE **4.9** (mêmes poids que le modèle ONNX) et **4.6** (~25 % plus léger) dans
  `assets/ncnn/`.
* `FrameEngine` : interface commune aux moteurs ; `InferencePool` accepte un moteur partagé
  thread-safe (ncnn : un extracteur par appel) et attache désormais chaque thread à **son** moteur
  (avant, deux tâches pouvaient tomber sur la même session ONNX en même temps).
* `EngineFactory` : choix du moteur avec **auto-test** à la résolution de la tâche — RIFE appliqué à
  une image et à elle-même doit rendre cette image (≥ 30 dB). Un pilote qui plante, renvoie des zéros
  ou des NaN est écarté et la tâche passe au moteur suivant : GPU ncnn → CPU ncnn → ONNX Runtime.
  Le mode « High quality » (ensemble) reste sur ONNX Runtime, seul à avoir ces poids.
* Banc d'essai refait : GPU ×1/×2/×3, fp16 vs fp32, 4.9 vs 4.6, CPU ncnn, ONNX Runtime ; **chaque
  sortie est comparée à la référence ONNX** (dB) et une configuration fausse ne peut pas gagner.
  « Apply best » écrit moteur, modèle et fp16.

**Validation (sur la VM, pas sur le téléphone)**

| Mesure | Résultat |
|---|---|
| ncnn CPU vs ONNX Runtime, RIFE 4.9, 64×48 | 61,8 dB (identique) |
| ncnn Vulkan (lavapipe) fp16 storage | 60,1 dB |
| ncnn Vulkan, fp16 arithmetic | 50,5 dB (invisible) |
| Vraies images 540×540 (Big Buck Bunny), ncnn 4.9 vs ONNX 4.9 | 63 dB |
| Pool de 3 workers sur un moteur partagé | sortie identique au bit près |
| CPU x86 2 cœurs, 540×540 : ONNX Runtime / ncnn 4.9 / ncnn 4.6 | ~470 / ~340 / ~250 ms |

Les tests JVM (`NcnnRifeEngineTest`) pilotent la vraie bibliothèque JNI compilée pour l'hôte :

```bash
cd android
NCNN_HOST=<ncnn-ubuntu> cmake -S native-test -B /tmp/hb -G Ninja && cmake --build /tmp/hb
./gradlew testDebugUnitTest -PnativeTestLib=/tmp/hb     # sans -P, ces tests sont ignorés
```

Prévision pour un Snapdragon 8 Elite (Adreno 830) à 540×540, x4 : ~40-70 ms par inférence, soit
**~5-8 images source/s** (estimation à partir du chiffre public ci-dessus, non mesurée ; le banc
d'essai embarqué donne la vraie valeur). Le build NDK demande r30 (`ndkVersion` dans
`app/build.gradle.kts`) : le prébuilt ncnn est compilé avec le libc++ de clang 21.

### Version 1.5.1 — fp16, blocages et mode hybride GPU + CPU

Mesure de la 1.5.0 sur Snapdragon 8 Elite (Adreno 830), 540x540 en x4 : **6 fps source avec le GPU en fp32** (contre 1,40 fps en 1.4.0 sur CPU). En revanche, l'arithmétique fp16 bloquait le GPU et figeait le benchmark.

- **fp16 désactivé par défaut.** Le suspect principal est le chemin « cooperative matrix » d'Adreno, réactivé par ncnn 20261020 alors que la version 20260526 l'avait désactivé sur Qualcomm pour des bugs de pilote. Ce chemin a maintenant son propre interrupteur (désactivé), et il ne s'applique qu'avec le fp16. Sans fp16, ncnn garde son comportement par défaut, c'est-à-dire la configuration mesurée à 6 fps.
- **Plus aucun blocage possible.**
  - L'auto-test du moteur est limité à 45 s ; après un blocage, la tâche passe sur le CPU.
  - Chaque configuration du benchmark est limitée à 90 s ; après un blocage du GPU, les tests GPU restants sont ignorés.
  - Une inférence est limitée à 120 s pendant une tâche : la tâche échoue avec un message clair au lieu de rester figée.
  - Un moteur bloqué n'est jamais libéré pendant qu'un appel natif tourne.
- **Mode hybride (« CPU assist »).** Des workers ncnn CPU fp32 puisent dans la même file que les workers GPU. Chaque thread est attaché à un moteur, et la fenêtre d'images en avance est doublée pour que le GPU ne s'arrête pas en attendant une image CPU plus lente. Le journal de la tâche affiche la répartition (« Work split »).
- **Ordre du benchmark.** Les configurations sûres passent en premier : ONNX, GPU ×1/×2/×3, GPU 4.6, les variantes hybrides (1×4T, 1×6T, 2×3T) et ncnn CPU. Les variantes fp16 passent en dernier. Le débit est mesuré avec toutes les inférences en file en même temps, comme dans une vraie tâche. Le rapport inclut la ligne d'infos Vulkan (pilote, fp16, coopmat).

### Version 1.5.2 — benchmark en direct

Mesures de la 1.5.1 sur Adreno 840, canevas de 1920x1088, en ms par inférence :

| Configuration | ms/inférence | PSNR vs ONNX |
|---|---|---|
| ONNX Runtime CPU | 1317 | référence |
| GPU ×3 | 249 | 73 dB |
| GPU 4.6 ×2 | 198 | 45 dB |
| GPU fp16 | 280 | 60 dB |
| GPU fp16 + coopmat | 204 | 60 dB |
| Hybride | 269 à 293 | 73 dB |

- **Mode hybride :** il ne gagne rien. Le CPU met plus de 2 s par image, ne traite que 12 % des images et ralentit le GPU.
- **fp16 + coopmat :** ce n'était pas un blocage. Le pilote a mis 98 s à construire les pipelines au premier lancement, puis cette configuration a été la plus rapide en RIFE 4.9.
- **Logs en temps réel :** le benchmark annonce chaque configuration et chaque phase (chargement, warm-up, contrôle qualité, mesure). Toutes les 5 s, il affiche une ligne de progression avec le temps écoulé et le nombre d'inférences terminées.
- **Réponses tardives :** une réponse arrivée après la limite est signalée comme lente, et le GPU est de nouveau utilisable.
- **Limite de temps pour fp16 + coopmat :** 240 s, au benchmark comme à l'auto-test d'une tâche.

### Version 1.6.0 — cache GPU, 1440p/4K, fps exact, moniteur

- **Cache des shaders GPU persistant.** Les shaders SPIR-V compilés par ncnn et le cache de
  pipelines du pilote Vulkan sont enregistrés dans `files/gpu-shader-cache`. Le premier lancement
  en fp16 + coopmat (98 s de construction des pipelines sur l'Adreno 840) n'est payé qu'une fois ;
  les lancements suivants relisent le cache. Le log et le benchmark affichent
  `GPU cache: shaders: … compiled, … from the disk cache · GPU pipelines: …`. Un bouton
  « Clear GPU shader cache » le vide (utile après une mise à jour du pilote).
- **1440p et 4K.** Le « out of memory » au-dessus de 1080p venait du pipeline Java : chaque image
  y était stockée en float (12 octets/pixel, ~100 Mo par image 4K) et copiée plusieurs fois. Les
  images restent maintenant en ARGB 8 bits (4 octets/pixel), sont partagées sans copie entre les
  deux paires qui les utilisent, et la conversion float ↔ 8 bits ainsi que la conversion
  RGB → YUV de l'encodeur sont faites en C++. Au-dessus de 1440p une seule session GPU est
  utilisée (une seule sature déjà le GPU). Nouveau choix « 4K » dans la résolution.
- **FPS exact.** En plus du multiplicateur, on peut demander une cadence de sortie précise
  (30, 48, 60, 90, 120, 144, 165, 240 ou une valeur libre comme 59.94). Les images de sortie sont
  placées sur une grille régulière et RIFE est appelé avec le pas fractionnaire correspondant
  (24 → 60 fps = x2,5) ; les images qui tombent sur une image source la réutilisent sans calcul.
- **Moniteur et minuteur.** L'écran principal affiche la RAM (application, part GPU, téléphone) et
  les températures CPU / GPU / NPU / batterie lues dans `/sys/class/thermal` (selon ce que le
  constructeur autorise), ainsi que le temps écoulé, le temps restant estimé, l'heure de fin et,
  à la fin, le temps total du travail. Remarque : RIFE tourne sur le GPU, pas sur le NPU ; la
  température NPU est indicative.

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
* **ncnn** (Tencent) — moteur d'inférence GPU/CPU, licence BSD-3.
* **rife-ncnn-vulkan** (nihui, modèles v4.x par TNTwise) — couche Warp et modèles ncnn, licence MIT.
* **RIFE / Practical-RIFE** (hzwer et al.) — réseau d'interpolation, licence MIT.
* Exports ONNX utilisés : `yuvraj108c/rife-onnx` et `ChimairrA/rife49_ensemble_False_scale_1_sim`
  (Hugging Face, licence MIT), conversions du modèle RIFE 4.9 de `hzwer/ECCV2022-RIFE`.
* **ONNX Runtime** (Microsoft, licence MIT) pour l'inférence.
