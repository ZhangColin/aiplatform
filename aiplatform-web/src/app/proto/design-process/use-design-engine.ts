"use client";

/**
 * 原 型 —— 设计过程体验（#278）：播放器宿主（血统承 proto/_shared/use-run-engine）。
 * 定时播放＋gate 交互续跑。探索轮（kind:"explore"）全动态派发——点哪改哪：
 * 改稿＝对选中稿的设计物出新一代（buildRevisionSteps 现组步）；定稿＝按完成度
 * 定去向（全完→构建/下单段；下一件没稿→跳它的首产段；有稿→留在探索 gate）。
 */

import * as React from "react";

import {
  buildRevisionSteps,
  exploreGate,
  initialState,
  reduce,
  SCENARIOS,
  segmentOf,
  type Ev,
  type Gate,
  type DesignState,
  type Step,
} from "./engine";
import { draftName } from "./media";

export function useDesignEngine() {
  const [scenarioIdx, setScenarioIdx] = React.useState(0);
  const [state, setState] = React.useState<DesignState>(() => initialState("towel"));
  const [playing, setPlaying] = React.useState(false);
  const [speed, setSpeedState] = React.useState(1);
  const [gate, setGate] = React.useState<Gate | null>(null);
  const stateRef = React.useRef(state);
  const segIdRef = React.useRef<string | null>(null);
  const timer = React.useRef<ReturnType<typeof setTimeout> | null>(null);
  const speedRef = React.useRef(speed);
  const scenarioIdxRef = React.useRef(scenarioIdx);
  React.useEffect(() => {
    speedRef.current = speed;
  }, [speed]);
  React.useEffect(() => {
    scenarioIdxRef.current = scenarioIdx;
  }, [scenarioIdx]);

  function commit(ev: Ev) {
    const next = reduce(stateRef.current, ev);
    stateRef.current = next;
    setState(next);
  }

  function stop() {
    setPlaying(false);
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
  }

  function resetTo(s: DesignState) {
    stateRef.current = s;
    setState(s);
  }

  const pendingStep = React.useRef<{ steps: Step[]; i: number; gateAfter: Gate | null } | null>(null);

  /** 播一段步组，收口落到 gateAfter（null＝单纯收播）。 */
  function runSteps(steps: Step[], gateAfter: Gate | null) {
    if (timer.current) clearTimeout(timer.current);
    setGate(null);
    setPlaying(true);
    scheduleFrom(steps, 0, gateAfter);
  }

  function scheduleFrom(steps: Step[], i: number, gateAfter: Gate | null) {
    if (i >= steps.length) {
      pendingStep.current = null;
      setPlaying(false);
      if (gateAfter) setGate(gateAfter);
      return;
    }
    pendingStep.current = { steps, i, gateAfter };
    const [delay, ev] = steps[i];
    timer.current = setTimeout(() => {
      pendingStep.current = null;
      commit(ev);
      scheduleFrom(steps, i + 1, gateAfter);
    }, delay / speedRef.current);
  }

  /** 倍速改动立即生效：挂起中的步按新速重排。 */
  const setSpeed = (n: number) => {
    setSpeedState(n);
    speedRef.current = n;
    const p = pendingStep.current;
    if (p) {
      if (timer.current) clearTimeout(timer.current);
      scheduleFrom(p.steps, p.i, p.gateAfter);
    }
  };

  function jump(segId: string) {
    const seg = segmentOf(SCENARIOS[scenarioIdxRef.current], segId);
    segIdRef.current = segId;
    runSteps(seg.steps, seg.gate ?? null);
  }

  const play = (idx: number) => {
    stop();
    setScenarioIdx(idx);
    scenarioIdxRef.current = idx;
    setGate(null);
    const sc = SCENARIOS[idx];
    resetTo(initialState(sc.id));
    segIdRef.current = sc.segments[0].id;
    runSteps(sc.segments[0].steps, sc.segments[0].gate ?? null);
  };

  const selectScenario = (idx: number) => {
    stop();
    setScenarioIdx(idx);
    scenarioIdxRef.current = idx;
    setGate(null);
    resetTo(initialState(SCENARIOS[idx].id));
    segIdRef.current = null;
  };

  /** 发送框提交。探索轮 → 点哪改哪的动态改稿；run 在途 → 排队回执；其余 → 轻回执。 */
  const onSend = (
    text: string,
    attachment?: { name: string; url: string },
    range?: "refine" | "explore" | "reimagine",
  ) => {
    commit({ t: "user", text, attachment });
    const rangeLabel = range === "refine" ? "微调" : range === "reimagine" ? "大胆" : "探索";
    if (gate?.kind === "explore") {
      const scope = stateRef.current.picked;
      commit({
        t: "agent",
        text: scope
          ? `好——就「${draftName(mediaOf(stateRef.current, scope))}」这版，按「${rangeLabel}」幅度改。旧稿都留在画布上，随时回头。`
          : `好——按「${rangeLabel}」幅度出新一组，旧稿都留在画布上。`,
      });
      runSteps(buildRevisionSteps(stateRef.current, scope, rangeLabel), gate);
      return;
    }
    if (stateRef.current.runActive) {
      commit({ t: "agent", text: "收到——这轮出稿做完就处理您这条（先排上队）。" });
      return;
    }
    commit({ t: "agent", text: "收到。原型演示里这条先不展开——真实平台会照常受理。" });
  };

  /** 定稿。探索轮 → 去向动态判：全完→构建/收尾段；下一件无稿→跳其首产段；有稿→留探索。 */
  const onFinalize = (draftId: string) => {
    commit({ t: "finalize", id: draftId });
    if (gate?.kind !== "explore") return;
    const st = stateRef.current;
    if (st.items.every((i) => i.status === "done")) {
      jump(st.scenario === "coffee" ? "build" : "done");
      return;
    }
    const next = st.items.find((i) => i.status !== "done")!;
    if (st.drafts.some((d) => d.item === next.id)) {
      /* 下一件已有稿（点哪改哪回溯过）——留在探索 gate 继续挑 */
      setPlaying(false);
      setGate(exploreGate(`「${next.title}」还没定稿——点它的稿挑一张定，或点哪改哪继续探索`));
    } else {
      jump(next.id === "menu" ? "menu" : "item2");
    }
  };

  const onOrderPlace = () => {
    commit({ t: "order-place" });
    if (gate?.order) jump(gate.order);
  };

  const onPay = () => {
    if (gate?.pay) jump(gate.pay);
    else commit({ t: "pay" });
  };

  return {
    state, playing, speed, scenarioIdx, gate,
    setSpeed, play, selectScenario, commit, onSend, onFinalize, onOrderPlace, onPay,
  };
}

/** 稿 id → 媒体 id（改稿代带 -gN 后缀）。 */
function mediaOf(state: DesignState, draftId: string): string {
  return state.drafts.find((d) => d.id === draftId)?.media ?? draftId;
}

export type DesignEngine = ReturnType<typeof useDesignEngine>;
