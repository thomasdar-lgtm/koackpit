#!/bin/sh
# Allège le modèle 3D de la Golf pour l'appli (source : source/golf5.glb, 7,6 Mo) :
#  1. normales « arêtes vives » calculées une fois ici (bake.js, dans Chromium via Playwright) au lieu
#     de chaque ouverture de l'écran voiture, géométrie réindexée (≈ 4× moins de sommets) ;
#  2. gltfpack (données compactées en standard KHR_mesh_quantization, sans décodeur à part), noms des pièces et
#     matières conservés → assets/golf3d/golf5.glb (≈ 2 Mo).
# Prérequis : npm i three@0.160.0 esbuild@0.24.0 ; Python + Playwright ; npx gltfpack@0.21.0.
set -e
cd "$(dirname "$0")"
T=$(mktemp -d)
npx esbuild bake.js --bundle --format=iife --outfile="$T/bake.js" --log-level=warning
cp source/golf5.glb "$T/golf5.glb"
echo '<script src="bake.js"></script>' > "$T/index.html"
python3 bake-run.py "$T" "$T/baked.glb"
npx -y gltfpack@0.21.0 -i "$T/baked.glb" -o ../../app/src/main/assets/golf3d/golf5.glb -kn -km -ke -vp 16
rm -rf "$T"
