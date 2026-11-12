/**
 * 数据访问入口（基座预置·#471）：习惯入口位——只 re-export 健壮件单例
 * （src/lib/robust/data-access：超时＋瞬时错误重试＋redis 恒降级的行为契约
 * 都在那件里）。应用代码从本文件 import 即用，不要另建数据访问文件、
 * 也不要在别处自行构造客户端（收口 lint 强制档会拦，修不完收口被拒）。
 */
export {
  query,
  queryOne,
  withTransaction,
  cacheGet,
  cacheSet,
  cacheDelete,
} from "./robust/data-access";
export { DataAccessError } from "./robust/data-access";
export type { QueryOptions, CacheOptions, TxClient } from "./robust/data-access";
