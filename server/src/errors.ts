export class ApiError extends Error {
  constructor(public status: number, public code: string, public publicMessage: string) { super(code); }
}
export const invalidProvider = () => new ApiError(502, 'INVALID_PROVIDER_RESPONSE', 'Ответ ИИ пустой, обрезан или не соответствует схеме. Повторите запрос.');
