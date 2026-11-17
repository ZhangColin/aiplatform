import { useId, useState } from "react";
import { cn } from "@/lib/utils";

/**
 * 列表模式件（ADR-0051 基座模式件）：「列表＋筛选＋分页」的行为骨架
 * 收口——筛选状态管理（文本搜索＋下拉等值、变更自动回第 1 页）与分页数据流
 * （客户端全量切片 / 服务端 total 上抛两形）。机制/呈现分离（三态 UX 件同款律）：
 *
 * - 样式只吃语义 token 类（与 globals.css @theme 映射同源）——换肤只动 :root；
 *   禁裸色、禁任意值、禁 inline style（呈现零主张）。
 * - 文案默认可覆盖（emptyText / allOptionLabel / prevText / nextText 等 props）。
 * - 定制三通道封顶：参数（items/filters/renderItem/children props）、文案覆盖、
 *   wrapper（外层包卡片/工具条）；本件内部不改（基座件不改约）。
 *
 * 用法骨架（客户端形——全量数据进、件内筛选＋分页）：
 *   <FilterableList
 *     items={orders}
 *     filters={[{ name: 'status', label: '状态', type: 'select', options }]}
 *     renderItem={(o) => <div>{o.title}</div>} />
 *
 * 服务端形：传 total（页信息以它为准、不自切片），翻页/筛选经 onQueryChange
 * 上抛、由调用方回填当前页 items；内容区可用 children 函数整块接管（表格等）。
 * 服务端形下 onQueryChange 的乱序回填（快速连续变更时旧请求后到）归调用方
 * 自理——本件只保证按事件序如实上抛。
 */

export interface PatternListFilterDef {
  /** 筛选名；select 形按条目同名字段等值匹配。 */
  name: string;
  label: string;
  type?: "text" | "select";
  placeholder?: string;
  options?: { value: string; label: string }[];
  /** text 形参与匹配的条目字段名；缺省＝条目全部字符串字段（搜索语义）。 */
  matchFields?: string[];
}

export interface PatternListQuery {
  filters: Record<string, string>;
  page: number;
  pageSize: number;
}

export interface PatternListView<T> {
  /** 当前页条目（客户端形已切页；服务端形＝调用方传入的原样）。 */
  items: T[];
  filters: Record<string, string>;
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
}

export interface FilterableListProps<T> {
  items: T[];
  filters?: PatternListFilterDef[];
  /** 筛选初值。 */
  defaultFilters?: Record<string, string>;
  pageSize?: number;
  /**
   * 服务端形标记：传入即客户端不切片——items 须为当前页数据、total 为总条数，
   * 翻页/筛选经 onQueryChange 上抛由调用方取数；缺省＝客户端形（全量进、件内
   * 筛选＋分页）。
   */
  total?: number;
  onQueryChange?: (query: PatternListQuery) => void;
  /** 条目渲染（简单形）。 */
  renderItem?: (item: T, index: number) => React.ReactNode;
  /** 内容区整块接管（高级形：表格等自定标记）；为函数时优先于 renderItem。 */
  children?: (view: PatternListView<T>) => React.ReactNode;
  /** 列表 key 提取（动态数据建议传，缺省用序号）。 */
  itemKey?: (item: T, index: number) => string | number;
  className?: string;
  /** 以下文案全量可覆盖（呈现主权在项目侧）。 */
  emptyText?: string;
  allOptionLabel?: string;
  prevText?: string;
  nextText?: string;
}

function stringFieldKeys<T>(item: T): string[] {
  return Object.entries(item ?? {})
    .filter(([, v]) => typeof v === "string")
    .map(([k]) => k);
}

/** 页码序列（≤7 页全列；否则首末页常驻＋当前页邻域，间隔以省略号呈现）。 */
function pageSequence(current: number, totalPages: number): (number | "…")[] {
  if (totalPages <= 7) {
    return Array.from({ length: totalPages }, (_, i) => i + 1);
  }
  const keep = new Set([
    1,
    2,
    totalPages - 1,
    totalPages,
    current - 1,
    current,
    current + 1,
  ]);
  const sequence: (number | "…")[] = [];
  let previous = 0;
  for (let p = 1; p <= totalPages; p++) {
    if (!keep.has(p)) {
      continue;
    }
    if (p - previous > 1) {
      sequence.push("…");
    }
    sequence.push(p);
    previous = p;
  }
  return sequence;
}

