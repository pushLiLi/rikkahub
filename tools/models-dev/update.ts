// 拉取 https://models.dev/api.json，只保留模型匹配与供应商目录用到的字段，写入 app assets 作为内置快照。
// 字段与 ai 模块 ModelsDevCatalog 解析的保持一致；app 运行时刷新也会写出同样格式。
// 用法：bun tools/models-dev/update.ts

const OUTPUT = `${import.meta.dir}/../../app/src/main/assets/models_dev.json`;
const USED_MODALITIES = new Set(["text", "image"]);

type Model = {
  name?: string | null;
  status?: string | null;
  modalities?: { input?: string[]; output?: string[] };
  tool_call?: boolean | null;
  reasoning?: boolean | null;
  limit?: { context?: number | null };
  canonical_model_id?: string | null;
};

type Provider = {
  name?: string | null;
  api?: string | null;
  doc?: string | null;
  npm?: string | null;
  models?: Record<string, Model>;
};

const response = await fetch("https://models.dev/api.json");
if (!response.ok) throw new Error(`models.dev responded ${response.status}`);
const api = (await response.json()) as Record<string, Provider>;

const slim: Record<string, { name?: string; api?: string; doc?: string; npm?: string; models: Record<string, unknown> }> = {};
let count = 0;
for (const [providerId, provider] of Object.entries(api)) {
  const models: Record<string, unknown> = {};
  for (const [modelId, model] of Object.entries(provider.models ?? {})) {
    models[modelId] = {
      ...(model.name ? { name: model.name } : {}),
      ...(model.status ? { status: model.status } : {}),
      modalities: {
        input: (model.modalities?.input ?? ["text"]).filter((it) => USED_MODALITIES.has(it)),
        output: (model.modalities?.output ?? ["text"]).filter((it) => USED_MODALITIES.has(it)),
      },
      ...(model.tool_call ? { tool_call: true } : {}),
      ...(model.reasoning ? { reasoning: true } : {}),
      ...(model.limit?.context ? { limit: { context: model.limit.context } } : {}),
      ...(model.canonical_model_id ? { canonical_model_id: model.canonical_model_id } : {}),
    };
    count += 1;
  }
  slim[providerId] = {
    ...(provider.name ? { name: provider.name } : {}),
    ...(provider.api ? { api: provider.api } : {}),
    ...(provider.doc ? { doc: provider.doc } : {}),
    ...(provider.npm ? { npm: provider.npm } : {}),
    models,
  };
}

const bytes = await Bun.write(OUTPUT, JSON.stringify(slim));
console.log(`${Object.keys(slim).length} providers, ${count} models, ${bytes} bytes -> ${OUTPUT}`);
