import { HEX_KEY, PEER_ID, VERSION } from "./protocol";
export { RoomObject, MailboxObject } from "./relay";

export interface Env {
  ROOMS: DurableObjectNamespace;
  MAILBOXES: DurableObjectNamespace;
  MAX_CONNECTIONS?: string;
  MAX_FRAME_BYTES?: string;
  PACKETS_PER_MINUTE?: string;
  MAILBOX_MAX_PACKETS?: string;
  MAILBOX_TTL_SECONDS?: string;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/health" && request.method === "GET") {
      return Response.json({ service: "bitchat-relay", protocol: VERSION });
    }
    const match = /^\/v1\/ws\/(room|mail)\/([a-f0-9]+)$/.exec(url.pathname);
    if (!match || request.method !== "GET") return new Response("Not found", { status: 404 });
    const kind = match[1]!;
    const scope = match[2]!;
    if (!(kind === "room" ? HEX_KEY : PEER_ID).test(scope)) return new Response("Invalid scope", { status: 400 });
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") return new Response("WebSocket required", { status: 426 });
    const namespace = kind === "room" ? env.ROOMS : env.MAILBOXES;
    return namespace.get(namespace.idFromName(scope)).fetch(request);
  }
} satisfies ExportedHandler<Env>;
