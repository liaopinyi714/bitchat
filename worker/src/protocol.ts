export const VERSION = 1;
const encoder = new TextEncoder();
export const HEX_KEY = /^[a-f0-9]{64}$/;
export const PEER_ID = /^[a-f0-9]{16}$/;

export class ProtocolError extends Error {}

export function fromHex(value: string): Uint8Array {
  if (!/^(?:[a-f0-9]{2})+$/.test(value)) throw new ProtocolError("invalid_hex");
  return Uint8Array.from(value.match(/../g)!, (b) => parseInt(b, 16));
}

export function toHex(bytes: ArrayBuffer | Uint8Array): string {
  return Array.from(new Uint8Array(bytes instanceof Uint8Array ? bytes : bytes), (b) => b.toString(16).padStart(2, "0")).join("");
}

export async function sha256(bytes: Uint8Array): Promise<string> {
  return toHex(await crypto.subtle.digest("SHA-256", bytes));
}

export function decodePacket(value: unknown, maxBytes: number): Uint8Array {
  if (typeof value !== "string" || value.length > Math.ceil(maxBytes / 3) * 4 ||
      value.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(value)) {
    throw new ProtocolError("invalid_packet");
  }
  let raw: string;
  try { raw = atob(value); } catch { throw new ProtocolError("invalid_packet"); }
  if (raw.length < 21 || raw.length > maxBytes) throw new ProtocolError("packet_size");
  return Uint8Array.from(raw, (b) => b.charCodeAt(0));
}

export function parseFrame(raw: string, maxBytes: number): Record<string, unknown> {
  if (encoder.encode(raw).length > maxBytes) throw new ProtocolError("frame_size");
  let value: unknown;
  try { value = JSON.parse(raw); } catch { throw new ProtocolError("invalid_json"); }
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new ProtocolError("invalid_frame");
  return value as Record<string, unknown>;
}

export function authenticationBytes(kind: string, scope: string, nonce: string, peer: string, noise: string, signing: string, challengeKey: string): Uint8Array {
  return encoder.encode(["bitchat-relay-v1", kind, scope, nonce, peer, noise, signing, challengeKey].join("\n"));
}

export interface Identity { peer: string; noise: string; signing: string }

export async function authenticate(frame: Record<string, unknown>, kind: string, scope: string, nonce: string, challengeKey: string, challengePrivate: string): Promise<Identity> {
  const { peer, noise, signing, signature, proof } = frame;
  if (typeof peer !== "string" || !PEER_ID.test(peer) || typeof noise !== "string" || !HEX_KEY.test(noise) ||
      typeof signing !== "string" || !HEX_KEY.test(signing) || typeof signature !== "string" || !/^[a-f0-9]{128}$/.test(signature) ||
      typeof proof !== "string" || !HEX_KEY.test(proof)) {
    throw new ProtocolError("invalid_identity");
  }
  if ((await sha256(fromHex(noise))).slice(0, 16) !== peer) throw new ProtocolError("identity_binding");
  const key = await crypto.subtle.importKey("raw", fromHex(signing), "Ed25519", false, ["verify"]);
  const bytes = authenticationBytes(kind, scope, nonce, peer, noise, signing, challengeKey);
  if (!await crypto.subtle.verify("Ed25519", key, fromHex(signature), bytes)) {
    throw new ProtocolError("invalid_signature");
  }
  const privateKey = await crypto.subtle.importKey("pkcs8", fromHex(challengePrivate), "X25519", false, ["deriveBits"]);
  const publicKey = await crypto.subtle.importKey("raw", fromHex(noise), "X25519", false, []);
  const algorithm = { name: "X25519", public: publicKey };
  const secret = await crypto.subtle.deriveBits(algorithm, privateKey, 256);
  const macKey = await crypto.subtle.importKey("raw", secret, { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  if (!await crypto.subtle.verify("HMAC", macKey, fromHex(proof), bytes)) throw new ProtocolError("noise_key_proof");
  return { peer, noise, signing };
}

export interface PacketInfo { sender: string; recipient?: string; type: number }

export function packetInfo(packet: Uint8Array): PacketInfo {
  const version = packet[0];
  const header = version === 1 ? 14 : version === 2 ? 16 : 0;
  const flags = packet[11]!;
  // Upstream fragment wrappers are unsigned; Android verifies the reassembled packet.
  const fragmented = packet[1] === 0x20;
  if (!header || packet.length < header + 8 || (flags & 0xf0) || (!(flags & 2) && !fragmented)) throw new ProtocolError("invalid_binary_packet");
  const view = new DataView(packet.buffer, packet.byteOffset, packet.byteLength);
  const payloadLength = version === 1 ? view.getUint16(12) : view.getUint32(12);
  let offset = header + 8;
  const sender = toHex(packet.slice(header, offset));
  let recipient: string | undefined;
  if (flags & 1) { recipient = toHex(packet.slice(offset, offset + 8)); offset += 8; }
  if (flags & 8) {
    if (version !== 2 || packet.length <= offset) throw new ProtocolError("invalid_route");
    offset += 1 + packet[offset]! * 8;
  }
  if (packet.length !== offset + payloadLength + ((flags & 2) ? 64 : 0)) throw new ProtocolError("binary_length");
  return { sender, recipient, type: packet[1]! };
}

export function numberLimit(value: string | undefined, fallback: number, ceiling: number): number {
  if (!value || !/^\d+$/.test(value)) return fallback;
  return Math.max(1, Math.min(ceiling, Number(value)));
}
