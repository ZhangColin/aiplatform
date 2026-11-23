#!/bin/sh
# 三道题并行跑内核对齐版脚本。用法: VARIANT=<1|2> sh run_batch.sh <prd-dir> <prefix>
P="$1"; X="$2"
export PYTHONIOENCODING=utf-8
python v0-harness3.py "$X-q1" "$P/prd-run1-helpdesk.md"  "$X-q1-log.jsonl" > "$X-q1.out" 2>&1 &
python v0-harness3.py "$X-q2" "$P/prd-run2-kids.md"      "$X-q2-log.jsonl" > "$X-q2.out" 2>&1 &
python v0-harness3.py "$X-q3" "$P/prd-run3-warehouse.md" "$X-q3-log.jsonl" > "$X-q3.out" 2>&1 &
wait
echo "ALL DONE $(date +%H:%M:%S)"
