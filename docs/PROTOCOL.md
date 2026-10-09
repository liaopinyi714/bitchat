# Relay protocol v1

## Transport and identity

- Endpoint: `wss://chat.123456714.xyz/v1/ws/room/<64 hex room ID>` or `.../mail/<16 hex mesh peer ID>`.
- Room ID: SHA-256 of UTF-8 `bitchat/room/v1\n<normalized channel>`. Normalize with Unicode NFC, trim, prepend # when absent; preserve case; forbid spaces and control characters; maximum 255 UTF-8 bytes.
- Mesh peer ID: first 16 lowercase hex digits of SHA-256 of the static Noise X25519 public key.
- Frames are JSON text, bounded by UTF-8 byte count. The receiver verifies packet hashes, original packet signatures, Noise identity/session state and topic envelopes.

On connection, the server sends `challenge` with protocol, kind, scope, random nonce and ephemeral X25519 public key. Room challenges additionally include the creator public key and optional password-key commitment. The client's authenticated identity contains peer ID, Noise key, signing key, Ed25519 signature and a Noise-key possession proof.

Canonical authentication bytes are the following eight strings joined with LF, with no final LF:

```text
bitchat-relay-v1
kind
scope
nonce
peer ID
Noise public key hex
Ed25519 public key hex
server ephemeral X25519 public key hex
```

The proof is HMAC-SHA256 of these bytes, keyed by X25519(client static private key, server ephemeral public key). The server verifies both this proof and the Ed25519 signature. Checking only the signing key would allow someone to substitute their own signing key alongside another person's public Noise identity. Nonces expire after 30 seconds and cannot be reused for another scope. The client private key is never transmitted.

After authentication the server sends `ready`. Socket attachments retain identity and rate counters across hibernation; the server ephemeral private key and nonce are discarded.

## Packets and routing

`packet` frames contain `id` (SHA-256 of raw unpadded binary bytes), `packet` (base64), and optional mailbox `target`. Packets retain the upstream v1/v2 binary format. Public packets are signed. Upstream fragment wrappers are unsigned; their reassembled inner packet must pass the normal signature check.

Normal room traffic must originate from the authenticated sender, and cannot have a private recipient or target. Normal mailbox traffic must originate from its owner and match its target recipient; directed identity announcements are the exception to the original announcement's recipient field.

For scoped recent-history synchronization, a room frame may contain `historical:true` and a `target` peer currently in that same room. Only signed ANNOUNCE and broadcast MESSAGE packets are allowed. They preserve the original sender identity and signature, so the receiver must verify them independently; socket authentication does not authenticate forwarded historical content. These frames are never sent through mailboxes or broadcast to other rooms.

Each client's per-room GossipSyncManager stores at most 100 recent text packets and identity announcements. REQUEST_SYNC payloads are wrapped in the same topic envelope, including encryption for protected rooms. Re-entry requests synchronization from active clients. There is no permanent server channel archive or all-room filter.

`peer_left` removes room presence. `accepted` means the server processed a transport frame, not that the peer read or persisted a message. Mailbox `received` is a transport acknowledgement that deletes a pending copy; original encrypted delivery/read receipts remain authoritative. Incoming packets may still fail application validation after transport receipt, so recovery also depends on the original sender outbox.

## Topic envelope

```text
8 bytes ASCII BCTOPIC1
2 bytes big-endian channel UTF-8 byte length
channel UTF-8 bytes
1 byte mode
mode 0: original text / file / voice payload bytes
mode 1:
  32 bytes SHA-256 AES key commitment
  12 bytes random GCM nonce
  AES-256-GCM ciphertext, including 16-byte authentication tag
```

AES key: PBKDF2-HMAC-SHA256(password, UTF-8 `bitchat/topic-key/v1\n<channel>`, 600000 iterations, 32 bytes). Key derivation runs off the UI thread. GCM AAD is the complete header through the key commitment, binding the name and encryption mode.

Known protected channels reject mode 0, missing keys, incorrect commitments, altered names and invalid tags. Keys are process-memory only. The server records the creator's Noise key and commitment, never the password or AES key. The authenticated creator may set a commitment with `protect`; a changed commitment closes other connections so they rejoin. Repeating the same commitment does not disconnect members.

Shared passwords permit offline guessing against the public commitment. Use strong passwords. This scheme is content encryption, not MLS, member revocation or group forward secrecy. Room IDs and metadata do not hide channel existence.

The Android receiver checks that a room ingress ID matches the wrapped topic even after fragment reassembly. Public PTT is played only for the selected topic; private PTT remains in its private conversation. Recording and media send targets are captured explicitly to prevent a UI navigation change from changing the destination.

## Golden vectors

Synthetic channel `#test`:

- Room ID: `3acb1ac4cc9a14d1e7aa4838ba4e6a042ee3c068b65b6dcfe21fb023e0b0de49`
- Password `synthetic-password` → AES key: `5b801ece9eac7446d1cf0c8e2cfd20b9fea250479208f2fa3f1daf964008c0b1`
- Key commitment: `577b6d3ebf1ddce17d59f08e51b41f7982c37a0d6dd8b251516fd44c7cbf25d6`
- Public `hello` envelope: `4243544f50494331000523746573740068656c6c6f`

Relay identity proof uses RFC 7748's Alice private key and Bob public key. For ASCII challenge `synthetic-relay-challenge`, expected HMAC is `1e89938adc960c8d3318394ce337ba3c9373fadcfa6d9aa46cc238c3b02471ba`. These vectors use synthetic test material only.

Any incompatible change requires a new protocol version, updated vectors and cross-version fail-closed behavior.
