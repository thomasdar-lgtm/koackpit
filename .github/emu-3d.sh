#!/bin/sh
# Lancé dans l'émulateur par emulator-3d.yml : installe la version de test, affiche la Golf 3D, captures.
mkdir -p emu
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Pas de service Koackpit ici (il ramène l'accueil par-dessus l'écran de test)
adb root; sleep 3
adb shell pm disable com.koala.koackpit/.WakeService
adb shell pm disable com.koala.koackpit/.BootReceiver
adb shell wm size 1024x600; adb shell wm density 160
A=com.koala.koackpit/.Golf3DPreview
adb shell settings put secure immersive_mode_confirmations confirmed
adb logcat -c
# 1er lancement : le service de Koackpit démarre et ramène l'accueil ; on relance l'écran 3D par-dessus ensuite
adb shell am start -n $A; sleep 20
adb shell am start -n $A; sleep 3
adb exec-out screencap -p > emu/0-telechargement.png
# Pack d'images (0.20.0) : téléchargé par l'appli, on attend qu'il soit prêt (3 min max)
for i in $(seq 1 36); do adb shell run-as com.koala.koackpit ls files/golf-vues-1/views.json >/dev/null 2>&1 && break; sleep 5; done
adb shell run-as com.koala.koackpit ls files/golf-vues-1 | wc -l > emu/pack-fichiers.txt
adb shell am start -n $A; sleep 10
adb exec-out screencap -p > emu/1-depart.png
adb shell input swipe 700 300 400 320 600; sleep 4
adb exec-out screencap -p > emu/2-tourne.png
adb shell am start -n $A --ei mask 63; sleep 6
adb exec-out screencap -p > emu/3-ouvrants.png
adb shell input tap 512 300; sleep 3
adb exec-out screencap -p > emu/4-fiche.png
adb shell am start -n $A --ei mask 0; sleep 4
adb exec-out screencap -p > emu/5-ferme.png
adb shell input tap 590 425; sleep 3
adb exec-out screencap -p > emu/5b-fiche-roue.png
# Zoom ×2,5 décalé vers l'avant de la voiture (0.19.2), puis appui sur une pièce (vérifie le calcul du point touché)
adb shell am start -n $A --ei mask 0 --ef zoom 2.5 --ef offx 500 --ef offy -60; sleep 4
adb exec-out screencap -p > emu/7-zoom.png
adb shell input tap 570 465; sleep 3
adb exec-out screencap -p > emu/7b-zoom-fiche.png
adb shell input swipe 700 300 500 300 500; sleep 3
adb exec-out screencap -p > emu/7c-zoom-tourne.png
adb shell am start -n $A --ei mask 0 --ef zoom 1; sleep 3
# Vue de dessus (bouton / extra), geste vertical, combinaisons d'ouvrants
adb shell am start -n $A --ei mask 0 --ei elev 1; sleep 6
adb exec-out screencap -p > emu/8-dessus.png
adb shell input swipe 700 300 400 320 600; sleep 4
adb exec-out screencap -p > emu/8b-dessus-tourne.png
adb shell input swipe 512 400 512 150 400; sleep 4
adb exec-out screencap -p > emu/8c-geste-haut.png
adb shell am start -n $A --ei mask 17 --ei elev 0; sleep 6
adb exec-out screencap -p > emu/9-conducteur-coffre.png
adb shell am start -n $A --ei mask 6; sleep 6
adb exec-out screencap -p > emu/9b-passager-arg.png
adb shell am start -n $A --ei mask 0; sleep 3
# Thème jour (fond clair)
adb shell am force-stop com.koala.koackpit
adb shell am start -n $A --ez jour true; sleep 15
adb exec-out screencap -p > emu/6-jour.png
adb shell dumpsys gfxinfo com.koala.koackpit > emu/gfxinfo.txt
adb logcat -d | grep -iE "filament|golf3d|koackpit|AndroidRuntime|FATAL|gltfio" | tail -300 > emu/logcat.txt
adb shell run-as com.koala.koackpit cat files/wake-log.txt > emu/journal.txt
true
