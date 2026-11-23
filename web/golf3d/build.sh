#!/bin/sh
# Construit la vue 3D de la Golf (page web de travail, dist/ ; sert à dessiner les vues de Koackpit, voir build-views.sh).
# Prérequis : npm i three@0.160.0 esbuild@0.24.0 (dans ce dossier ou un dossier parent).
set -e
cd "$(dirname "$0")"
OUT=dist
mkdir -p "$OUT"
npx esbuild main.js --bundle --minify --format=iife --target=chrome70 --legal-comments=none --outfile="$OUT/golf3d.js"
cp index.html "$OUT/index.html"
