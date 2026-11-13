import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["src/**/*.test.ts"],
    // Tests share one Firestore emulator: run files one after another
    fileParallelism: false,
    testTimeout: 20_000,
  },
});
