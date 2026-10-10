import { DurableObject } from "cloudflare:workers";
import type { Env } from "./index";
import { authenticate, decodePacket, MAX_PACKET_BYTES, numberLimit, packetInfo, parseFrame, PEER_ID, ProtocolError, sha256, toHex, VERSION, type Identity } from "./protocol";

interface Attachment {
  nonce: string;
  challengeKey: string;
  challengePrivate: string;
  openedAt: number;
  scope: string;
  identity?: Identity;
  announcement?: string;
  windowAt: number;
  used: number;
  authenticating?: boolean;
  failed?: boolean;
}

abstract class RelayObject extends DurableObject<Env> {
  protected abstract get kind(): "room" | "mail";
  private pendingUpgrades = 0;

  private roomConfig(): { owner: string; commitment: string | null } | undefined {
    if (this.kind !== "room") return;
    this.ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS room_config (singleton INTEGER PRIMARY KEY CHECK(singleton=1), owner TEXT NOT NULL, commitment TEXT)");
    return this.ctx.storage.sql.exec<{ owner: string; commitment: string | null }>("SELECT owner,commitment FROM room_config WHERE singleton=1").toArray()[0];
  }

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request: Request): Promise<Response> {
    if (new URL(request.url).pathname === "/internal/deliver") return this.deliver(request);
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") return new Response("WebSocket required", { status: 426 });
    const max = numberLimit(this.env.MAX_CONNECTIONS, 256, 16384);
    let active = 0;
    for (const socket of this.ctx.getWebSockets()) {
      const state = socket.deserializeAttachment() as Attachment | null;
      if (state?.failed || (!state?.identity && Date.now() - (state?.openedAt ?? 0) > 30000)) {
        try { socket.close(1008, "authentication_expired"); } catch { /* closed */ }
      } else active++;
    }
    if (active + this.pendingUpgrades >= max) return new Response("Busy", { status: 429 });
    this.pendingUpgrades++;
    try {
      const scope = new URL(request.url).pathname.split("/").at(-1)!;
      const pair = new WebSocketPair();
      const client = pair[0];
      const server = pair[1];
      const now = Date.now();
      const keys = await crypto.subtle.generateKey("X25519", true, ["deriveBits"]) as CryptoKeyPair;
      const challengeKey = toHex(await crypto.subtle.exportKey("raw", keys.publicKey) as ArrayBuffer);
      const challengePrivate = toHex(await crypto.subtle.exportKey("pkcs8", keys.privateKey) as ArrayBuffer);
      const state: Attachment = { nonce: crypto.randomUUID(), challengeKey, challengePrivate, openedAt: now, scope, windowAt: now, used: 0 };
      this.ctx.acceptWebSocket(server);
      server.serializeAttachment(state);
      const config = this.roomConfig();
      server.send(JSON.stringify({ type: "challenge", protocol: VERSION, nonce: state.nonce, key: challengeKey, kind: this.kind, scope, owner: config?.owner, commitment: config?.commitment ?? undefined }));
      return new Response(null, { status: 101, webSocket: client });
    } finally { this.pendingUpgrades--; }
  }

  async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): Promise<void> {
    try {
      if (typeof raw !== "string") throw new ProtocolError("text_frame_required");
      const max = numberLimit(this.env.MAX_FRAME_BYTES, 98304, 1048576);
      const frame = parseFrame(raw, max);
      const state = ws.deserializeAttachment() as Attachment;
      if (!state) throw new ProtocolError("missing_session");
      if (state.failed) return;
      const now = Date.now();
      if (now - state.windowAt >= 60000) { state.windowAt = now; state.used = 0; }
      if (++state.used > numberLimit(this.env.PACKETS_PER_MINUTE, 2400, 60000)) throw new ProtocolError("rate_limit");
      ws.serializeAttachment(state);

      if (!state.identity) {
        if (frame.type !== "auth" || state.authenticating || now - state.openedAt > 30000) throw new ProtocolError("authentication_required");
        state.authenticating = true;
        ws.serializeAttachment(state);
        const identity = await authenticate(frame, this.kind, state.scope, state.nonce, state.challengeKey, state.challengePrivate);
        if ((ws.deserializeAttachment() as Attachment | null)?.failed || Date.now() - state.openedAt > 30000) throw new ProtocolError("authentication_expired");
        if (this.kind === "mail" && identity.peer !== state.scope) throw new ProtocolError("mailbox_owner");
        state.identity = identity;
        state.authenticating = false;
        if (this.kind === "room") {
          this.roomConfig();
          this.ctx.storage.sql.exec("INSERT OR IGNORE INTO room_config (singleton,owner) VALUES (1,?)", state.identity.noise);
        }
        state.nonce = "";
        state.challengePrivate = "";
        ws.serializeAttachment(state);
        const config = this.roomConfig();
        ws.send(JSON.stringify({ type: "ready", protocol: VERSION, owner: config?.owner, commitment: config?.commitment ?? undefined }));
        for (const other of this.ctx.getWebSockets()) {
          const peer = other.deserializeAttachment() as Attachment | null;
          if (other !== ws && peer?.identity && peer.announcement) {
            ws.send(JSON.stringify({ type: "packet", peer: peer.identity.peer, packet: peer.announcement }));
          }
        }
        if (this.kind === "mail") await this.drain(ws, state.identity.peer);
        return;
      }

      if (frame.type === "received" && this.kind === "mail") {
        if (typeof frame.id !== "string" || !/^[a-f0-9]{64}$/.test(frame.id)) throw new ProtocolError("invalid_ack");
        this.initQueue();
        this.ctx.storage.sql.exec("DELETE FROM pending WHERE id = ?", frame.id);
        return;
      }
      if (frame.type === "protect" && this.kind === "room") {
        const config = this.roomConfig();
        if (config?.owner !== state.identity.noise) throw new ProtocolError("room_owner");
        if (typeof frame.commitment !== "string" || !/^[a-f0-9]{64}$/.test(frame.commitment)) throw new ProtocolError("invalid_commitment");
        if (config.commitment === frame.commitment) {
          ws.send(JSON.stringify({ type: "protected", commitment: frame.commitment }));
          return;
        }
        this.ctx.storage.sql.exec("UPDATE room_config SET commitment=? WHERE singleton=1", frame.commitment);
        ws.send(JSON.stringify({ type: "protected", commitment: frame.commitment }));
        for (const other of this.ctx.getWebSockets()) {
          if (other !== ws) { this.sendSafe(other, JSON.stringify({ type: "error", code: "password_changed" })); other.close(1000, "password_changed"); }
        }
        return;
      }
      if (frame.type !== "packet") throw new ProtocolError("unknown_type");
      const packet = decodePacket(frame.packet, MAX_PACKET_BYTES);
      const info = packetInfo(packet);
      const historical = frame.historical === true;
      if (historical && (this.kind !== "room" || ![1, 2].includes(info.type))) throw new ProtocolError("invalid_history");
      if (!historical && info.sender !== state.identity.peer) throw new ProtocolError("packet_sender");
      const id = await sha256(packet);
      if (frame.id !== id) throw new ProtocolError("packet_hash");
      // Android verifies the original packet signature and encrypted payload.
      const forwarded = JSON.stringify({ type: "packet", id, peer: info.sender, packet: frame.packet });
      if (info.type === 1 && !historical) {
        if (packet.length > 8192) throw new ProtocolError("announcement_size");
        state.announcement = frame.packet as string;
        ws.serializeAttachment(state);
      }

      if (this.kind === "room") {
        if (info.recipient && info.recipient !== "ffffffffffffffff") throw new ProtocolError("private_room_packet");
        if (historical) {
          if (typeof frame.target !== "string" || !PEER_ID.test(frame.target)) throw new ProtocolError("invalid_target");
          for (const other of this.ctx.getWebSockets()) {
            if ((other.deserializeAttachment() as Attachment | null)?.identity?.peer === frame.target) this.sendSafe(other, forwarded);
          }
          ws.send(JSON.stringify({ type: "accepted", id }));
          return;
        }
        if (frame.target !== undefined) throw new ProtocolError("room_target");
        for (const other of this.ctx.getWebSockets()) {
          if (other !== ws && (other.deserializeAttachment() as Attachment | null)?.identity) this.sendSafe(other, forwarded);
        }
      } else {
        if (typeof frame.target !== "string" || !PEER_ID.test(frame.target) || frame.target === state.identity.peer) {
          throw new ProtocolError("invalid_target");
        }
        if (info.type !== 1 && info.recipient !== frame.target) throw new ProtocolError("packet_recipient");
        const response = await this.env.MAILBOXES.get(this.env.MAILBOXES.idFromName(frame.target)).fetch(
          new Request("https://internal/internal/deliver", { method: "POST", body: JSON.stringify({ id, frame: forwarded }) })
        );
        if (!response.ok) { ws.send(JSON.stringify({ type: "error", code: "recipient_busy", id })); return; }
      }
      ws.send(JSON.stringify({ type: "accepted", id }));
    } catch (error) {
      const code = error instanceof ProtocolError ? error.message : "internal_error";
      const state = ws.deserializeAttachment() as Attachment | null;
      if (state) { state.failed = true; state.challengePrivate = ""; state.nonce = ""; ws.serializeAttachment(state); }
      try { ws.send(JSON.stringify({ type: "error", code })); ws.close(1008, code); } catch { /* already closed */ }
    }
  }

  private sendSafe(ws: WebSocket, frame: string): boolean {
    try { ws.send(frame); return true; } catch { return false; }
  }

  private initQueue(): void {
    this.ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS pending (id TEXT PRIMARY KEY, frame TEXT NOT NULL, expires INTEGER NOT NULL)");
  }

  private async deliver(request: Request): Promise<Response> {
    if (this.kind !== "mail" || request.method !== "POST") return new Response(null, { status: 404 });
    const raw = await request.text();
    const item = parseFrame(raw, numberLimit(this.env.MAX_FRAME_BYTES, 98304, 1048576) + 1024);
    if (typeof item.id !== "string" || !/^[a-f0-9]{64}$/.test(item.id) || typeof item.frame !== "string") return new Response(null, { status: 400 });
    let sent = false;
    for (const ws of this.ctx.getWebSockets()) {
      if ((ws.deserializeAttachment() as Attachment | null)?.identity) sent = this.sendSafe(ws, item.frame) || sent;
    }
    if (!sent) {
      const frame = parseFrame(item.frame, numberLimit(this.env.MAX_FRAME_BYTES, 98304, 1048576));
      const bytes = decodePacket(frame.packet, MAX_PACKET_BYTES);
      // Only Noise ciphertext is queued. Announcements, handshake packets and live
      // audio are ephemeral: established-session delivery stays on the sender outbox.
      if (packetInfo(bytes).type !== 0x11) return new Response(null, { status: 202 });
      this.initQueue();
      const now = Date.now();
      this.ctx.storage.sql.exec("DELETE FROM pending WHERE expires <= ?", now);
      if (this.ctx.storage.sql.exec("SELECT id FROM pending WHERE id=?", item.id).toArray().length) return new Response(null, { status: 202 });
      const count = this.ctx.storage.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM pending").one().n;
      if (count >= numberLimit(this.env.MAILBOX_MAX_PACKETS, 100, 10000)) return new Response(null, { status: 429 });
      const expires = now + numberLimit(this.env.MAILBOX_TTL_SECONDS, 300, 86400) * 1000;
      this.ctx.storage.sql.exec("INSERT OR IGNORE INTO pending (id,frame,expires) VALUES (?,?,?)", item.id, item.frame, expires);
      if (await this.ctx.storage.getAlarm() === null) await this.ctx.storage.setAlarm(expires);
    }
    return new Response(null, { status: 202 });
  }

  private async drain(ws: WebSocket, _peer: string): Promise<void> {
    this.initQueue();
    this.ctx.storage.sql.exec("DELETE FROM pending WHERE expires <= ?", Date.now());
    for (const row of this.ctx.storage.sql.exec<{ frame: string }>("SELECT frame FROM pending ORDER BY expires")) this.sendSafe(ws, row.frame);
    // Keep until a recipient acknowledgement, rather than treating a socket write
    // as delivered. The bounded TTL is the recovery fallback.
  }

  async alarm(): Promise<void> {
    this.initQueue();
    this.ctx.storage.sql.exec("DELETE FROM pending WHERE expires <= ?", Date.now());
    const row = this.ctx.storage.sql.exec<{ expires: number | null }>("SELECT MIN(expires) AS expires FROM pending").one();
    if (row.expires !== null) await this.ctx.storage.setAlarm(row.expires);
  }

  webSocketClose(ws: WebSocket, code: number): void {
    const state = ws.deserializeAttachment() as Attachment | null;
    if (state?.identity && this.kind === "room") {
      const peer = state.identity.peer;
      const others = this.ctx.getWebSockets().filter((other) => other !== ws);
      if (!others.some((other) => (other.deserializeAttachment() as Attachment | null)?.identity?.peer === peer)) {
        for (const other of others) {
          if ((other.deserializeAttachment() as Attachment | null)?.identity) this.sendSafe(other, JSON.stringify({ type: "peer_left", peer }));
        }
      }
    }
    try { ws.close(code); } catch { /* already closed */ }
  }
  webSocketError(ws: WebSocket): void { try { ws.close(1011, "connection_error"); } catch { /* already closed */ } }
}

export class RoomObject extends RelayObject { protected get kind(): "room" { return "room"; } }
export class MailboxObject extends RelayObject { protected get kind(): "mail" { return "mail"; } }
