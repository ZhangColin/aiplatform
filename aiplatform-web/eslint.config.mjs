import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

import buttonRenderNativeButton from "./eslint-local-rules/button-render-native-button.mjs";

const localRules = {
  meta: { name: "local-rules" },
  rules: {
    "button-render-native-button": buttonRenderNativeButton,
  },
};

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  {
    plugins: { "local-rules": localRules },
    rules: {
      "local-rules/button-render-native-button": "error",
    },
  },
  // shadcn registry output is vendored upstream code kept pristine (ADR 0001,
  // re-synced via CLI). react-hooks v6 flags official upstream patterns here;
  // exempt these paths instead of diverging from the registry source.
  {
    files: ["src/components/ui/**/*.{ts,tsx}", "src/hooks/use-mobile.ts"],
    rules: {
      "react-hooks/set-state-in-effect": "off",
    },
  },
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
  ]),
]);

export default eslintConfig;
