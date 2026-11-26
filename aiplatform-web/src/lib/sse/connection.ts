import { ApiError } from "@/lib/api/api-error";
import { api } from "@/lib/api/client";
import { useSseStatusStore, type SseChannel } from "@/lib/store/sse-status";

/**
 * 原生 EventSource 封装：单通道一条连接，不接管重连——
 * 断流由浏览器自动重试（白送 Last-Event-ID 头）；本层只管状态 / 失败计数 /
 * 401 盲区探针 / agent 流去重 / StrictMode 双挂载幂等 / 空闲看门狗。
 *
 * 浏览器对非 2xx 响应会置 CLOSED 且不再自动重连（原生重连只覆盖断流），
 * 此时探一次会话（{@link probeSessionAlive}，探 /api/projects）：401 停手交
 * 全局 401 出口；非 401 按原生节奏（~3s）再播种，
 * 防后端重启 / 代理瞬时 5xx 让通道永久失活。
 *
 * <p><b>看门狗（#652）</b>：经代理的连接可能「开着但沉默」（半开僵死——后端
 * 已重启、代理不传播断连），浏览器既不 error 也不重连，页面静默假死。服务端
 * 每 15s 发一条具名 ping 事件（原生 EventSource 可观测），本层看门狗以
 * {@link WATCHDOG_TIMEOUT_MS} 内无任何活动（open／事件／ping）判死：立即关闭
 * 僵死连接（HTTP/1.1 下半开流占着连接池槽位会饿死 POST）、置 offline（既有
 * toast／门控轮询兜底生效），并按再播种节奏重建。</p>
 */

export type SseEvent = {
  /** SSE `id:` 字段（通知族 {projectId}:{seq}，智能体事件族 {runId}:{seq}）。 */
  id: string;
  data: string;
};

export type SseConnectionOptions = {
  channel: SseChannel;
  /** 相对路径（rewrite → 后端，同源 cookie 自动携带），如 `/api/events?projectId=p1`。 */
  url: string;
  /** agent 流通道去重（键 = 完整事件 id，带上限清空——将来接补发的现成缝）；通知通道消费幂等，不开。 */
  dedupe?: boolean;
  onEvent: (event: SseEvent) => void;
  /** error 之后恢复的 onopen（重连成功 → 广谱 invalidate 钩子）。 */
  onReconnect?: () => void;
};

/** 去重 Set 上限：超限整表清空。 */
const DEDUP_CAP = 1000;

/** 连续失败多少次探一次会话探针（issue #16：401 盲区）。 */
const FAILURE_PROBE_THRESHOLD = 5;

/** 再播种延迟：对齐浏览器原生重连节奏（~3s），非退避。 */
const RESEED_DELAY_MS = 3_000;

/**
 * 看门狗阈值：3× 心跳间隔（15s）——连续三拍无任何活动判死（容忍单次丢拍；
 * DSH 同型 WS 载体漏跳两拍判死）。判的是「开着但沉默」的僵死连接，浏览器
 * 对此既不 error 也不重连（#652 现象链根因）。
 */
const WATCHDOG_TIMEOUT_MS = 45_000;

/**
 * 探会话（EventSource 读不到状态码，会话过期只表现为反复 error）：
 * 401 → false（本探针只决定 SSE 停手）；其余（200 / 404 / 网络错）→ true，
 * 按会话未知处理、继续原生重连。探针是匿名可逛面上的背景查询——显式
 * {@code silentUnauthorized}，401 不弹 M01（首页匿名访问不因探活被拦）。
 *
 * <p><b>探 {@code /api/projects} 而非 {@code /me}（访客 era 修正）</b>：/me 是
 * 账户端点，访客凭证按设计吃 401——拿它探活会把持有效凭证的访客误判成
 * 「会话已死」，SSE 通道（通知＋智能体两通道）根本不建连，agent 流/error
 * 兜底事件全数失明。列表端点过 /api/** 拦截面（账户会话或有效访客凭证
 * 任一在场即 200、全无才 401），正是本探针要判定的语义。</p>
 */
