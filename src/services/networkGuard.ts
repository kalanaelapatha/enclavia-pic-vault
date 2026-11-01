/**
 * Defence in depth: release builds already have no INTERNET permission (enforced by Android).
 * This also removes the JavaScript networking APIs so no code path can even attempt a request.
 */
export function installNetworkGuard(): void {
  const blocked = () => {
    throw new Error('Network access is disabled in Enclavia');
  };
  const g = globalThis as Record<string, unknown>;
  g.fetch = () => Promise.reject(new Error('Network access is disabled in Enclavia'));
  g.XMLHttpRequest = function XMLHttpRequest() {
    blocked();
  };
  g.WebSocket = function WebSocket() {
    blocked();
  };
  g.EventSource = undefined;
}
