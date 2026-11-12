/**
 * 会话与密码入口（基座预置·#471）：习惯入口位——只 re-export 健壮件
 * （src/lib/robust/session：会话建/读/销与密码哈希校验的行为契约都在那件里，
 * redis 挂了降级不挂死）。登录页面/流程/角色模型是功能域、自己写；但会话
 * 存取与密码原语从本文件 import 即用，不要手写会话存取、也不要自建同类入口
 * 文件（收口 lint 预置档会点名标注）。
 */
export {
  createSession,
  getSession,
  destroySession,
  hashPassword,
  verifyPassword,
} from "./robust/session";
export type { SessionBindingOptions } from "./robust/session";
