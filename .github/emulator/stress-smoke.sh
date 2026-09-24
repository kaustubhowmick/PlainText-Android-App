#!/usr/bin/env bash
# Opens the stress-test files in the debug build on an emulator and reports,
# per scenario: seconds until the file is loaded and until the app answers
# again, crashes, ANRs, and main-thread stalls ("Skipped N frames"). The
# emulator is slower than most phones; compare runs, not absolute numbers.
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

# Texts on screen. Only for screens without a big document: the UI dump goes
# through accessibility, which makes TextView copy all its text and can itself
# stall the app for seconds.
ui() {
  timeout 15 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || { echo "<no answer>"; return; }
  adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]\{1,80\}"' | grep -v 'text=""' | sed 's/text="\(.*\)"/\1/' | head -12 | tr '\n' '|'
}

# Seconds until the screen shows $1 (or 90 s). Uses ui(): dialogs only.
wait_for() { # needle
  local start=$SECONDS s
  while (( SECONDS - start < 90 )); do
    s=$(ui)
    if [[ "$s" == *"$1"* ]]; then echo $((SECONDS - start)); return; fi
    sleep 1
  done
  echo ">90"
}

# Seconds until an open of $1 finished: the app adds it to Recent files right
# after putting the text in the editor.
wait_loaded() { # device-name
  local start=$SECONDS
  while (( SECONDS - start < 150 )); do
    adb exec-out run-as "$PKG" cat shared_prefs/settings.xml 2>/dev/null | grep -q "$1" && { echo $((SECONDS - start)); return; }
    sleep 1
  done
  echo ">150"
}

# Seconds until the app's main thread answers again: "dumpsys activity top"
# is handled on it, so it returns quickly only when the app isn't busy.
wait_idle() {
  local start=$SECONDS t0 ms
  while (( SECONDS - start < 180 )); do
    t0=$(date +%s%N)
    timeout 60 adb shell dumpsys activity top >/dev/null 2>&1
    ms=$(( ($(date +%s%N) - t0) / 1000000 ))
    if (( ms < 1500 )); then echo $((SECONDS - start)); return; fi
  done
  echo ">180"
}

report() { # label timings
  local label=$1 f
  f="$OUT/$(echo "$label" | tr '/ ' '__').log"
  adb logcat -d > "$f"
  local pid; pid=$(adb shell pidof "$PKG" | tr -d '\r')
  local skipped; skipped=$(grep -o 'Skipped [0-9]* frames' "$f" | awk '{s+=$2} END {print s+0}')
  local status=alive
  [ -z "$pid" ] && status=DEAD
  grep -q "FATAL EXCEPTION" "$f" && status=CRASH
  grep -q "ANR in $PKG" "$f" && status=ANR
  printf '%-44s %-6s %-26s stalls=%5.1fs\n' "$label" "$status" "$2" "$(echo "$skipped" | awk '{print $1*0.0167}')" | tee -a "$OUT/summary.txt"
  if [ "$status" != alive ]; then
    echo "----- $label -----"
    grep -E -A30 'FATAL EXCEPTION|ANR in|OutOfMemory|StackOverflow' "$f" | head -60
    grep -E "Fatal signal|lowmemorykiller|lmkd|has died|Killing .*plaintext|Force stopping|backtrace:|#0[0-9] pc" "$f" | head -30
    echo "------------------"
  fi
}

# Force-stops, sets word wrap, opens $1, and waits until it is shown. Prints "load=… idle=…".
open_doc() { # device-name wrap
  fresh "$2" 100
  launch "$1"
  local l i
  l=$(wait_loaded "$1")
  i=$(wait_idle)
  echo "load=${l}s idle=+${i}s"
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
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local b
  b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -E "(text|content-desc)=\"$1\"" | head -1 \
      | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
  if [ -z "$b" ]; then echo "  (no \"$1\" on screen)"; return 1; fi
  read -r x1 y1 x2 y2 <<< "$b"
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

N500K=${NAME[500000-short-lines.txt]}
N10M=${NAME[normal-10MB.txt]}
N1M=${NAME[normal-1MB.txt]}
N5M=${NAME[single-line-5MB.txt]}

echo "== open, word wrap on =="
for base in encoding-utf8.txt normal-1MB.txt normal-10MB.txt 500000-short-lines.txt content-long-words.txt content-tabs.txt; do
  t=$(open_doc "${NAME[$base]}" true); report "open/$base" "$t"
done

echo "== open, word wrap off =="
for n in "$N10M" "$N500K"; do
  t=$(open_doc "$n" false); report "open-nowrap/$n" "$t"
done

echo "== files that open with a dialog =="
for base in normal-50MB.txt binary-png-renamed.txt single-line-5MB.txt; do
  fresh true 100; launch "${NAME[$base]}"; sleep 5
  report "dialog/$base" "screen: $(ui)"
done

echo "== zoom in 3 steps (Ctrl+=) =="
for n in "$N1M" "$N10M" "$N500K"; do
  open_doc "$n" true >/dev/null; adb logcat -c
  for _ in 1 2 3; do adb shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_EQUALS; done
  sleep 2
  report "zoom/$n" "idle=+$(wait_idle)s"
done

echo "== toggle word wrap from the menu =="
for n in "$N10M" "$N500K"; do
  open_doc "$n" true >/dev/null
  tap "More options"; sleep 2; tap "Format"; sleep 2; adb logcat -c; tap "Word Wrap"
  sleep 2
  report "wrap-toggle/$n" "idle=+$(wait_idle)s"
done

echo "== rotate to landscape and back =="
adb shell settings put system accelerometer_rotation 0
for n in "$N10M" "$N500K"; do
  open_doc "$n" true >/dev/null; adb logcat -c
  adb shell settings put system user_rotation 1; sleep 2; t1=$(wait_idle)
  adb shell settings put system user_rotation 0; sleep 2; t2=$(wait_idle)
  report "rotate/$n" "idle=+${t1}s,+${t2}s"
done

echo "== type 5 characters, then undo =="
for n in "$N1M" "$N10M" "$N500K"; do
  open_doc "$n" true >/dev/null
  adb shell input tap "$CX" "$CY"; sleep 2; wait_idle >/dev/null; adb logcat -c
  adb shell input text hello; sleep 1; t1=$(wait_idle)
  adb shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_Z; sleep 1; t2=$(wait_idle)
  report "type-undo/$n" "type=+${t1}s undo=+${t2}s"
done

echo "== single-line-5MB: Open read-only, then type =="
fresh true 100; launch "$N5M"; wait_for "very long lines" >/dev/null
tap "OPEN READ-ONLY"
t=$(wait_loaded "$N5M"); i=$(wait_idle)
adb shell input tap "$CX" "$CY"; sleep 2; wait_idle >/dev/null; adb logcat -c
adb shell input text hello; sleep 1
report "long-lines-readonly/$N5M" "load=${t}s idle=+${i}s type=+$(wait_idle)s"

echo
echo "================ SUMMARY ================"
cat "$OUT/summary.txt"
