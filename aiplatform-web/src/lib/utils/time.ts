import { formatDistanceToNow } from "date-fns";
import { zhCN } from "date-fns/locale";

/** ISO 时间 → 中文相对时间（「3 分钟前」）；缺失 / 不可解析返回空串（调用处可不渲染）。 */
export function formatRelativeTime(iso: string | undefined): string {
  if (!iso) return "";
  const time = Date.parse(iso);
  if (Number.isNaN(time)) return "";
  return formatDistanceToNow(time, { addSuffix: true, locale: zhCN });
}

/**
 * 运行秒数 → mm:ss（项目页顶栏 LIVE 计时口径）；满 1 小时退化为 h:mm:ss
 * （小时位不补零）。负值归零兜底（计时器重置/时钟漂移不溢出）。
 */
export function formatElapsed(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const sec = s % 60;
  const min = Math.floor(s / 60);
  if (s >= 3600) {
    const hour = Math.floor(s / 3600);
    return `${hour}:${String(min % 60).padStart(2, "0")}:${String(sec).padStart(2, "0")}`;
  }
  return `${String(min).padStart(2, "0")}:${String(sec).padStart(2, "0")}`;
}
