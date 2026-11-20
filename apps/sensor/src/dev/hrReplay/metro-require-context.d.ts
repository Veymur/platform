interface MetroRequireContext {
  keys(): string[];
  (id: string): unknown;
}
interface NodeRequire {
  context(
    directory: string,
    recursive?: boolean,
    filter?: RegExp,
  ): MetroRequireContext;
}
