import * as SecureStore from "expo-secure-store";
import type { TokenStore } from "@finance-tracker/contracts";
import { NATIVE_SESSION_TOKEN_STORAGE_KEY } from "./tokenKey";

export { NATIVE_SESSION_TOKEN_STORAGE_KEY } from "./tokenKey";

export const nativeTokenStore: TokenStore = {
  get: () => SecureStore.getItemAsync(NATIVE_SESSION_TOKEN_STORAGE_KEY),
  set: (value) => SecureStore.setItemAsync(NATIVE_SESSION_TOKEN_STORAGE_KEY, value),
  clear: () => SecureStore.deleteItemAsync(NATIVE_SESSION_TOKEN_STORAGE_KEY),
};
