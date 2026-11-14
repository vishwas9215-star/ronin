# Ronin signing keys (encrypted)

`ronin-keys.tar.enc` holds Ronin's two signing keys, encrypted with AES-256
(openssl, PBKDF2 600,000 rounds) and a 40-character random password:

- `ronin-release-private.key` - signs Windows update files (manifest.json.sig)
- `ronin-android.p12` - signs Ronin.apk (Android only installs updates signed with the same key)

The password is only in the GitHub secret `RONIN_KEYS_PASS` and in the owner's
own backup. Without it this file is useless. Never commit the plain keys.
