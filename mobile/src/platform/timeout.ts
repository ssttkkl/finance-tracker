import { ApiError } from "@finance-tracker/api-client";

const WRITE_TIMEOUT_MS = 15_000;

export function withWriteTimeout<T>(promise: Promise<T>, timeoutMs = WRITE_TIMEOUT_MS): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<T>((_resolve, reject) => {
    timer = setTimeout(() => reject(new ApiError("request_timeout", 0)), timeoutMs);
  });
  return Promise.race([promise, timeout]).finally(() => {
    if (timer) clearTimeout(timer);
  });
}
