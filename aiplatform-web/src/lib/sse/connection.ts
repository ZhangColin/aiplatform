import { ApiError } from "@/lib/api/api-error";
import { api } from "@/lib/api/client";
import { useSseStatusStore, type SseChannel } from "@/lib/store/sse-status";

/**
 * 原生 EventSource 封装（ADR 0003）：单通道一条连接，不接管重连——
 * 断流由浏览器自动重试（白送 Last-Event-ID 头）；本层只管状态 / 失败计数 /
 * 401 盲区探针 / agent 流去重 / StrictMode 双挂载幂等。
 *
 * 浏览器对非 2xx 响应会置 CLOSED 且不再自动重连（原生重连只覆盖断流），
 * 此时探一次 /api/me：401 停手交全局 401 出口；非 401 按原生节奏（~3s）再播种，
 * 防后端重启 / 代理瞬时 5xx 让通道永久失活。
 */

export type SseEvent = {
  /** SSE `id:` 字段（通知通道 {projectId}:{seq}，agent 流 {runId}:{seq}）。 */
  id: string;
  data: string;
};

export type SseConnectionOptions = {
  channel: SseChannel;
  /** 相对路径（rewrite → 后端，同源 cookie 自动携带），如 `/api/agent-events?projectId=p1`。 */
  url: string;
  /** agent 流通道去重（键 = 完整事件 id，带上限清空——将来接补发的现成缝）；通知通道消费幂等，不开。 */
  dedupe?: boolean;
  onEvent: (event: SseEvent) => void;
  /** error 之后恢复的 onopen（重连成功 → 广谱 invalidate 钩子）。 */
  onReconnect?: () => void;
};

/** 去重 Set 上限：超限整表清空。 */
const DEDUP_CAP = 1000;

/** 连续失败多少次探一次 /api/me（issue #16：401 盲区）。 */
const FAILURE_PROBE_THRESHOLD = 5;

/** 再播种延迟：对齐浏览器原生重连节奏（~3s），非退避。 */
const RESEED_DELAY_MS = 3_000;

/**
 * 探会话（EventSource 读不到状态码，会话过期只表现为反复 error）：
 * 401 → false（薄 client 的全局 401 出口已接管整页跳）；其余（200 / 404 / 网络错）
 * → true，按会话未知处理、继续原生重连。
 */
export async function probeSessionAlive(): Promise<boolean> {
  try {
    await api.get("/me");
    return true;
  } catch (error) {
    return !(error instanceof ApiError && error.status === 401);
  }
}

export class SseConnection {
  private es: EventSource | null = null;
  private closed = false;
  private seenIds = new Set<string>();
  private hadError = false;
  private failures = 0;
  private reseedTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly opts: SseConnectionOptions) {}

  connect() {
    if (this.closed) return;
    // 幂等：同实例重复 connect（StrictMode 双挂载）/ 建连中 / 已连 → 不另起连接
    if (this.es && this.es.readyState !== EventSource.CLOSED) return;

    const es = new EventSource(this.opts.url);
    this.es = es;
    this.setStatus("connecting");

    es.onopen = () => {
      this.setStatus("connected");
      this.failures = 0;
      if (this.hadError) {
        this.hadError = false;
        this.opts.onReconnect?.();
      }
    };
    es.onerror = () => {
      this.setStatus("offline");
      this.hadError = true;
      this.failures += 1;
      const gaveUp = es.readyState === EventSource.CLOSED;
      if (this.failures < FAILURE_PROBE_THRESHOLD && !gaveUp) return;
      this.failures = 0;
      void probeSessionAlive().then((alive) => {
        if (!alive) {
          this.close(); // 401：停手，全局 401 出口已在薄 client 内触发整页跳
        } else if (gaveUp) {
          this.scheduleReseed(es);
        }
      });
    };
    es.addEventListener("event", (ev) => {
      const message = ev as MessageEvent<string>;
      const id = message.lastEventId ?? "";
      if (this.opts.dedupe && id) {
        if (this.seenIds.has(id)) return;
        this.seenIds.add(id);
        // 上限整表清空而非逐出：简单且有界；将来接补发时旧段重放可容忍
        if (this.seenIds.size > DEDUP_CAP) this.seenIds.clear();
      }
      this.opts.onEvent({
        id,
        data: typeof message.data === "string" ? message.data : String(message.data),
      });
    });
  }

  /** 显式关闭（agent 通道 unmount / 401 停手）：关闭后本实例不再复活。 */
  close() {
    this.closed = true;
    if (this.reseedTimer !== null) {
      clearTimeout(this.reseedTimer);
      this.reseedTimer = null;
    }
    this.es?.close();
    this.es = null;
    this.setStatus("offline");
  }

  /**
   * 浏览器放弃重连（CLOSED，典型 = 非 2xx）后的再播种：不是接管退避——
   * 无指数、节奏对齐原生 ~3s 重试；只在原生通道已死时兜一次底。
   * 定时期间若已另有新连接（外部 connect 重建），不误关它。
   */
  private scheduleReseed(deadEs: EventSource) {
    if (this.closed || this.reseedTimer !== null) return;
    this.reseedTimer = setTimeout(() => {
      this.reseedTimer = null;
      if (this.closed) return;
      if (this.es !== deadEs) return; // 已有新连接接管，作废本次播种
      this.es.close();
      this.es = null;
      this.connect();
    }, RESEED_DELAY_MS);
  }

  private setStatus(status: "connecting" | "connected" | "offline") {
    useSseStatusStore.getState().setStatus(this.opts.channel, status);
  }
}
