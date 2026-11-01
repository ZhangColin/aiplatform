import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import type { Components } from "react-markdown";

/** 对话面 markdown 呈现单点（区别于 prd-doc 的文档面单点）：对话气泡话语与
 *  工作卡解说段共用——AI 产出的是 markdown 文本（加粗/列表/代码/链接），按
 *  GFM 渲染不出源码记号。块间距（space-y）由各容器自定，不在此内置。 */
const chatMarkdownComponents: Components = {
  h1: ({ children }) => <h1 className="mt-2 text-base font-semibold first:mt-0">{children}</h1>,
  h2: ({ children }) => <h2 className="mt-2 text-sm font-semibold first:mt-0">{children}</h2>,
  h3: ({ children }) => <h3 className="mt-2 text-sm font-semibold first:mt-0">{children}</h3>,
  p: ({ children }) => <p className="leading-relaxed">{children}</p>,
  ul: ({ children }) => <ul className="list-disc space-y-1 pl-5">{children}</ul>,
  ol: ({ children }) => <ol className="list-decimal space-y-1 pl-5">{children}</ol>,
  a: ({ children, href }) => (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className="text-primary underline underline-offset-2"
    >
      {children}
    </a>
  ),
  blockquote: ({ children }) => (
    <blockquote className="border-l-2 pl-3 text-muted-foreground">{children}</blockquote>
  ),
  code: ({ children }) => (
    <code className="rounded bg-muted px-1 py-0.5 font-mono text-[0.8125rem]">{children}</code>
  ),
  pre: ({ children }) => (
    <pre className="overflow-x-auto rounded-lg border bg-muted/50 p-2.5 font-mono text-xs [&_code]:rounded-none [&_code]:bg-transparent [&_code]:px-0 [&_code]:py-0">
      {children}
    </pre>
  ),
};

export function ChatMarkdown({ children }: { children: string }) {
  return (
    <Markdown remarkPlugins={[remarkGfm]} components={chatMarkdownComponents}>
      {children}
    </Markdown>
  );
}
