import { env } from "cloudflare:workers";
import { evictDurableObject, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import worker, { type Env } from "../src/index";
import { authenticate, authenticationBytes, decodePacket, fromHex, packetInfo, parseFrame, sha256, toHex } from "../src/protocol";

const bindings = env as Env;
const room = "a".repeat(64);

class Inbox {
  private queue: Record<string, unknown>[] = [];
  private waiting: ((value: Record<string, unknown>) => void)[] = [];
  constructor(readonly socket: WebSocket) {
    socket.addEventListener("message", (event) => {
      if (typeof event.data !== "string" || event.data === "pong") return;
      const value = JSON.parse(event.data) as Record<string, unknown>;
      const resolve = this.waiting.shift();
      if (resolve) resolve(value); else this.queue.push(value);
    });
  }
  next(): Promise<Record<string, unknown>> {
    const value = this.queue.shift();
    return value ? Promise.resolve(value) : new Promise((resolve) => this.waiting.push(resolve));
  }
  close(): void { this.socket.close(1000); }
}

async function identity() {
  const signing = await crypto.subtle.generateKey("Ed25519", true, ["sign", "verify"]) as CryptoKeyPair;
  const noise = await crypto.subtle.generateKey("X25519", true, ["deriveBits"]) as CryptoKeyPair;
  const signingHex = toHex(await crypto.subtle.exportKey("raw", signing.publicKey) as ArrayBuffer);
  const noiseHex = toHex(await crypto.subtle.exportKey("raw", noise.publicKey) as ArrayBuffer);
  return { signing, noise, signingHex, noiseHex, peer: (await sha256(fromHex(noiseHex))).slice(0, 16) };
}

type TestIdentity = Awaited<ReturnType<typeof identity>>;

async function authentication(id: TestIdentity, challenge: Record<string, unknown>) {
  const bytes = authenticationBytes(String(challenge.kind), String(challenge.scope), String(challenge.nonce), id.peer, id.noiseHex, id.signingHex, String(challenge.key));
  const serverKey = await crypto.subtle.importKey("raw", fromHex(String(challenge.key)), "X25519", false, []);
  const algorithm = { name: "X25519", public: serverKey };
  const secret = await crypto.subtle.deriveBits(algorithm, id.noise.privateKey, 256);
  const mac = await crypto.subtle.importKey("raw", secret, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return {
    type: "auth", peer: id.peer, noise: id.noiseHex, signing: id.signingHex,
    signature: toHex(await crypto.subtle.sign("Ed25519", id.signing.privateKey, bytes)),
    proof: toHex(await crypto.subtle.sign("HMAC", mac, bytes))
  };
}

async function connect(kind: "room" | "mail", scope: string, id?: TestIdentity) {
  const response = await worker.fetch(new Request(`https://relay.test/v1/ws/${kind}/${scope}`, { headers: { Upgrade: "websocket" } }), bindings);
  expect(response.status).toBe(101);
  const inbox = new Inbox(response.webSocket!);
  inbox.socket.accept();
  const challenge = await inbox.next();
  expect(challenge.type).toBe("challenge");
  if (id) {
    inbox.socket.send(JSON.stringify(await authentication(id, challenge)));
    expect((await inbox.next()).type).toBe("ready");
  }
  return { inbox, challenge };
}

function packet(peer: string, recipient = "ffffffffffffffff", type = 2): Uint8Array {
  const value = new Uint8Array(14 + 8 + 8 + 5 + 64);
  value[0] = 1; value[1] = type; value[2] = 7; value[11] = 3;
  new DataView(value.buffer).setUint16(12, 5);
  value.set(fromHex(peer), 14); value.set(fromHex(recipient), 22);
  value.set(new TextEncoder().encode("hello"), 30);
  return value;
}

async function send(inbox: Inbox, bytes: Uint8Array, target?: string) {
  const frame = { type: "packet", id: await sha256(bytes), packet: btoa(String.fromCharCode(...bytes)), ...(target ? { target } : {}) };
  inbox.socket.send(JSON.stringify(frame));
  return frame;
}

describe("relay authentication and isolation", () => {
  it("exposes health and rejects unknown routes and ordinary HTTP upgrades", async () => {
    expect((await worker.fetch(new Request("https://relay.test/health"), bindings)).status).toBe(200);
    expect((await worker.fetch(new Request("https://relay.test/v1/ws/room/nope"), bindings)).status).toBe(404);
    expect((await worker.fetch(new Request("https://relay.test/v1/ws/room/abcd"), bindings)).status).toBe(400);
    expect((await worker.fetch(new Request(`https://relay.test/v1/ws/room/${room}`), bindings)).status).toBe(426);
    expect((await worker.fetch(new Request("https://relay.test/internal/deliver"), bindings)).status).toBe(404);
  });

  it("requires possession of the Noise key as well as a signing key", async () => {
    const id = await identity();
    const server = await crypto.subtle.generateKey("X25519", true, ["deriveBits"]) as CryptoKeyPair;
    const key = toHex(await crypto.subtle.exportKey("raw", server.publicKey) as ArrayBuffer);
    const privateKey = toHex(await crypto.subtle.exportKey("pkcs8", server.privateKey) as ArrayBuffer);
    const challenge = { kind: "room", scope: room, nonce: "synthetic-nonce", key };
    const valid = await authentication(id, challenge);
    expect((await authenticate(valid, "room", room, "synthetic-nonce", key, privateKey)).peer).toBe(id.peer);
    await expect(authenticate({ ...valid, proof: "0".repeat(64) }, "room", room, "synthetic-nonce", key, privateKey)).rejects.toThrow("noise_key_proof");
    await expect(authenticate(valid, "room", room, "different-nonce", key, privateKey)).rejects.toThrow("invalid_signature");
    await expect(authenticate(valid, "room", "b".repeat(64), "synthetic-nonce", key, privateKey)).rejects.toThrow("invalid_signature");
  });

  it("rejects data before authentication", async () => {
    const { inbox } = await connect("room", "c".repeat(64));
    try {
      inbox.socket.send(JSON.stringify({ type: "packet" }));
      expect((await inbox.next()).code).toBe("authentication_required");
    } finally { inbox.close(); }
  });

  it("forwards a room packet and restores authentication after hibernation", async () => {
    const a = await identity(); const b = await identity();
    const x = await connect("room", room, a); const y = await connect("room", room, b);
    try {
      await evictDurableObject(bindings.ROOMS.get(bindings.ROOMS.idFromName(room)));
      const frame = await send(x.inbox, packet(a.peer));
      const received = await y.inbox.next();
      expect(received.packet).toBe(frame.packet);
      expect(received.peer).toBe(a.peer);
      expect((await x.inbox.next()).type).toBe("accepted");
    } finally { x.inbox.close(); y.inbox.close(); }
  });

  it("rejects sender impersonation even on an authenticated socket", async () => {
    const a = await identity(); const b = await identity();
    const { inbox } = await connect("room", "d".repeat(64), a);
    try { await send(inbox, packet(b.peer)); expect((await inbox.next()).code).toBe("packet_sender"); }
    finally { inbox.close(); }
  });

  it("allows a mailbox connection only for its owner", async () => {
    const a = await identity(); const b = await identity();
    const { inbox, challenge } = await connect("mail", b.peer);
    try {
      inbox.socket.send(JSON.stringify(await authentication(a, challenge)));
      expect((await inbox.next()).code).toBe("mailbox_owner");
    } finally { inbox.close(); }
  });

  it("forwards directed ciphertext between mailboxes", async () => {
    const a = await identity(); const b = await identity();
    const x = await connect("mail", a.peer, a); const y = await connect("mail", b.peer, b);
    try {
      const frame = await send(x.inbox, packet(a.peer, b.peer, 0x11), b.peer);
      expect((await y.inbox.next()).id).toBe(frame.id);
      expect((await x.inbox.next()).type).toBe("accepted");
    } finally { x.inbox.close(); y.inbox.close(); }
  });

  it("keeps the authenticated channel creator through hibernation", async () => {
    const a = await identity(); const b = await identity();
    const id = "f".repeat(64);
    const x = await connect("room", id, a);
    try {
      x.inbox.socket.send(JSON.stringify({ type: "protect", commitment: "1".repeat(64) }));
      expect((await x.inbox.next()).type).toBe("protected");
      await evictDurableObject(bindings.ROOMS.get(bindings.ROOMS.idFromName(id)));
      const y = await connect("room", id, b);
      try {
        expect(y.challenge.owner).toBe(a.noiseHex);
        expect(y.challenge.commitment).toBe("1".repeat(64));
        y.inbox.socket.send(JSON.stringify({ type: "protect", commitment: "2".repeat(64) }));
        expect((await y.inbox.next()).code).toBe("room_owner");
      } finally { y.inbox.close(); }
    } finally { x.inbox.close(); }
  });

  it("requires clients to rejoin when the creator rotates protection", async () => {
    const a = await identity(); const b = await identity();
    const id = "e".repeat(64);
    const x = await connect("room", id, a); const y = await connect("room", id, b);
    try {
      x.inbox.socket.send(JSON.stringify({ type: "protect", commitment: "3".repeat(64) }));
      expect((await x.inbox.next()).type).toBe("protected");
      expect((await y.inbox.next()).code).toBe("password_changed");
    } finally { x.inbox.close(); y.inbox.close(); }
  });

  it("rejects addressed private packets on the room path", async () => {
    const a = await identity(); const b = await identity();
    const { inbox } = await connect("room", "1".repeat(64), a);
    try { await send(inbox, packet(a.peer, b.peer, 0x11)); expect((await inbox.next()).code).toBe("private_room_packet"); }
    finally { inbox.close(); }
  });

  it("rejects a mailbox target that differs from the inner recipient", async () => {
    const a = await identity(); const b = await identity(); const c = await identity();
    const { inbox } = await connect("mail", a.peer, a);
    try { await send(inbox, packet(a.peer, b.peer, 0x11), c.peer); expect((await inbox.next()).code).toBe("packet_recipient"); }
    finally { inbox.close(); }
  });

  it("holds offline ciphertext until a recipient transport acknowledgement", async () => {
    const a = await identity(); const b = await identity();
    const x = await connect("mail", a.peer, a);
    const stub = bindings.MAILBOXES.get(bindings.MAILBOXES.idFromName(b.peer));
    try {
      const frame = await send(x.inbox, packet(a.peer, b.peer, 0x11), b.peer);
      expect((await x.inbox.next()).type).toBe("accepted");
      const y = await connect("mail", b.peer, b);
      try {
        expect((await y.inbox.next()).id).toBe(frame.id);
        expect(await runInDurableObject(stub, async (_instance, state) =>
          state.storage.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM pending").one().n)).toBe(1);
        y.inbox.socket.send(JSON.stringify({ type: "received", id: frame.id }));
        await send(y.inbox, packet(b.peer, a.peer, 0x11), a.peer);
        expect((await y.inbox.next()).type).toBe("accepted");
        expect(await runInDurableObject(stub, async (_instance, state) =>
          state.storage.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM pending").one().n)).toBe(0);
      } finally { y.inbox.close(); }
    } finally { x.inbox.close(); }
  });

  it("expires offline ciphertext and stops the alarm when the queue is empty", async () => {
    const a = await identity(); const b = await identity();
    const x = await connect("mail", a.peer, a);
    const stub = bindings.MAILBOXES.get(bindings.MAILBOXES.idFromName(b.peer));
    try {
      await send(x.inbox, packet(a.peer, b.peer, 0x11), b.peer);
      expect((await x.inbox.next()).type).toBe("accepted");
      const remaining = await runInDurableObject(stub, async (instance, state) => {
        expect(await state.storage.getAlarm()).not.toBeNull();
        state.storage.sql.exec("UPDATE pending SET expires=?", Date.now() - 1);
        await state.storage.deleteAlarm();
        await (instance as unknown as { alarm(): Promise<void> }).alarm();
        return { n: state.storage.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM pending").one().n, alarm: await state.storage.getAlarm() };
      });
      expect(remaining).toEqual({ n: 0, alarm: null });
    } finally { x.inbox.close(); }
  });

  it("enforces the per-connection packet budget after restoring attachments", async () => {
    const a = await identity();
    const id = "2".repeat(64); const { inbox } = await connect("room", id, a);
    const stub = bindings.ROOMS.get(bindings.ROOMS.idFromName(id));
    try {
      await runInDurableObject(stub, async (_instance, state) => {
        for (const socket of state.getWebSockets()) {
          const attachment = socket.deserializeAttachment() as Record<string, unknown>;
          attachment.used = 2400; attachment.windowAt = Date.now(); socket.serializeAttachment(attachment);
        }
      });
      await send(inbox, packet(a.peer));
      expect((await inbox.next()).code).toBe("rate_limit");
    } finally { inbox.close(); }
  });

  it("targets original signed history only to a member of the same room", async () => {
    const a = await identity(); const b = await identity(); const original = await identity();
    const id = "4".repeat(64);
    const x = await connect("room", id, a); const y = await connect("room", id, b);
    try {
      const bytes = packet(original.peer);
      const frame = { type: "packet", id: await sha256(bytes), packet: btoa(String.fromCharCode(...bytes)), historical: true, target: b.peer };
      x.inbox.socket.send(JSON.stringify(frame));
      expect((await y.inbox.next()).peer).toBe(original.peer);
      expect((await x.inbox.next()).type).toBe("accepted");
    } finally { x.inbox.close(); y.inbox.close(); }
  });

  it("rejects private ciphertext disguised as room history", async () => {
    const a = await identity(); const b = await identity();
    const { inbox } = await connect("room", "5".repeat(64), a);
    try {
      const bytes = packet(a.peer, b.peer, 0x11);
      inbox.socket.send(JSON.stringify({ type: "packet", id: await sha256(bytes), packet: btoa(String.fromCharCode(...bytes)), historical: true, target: b.peer }));
      expect((await inbox.next()).code).toBe("invalid_history");
    } finally { inbox.close(); }
  });

  it("does not rotate or evict connections when protection is unchanged", async () => {
    const a = await identity(); const b = await identity();
    const id = "6".repeat(64); const x = await connect("room", id, a);
    try {
      x.inbox.socket.send(JSON.stringify({ type: "protect", commitment: "4".repeat(64) }));
      expect((await x.inbox.next()).type).toBe("protected");
      const y = await connect("room", id, b);
      try {
        x.inbox.socket.send(JSON.stringify({ type: "protect", commitment: "4".repeat(64) }));
        expect((await x.inbox.next()).type).toBe("protected");
        await send(y.inbox, packet(b.peer));
        expect((await y.inbox.next()).type).toBe("accepted");
        expect((await x.inbox.next()).peer).toBe(b.peer);
      } finally { y.inbox.close(); }
    } finally { x.inbox.close(); }
  });
});

describe("bounded protocol parsing", () => {
  it("rejects malformed and oversized frames without allocation of a packet", () => {
    expect(() => parseFrame("[]", 10)).toThrow();
    expect(() => parseFrame("{", 10)).toThrow();
    expect(() => parseFrame(JSON.stringify({ text: "界".repeat(100) }), 100)).toThrow("frame_size");
    expect(() => decodePacket("!bad", 100)).toThrow();
    expect(() => decodePacket("A".repeat(1000), 100)).toThrow();
  });
  it("rejects unsigned, truncated and ambiguous binary packets", () => {
    const valid = packet("0".repeat(16));
    expect(packetInfo(valid).recipient).toBe("ffffffffffffffff");
    expect(() => packetInfo(valid.slice(0, -1))).toThrow("binary_length");
    const unsigned = valid.slice(); unsigned[11] = 1;
    expect(() => packetInfo(unsigned)).toThrow("invalid_binary_packet");
  });
});
