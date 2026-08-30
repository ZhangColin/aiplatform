// PROTOTYPE（throwaway）—— 独立浏览器页打开的系统预览（工作台外无壳）
import { PREVIEW_IFRAME_PROPS, PREVIEW_SRCDOC, PROJECT } from "../workbench/canned"

export default function PrototypePreviewPage() {
  if (process.env.NODE_ENV === "production") return null

  return (
    <main className="flex h-svh flex-col">
      <header className="flex h-10 shrink-0 items-center gap-2 border-b px-4 text-sm">
        <span className="font-medium">预览 · {PROJECT.name}</span>
        <code className="rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
          {PROJECT.previewUrl}
        </code>
        <span className="ml-auto text-xs text-muted-foreground">PROTOTYPE 罐头</span>
      </header>
      <iframe {...PREVIEW_IFRAME_PROPS} srcDoc={PREVIEW_SRCDOC} className="min-h-0 flex-1 border-0 bg-white" />
    </main>
  )
}
