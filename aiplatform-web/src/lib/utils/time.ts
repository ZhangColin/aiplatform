import { formatDistanceToNow } from "date-fns";
import { zhCN } from "date-fns/locale";

/** ISO 时间 → 中文相对时间（「3 分钟前」）；缺失 / 不可解析返回空串（调用处可不渲染）。 */
export function formatRelativeTime(iso: string | undefined): string {
  if (!iso) return "";
  const time = Date.parse(iso);
  if (Number.isNaN(time)) return "";
  return formatDistanceToNow(time, { addSuffix: true, locale: zhCN });
}
