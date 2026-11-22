#!/bin/sh
# Lancé dans l'émulateur par emulator-3d.yml : installe la version de test, affiche la Golf 3D, captures.
mkdir -p emu
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell wm size 1024x600; adb shell wm density 160
A=com.koala.koackpit/.Golf3DPreview
adb shell settings put secure immersive_mode_confirmations confirmed
adb logcat -c
# 1er lancement : le service de Koackpit démarre et ramène l'accueil ; on relance l'écran 3D par-dessus ensuite
adb shell am start -n $A; sleep 20
adb shell am start -n $A; sleep 12
adb exec-out screencap -p > emu/1-depart.png
adb shell input swipe 700 300 400 320 600; sleep 4
adb exec-out screencap -p > emu/2-tourne.png
adb shell am start -n $A --ei mask 63; sleep 6
adb exec-out screencap -p > emu/3-ouvrants.png
adb shell input tap 512 300; sleep 3
adb exec-out screencap -p > emu/4-fiche.png
adb shell am start -n $A --ei mask 0; sleep 4
adb exec-out screencap -p > emu/5-ferme.png
adb shell dumpsys gfxinfo com.koala.koackpit > emu/gfxinfo.txt
adb logcat -d | grep -iE "filament|koackpit|AndroidRuntime|FATAL|gltfio" | tail -300 > emu/logcat.txt
adb shell run-as com.koala.koackpit cat files/wake-log.txt > emu/journal.txt
true
