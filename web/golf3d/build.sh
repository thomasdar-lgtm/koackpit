#!/bin/sh
# Construit la vue 3D de la Golf pour l'appli (un seul script classique, sans CDN, compatible WebView Android 10).
# Prérequis : npm i three@0.160.0 esbuild@0.24.0 (dans ce dossier ou un dossier parent).
set -e
cd "$(dirname "$0")"
OUT=../../app/src/main/assets/golf3d
mkdir -p "$OUT"
npx esbuild main.js --bundle --minify --format=iife --target=chrome70 --legal-comments=none --outfile="$OUT/golf3d.js"
cp index.html "$OUT/index.html"
