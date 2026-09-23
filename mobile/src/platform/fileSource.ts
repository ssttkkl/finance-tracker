import * as DocumentPicker from "expo-document-picker";
import { File } from "expo-file-system";
import { Platform } from "react-native";
import type { FileSource } from "@finance-tracker/contracts";

const statementTypes = [
  "text/csv",
  "text/comma-separated-values",
  "application/pdf",
  "application/vnd.ms-excel",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
];

// Android DownloadsProvider may expose CSV files with a vendor-specific MIME
// type that DocumentsUI does not match against EXTRA_MIME_TYPES.
const documentPickerType = Platform.OS === "android" ? "*/*" : statementTypes;

export async function pickStatementFiles(): Promise<FileSource[]> {
  const result = await DocumentPicker.getDocumentAsync({
    copyToCacheDirectory: true,
    multiple: true,
    type: documentPickerType,
  });
  if (result.canceled || !result.assets) return [];
  return result.assets
    .filter((asset) => Boolean(asset.uri))
    .map((asset) => {
      const file = new File(asset.uri);
      return {
        name: asset.name || "statement",
        mediaType: asset.mimeType ?? null,
        size: asset.size ?? null,
        read: () => file.bytes(),
      };
    });
}

export async function pickStatementFile(): Promise<FileSource | null> {
  return (await pickStatementFiles())[0] ?? null;
}
