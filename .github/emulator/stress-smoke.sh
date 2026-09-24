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
    grep -E "Fatal signal|lowmemorykiller|lmkd|has died|Killing .*plaintext|Force stopping|backtrace:|#0[0-9] pc" "$f" | head -30
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

# Taps the first on-screen element whose text or content-desc equals $1.
tap() {
  timeout 15 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local b
  b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -E "(text|content-desc)=\"$1\"" | head -1 \
      | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
  if [ -z "$b" ]; then echo "  (no \"$1\" on screen)"; return 1; fi
  read -r x1 y1 x2 y2 <<< "$b"
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

# Opens $1 and waits for its name in the title.
opened() { # device-name wrap
  fresh "$2" 100; launch "$1"; wait_for "$1" >/dev/null; adb logcat -c
}

N500K=${NAME[500000-short-lines.txt]}
N10M=${NAME[normal-10MB.txt]}
N1M=${NAME[normal-1MB.txt]}
N5M=${NAME[single-line-5MB.txt]}

echo "== open, word wrap on =="
for base in encoding-utf8.txt normal-1MB.txt normal-10MB.txt normal-50MB.txt \
  500000-short-lines.txt content-long-words.txt binary-png-renamed.txt content-tabs.txt; do
  open_case "open/$base" "${NAME[$base]}" true
done

echo "== open, word wrap off =="
for base in normal-10MB.txt 500000-short-lines.txt; do
  open_case "open-nowrap/$base" "${NAME[$base]}" false
done

echo "== zoom in 3 steps (Ctrl+=), time until the status bar shows 130% and the UI answers =="
for n in "$N1M" "$N10M" "$N500K"; do
  opened "$n" true
  for _ in 1 2 3; do adb shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_EQUALS; done
  sleep 1
  report "zoom/$n" "$(wait_for 130%)"
done

echo "== toggle word wrap from the menu =="
for n in "$N10M" "$N500K"; do
  opened "$n" true
  tap "More options"; sleep 2; tap "Format"; sleep 2; tap "Word Wrap"
  sleep 2
  report "wrap-toggle/$n" "$(wait_for "$n")"
done

echo "== rotate to landscape and back =="
adb shell settings put system accelerometer_rotation 0
for n in "$N10M" "$N500K"; do
  opened "$n" true
  adb shell settings put system user_rotation 1; sleep 3
  t1=$(wait_for "$n")
  adb shell settings put system user_rotation 0; sleep 3
  report "rotate/$n" "$t1/$(wait_for "$n")"
done

echo "== type 5 characters, then undo =="
for n in "$N1M" "$N10M" "$N500K"; do
  opened "$n" true
  adb shell input tap "$CX" "$CY"; sleep 2
  adb shell input text hello
  t1=$(wait_for "*$n")
  adb shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_Z
  report "type-undo/$n" "$t1"
done

echo "== single-line-5MB: long-lines dialog =="
fresh true 100; launch "$N5M"
report "long-lines-dialog/$N5M" "$(wait_for "very long lines")"
tap "OPEN READ-ONLY"
t=$(wait_for "$N5M")
adb shell input tap "$CX" "$CY"; sleep 1; adb logcat -c
adb shell input text hello; sleep 5
report "long-lines-readonly-type/$N5M" "$t"

echo
echo "================ SUMMARY ================"
cat "$OUT/summary.txt"
