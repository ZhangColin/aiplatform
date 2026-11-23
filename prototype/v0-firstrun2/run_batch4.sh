#!/bin/sh
# #590 修正轮：三处保护照 DSH 改齐后的 v0-harness4.py，三道题并行。用法: sh run_batch4.sh <旧题面目录> <前缀>
P="$1"; X="$2"
export PYTHONIOENCODING=utf-8 VARIANT=3
python v0-harness4.py "$X-q1" "$P/prd-run1-helpdesk.md"  "$X-q1-log.jsonl" > "$X-q1.out" 2>&1 &
python v0-harness4.py "$X-q2" "$P/prd-run2-kids.md"      "$X-q2-log.jsonl" > "$X-q2.out" 2>&1 &
python v0-harness4.py "$X-q3" "$P/prd-run3-warehouse.md" "$X-q3-log.jsonl" > "$X-q3.out" 2>&1 &
wait
echo "ALL DONE $(date +%H:%M:%S)"