export async function probeSessionAlive(): Promise<boolean> {
  try {
    await api.get("/projects", { query: { page: 1, size: 1 }, silentUnauthorized: true });
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
  private watchdogTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly opts: SseConnectionOptions) {}

  connect() {
    if (this.closed) return;
    // 幂等：同实例重复 connect（StrictMode 双挂载）/ 建连中 / 已连 → 不另起连接
    if (this.es && this.es.readyState !== EventSource.CLOSED) return;

    const es = new EventSource(this.opts.url);
    this.es = es;
    this.setStatus("connecting");
    this.armWatchdog(); // 建连期也计：SYN 卡死的「connecting 僵死」同样要判死

    es.onopen = () => {
      this.setStatus("connected");
      this.failures = 0;
      this.armWatchdog();
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
          this.close(); // 401：停手——401 出口已在薄 client 内事件化（#326，弹窗归 M01）
        } else if (gaveUp) {
          this.scheduleReseed();
        }
      });
    };
    es.addEventListener("event", (ev) => {
      const message = ev as MessageEvent<string>;
      const id = message.lastEventId ?? "";
      this.armWatchdog();
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
    // 心跳具名事件（服务端每 15s 一条，无 id）：只喂看门狗，不进 onEvent／去重集
    es.addEventListener("ping", () => {
      this.armWatchdog();
    });
  }

  /** 显式关闭（agent 通道 unmount / 401 停手）：关闭后本实例不再复活。 */
  close() {
    this.closed = true;
    this.disarmWatchdog();
    if (this.reseedTimer !== null) {
      clearTimeout(this.reseedTimer);
      this.reseedTimer = null;
    }
    this.es?.close();
    this.es = null;
    this.setStatus("offline");
  }

  /**
   * 浏览器放弃重连（CLOSED，典型 = 非 2xx）与看门狗判死（{@link #declareDead}）
   * 共用的再播种：不是接管退避——无指数、节奏对齐原生 ~3s 重试；只在当前无活
   * 连接时兜底。定时期间若已另有新连接（外部 connect 重建），不误关它。
   */
  private scheduleReseed() {
    if (this.closed || this.reseedTimer !== null) return;
    this.reseedTimer = setTimeout(() => {
      this.reseedTimer = null;
      if (this.closed) return;
      if (this.es && this.es.readyState !== EventSource.CLOSED) return; // 已有活连接接管
      this.es?.close();
      this.es = null;
      this.connect();
    }, RESEED_DELAY_MS);
  }

  /** 看门狗上弦：任何活动（open／事件／ping）调用一次，阈值见 {@link WATCHDOG_TIMEOUT_MS}。 */
  private armWatchdog() {
    if (this.closed) return;
    this.disarmWatchdog();
    const es = this.es;
    if (!es) return;
    this.watchdogTimer = setTimeout(() => this.declareDead(es), WATCHDOG_TIMEOUT_MS);
  }

  private disarmWatchdog() {
    if (this.watchdogTimer !== null) {
      clearTimeout(this.watchdogTimer);
      this.watchdogTimer = null;
    }
  }

  /**
   * 判死（#652）：连接开着但 {@link WATCHDOG_TIMEOUT_MS} 内无任何活动——半开
   * 僵死，浏览器对此不 error 不重连。立即关闭（释放连接池槽位：HTTP/1.1 下
   * 半开流占槽会饿死作答 POST）、置 offline（既有 toast／门控轮询兜底生效），
   * 按再播种节奏重建；重建成功走 onReconnect（hadError 置位）。
   *
   * <p><b>取舍</b>：判死重建的是新 EventSource——不带 Last-Event-ID（浏览器只在
   * 同一实例的自动重连里携带），&gt;45s 长断流后的恢复从「带锚补发断线窗口」
   * 变为「新连接＋onReconnect 广谱 invalidate 的 REST 收敛」——与刷新同语义
   * （#89：对话史经 REST 水合为正本，补发只是断线窗口的优化路径；原生重试
   * ~3s 节奏下活后端的重连远早于 45s，走到判死的只剩真僵死与长不可用）。</p>
   */
  private declareDead(es: EventSource) {
    if (this.closed || this.es !== es) return;
    es.close();
    this.es = null;
    this.hadError = true;
    this.setStatus("offline");
    this.scheduleReseed();
  }

  private setStatus(status: "connecting" | "connected" | "offline") {
    useSseStatusStore.getState().setStatus(this.opts.channel, status);
  }
}
