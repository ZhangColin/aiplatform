import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { useSseFallbackPolling } from "@/lib/sse/provider";
import {
  buildCloseBugInput,
  normalizeBug,
  normalizeTask,
  normalizeTaskCard,
  normalizeTaskDetail,
  type BugResponse,
  type CreateTaskCommand,
  type RejectTaskCommand,
  type SubmitTaskCommand,
  type TaskCardResponse,
  type TaskDetailResponse,
  type TaskResponse,
} from "@/lib/tasks/task";

/**
 * 任务系统数据层（issue #22，spec 0003 §2.7）：opc 我的任务 / 详情 / 开始 / 提交，
 * dev 项目任务 / 建任务 / 确认 / 驳回 / 取消 / Bug 面板。实时性走 SSE task-updated
 * （通知通道）→ 桥失效 tasks / projects / todos 三域（#21 已挂）；写操作成功本地
 * 同样失效（正确性以 REST 为准，不等 SSE 到达）。错误面（403 TASK_004/TASK_009、
 * 409 TASK_002、404 TASK_005/TASK_008、400 TASK_006）由薄 client 解包成 ApiError，
 * 组件侧 errorText 直出后端中文 message。
 */

/** 写操作成功后的统一失效：任务域 + 待办域（任务迁移即待办增删）。 */
function invalidateTasksAndTodos(queryClient: ReturnType<typeof useQueryClient>) {
  void queryClient.invalidateQueries({ queryKey: queryKeys.tasks.all });
  void queryClient.invalidateQueries({ queryKey: queryKeys.todos.all });
}

/** 任务/待办 + 项目域：期推进（stage-changed）与开发完成门 gate.ready 依赖项目详情重拉。 */
function invalidateTasksTodosAndProjects(queryClient: ReturnType<typeof useQueryClient>) {
  invalidateTasksAndTodos(queryClient);
  void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
}

// ── OPC 侧 ──────────────────────────────────────────────────────────────────

/** 我的任务卡片（assignee=me，新→旧）；门控轮询兜底看通知通道（task-updated 载体）。 */
export function useMyTasks() {
  const fallbackPolling = useSseFallbackPolling("notification");
  return useQuery({
    queryKey: queryKeys.tasks.list,
    queryFn: ({ signal }) =>
      api
        .get<TaskCardResponse[]>("/tasks", { signal })
        .then((items) => (items ?? []).map(normalizeTaskCard)),
    refetchInterval: fallbackPolling,
  });
}

/** 任务详情（task + ProjectBrief + bugs[]）；非指派且非项目 owner → 403 TASK_004。 */
export function useTaskDetail(taskId: string | undefined) {
  const fallbackPolling = useSseFallbackPolling("notification");
  return useQuery({
    queryKey: queryKeys.tasks.detail(taskId ?? ""),
    queryFn: ({ signal }) =>
      api.get<TaskDetailResponse>(`/tasks/${taskId}`, { signal }).then(normalizeTaskDetail),
    enabled: taskId !== undefined,
    refetchInterval: fallbackPolling,
  });
}

/** 开始测试（已发布 → 执行中留痕）；仅指派本人，否则 403 TASK_004。 */
export function useStartTask(taskId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<TaskResponse>(`/tasks/${taskId}/start`),
    onSuccess: () => invalidateTasksAndTodos(queryClient),
  });
}

/** 整任务一次性提交（首轮 / 复测两形状由组件按 bugs[] 判别构造，spec §2.3）。 */
export function useSubmitTask(taskId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (command: SubmitTaskCommand) =>
      api.post<TaskResponse>(`/tasks/${taskId}/submit`, command),
    onSuccess: () => invalidateTasksAndTodos(queryClient),
  });
}

// ── dev 侧 ──────────────────────────────────────────────────────────────────

/** 项目任务全量（含 submittedPayload / rejectReason 裁决面）。 */
export function useProjectTasks(projectId: string | undefined) {
  const fallbackPolling = useSseFallbackPolling("notification");
  return useQuery({
    queryKey: queryKeys.tasks.projectTasks(projectId ?? ""),
    queryFn: ({ signal }) =>
      api
        .get<TaskResponse[]>(`/projects/${projectId}/tasks`, { signal })
        .then((items) => (items ?? []).map(normalizeTask)),
    enabled: projectId !== undefined,
    refetchInterval: fallbackPolling,
  });
}

/**
 * 建测试任务（开发段内开发 → 测试的唯一触发）：除任务域 / 待办域外连带失效
 * projects 域——期推进（stage-changed）刷新进度条不等 SSE。
 */
export function useCreateTask(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (command: CreateTaskCommand) =>
      api.post<TaskResponse>(`/projects/${projectId}/tasks`, command),
    onSuccess: () => invalidateTasksTodosAndProjects(queryClient),
  });
}

/** 确认通过（一事务内 Bug 入库 / 复测翻态）；非项目 owner 403 TASK_009。 */
export function useConfirmTask() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (taskId: string) => api.post<TaskResponse>(`/tasks/${taskId}/confirm`),
    onSuccess: () => invalidateTasksAndTodos(queryClient),
  });
}

/** 驳回（reason 必填，表单侧空值拦截后才发）；状态回执行中待重交。 */
export function useRejectTask() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ taskId, command }: { taskId: string; command: RejectTaskCommand }) =>
      api.post<TaskResponse>(`/tasks/${taskId}/reject`, command),
    onSuccess: () => invalidateTasksAndTodos(queryClient),
  });
}

/** 取消任务（已发布 / 执行中可取消；已提交只能驳回，409 TASK_002）。 */
export function useCancelTask() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (taskId: string) => api.post<TaskResponse>(`/tasks/${taskId}/cancel`),
    onSuccess: () => invalidateTasksAndTodos(queryClient),
  });
}

/** 项目 Bug 面板（三态行；fixRunId / fixNote 内容随后端 #27，字段占位不深呈现）。 */
export function useProjectBugs(projectId: string | undefined) {
  const fallbackPolling = useSseFallbackPolling("notification");
  return useQuery({
    queryKey: queryKeys.tasks.projectBugs(projectId ?? ""),
    queryFn: ({ signal }) =>
      api
        .get<BugResponse[]>(`/projects/${projectId}/bugs`, { signal })
        .then((items) => (items ?? []).map(normalizeBug)),
    enabled: projectId !== undefined,
    refetchInterval: fallbackPolling,
  });
}

/**
 * 关闭 Bug（issue #38）：OPEN/FIXED → VERIFIED + closed_reason（reason 必填，
 * 表单侧空值拦截后才发；VERIFIED 终态再关 409 TASK_002 由后端守卫）。关掉最后一个
 * 未关闭 Bug 会推进开发完成确认门（gate.ready 后端驱动），故连带失效 projects 域。
 */
export function useCloseBug(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ bugId, reason }: { bugId: string; reason: string }) => {
      const input = buildCloseBugInput(bugId, reason);
      return api.post<BugResponse>(
        `/projects/${projectId}/bugs/${input.bugId}/close`,
        input.command,
      );
    },
    onSuccess: () => invalidateTasksTodosAndProjects(queryClient),
  });
}

/**
 * 派发修复（issue #38）：幂等手动、无体。触发后待修复 Bug 逐条进修复链（后端派
 * 开发智能体、agent 流走 SSE），开发完成门随修复收口解锁（gate.ready 后端驱动）——
 * 故连带失效 projects 域重拉 gate。
 */
export function useDispatchFixes(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<void>(`/projects/${projectId}/bugs/dispatch-fixes`),
    onSuccess: () => invalidateTasksTodosAndProjects(queryClient),
  });
}
