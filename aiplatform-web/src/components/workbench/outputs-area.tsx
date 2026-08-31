"use client";

import { Monitor, PackageCheck } from "lucide-react";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

import { PanelPlaceholder } from "./panel-placeholder";
import { PrdPanel } from "./prd-panel";

/**
 * 成果区（#20 长出）：项目界面的产物呈现区，文件 / 系统 / 项目三模式。本片
 * （需求环②）实装文件模式 = PRD 文件呈现（BA 产出/修订即此可见）；系统模式
 * 随生成环（开始做系统后呈现可操作的系统）、项目模式随交易环（订单卡）落位。
 * 模式自动切换（run 开始 → 系统模式）随对应切片接线，当前缺省停在文件模式。
 */
export function OutputsArea({ projectId }: { projectId: string }) {
  return (
    <Tabs defaultValue="files" className="flex h-full min-h-0 flex-col">
      <TabsList className="m-2 grid grid-cols-3">
        <TabsTrigger value="files">文件</TabsTrigger>
        <TabsTrigger value="system">系统</TabsTrigger>
        <TabsTrigger value="project">项目</TabsTrigger>
      </TabsList>
      <TabsContent value="files" className="min-h-0 flex-1 border-t">
        <PrdPanel projectId={projectId} />
      </TabsContent>
      <TabsContent value="system" className="min-h-0 flex-1 border-t">
        <PanelPlaceholder icon={<Monitor />} title="系统">
          开始做系统后，这里会出现可以操作的你的系统
        </PanelPlaceholder>
      </TabsContent>
      <TabsContent value="project" className="min-h-0 flex-1 border-t">
        <PanelPlaceholder icon={<PackageCheck />} title="项目">
          项目与订单的信息会在这里呈现
        </PanelPlaceholder>
      </TabsContent>
    </Tabs>
  );
}
