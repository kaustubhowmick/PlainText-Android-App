#!/usr/bin/env bash
# Opens the stress-test files in the debug build on an emulator and reports
# crashes, ANRs, and main-thread stalls ("Skipped N frames") per scenario.
# Never fails the job by itself: the summary is the output.
set -u

PKG=io.github.kaustubhowmick.plaintext.debug
ACT=io.github.kaustubhowmick.plaintext.EditorActivity
SRC=stress-test-files/files
OUT=emulator-out
mkdir -p "$OUT"/wrap-on "$OUT"/wrap-off "$OUT"/zoom "$OUT"/edit "$OUT"/edited

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell getprop ro.build.version.release
adb shell getprop dalvik.vm.heapgrowthlimit
adb shell wm size

read -r W H < <(adb shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
CX=$((W / 2)); CY=$((H / 2))

# Copies a file into the app's private files dir under an ASCII name.
put() { # local-path device-name
  adb exec-in run-as "$PKG" sh -c "mkdir -p files && cat > 'files/$2'" < "$1"
}

prefs() { # word_wrap zoom
  printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n<boolean name="word_wrap" value="%s" />\n<int name="zoom_percent" value="%s" />\n<boolean name="first_run_done" value="true" />\n</map>\n' "$1" "$2" \
    | adb exec-in run-as "$PKG" sh -c 'mkdir -p shared_prefs && cat > shared_prefs/settings.xml'
}

launch() { # device-name
  adb shell am start -a android.intent.action.VIEW -t text/plain \
    -d "file:///data/data/$PKG/files/$1" -n "$PKG/$ACT" >/dev/null
}

report() { # label
  local label=$1
  adb logcat -d > "$OUT/$label.log"
  adb exec-out screencap -p > "$OUT/$label.png" 2>/dev/null
  local pid; pid=$(adb shell pidof "$PKG" | tr -d '\r')
  local skipped; skipped=$(grep -o 'Skipped [0-9]* frames' "$OUT/$label.log" | awk '{s+=$2} END {print s+0}')
  local worst; worst=$(grep -o 'Skipped [0-9]* frames' "$OUT/$label.log" | awk '$2>m{m=$2} END {print m+0}')
  local status=alive
  [ -z "$pid" ] && status=DEAD
  grep -q 'FATAL EXCEPTION' "$OUT/$label.log" && status=CRASH
  grep -q "ANR in $PKG" "$OUT/$label.log" && status=ANR
  printf '%-44s %-6s skipped=%-6s (~%ss) worst=%s\n' "$label" "$status" "$skipped" "$((skipped * 16 / 1000))" "$worst" | tee -a "$OUT/summary.txt"
  if [ "$status" != alive ]; then
    echo "----- $label: first error lines -----"
    grep -E -A40 'FATAL EXCEPTION|ANR in|OutOfMemory|StackOverflow' "$OUT/$label.log" | head -80
    echo "-------------------------------------"
  fi
}

# Scenario: open a file with the given settings and wait.
scenario() { # label device-name wrap zoom wait-seconds
  adb shell am force-stop "$PKG"
  prefs "$3" "$4"
  adb logcat -c
  launch "$2"
  sleep "$5"
  # Poke the UI so a blocked main thread turns into an ANR report.
  adb shell input keyevent KEYCODE_DPAD_DOWN
  sleep 6
  report "$1"
}

i=0
declare -A NAME
for f in "$SRC"/*; do
  i=$((i + 1))
  base=$(basename "$f")
  ext="${base##*.}"
  dev=$(printf 'f%02d.%s' "$i" "$ext")
  case "$base" in *[!A-Za-z0-9._-]*) ;; *) dev="$base" ;; esac
  NAME["$base"]=$dev
  put "$f" "$dev"
done

echo "== every file, word wrap on =="
for base in "${!NAME[@]}"; do
  scenario "wrap-on/$base" "${NAME[$base]}" true 100 12
done

echo "== word wrap off =="
for base in normal-1MB.txt normal-10MB.txt single-line-5MB.txt 500000-short-lines.txt content-long-words.txt; do
  scenario "wrap-off/$base" "${NAME[$base]}" false 100 20
done

echo "== zoom in 3 steps (Ctrl+=) after open =="
for base in normal-1MB.txt normal-10MB.txt single-line-5MB.txt; do
  adb shell am force-stop "$PKG"; prefs true 100
  launch "${NAME[$base]}"; sleep 20; adb logcat -c
  for _ in 1 2 3; do adb shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_EQUALS; sleep 5; done
  sleep 5; report "zoom/$base"
done

echo "== typing 5 characters into the document =="
for base in normal-1MB.txt normal-10MB.txt single-line-5MB.txt; do
  adb shell am force-stop "$PKG"; prefs true 100
  launch "${NAME[$base]}"; sleep 20
  adb shell input tap "$CX" "$CY"; sleep 3; adb logcat -c
  adb shell input text hello; sleep 15
  report "edit/$base"
done

echo "== single-line-5MB: edit, then open the edited copy fresh =="
adb shell am force-stop "$PKG"; prefs true 100
python3 - "$SRC/single-line-5MB.txt" "$OUT/edited.txt" <<'EOF'
import sys
d = open(sys.argv[1], 'rb').read()
mid = len(d) // 2
open(sys.argv[2], 'wb').write(d[:mid] + b'EDITED' + d[mid + 6:])
EOF
put "$OUT/edited.txt" edited.txt
scenario "edited/single-line-5MB-caret0" edited.txt true 100 20

echo
echo "================ SUMMARY ================"
cat "$OUT/summary.txt"
