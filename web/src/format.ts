export function formatOccurredAt(value: string): string {
  if (!value || Number.isNaN(new Date(value).getTime())) return "未提供";
  return new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "Asia/Shanghai",
  }).format(new Date(value));
}