export function FilterableList<T>({
  items,
  filters: filterDefs,
  defaultFilters,
  pageSize = 10,
  total: serverTotal,
  onQueryChange,
  renderItem,
  children,
  itemKey,
  className,
  emptyText = "暂无数据",
  allOptionLabel = "全部",
  prevText = "上一页",
  nextText = "下一页",
}: FilterableListProps<T>) {
  const listId = useId();
  const [filterValues, setFilterValues] = useState<Record<string, string>>(
    () => ({ ...(defaultFilters ?? {}) }),
  );
  const [page, setPage] = useState(1);

  const clientMode = serverTotal === undefined;
  const clientFiltered = clientMode
    ? items.filter((item) =>
        (filterDefs ?? []).every((def) => {
          const value = (filterValues[def.name] ?? "").trim();
          if (!value) {
            return true;
          }
          if (def.type === "select") {
            return String((item as Record<string, unknown>)[def.name]) === value;
          }
          const fields = def.matchFields ?? stringFieldKeys(item);
          const needle = value.toLowerCase();
          return fields.some((f) =>
            String((item as Record<string, unknown>)[f] ?? "")
              .toLowerCase()
              .includes(needle),
          );
        }),
      )
    : items;

  const total = clientMode ? clientFiltered.length : serverTotal;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.min(Math.max(1, page), totalPages);
  const viewItems = clientMode
    ? clientFiltered.slice((safePage - 1) * pageSize, safePage * pageSize)
    : items;

  function changeFilter(name: string, value: string) {
    const next = { ...filterValues, [name]: value };
    setFilterValues(next);
    setPage(1);
    onQueryChange?.({ filters: next, page: 1, pageSize });
  }

  function goToPage(next: number) {
    const clamped = Math.min(Math.max(1, next), totalPages);
    setPage(clamped);
    onQueryChange?.({ filters: filterValues, page: clamped, pageSize });
  }

  const view: PatternListView<T> = {
    items: viewItems,
    filters: filterValues,
    page: safePage,
    pageSize,
    total,
    totalPages,
  };

  const pageButtonClass =
    "inline-flex size-8 items-center justify-center rounded-md border border-border bg-background text-sm text-foreground transition-colors hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50";
  const stepButtonClass =
    "inline-flex h-8 items-center justify-center rounded-md border border-border bg-background px-2 text-sm text-foreground transition-colors hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50";

  return (
    <div className={cn("flex w-full flex-col gap-4", className)}>
      {filterDefs && filterDefs.length > 0 ? (
        <div className="flex flex-wrap items-end gap-3">
          {filterDefs.map((def) => {
            const id = `${listId}-${def.name}`;
            return (
              <div key={def.name} className="flex flex-col gap-1.5">
                <label htmlFor={id} className="text-sm text-muted-foreground">
                  {def.label}
                </label>
                {def.type === "select" ? (
                  <select
                    id={id}
                    value={filterValues[def.name] ?? ""}
                    className="h-9 rounded-md border border-input bg-background px-3 text-sm text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
                    onChange={(e) => changeFilter(def.name, e.target.value)}
                  >
                    <option value="">{allOptionLabel}</option>
                    {def.options?.map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                ) : (
                  <input
                    id={id}
                    type="text"
                    value={filterValues[def.name] ?? ""}
                    placeholder={def.placeholder}
                    className="h-9 w-52 rounded-md border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
                    onChange={(e) => changeFilter(def.name, e.target.value)}
                  />
                )}
              </div>
            );
          })}
        </div>
      ) : null}
      {viewItems.length === 0 ? (
        <p className="min-h-16 px-1 py-6 text-center text-sm text-muted-foreground">
          {emptyText}
        </p>
      ) : typeof children === "function" ? (
        children(view)
      ) : (
        <div className="flex flex-col divide-y divide-border rounded-lg border border-border bg-background">
          {viewItems.map((item, index) => (
            <div key={itemKey ? itemKey(item, index) : index} className="px-4 py-3">
              {renderItem?.(item, index)}
            </div>
          ))}
        </div>
      )}
      {totalPages > 1 ? (
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-sm text-muted-foreground">
            共 {total} 条 · {safePage} / {totalPages} 页
          </p>
          <nav aria-label="分页" className="flex items-center gap-1">
            <button
              type="button"
              className={stepButtonClass}
              disabled={safePage <= 1}
              onClick={() => goToPage(safePage - 1)}
            >
              {prevText}
            </button>
            {pageSequence(safePage, totalPages).map((entry, index) =>
              typeof entry === "number" ? (
                <button
                  key={entry}
                  type="button"
                  aria-label={`第 ${entry} 页`}
                  aria-current={entry === safePage ? "page" : undefined}
                  className={cn(pageButtonClass, {
                    "border-transparent bg-accent font-medium": entry === safePage,
                  })}
                  onClick={() => goToPage(entry)}
                >
                  {entry}
                </button>
              ) : (
                <span
                  key={`ellipsis-${index}`}
                  aria-hidden
                  className="px-1 text-sm text-muted-foreground"
                >
                  …
                </span>
              ),
            )}
            <button
              type="button"
              className={stepButtonClass}
              disabled={safePage >= totalPages}
              onClick={() => goToPage(safePage + 1)}
            >
              {nextText}
            </button>
          </nav>
        </div>
      ) : null}
    </div>
  );
}
