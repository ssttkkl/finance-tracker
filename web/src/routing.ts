export type WorkspaceRoute = { workspaceId: string; path: string };

export function parseWorkspacePath(pathname: string): WorkspaceRoute | null {
  if (!pathname.startsWith("/w/")) return null;
  const remainder = pathname.slice(3);
  const separator = remainder.indexOf("/");
  const encodedId = separator === -1 ? remainder : remainder.slice(0, separator);
  if (!encodedId) return null;
  try {
    const workspaceId = decodeURIComponent(encodedId);
    if (!workspaceId) return null;
    return { workspaceId, path: separator === -1 ? "/" : remainder.slice(separator) || "/" };
  } catch {
    return null;
  }
}

export function workspacePath(workspaceId: string, path = "/"): string {
  const childPath = path.startsWith("/") ? path : `/${path}`;
  return `/w/${encodeURIComponent(workspaceId)}${childPath || "/"}`;
}

export function workspaceChildPath(pathname: string): string {
  return parseWorkspacePath(pathname)?.path ?? pathname;
}

export function workspaceUrl(workspaceId: string, pathname: string): string {
  return workspacePath(workspaceId, workspaceChildPath(pathname) || "/");
}
