import { create } from "zustand";

/**
 * 版本记录抽屉开合状态（#344，spec #320 ⑥——交接包 §5.2、原型未画→补建）：
 * 顶栏「版本记录」与 Demo 版本 chip（现为系统面板浏览器条最小 chip，#347 迁
 * Demo 横幅后同接）双挂点共用的开合契约——同一抽屉，两处入口。抽屉本体由
 * 项目空间壳（ProjectSpaceShell）挂载，挂点只写本 store 不各自持抽屉。
 */
export type VersionRecordState = {
  open: boolean;
  openVersionRecord: () => void;
  closeVersionRecord: () => void;
};

export const useVersionRecordStore = create<VersionRecordState>((set) => ({
  open: false,
  openVersionRecord: () => set({ open: true }),
  closeVersionRecord: () => set({ open: false }),
}));
