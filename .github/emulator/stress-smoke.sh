#!/usr/bin/env bash
# Opens the stress-test files in the debug build on an emulator and reports,
# per scenario: how long until the UI answers again, what is on screen,
# crashes, ANRs, and main-thread stalls ("Skipped N frames").
# Never fails the job by itself: the log is the output.
set -u

PKG=io.github.kaustubhowmick.plaintext.debug
ACT=io.github.kaustubhowmick.plaintext.EditorActivity
SRC=stress-test-files/files
OUT=emulator-out
mkdir -p "$OUT"

adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
echo "Android $(adb shell getprop ro.build.version.release), heap limit $(adb shell getprop dalvik.vm.heapgrowthlimit)"

read -r W H < <(adb shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
CX=$((W / 2)); CY=$((H / 2))

put() { # local-path device-name
  adb exec-in run-as "$PKG" sh -c "mkdir -p files && cat > 'files/$2'" < "$1"
}

prefs() { # word_wrap zoom
  printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n<boolean name="word_wrap" value="%s" />\n<int name="zoom_percent" value="%s" />\n<boolean name="first_run_done" value="true" />\n</map>\n' "$1" "$2" \
    | adb exec-in run-as "$PKG" sh -c 'mkdir -p shared_prefs && cat > shared_prefs/settings.xml'
}

fresh() { # word_wrap zoom
  adb shell am force-stop "$PKG"
  adb exec-out run-as "$PKG" sh -c 'rm -rf no_backup/recovery' >/dev/null 2>&1
  prefs "$1" "$2"
  adb logcat -c
}

launch() { # device-name
  adb shell am start -a android.intent.action.VIEW -t text/plain \
    -d "file:///data/data/$PKG/files/$1" -n "$PKG/$ACT" >/dev/null
}

# Texts on screen (title, status bar, dialogs); empty if the UI didn't answer in 15 s.
ui() {
  timeout 15 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || { echo "<no answer>"; return; }
  adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]\{1,80\}"' | grep -v 'text=""' | sed 's/text="\(.*\)"/\1/' | head -12 | tr '\n' '|'
}

# Seconds until the screen shows $1 (or 90 s).
wait_for() { # needle
  local start=$SECONDS s
  while (( SECONDS - start < 90 )); do
    s=$(ui)
    if [[ "$s" == *"$1"* ]]; then echo $((SECONDS - start)); return; fi
    sleep 1
  done
  echo ">90"
}

report() { # label seconds
  local label=$1 f
  f="$OUT/$(echo "$label" | tr '/ ' '__').log"
  adb logcat -d > "$f"
  local pid; pid=$(adb shell pidof "$PKG" | tr -d '\r')
  local skipped; skipped=$(grep -o 'Skipped [0-9]* frames' "$f" | awk '{s+=$2} END {print s+0}')
  local status=alive
  [ -z "$pid" ] && status=DEAD
  grep -q "FATAL EXCEPTION" "$f" && status=CRASH
  grep -q "ANR in $PKG" "$f" && status=ANR
  printf '%-40s %-6s t=%-4s stalls=%5.1fs  screen: %s\n' "$label" "$status" "$2" "$(echo "$skipped" | awk '{print $1*0.0167}')" "$(ui)" | tee -a "$OUT/summary.txt"
  if [ "$status" != alive ]; then
    echo "----- $label -----"
    grep -E -A30 'FATAL EXCEPTION|ANR in|OutOfMemory|StackOverflow' "$f" | head -60
    echo "------------------"
  fi
}

open_case() { # label device-name wrap
  fresh "$3" 100
  launch "$2"
  local t; t=$(wait_for "$2")
  report "$1" "$t"
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

echo "== open, word wrap on =="
for base in encoding-utf8.txt normal-1MB.txt normal-10MB.txt normal-50MB.txt single-line-5MB.txt \
  500000-short-lines.txt content-long-words.txt binary-png-renamed.txt; do
  open_case "open/$base" "${NAME[$base]}" true
done

echo "== open, word wrap off =="
for base in normal-1MB.txt normal-10MB.txt single-line-5MB.txt 500000-short-lines.txt; do
  open_case "open-nowrap/$base" "${NAME[$base]}" false
done

echo "== zoom in once (Ctrl+=), time until the status bar shows 110% =="
for base in normal-1MB.txt normal-10MB.txt single-line-5MB.txt 500000-short-lines.txt; do
  fresh true 100; launch "${NAME[$base]}"; wait_for "${NAME[$base]}" >/dev/null; adb logcat -c
  adb shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_EQUALS
  report "zoom/$base" "$(wait_for 110%)"
done

echo "== type 5 characters, time until the title shows the * =="
for base in normal-1MB.txt normal-10MB.txt single-line-5MB.txt 500000-short-lines.txt; do
  fresh true 100; launch "${NAME[$base]}"; wait_for "${NAME[$base]}" >/dev/null
  adb shell input tap "$CX" "$CY"; sleep 2; adb logcat -c
  adb shell input text hello
  report "type/$base" "$(wait_for "*${NAME[$base]}")"
done

echo "== single-line-5MB: caret in the middle, then reopen (process-death restore path) =="
fresh true 100; launch "${NAME[single-line-5MB.txt]}"; wait_for "${NAME[single-line-5MB.txt]}" >/dev/null
adb shell input tap "$CX" "$CY"; sleep 2
adb shell input keyevent KEYCODE_MOVE_END; sleep 5
adb logcat -c
adb shell input keyevent KEYCODE_HOME; sleep 2   # background the app
adb shell am kill "$PKG"; sleep 2                 # simulated low-memory kill
adb shell am start -n "$PKG/$ACT" >/dev/null
report "restore/single-line-5MB" "$(wait_for "${NAME[single-line-5MB.txt]}")"

echo
echo "================ SUMMARY ================"
cat "$OUT/summary.txt"
