import * as DocumentPicker from "expo-document-picker";
import { File } from "expo-file-system";
import type { FileSource } from "@finance-tracker/contracts";

export async function pickStatementFile(): Promise<FileSource | null> {
  const result = await DocumentPicker.getDocumentAsync({
    copyToCacheDirectory: true,
    multiple: false,
    type: ["text/csv", "application/pdf", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"],
  });
  if (result.canceled || !result.assets?.[0]?.uri) return null;
  const asset = result.assets[0];
  const file = new File(asset.uri);
  return {
    name: asset.name || "statement",
    mediaType: asset.mimeType ?? null,
    size: asset.size ?? null,
    read: () => file.bytes(),
  };
}
