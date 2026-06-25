# Release signing

Private signing material must never be committed to this repository.

The current 1.16 release certificate is:

- Subject: `CN=Tromp 1.16, OU=Comtek Global, O=Comtek Global, C=US`
- SHA-256: `1434f24c6eacca1f4a07832d9f3f80d42042d4fd2c5abd5ff6981878b41f9617`
- Key: RSA 4096

Local builds read `TROMP_KEYSTORE_PATH`, `TROMP_KEYSTORE_PASSWORD`,
`TROMP_KEY_ALIAS`, and `TROMP_KEY_PASSWORD` from private Gradle properties or
the environment.

Tagged GitHub releases additionally require:

- `TROMP_KEYSTORE_B64`
- `TROMP_KEYSTORE_PASSWORD`
- `TROMP_KEY_ALIAS`
- `TROMP_KEY_PASSWORD`

The keys committed before 1.16.1 are public and must not be used for trusted
releases.

Keep an encrypted offline backup of the current private keystore. Losing it
breaks Android update continuity just as surely as rotating it.
