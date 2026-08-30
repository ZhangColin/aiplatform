import { SeverityBadge } from "@/components/tasks/task-badges";
import type { Bug, SubmittedPayload } from "@/lib/tasks/task";

/**
 * 提交载荷明细（issue #22，opc 已提交态 + dev 裁决面共用）：report + 首轮 bugs
 * 或复测 results 的只读呈现。results 的 bugId → 标题靠项目 Bug 清单反查（查不到
 * 显示 #bugId 兜底）。
 */
export function SubmittedPayloadView({
  payload,
  bugs,
}: {
  payload: SubmittedPayload;
  /** 复测结果的 bugId → Bug 反查源（dev 面板传项目 Bug 清单，opc 详情传详情 bugs[]）。 */
  bugs?: Bug[];
}) {
  const bugTitle = (bugId: string) => bugs?.find((b) => b.bugId === bugId)?.title;

  return (
    <div className="space-y-3">
      <div className="space-y-1">
        <p className="text-xs font-medium text-muted-foreground">测试报告</p>
        <p className="text-sm leading-relaxed whitespace-pre-wrap">{payload.report || "—"}</p>
      </div>

      {payload.bugs.length > 0 && (
        <div className="space-y-2">
          <p className="text-xs font-medium text-muted-foreground">
            Bug 清单（{payload.bugs.length}）
          </p>
          <ul className="space-y-2">
            {payload.bugs.map((bug, i) => (
              <li key={i} className="rounded-lg border p-3 text-sm">
                <div className="flex items-center gap-2">
                  <span className="min-w-0 flex-1 truncate font-medium">
                    {bug.title || "未命名 Bug"}
                  </span>
                  <SeverityBadge severity={bug.severity} />
                </div>
                {bug.description && (
                  <p className="mt-1 text-muted-foreground">{bug.description}</p>
                )}
                {bug.reproSteps && (
                  <p className="mt-1 text-xs text-muted-foreground/80 whitespace-pre-wrap">
                    复现步骤：{bug.reproSteps}
                  </p>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}

      {payload.results.length > 0 && (
        <div className="space-y-2">
          <p className="text-xs font-medium text-muted-foreground">
            复测结果（{payload.results.length}）
          </p>
          <ul className="space-y-1.5">
            {payload.results.map((result) => (
              <li key={result.bugId} className="rounded-lg border p-3 text-sm">
                <div className="flex items-center gap-2">
                  <span className="min-w-0 flex-1 truncate">
                    {bugTitle(result.bugId) ?? `Bug #${result.bugId}`}
                  </span>
                  <span
                    className={
                      result.pass
                        ? "shrink-0 text-xs font-medium text-emerald-700 dark:text-emerald-400"
                        : "shrink-0 text-xs font-medium text-destructive"
                    }
                  >
                    {result.pass ? "复测通过" : "不通过"}
                  </span>
                </div>
                {result.note && (
                  <p className="mt-1 text-xs text-muted-foreground">{result.note}</p>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
