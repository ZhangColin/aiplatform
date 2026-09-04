import type { Metadata } from "next";

import { PreviewWindow } from "@/components/preview-window";

export const metadata: Metadata = { title: "系统预览" };

/** 预览独立页（#80「在新窗口打开」）：浅色锁定的用户系统全幅页，(site) 壳外。 */
export default async function PreviewPage(props: PageProps<"/preview/[id]">) {
  const { id } = await props.params;
  return <PreviewWindow projectId={id} />;
}
