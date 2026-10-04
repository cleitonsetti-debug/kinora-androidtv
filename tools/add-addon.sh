#!/bin/sh
# Adiciona um addon no Kinora da TV pelo computador (a TV pede confirmacao).
# Requer o ADB e a TV com a depuracao ativada:  adb connect IP_DA_TV
# Uso: sh tools/add-addon.sh https://endereco-do-addon/manifest.json
if [ -z "$1" ]; then
  echo "Uso: sh tools/add-addon.sh <url do manifest>"
  exit 1
fi
adb shell "am start -a android.intent.action.VIEW -d 'kinora://add?addon=$1' com.kinora.tv"
