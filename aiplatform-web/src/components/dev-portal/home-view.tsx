"use client";

import { Plus } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { CreateProjectForm } from "@/components/user-portal/create-project-form";
import { ProjectListView } from "@/components/user-portal/project-list-view";

/**
 * 开发平台落地页视图（spec 0003 §1，issue #39）：页头「新建项目」按钮 → 对话框
 * 内一句话建项目（同一 CC 框表单），成功后直进该项目工作台（dev 三模式）。列表
 * 组件场景化取用（#20 组件，issue #21）。
 */
export function DevHomeView() {
  const router = useRouter();
  const [open, setOpen] = useState(false);

  return (
    <>
      <ProjectListView
        title="项目列表"
        description="主链推进中的项目；门就绪与智能体等待点会进入待办中心"
        headerAction={
          <Button size="sm" onClick={() => setOpen(true)}>
            <Plus /> 新建项目
          </Button>
        }
      />
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="sm:max-w-xl">
          <DialogHeader>
            <DialogTitle>新建项目</DialogTitle>
            <DialogDescription>一句话描述，团队开始搭建</DialogDescription>
          </DialogHeader>
          <CreateProjectForm
            onCreated={(id) => {
              setOpen(false);
              router.push(`/dev/projects/${id}`);
            }}
          />
        </DialogContent>
      </Dialog>
    </>
  );
}
