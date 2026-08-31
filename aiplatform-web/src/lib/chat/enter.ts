/**
 * 回车提交判定（issue #19 需求环①，纯逻辑）：首页一句话输入框与指令区输入条
 * 共用的「Enter 提交、Shift+Enter 换行」口径。中文输入法组词期的 Enter 是选字
 * 不是提交——以 nativeEvent.isComposing 拦下（键盘事件浏览器侧字段，SSR 断言
 * 走同等形状的裸对象）。
 */
export function isSubmitEnter(event: {
  key: string;
  shiftKey: boolean;
  nativeEvent?: { isComposing?: boolean };
}): boolean {
  if (event.key !== "Enter" || event.shiftKey) return false;
  return event.nativeEvent?.isComposing !== true;
}
