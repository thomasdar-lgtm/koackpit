#!/bin/sh
# Modèle 3D de l'appli (moteur natif Filament, depuis 0.17.0) → app/src/main/assets/golf/golf.glb + golf.json
#  1. build.sh + build-model.sh : page web de travail (dist/) avec la Golf préparée (pneus, compartiment moteur,
#     coffre, peinture « Ma Golf »), exactement comme l'ancienne vue web ;
#  2. export-run.py : ouvre la page (?export=1) dans Chromium et appelle koackpitExport() → .glb complet
#     (pièces nommées info_N, ouvrants open_*, compartiment « bay », ombre au sol) + fiches et vue de départ (.json) ;
#  3. gltfpack : géométrie compactée (KHR_mesh_quantization, lue nativement par Filament).
# Lumière : ibl.ktx = default_env_ibl.ktx du dépôt Filament (v1.56.x) (docs/webgl/default_env, Apache 2.0).
set -e
cd "$(dirname "$0")"
./build.sh
./build-model.sh
cp index.html dist/ 2>/dev/null || true
T=$(mktemp -d)
python3 export-run.py dist "$T/golf"
mkdir -p ../../app/src/main/assets/golf
npx -y gltfpack@0.21.0 -i "$T/golf.glb" -o ../../app/src/main/assets/golf/golf.glb -kn -km -ke -vp 16 -si 0.5
cp "$T/golf.json" ../../app/src/main/assets/golf/golf.json
rm -rf "$T"
