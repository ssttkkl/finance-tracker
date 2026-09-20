import * as SecureStore from "expo-secure-store";
import { NATIVE_API_ORIGIN_OVERRIDE_STORAGE_KEY, type ApiOriginStorage } from "./config";

export const nativeApiOriginStorage: ApiOriginStorage = {
  get: () => SecureStore.getItemAsync(NATIVE_API_ORIGIN_OVERRIDE_STORAGE_KEY),
  set: (value) => SecureStore.setItemAsync(NATIVE_API_ORIGIN_OVERRIDE_STORAGE_KEY, value),
  clear: () => SecureStore.deleteItemAsync(NATIVE_API_ORIGIN_OVERRIDE_STORAGE_KEY),
};
