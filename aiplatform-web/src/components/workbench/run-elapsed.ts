"use client";

import { useEffect, useState } from "react";

import type { AgentRun } from "@/lib/store/agent-streams";

/** run 进行中（running / waiting，等用户 ≠ 终态）：顶栏 LIVE 挂载与计时 tick 共用谓词。 */
export function isRunInFlight(run: AgentRun | undefined): boolean {
  return run !== undefined && run.status !== "finished" && run.status !== "error";
}

/**
 * 运行计时（#40 运行条 / #59 顶栏 LIVE 共用）：锚 run.startedAt，finished/error
 * 冻结；tick 归组件局部（UI 关注，非流状态，不写流 store）。
 */
export function useRunElapsed(run: AgentRun | undefined): number {
  const [now, setNow] = useState(() => Date.now());
  const ticking = isRunInFlight(run);
  useEffect(() => {
    if (!ticking) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [run?.runId, ticking]);
  if (!run?.startedAt) return 0;
  return Math.max(0, Math.floor((now - run.startedAt) / 1000));
}
