# Upstream provenance

- Project: [permissionlesstech/bitchat-android](https://github.com/permissionlesstech/bitchat-android)
- Release tag: v2.0.1
- Tag object: `1620810ee9288cb8bed7726f730134d16573009b`
- Source commit: `93e9594bad3e537b4ec6fd096c0fde7533f22e74`
- Upstream tree: `14d1c1dc4d12a725d09f9427302be62c3052ad65`
- Baseline obtained from GitHub's official tag source archive.

The full source baseline is retained under `android/`, including upstream attribution and `android/LICENSE.md`. The actual upstream license file contains GPL version 3; the public-domain wording in the upstream README does not replace that file. This fork is distributed under GPL-3.0, with its corresponding source available in this repository.

Fork changes add a Cloudflare transport and named-topic envelope, replace geographic channel entry points, repair incomplete channel password handling, and use an independent application ID and update source. Upstream UI, local transports, Noise sessions, media, identity, verification, favorites, panic handling and other source components are retained.

Compatibility with official online channels or official application updates is not promised. Shared-password topic encryption is a fork-specific protocol. The application display name remains bitchat at the repository owner's request; About and documentation identify this distribution as a fork.
