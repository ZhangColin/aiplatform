"use client";

import { Radio } from "lucide-react";
import type { ReactNode } from "react";

import { StageRejectionBanner } from "@/components/main-chain/rejection-banner";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { isGateReady } from "@/lib/main-chain/project";
import { useProjectJourney } from "@/hooks/use-project";
import { useProjectWaits } from "@/hooks/use-waits";

import { AdvisorChat } from "./advisor-chat";
import { ChatMode, type ChatFocus } from "./chat-mode";
import { PanelPlaceholder } from "./panel-placeholder";
import { PendingQueue } from "./pending-queue";

/**
 * Agent 区（通用层，spec 0001 §4 / spec 0002 §4）：同一任务的多副面孔。开发平台
 * = 三模式 tab（对话 / 直播 / 待处理）；需求端 = 顾问单对话模式（无直播 / 待处理）。
 * 顶常驻驳回理由横幅（#43：StageRejectionBanner 消费 project-notices store，桥为
 * 唯一写入方），跨模式可见。待处理 tab 计数徽章 = 待处理等待点 + 门就绪（#44）。
 */
export type AgentAreaVariant = "advisor" | "dev";

export function AgentArea({
  variant,
  projectId,
  focus,
}: {
  variant: AgentAreaVariant;
  projectId: string;
  /** 深链聚焦（对话区消费：dev 对话模式 issue #44、顾问对话 issue #49）。 */
  focus?: ChatFocus;
}) {
  return (
    <div className="flex h-full min-h-0 flex-col">
      <StageRejectionBanner projectId={projectId} className="mx-2 mt-2 shrink-0" />
      {variant === "advisor" ? (
        <AdvisorChat projectId={projectId} focus={focus} />
      ) : (
        <DevAgentTabs projectId={projectId} focus={focus} />
      )}
    </div>
  );
}

function DevAgentTabs({ projectId, focus }: { projectId: string; focus?: ChatFocus }) {
  const { data: waits } = useProjectWaits(projectId);
  const { data: detail } = useProjectJourney(projectId);
  // 待处理计数 = 待处理等待点 + 门就绪可拍板（#58 收口口径：ready 显式 true 才
  // 计，缺失 = 未就绪——与挂卡点同源，计数有卡可对应）
  const gateActionable = isGateReady(detail?.gate);
  const pendingCount = (waits?.length ?? 0) + (gateActionable ? 1 : 0);

  return (
    <Tabs defaultValue="chat" className="flex min-h-0 flex-1 flex-col">
      <AgentAreaHeader>
        <TabsList className="h-7">
          <TabsTrigger value="chat" className="text-xs">
            对话
          </TabsTrigger>
          <TabsTrigger value="live" className="text-xs">
            直播
          </TabsTrigger>
          <TabsTrigger value="todo" className="text-xs">
            待处理
            {pendingCount > 0 && (
              <span className="ml-1 inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-amber-500 px-1 text-[10px] font-medium text-amber-950">
                {pendingCount}
              </span>
            )}
          </TabsTrigger>
        </TabsList>
      </AgentAreaHeader>
      <TabsContent value="chat" className="min-h-0 flex-1">
        <ChatMode projectId={projectId} focus={focus} />
      </TabsContent>
      <TabsContent value="live" className="min-h-0 flex-1">
        <PanelPlaceholder icon={<Radio />}>
          agent 执行的舞台时间线会出现在这里
        </PanelPlaceholder>
      </TabsContent>
      <TabsContent value="todo" className="min-h-0 flex-1">
        <PendingQueue projectId={projectId} />
      </TabsContent>
    </Tabs>
  );
}

function AgentAreaHeader({ children }: { children: ReactNode }) {
  return (
    <div className="flex h-10 shrink-0 items-center gap-1 border-b px-2">
      {children}
    </div>
  );
}
