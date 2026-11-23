#!/bin/sh
# Vues de la Golf pour Koackpit (depuis 0.17.5) → app/src/main/assets/golf/views
# La page web (dist/) dessine la voiture dans Chromium (sur le PC, pas l'autoradio) sous chaque angle et pour
# chaque ouvrant ; render-views.py enregistre les images (.webp transparentes) et les cartes des pièces (.png).
# Durée : ~30 min. Prérequis : python3, playwright (Chromium), pillow.
set -e
cd "$(dirname "$0")"
./build.sh
./build-model.sh
rm -rf ../../app/src/main/assets/golf/views
python3 render-views.py dist ../../app/src/main/assets/golf/views
