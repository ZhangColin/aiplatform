"use client";

/**
 * 原型共享：run 引擎的 React 宿主（定时播放 + 唯一写入口）。各方向壳复用。
 */

import * as React from "react";

import { preludeState, reduce, SCENARIOS, type Ev, type RunState, type Step } from "./run-engine";

export function useRunEngine(defaultTab: string) {
  const [scenarioIdx, setScenarioIdx] = React.useState(0);
  const [state, setState] = React.useState<RunState>(() => preludeState(0, defaultTab));
  const [playing, setPlaying] = React.useState(false);
  const [speed, setSpeed] = React.useState(1);
  const stateRef = React.useRef(state);
  const timer = React.useRef<ReturnType<typeof setTimeout> | null>(null);
  const speedRef = React.useRef(speed);
  speedRef.current = speed;
  const pendingRef = React.useRef<{ steps: Step[]; next: number } | null>(null);

  function commit(ev: Ev) {
    const next = reduce(stateRef.current, ev);
    stateRef.current = next;
    setState(next);
  }

  function scheduleFrom(steps: Step[], i: number) {
    if (i >= steps.length) {
      setPlaying(false);
      return;
    }
    const [delay, ev] = steps[i];
    timer.current = setTimeout(() => {
      commit(ev);
      if (stateRef.current.waitingAnswer) {
        pendingRef.current = { steps, next: i + 1 };
        return;
      }
      scheduleFrom(steps, i + 1);
    }, delay / speedRef.current);
  }

  function stop() {
    setPlaying(false);
    pendingRef.current = null;
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
  }

  function resetTo(s: RunState) {
    stateRef.current = s;
    setState(s);
  }

  const play = (idx: number) => {
    stop();
    setScenarioIdx(idx);
    resetTo(preludeState(idx, defaultTab));
    setPlaying(true);
    scheduleFrom(SCENARIOS[idx].steps, 0);
  };

  const selectScenario = (idx: number) => {
    stop();
    setScenarioIdx(idx);
    resetTo(preludeState(idx, defaultTab));
  };

  const onAnswer = (choice: number) => {
    commit({ t: "answered", choice });
    const pend = pendingRef.current;
    pendingRef.current = null;
    if (pend) scheduleFrom(pend.steps, pend.next);
  };

  const onRetry = () => {
    const sc = SCENARIOS[scenarioIdx];
    if (!sc.retry || stateRef.current.runActive) return;
    setPlaying(true);
    scheduleFrom(sc.retry, 0);
  };

  return {
    state, playing, speed, scenarioIdx,
    setSpeed, play, selectScenario, onAnswer, onRetry, commit,
  };
}

export type RunEngine = ReturnType<typeof useRunEngine>;
