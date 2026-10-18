#!/bin/sh
# P2: キル・インジェクション再起動ループ（NFR-06 用）。
# UI 自動化を行わない。プロセス強制終了→再起動の成立（クラッシュしない再起動）のみ検証する。
# 完了後、末尾で DB（imageshot.db / -wal / -shm）を pull し、テーブル行数を表示する。
# 復元率（レシピ損失ゼロ）の本判定は Room DB の編集文書の整合を、pull した DB で別途確認する。
#
# Usage: ./run_kill_injection.sh [N]
#   N 省略時 10。端末は DEVICE 環境変数で上書き可（既定: 本PJ代表端末のシリアル）。
#   DB pull は debug ビルド（debuggable）必須。benchmark/release ビルドでは
#   run-as が使えず DB 部分は「取得不可」と表示する（再起動ループ自体は続行する）。
#
set -u

DEVICE="${DEVICE:-HA2EPG4J}"
ADB="adb -s $DEVICE"
PKG="com.imageshot.app"
ACTIVITY="com.imageshot.app/.MainActivity"
N="${1:-10}"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/imageshot_kill.XXXXXX")"
LOG="$WORK_DIR/kill_loop.log"

pass_count=0
fail_count=0
it=0

echo "=== kill-injection loop: device=$DEVICE pkg=$PKG n=$N workdir=$WORK_DIR ==="
echo "=== kill-injection loop: device=$DEVICE pkg=$PKG n=$N workdir=$WORK_DIR ===" > "$LOG"

while [ "$it" -lt "$N" ]; do
  it=$((it + 1))

  # 1) force-stop
  $ADB shell am force-stop "$PKG" 2>/dev/null
  sleep $(awk -v s=0.5 -v e=1.5 'BEGIN{srand(int(systime())+'"$it"'); print s+rand()*(e-s)}')

  # 2) launch
  $ADB shell am start -n "$ACTIVITY" >/dev/null 2>&1
  sleep 3

  # 3) re-launch（起動済みなら resume、無ければ起動）
  $ADB shell am start -n "$ACTIVITY" >/dev/null 2>&1
  sleep 1

  # 4) process alive?
  PID="$($ADB shell pidof "$PKG" 2>/dev/null | tr -d '[:space:]')"
  TOP="$($ADB shell dumpsys activity top 2>/dev/null | grep -c "$PKG" || true)"

  if [ -n "$PID" ] && [ "$TOP" -ge 1 ]; then
    RESULT="PASS"
    pass_count=$((pass_count + 1))
  else
    RESULT="FAIL"
    fail_count=$((fail_count + 1))
  fi

  TOPACT="$($ADB shell dumpsys activity activities 2>/dev/null | grep "topResumedActivity" | grep "$PKG" | head -1 | sed 's/^ *//' || echo "(not top)")"
  echo "iter=$it pid=$PID result=$RESULT top=$TOPACT" | tee -a "$LOG"
done

echo "=== summary: total=$it pass=$pass_count fail=$fail_count ==="
echo "=== summary: total=$it pass=$pass_count fail=$fail_count ===" >> "$LOG"

# --- DB pull & 行数確認（debug ビルドのときのみ成功）---
echo "=== DB pull (run-as) ==="
DB_PULL="NG"
if $ADB shell run-as "$PKG" ls 'databases' >/dev/null 2>&1; then
  for f in imageshot.db imageshot.db-wal imageshot.db-shm; do
    $ADB shell "run-as $PKG cat databases/$f" > "$WORK_DIR/$f" 2>/dev/null
    if [ ! -s "$WORK_DIR/$f" ] && [ "$f" != "imageshot.db-shm" ]; then
      rm -f "$WORK_DIR/$f"
    fi
  done
  if python3 - "$WORK_DIR/imageshot.db" <<'PYEOF'
import sqlite3, sys, os
db = sys.argv[1]
# WAL があれば「その他のスキーマ/不安定(WAL含む)」状態でも読める。チェックポイントはしない（読み取り専用）。
wallen = os.path.exists(db + "-wal") and os.path.getsize(db + "-wal") or 0
con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
try:
    cur = con.cursor()
    tables = [t[0] for t in cur.execute(
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
    total = 0
    for t in tables:
        try:
            c = cur.execute(f'SELECT COUNT(*) FROM "{t}"').fetchone()[0]
            total += c
            if t in ("assets", "edit_documents", "edit_revisions",
                     "edit_snapshots", "export_jobs", "thumbnails"):
                print(f"{t}: {c}")
        except Exception as e:
            print(f"{t}: ERROR {e}")
    print(f"WAL bytes: {wallen}")
    if total == 0:
        print("WARN: 全テーブル行数 0 — ダンプが空の可能性（非debugビルド/未遷移）")
finally:
    con.close()
PYEOF
  then
    DB_PULL="OK"
  fi
else
  # 非 debuggable（benchmark/release ビルド）では run-as は使えない
  echo "SKIP: run-as $PKG 不可（debuggable ビルドでない）— DB 行数検証は debug ビルドで別途実施のこと"
fi
echo "db_pull=$DB_PULL" | tee -a "$LOG"

echo "log: $LOG / pulled-db: $WORK_DIR"
exit 0
