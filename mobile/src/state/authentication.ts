import {
  currentNativeApiOriginOverride,
  persistNativeApiOriginOverride,
  type ApiOriginStorage,
} from "../platform/config";

export async function authenticateWithNativeApiOrigin<T>(
  operation: () => Promise<T>,
  storage: ApiOriginStorage,
): Promise<T> {
  const result = await operation();
  const selectedOrigin = currentNativeApiOriginOverride();
  if (!selectedOrigin) return result;
  try {
    await persistNativeApiOriginOverride(selectedOrigin, storage);
  } catch {
    // 本机偏好写入失败不应撤销已经成功的认证结果。
  }
  return result;
}
