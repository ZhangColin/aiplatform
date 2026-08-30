/**
 * 本地 ESLint 规则：`<Button>` 通过 `render` 渲染非 `<button>` 元素时，
 * 必须显式 `nativeButton={false}`。
 *
 * 背景：`src/components/ui/button.tsx` 包装的 Base UI `Button` 默认
 * `nativeButton = true`。当 `render={<Link />}`（渲染 `<a>`）或
 * `render={<a download />}` 时，Base UI 在 dev 下告警，并给 `<a>` 施加
 * `type="button"` 与错误的键盘语义。仓库范式（pagination.tsx）是显式
 * `nativeButton={false}`，本规则把这一约束固化为 lint 错误，防止回退。
 */
const buttonRenderNativeButton = {
  meta: {
    type: "problem",
    docs: {
      description:
        "`<Button>` rendering a non-`<button>` element via `render` must set `nativeButton={false}`.",
    },
    messages: {
      missingNativeButton:
        "`render` 渲染非 `<button>` 元素时必须显式 `nativeButton={false}`，否则 Base UI 会施加错误的原生 button 语义并告警。",
    },
    fixable: "code",
    schema: [],
  },
  create(context) {
    return {
      JSXOpeningElement(node) {
        if (node.name?.type !== "JSXIdentifier" || node.name.name !== "Button") {
          return;
        }

        const renderAttr = node.attributes.find(
          (attr) => attr.type === "JSXAttribute" && attr.name?.name === "render"
        );
        if (!renderAttr) return;

        const renderTarget = renderTargetName(renderAttr.value);
        // 只关心 render 目标是明确的非 `<button>` 元素（如 <Link> / <a>）。
        if (!renderTarget || renderTarget === "button") return;

        const nativeButtonAttr = node.attributes.find(
          (attr) =>
            attr.type === "JSXAttribute" && attr.name?.name === "nativeButton"
        );

        // 安全形态 = 显式 `nativeButton={false}`。
        if (nativeButtonAttr && isFalseExpression(nativeButtonAttr.value)) {
          return;
        }

        context.report({
          node,
          messageId: "missingNativeButton",
          fix(fixer) {
            if (nativeButtonAttr) {
              return fixer.replaceText(nativeButtonAttr, "nativeButton={false}");
            }
            return fixer.insertTextAfter(node.name, " nativeButton={false}");
          },
        });
      },
    };
  },
};

export default buttonRenderNativeButton;

/** 从 `render={...}` 的属性值里解析出被渲染元素的标签名（如 `Link` / `a`）。 */
function renderTargetName(value) {
  if (!value || value.type !== "JSXExpressionContainer") return null;
  const expr = value.expression;
  if (expr?.type === "JSXElement") {
    const name = expr.openingElement?.name;
    return name?.type === "JSXIdentifier" ? name.name : null;
  }
  return null;
}

/** `{false}` 字面量 → 返回 true；其余（缺失 / `{true}` / 表达式）→ false。 */
function isFalseExpression(value) {
  if (!value || value.type !== "JSXExpressionContainer") return false;
  const expr = value.expression;
  return expr?.type === "Literal" && expr.value === false;
}
