import { z } from 'zod';

const envSchema = z.object({
  DEEPSEEK_API_KEY: z.string().default(''), AI_MODEL: z.string().min(1).default('deepseek-flash'),
  AI_BASE_URL: z.url().default('https://api.deepseek.com'),
  HOST: z.string().default('127.0.0.1'), PORT: z.coerce.number().int().min(1).max(65535).default(8787),
  STATE_FILE: z.string().default('.state/auth.json'),
  ACCESS_TTL_SECONDS: z.coerce.number().int().min(30).max(3600).default(900),
  REFRESH_TTL_DAYS: z.coerce.number().int().min(1).max(90).default(30),
  PAIR_TTL_SECONDS: z.coerce.number().int().min(30).max(600).default(300),
  REQUESTS_PER_MINUTE: z.coerce.number().int().min(1).max(120).default(6),
  PAIR_ATTEMPTS_PER_10_MINUTES: z.coerce.number().int().min(1).max(30).default(10),
  MAX_CONTEXT_BYTES: z.coerce.number().int().min(1024).max(98304).default(65536),
  MAX_OUTPUT_TOKENS: z.coerce.number().int().min(128).max(4096).default(1800),
  PROVIDER_TIMEOUT_MS: z.coerce.number().int().min(1000).max(120000).default(60000),
  PROVIDER_RETRIES: z.coerce.number().int().min(0).max(1).default(0),
  TRUST_PROXY: z.string().default('127.0.0.1'),
  ALLOW_LOCAL_HTTP: z.enum(['true', 'false']).default('true')
});
export type Config = z.infer<typeof envSchema>;
export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const config = envSchema.parse(env);
  if (new URL(config.AI_BASE_URL).protocol !== 'https:') throw new Error('AI_BASE_URL must use HTTPS');
  return config;
}
