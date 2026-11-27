# Ronin Agent: Claude works inside the owner's own Ronin (laptop + tablet + phone)

Design v6.1, 27 Nov 2026. Protocol simulation: **79 / 79 checks passed, 3 runs in a row** (section 12; full list in `simulation-result-full.txt`).
Ratings: v1 7.4, v2 8.4 (Claude Sonnet). 5-model panel — v3: Qwen 8.9, Opus 8.6, DeepSeek 8.6, Gemini 7.5, GPT 5.5. **v4: Qwen 9.3, Gemini 9.2, Opus 9.2, DeepSeek 9.0, GPT 8.6 — all five approve building the pilot.** Their stated ceiling for any paper design of this kind: 9.2–9.5; 9.5+ needs real-device data. **v5: Gemini 9.8, Qwen 9.7, DeepSeek 9.5, Opus 9.5, GPT 9.0 (average 9.5); all five approve the pilot with dummy data.** **v6: Qwen 9.9, Gemini 9.9, DeepSeek 9.8, Opus 9.8, GPT 9.0 — average 9.68, four of five above 9.5; all five approve the pilot (dummy data, read-only trading).** v6.1 folds in their last notes (section 11d) without changing the architecture.

## 1. Problem
- Claude can drive only the Claude desktop app's browser or a server-side Chromium. Neither has the owner's logins, home internet or real device.
- Human checks (Cloudflare etc.) fail there even when the owner solves them (automation-attached browser, fresh profile, datacenter IP).
- The trading-terminal login refuses to open; election (EPDS) data entry stops halfway; the server browser switches off when the trading engine needs RAM.

## 2. What it is — and what it deliberately is NOT
- **Is:** Claude works in a "🤖 Claude" tab of the owner's own Ronin, on his own devices, with his profile and internet, while he watches and can take over.
- **Is not:** a general robot for money sites. On broker / bank / payment sites Claude can **only read** (no taps, no typing, no keys, only the pages he listed). Passwords, OTPs, human checks and anything that moves money are **owner-only**, always.
- **Every other site starts read-only too.** Claude may follow plain links there (not links with a query string or action words like unsubscribe / delete / confirm, not `javascript:` links, never home-network or server addresses); any button, form or typing needs the owner to add that exact site to Claude's write list (one press-and-hold, 45 minutes, ends when the tab leaves the site). His real write sites (EPDS and similar) are listed once.

```
Claude (web / desktop / phone app, the owner's paid Claude plan)
   │ ONE custom connector  https://<host>/mcp   (MCP Streamable HTTP, OAuth 2.1 + PKCE)
   ▼
Ronin Agent Relay  (small Node service on the owner's server, no browser, stores no page content)
   │ one OUTGOING WebSocket per device, TLS + pinned relay key + per-frame HMAC
   ├──► Ronin on the laptop  (WebView2)
   ├──► Ronin on the tablet  (Android WebView)
   └──► Ronin on the phone   (Android WebView, same APK)
```

## 3. Threat model (honest)
| Attacker | What they can do | What they cannot do |
|---|---|---|
| Hostile web page / prompt injection | Show lying text, hidden layers, timers, fake buttons | Make Claude type a password/OTP, tick a human check, touch a money site, POST to an order/payment path, carry data to another site without the owner's tap |
| Network attacker (Wi-Fi, DNS, fake certificate) | Block the connection | Pretend to be the relay (pinned key), inject or replay a frame (HMAC + sequence) |
| Someone who steals a Claude token | Use Claude tools for ≤1 h | Anything outside the on-device rules; survive "Disconnect Claude" |
| **A compromised relay server** | Read what passes through it; send commands **only under the owner's still-valid signed grant** | Mint a grant; keep using one after "Disconnect Claude" (devices move to a new signed epoch, F7b); break any on-device rule (money read-only, unknown sites read-only, owner-only steps, taint, budget, visible banner) |
| Anyone hiding a "Disconnect" from a device (relay withholds it, device offline) | Keep that device usable by Claude for a while | Beyond **12 hours**: every grant expires on the device itself; a clock moved backwards stops Claude (F16) |
| Thief with the unlocked phone | Use Ronin like the owner | Export the device's signing key; sign a new grant without the screen-lock credential (TEE-only devices) or user presence (StrongBox devices) |

The relay is **trusted for delivery** and kept small and hardened; the device rules are what limit damage if it is not.

## 4. Sign-in (how Claude connects)
1. Owner adds the connector once in Claude → `https://<host>/mcp`. The relay's public key is **baked into the signed APK / exe at build time** (no trust-on-first-use). **Pilot step 0 verifies this works on his plan on web, desktop and phone before anything else is built.**
2. Spec-compliant discovery: `/.well-known/oauth-protected-resource` (RFC 9728), `/.well-known/oauth-authorization-server` (RFC 8414); a 401 carries `WWW-Authenticate: Bearer resource_metadata=…`.
3. Dynamic client registration; redirect URIs must match **exactly** `https://claude.ai/api/mcp/auth_callback` or `https://claude.com/api/mcp/auth_callback`.
4. `/authorize` (PKCE S256, `resource` must be this `/mcp`). The device shows a **fixed native card**: "Connect Claude to your Ronin? Code ABCD. Devices: laptop, tablet, phone" — **press and hold** to allow (palm-friendly). The device signs the **whole transaction** (nonce, client, redirect, PKCE challenge, device scope, code) with its **non-exportable ECDSA P-256 key** (Android Keystore: StrongBox with `setUserPresenceRequired` where available; TEE-only devices use `setUserAuthenticationRequired` with a short timeout and the screen lock; Windows: TPM via CNG, checked at setup). Press-and-hold is consent UI, not hardware proof — the key policy is the proof. Each device's actual protection (StrongBox + presence / TEE + screen lock / TPM) is probed at setup and shown to the owner; never silently downgraded. (API 30+: `setUserAuthenticationParameters(timeout, AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL)`.) The relay issues a code only if the signature verifies (A10). If the tablet is off, the phone, then the laptop, is asked.
5. That signed Allow is the **grant** (client, devices, code, epoch, issue time; valid 12 h on the device). It rides with every command and **each device checks it itself** — right signer, this device in scope, epoch not revoked. Relay-made tokens alone never run anything.
6. Tokens: access 1 h, audience = this `/mcp`; refresh 30 days, rotated on every use, tied to the client, with a 120 s grace so a retried request on bad mobile data does not log him out (A6) — but only while the new pair is still unused; an old refresh token used after the new pair was used is theft, and the whole token family is revoked (A6b).
7. Limits: per client on `/token`, generous per-IP on `/mcp` (Claude traffic comes from Anthropic's shared addresses), `/register` 5/min/IP, `/authorize` 10/min/IP.
8. "Disconnect Claude" (any device) kills every token **and** moves every device to a new epoch signed by the pressing device, so old grants die even if the relay misbehaves (F7, F7b). "Remove device" kills that device's key, link and the tokens it approved.

## 5. Device link (relay ↔ device)
- The device opens the WebSocket with its device key, a nonce and an ephemeral X25519 key. The relay answers with its own X25519 key and an ECDSA signature over both + nonce, made with the **relay key pinned in the app at pairing**. Wrong signature → "fake relay", nothing runs (A12).
- Both sides derive a session key; **every frame carries a sequence number and HMAC**. Injected or replayed frames are dropped (A13).
- Heartbeat 20 s; commands are re-sent after reconnect with the same id.

## 6. Tools (16), each takes `device`
`devices` · `open(url)` · `snapshot` · `get_text(ref)` · `click(ref)` · `type(ref, text, submit)` · `select(ref, option)` · `upload(ref, file)` · `press(key)` · `scroll` · `back` · `tabs` · `screenshot` · `wait_for(text)` (≤20 s) · `downloads` · `ask_owner(reason)`.
- **No tool call blocks long:** `ask_owner` returns at once ("with the owner"); later calls wait ≤15 s each until he taps Done.

## 7. On the device
**Page helper (`agent.js`) — a first filter, never the final authority**
- Windows: injected through CDP `Page.addScriptToEvaluateOnNewDocument` with a **`worldName`** (a real isolated world). (`AddScriptToExecuteOnDocumentCreated` runs in the main world, so it is not used for this.)
- Android: `WebViewCompat.addDocumentStartJavaScript` (feature-checked: `DOCUMENT_START_SCRIPT`), **main world — Android has no public isolated-world API**. Natives are captured before page scripts; results come back over `addWebMessageListener`. A fooled helper can at worst mislabel a harmless element; it can never unlock a refused action.
- Bundled inside the signed APK / exe, never downloaded at run time.
- Reads visible elements (shadow roots, same-origin frames), stable fingerprinted refs, flags (money words EN+HI, OTP shape, password, captcha), redacts ID numbers, lists cross-origin frames.

**Native checks (final authority)**
| Check | Windows (WebView2) | Android (WebView) |
|---|---|---|
| Money site = read only (no input tools) | device rule | device rule |
| Password / OTP box refused before any text is committed (re-checked right before dispatch) | CDP: resolve the focused node, `DOM.getAttributes` (type, autocomplete); unresolved → fail closed | **`EditorInfo.inputType`** from the WebView's `onCreateInputConnection` (`TYPE_TEXT_VARIATION_PASSWORD`, `TYPE_TEXT_VARIATION_WEB_PASSWORD`, `TYPE_NUMBER_VARIATION_PASSWORD`); no input connection after focus → unknown → fail closed; accessibility tree as a second check (often empty without an accessibility service). Either says secret → refuse; unknown → fail closed |
| Network guard on order / payment paths, at all times in the Claude tab (not only 2 s) | `WebResourceRequested` (+ body visible), `NavigationStarting` | `shouldInterceptRequest` (**no POST body**), `shouldOverrideUrlLoading`, `ServiceWorkerControllerCompat` hook |
| WebSocket on a write-listed unknown site | main-document response gets a CSP `connect-src` without `ws:`/`wss:` (+ watch `Network.webSocket*`) | main-document response rewritten with the same CSP; a site that needs WebSocket is not write-listed on Android |
| Service workers | covered by `WebResourceRequested` | `ServiceWorkerControllerCompat` client with the same rules |
| Cross-origin frames | read-only (write list is per exact origin) | same |
| Isolated helper | yes (`worldName`) | no (stated) |

The two platforms are **not** equal. That is why the money boundary is a **capability rule** (no input on money sites), not URL guessing. The path guard is a second fence for unknown sites; it cannot see WebSocket frames or Android POST bodies (residual risk, stated).

**Input**
- Android: `WebView.dispatchTouchEvent` on Ronin's own, laid-out WebView, on the UI thread, a matched DOWN/UP pair with consistent `downTime`/`eventTime` (a public View method — reliability is measured per device in the pilot). Text: `commitText` through the WebView's input connection; if that fails on a keyboard, `dispatchKeyEvent` per character. **After typing, the field's value is read back and reported as "verified", "already matched" or "unverified" — never a blind "done".** Secret fields are never read back or logged. The WebView version is written in every audit line; on a WebView major update the 21 hostile cases run again and a failing mechanism switches itself off. Fallback for taps: `ACTION_CLICK` on the WebView's own accessibility node.
- Windows: CDP `Input.dispatchMouseEvent`; text with `Input.insertText` (works for Hindi / Devanagari), `Input.dispatchKeyEvent` for Enter / Tab / Escape, and per character (with `text`) as a per-site fallback for rich editors that ignore insertText. Frame and focus are re-checked in the same step, right before dispatch.
- Pop-ups (`window.open`) from Claude's actions are refused in the Claude tab; every page change caused by any Claude input (click, select, press, redirect) passes the taint check.
- No remote-debugging port, no `--enable-automation`, no webdriver flag. This **is** automation inside a normal browser; it does not fake an identity. Ronin keeps its usual user agent; sites that refuse WebView (e.g. Google sign-in) are opened by the owner elsewhere.

**Take-over and approvals (designed for injured hands)**
- Banner: "Claude is working on <site> — **Pause | Take over | Stop**", thumb-size.
- Any touch by the owner **pauses Claude until he taps RESUME** (no silent auto-resume) (F1, F2).
- All approval cards are **fixed native templates** (site, action, data size); Claude's text appears only inside a separate, differently styled box headed **"Unverified AI message"**, never as the headline; the card always states the real action itself ("Claude wants to TAP 'Submit' on …"). One "Add to write list" lasts **45 minutes** for that exact site. On write sites the first 3 Saves of a batch show a native summary card (name, number of fields filled); after that one card per batch. Target: **≤5 owner taps per 20-record batch, ≤1 per trade read.**

**Life cycle**
- Android: foreground service only while Claude access is ON, `specialUse` with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property; notification with Stop; wake lock only during a command; the owner allows battery exemption and (optionally) full-screen notifications himself in Settings — Claude never changes them. OEM battery killers can still stop it: `devices` then shows it offline (measured in the pilot).
- Windows: Claude window kept non-minimized, `--disable-features=CalculateNativeWinOcclusion`, `rendering:false` reported when minimized.

## 8. Safety rules (on the device)
1. **Money sites (broker / bank / UPI / payment list + any site the owner marks): read only.** No click / type / select / upload / press at all (C5, C6). `open` only to pages he listed (e.g. `/positions`), no query strings (C7). Screenshots need his tap.
2. **Owner-only:** passwords and OTPs (input type, autocomplete, shape, label in EN/HI; and **any 4–8-digit-only text** unless the owner allowed digits in that exact field, e.g. "Pincode": C1, D5, D6, D6b, D6c). Secret entry happens only in the owner's take-over; human checks (E1), any money action.
3. **Unknown sites:** first change asks once (45 min); money words / money form actions refused (D2, D3); network guard blocks order/payment requests **at any time** — including a timer firing 3 s after Claude's tap (D19) — while ordinary POSTs still work (D18).
4. **Page text is data** (`page_text_untrusted`); injected "ignore previous instructions" does nothing (C5).
5. **Data leaving:** only http/https; >100 chars to a new site needs the owner (D8); **taint:** after reading a sensitive page (money, government, or any page where ID numbers were seen) any other registrable domain needs his tap (B9); daily outgoing-text budget; ≤2000 chars per type; uploads only from "Ronin/For Claude"; downloads need his Allow (D13). Claude's only ways to move data are `open`, `type`, `upload` — all gated. (Pages that already hold data and leak it themselves are outside Claude's control in any browser.)
6. **Privacy:** Aadhaar / PAN / account numbers hidden in snapshot and get_text (D11, D12); screenshots of sensitive pages need his tap (D14).
7. **Crash-safe writes:** every input command is journaled as "started" **before** it runs. If Ronin dies mid-command, the replay answers **"uncertain — check the page"** and never presses again (B10); **the same action on the same element stays blocked until the owner taps CHECKED**, so a new command cannot save the record twice (B11). Network breaks after an answer return the cached answer (B6).
8. **Stops:** local Stop flag needs no network (F9); Stop another device (F3); access switch (F4); daily action budget (F10); 5 tabs max; idle off (45 min sensitive, 8 h overall).
9. **Audit:** relay keeps metadata only (F6). Device keeps "what Claude did", hash-chained, and sends the chain head to the relay **after every input command** (and every 10 lines) — an edited line, a fully rewritten list, and a cut-off tail of input commands are detected (F11, F11b). It does not defeat a device and relay compromised together (stated).

## 9. Privacy and legal (owner decisions, blocking the EPDS feature only)
- Snapshots/screenshots go to Anthropic as part of the Claude chat; the relay stores none.
- **EPDS gate:** teachers' names/IDs would leave the device. Before EPDS mode is switched on, the owner confirms (a) the election office allows using an assistant and (b) data rules (India's DPDP Act 2023 — not certain how it applies here). Until then: EPDS runs with IDs hidden, or not at all. The trading-read and general features do not depend on this.

## 10. Relay operations
systemd: own user, no shell, `MemoryHigh=96M`, `MemoryMax=128M`, Node `--max-old-space-size=64`, `Restart=always`. **TLS ends on the owner's own server** (free DuckDNS hostname + Let's Encrypt), so tokens and page content are only ever on his box. (A Cloudflare Tunnel would let Cloudflare see the traffic — fallback only, stated.) Pinning protects the device link; it does not protect what a compromised relay box can read. Metadata logs rotated daily, 14 days. Runbooks: lost phone, lost server, key rotation, revoke-and-re-pair (rehearsed in the pilot).

## 11. Panel points → v4
| Raised by | Point | v4 |
|---|---|---|
| all 5 | Money blocking by URL/keywords is weak; WebSocket/GraphQL/bodies invisible | Capability rule: **no input at all** on money sites; path guard only a second fence; always-on (timer test D19) |
| GPT, Opus, DeepSeek | Copied key / DPAPI / Ed25519 claims wrong | Non-exportable ECDSA P-256 (StrongBox/TEE + presence; TPM/CNG); threat table honest |
| GPT, DeepSeek | Compromised relay / MITM after handshake | Honest threat model; ECDH session + per-frame HMAC + sequence (A13) |
| DeepSeek, Opus | 50–60 s blocking tools break MCP | ask_owner returns at once, ≤15 s polls; wait_for ≤20 s |
| Opus, GPT, DeepSeek | OAuth spec gaps, exact redirect, per-IP limit wrong | RFC 9728/8414, resource/audience, exact redirects, per-client limits (A2, A14) |
| Opus, DeepSeek | Approval fatigue / injected ask_owner text | Fixed native cards, hold-to-allow, 45-min site sessions, tap budget |
| GPT, Opus | Idempotency fails on crash | Write-ahead journal, "uncertain" answer (B10) |
| GPT | Auto-resume after 1.5 s unsafe | Latched pause until RESUME (F1, F2) |
| Opus | Hash chain can be rewritten whole | Heads anchored at the relay (F11b) |
| DeepSeek, Opus, GPT | WebView2 isolated world mis-stated; Input.insertText; InputConnection | CDP `worldName`; `dispatchKeyEvent`; focused InputConnection; parity table |
| DeepSeek | Password check relies on page JS | Native accessibility-tree check before commit |
| Gemini, GPT | Connector availability / cost unverified | Pilot step 0 on his paid plan, all three Claude apps |
| GPT, DeepSeek | EPDS legal gate | Section 9, blocks EPDS only |
| DeepSeek, Opus | MemoryMax, specialUse property, full-screen intent | Fixed (sections 7, 10) |
| Gemini | Use OS-level input (SendInput / AccessibilityService) | **Not taken:** SendInput moves the owner's real mouse; a system-wide AccessibilityService is a far bigger permission. In-app trusted events are enough; measured in the pilot |

## 11b. v4 panel points → v5
| Raised by | Point | v5 |
|---|---|---|
| GPT, Opus | Unknown sites: plain buttons could still call hidden APIs | Unknown sites read-only until the owner adds them; buttons included (D15b) |
| DeepSeek, Opus | Android accessibility tree may be empty | `EditorInfo.inputType` is the primary password check; fail closed |
| DeepSeek | Windows `dispatchKeyEvent` breaks Hindi | `Input.insertText` for text |
| Opus, DeepSeek | `commitText` fragile | Key-event fallback + read-back, "failed" not "done" |
| DeepSeek | Relay key pinned on first use (TOFU) | Baked into the signed build |
| DeepSeek | Refresh rotation logs him out on bad network | 120 s grace, same pair (A6) |
| DeepSeek, Opus | "Uncertain" can be bypassed with a new command | Action blocked until CHECKED (B11); summary card for first 3 Saves |
| GPT | Relay-minted tokens should not authorize execution | Device-checked signed grant + revocation epoch (F7b) |
| Opus, GPT | StrongBox vs TEE, press-and-hold ≠ presence | Key policy per hardware, stated |
| Opus | Tail truncation of the audit list | Head sent after every input command |
| GPT | TLS front / compromised relay can read | TLS on his own server; stated plainly |
| Gemini | Quarantine Claude's text in cards | "AI note" box |
| Qwen | 45-min allow too broad | Exact site only, ends when the tab leaves it |
| GPT | Test totals did not add up | Fixed: A14 + B11 + C8 + D21 + E3 + F15 = 72 |
| Gemini | End-to-end encryption Claude ↔ device | **Not possible while the relay is the MCP server** (our chosen integration): it must read tool calls; the signed grant + device rules limit the relay instead |
| Gemini | Accessibility click as primary input | **Kept as fallback:** the tree may be empty without an accessibility service; the pilot measures both |

## 11c. v5 panel points → v6
| Raised by | Point | v6 |
|---|---|---|
| DeepSeek, GPT, Qwen | Plain links can still change things (GET), script links, home network | Action links need the owner; `javascript:` refused; private addresses never (D9b, D20) |
| DeepSeek | Windows secret check unstated | CDP `DOM.getAttributes` on the focused node, fail closed |
| Opus, GPT | OTP boxes that look like plain text | Any 4–8-digit-only text = owner, unless he allowed that field (D6c) |
| DeepSeek | Refresh grace hides theft | Grace only while the new pair is unused; reuse afterwards kills the family (A6b) |
| GPT | Revocation can be withheld / device offline | Grants expire on the device after 12 h; clock-rollback stop (F16); max delay stated |
| Opus | WebSocket orders on write-listed sites | CSP without ws/wss on write-listed unknown sites (both platforms); red-team case added |
| Gemini | Service workers, cross-origin iframes | SW client hook; write list per exact origin; cross-origin frames read-only |
| Qwen | Fingerprints unstable in SPAs | Generated ids dropped; form action added |
| Qwen | Relay key rotation needs a rebuild | Ronin updates itself in minutes (no app store); plus an offline anchor key can sign an emergency rotation note |
| GPT, DeepSeek | "E2E impossible" too absolute | Now: "not possible while the relay is the MCP server (our chosen integration)" |
| Opus | Android constants, deprecated API | Fixed (section 4, 7) |
| GPT | Read-back "no change ≠ failed" | verified / already matched / unverified |
| Gemini | AI-note social engineering | "Unverified AI message" box; card states the real action |

## 11d. v6 panel notes → v6.1 (wording made honest + small fixes)
- **Best-effort, said plainly (GPT):** link filtering and secret-field detection are heuristics, not guarantees. The real guarantees are: money sites get no input; unknown sites get no form/input actions without the owner; passwords/OTPs are never in Claude's data (the owner types them in take-over); ambiguous fields go to an owner card.
- Action links: only an action word as the **last** path part, an action-like query key, or an `/api/` path — so `?q=update+drivers` costs no tap (Qwen; D20b).
- OTP: separators stripped before the 4–8-digit rule ("48 29-13" caught); a row of 4–8 one-character boxes is one secret field (DeepSeek, Gemini; D6d). A native "allow this time" button covers real 6-digit quantities.
- Private addresses: the hostname is **resolved natively** and blocked if any address is private, loopback (127/8), 0.0.0.0, link-local or CGNAT (100.64/10); cannot resolve → fail closed; DNS-rebinding case in the red team (Opus, GPT).
- CSP: explicit `https://` origins only — **no `'self'`, no bare `https:`** (CSP3 `'self'` allows same-origin wss) — applied to main documents **and worker scripts**; Android rewrites only GET main documents (POST main-document navigations go to the owner card; gzip handled; cookies/redirects tested). WebRTC is not covered by CSP: `RTCPeerConnection` is removed at document start, and a site that needs WebRTC or WebSocket is not write-listable. Windows `Network.webSocket*` is detection only — a WebSocket opening on a write-listed site closes the tab (Opus, DeepSeek, Gemini).
- WebView2 service workers: `AddWebResourceRequestedFilter` with `ServiceWorker` source kinds; runtime version checked at start (Opus, GPT).
- Windows check-then-type gap: the field is re-checked right after insertText too; if it became a secret field, it is cleared and Claude stops (Gemini).
- **Updater is a trust root (DeepSeek, Qwen):** update manifest and payload must be signed by the offline anchor key baked into the app; the relay/server only serves opaque bytes; a bad signature = no update. (Android additionally refuses an APK with a different signing certificate.) This is a pilot gate.

## 12. Protocol simulation v6.1 — 79 / 79, 3 runs in a row
A real Chromium page stands in for each WebView; this tests protocol and rules, not Android/WebView2 behaviour.
- **A sign-in & link (15):** refresh-token theft kills the family; 401 without token; exact redirect only; Deny; stolen code useless; Allow → token; refresh rotation with 120 s retry grace; connector rules; 16 tools; 3 devices; unsigned/forged Allow refused; /register flood 429; fake relay ignored; injected frame without HMAC ignored; RFC 9728 discovery + wrong resource refused.
- **B EPDS on tablet (11):** IDs hidden; one Allow for the site; dropdown; photo from folder only; other path refused; network cut after Save → saved once; record correct; **app killed during Save → "uncertain", not pressed again; the same Save blocked until CHECKED**; queue; taint after reading.
- **C trading on laptop (8):** password refused; read-only; ask_owner returns at once, Claude waits, owner logs in, "finished"; P&L read; Exit All refused; even harmless taps refused; only listed read pages open.
- **D hostile pages (26):** split 6-box OTP and spaced/dashed digits; ordinary search links cost no tap; 4–8-digit OTP-like text; home-network address; action / script links on unknown sites; clickjacking; unknown site read-only even for plain buttons; Hindi + icon-only money buttons; normal search; OTP by shape; password box on an ordinary site; lying prototypes; exfil URL; javascript:; text cap; ID redaction incl. get_text; download Allow; screenshot Allow; hidden fetch placeOrder; /order?confirm=1; **order fired by a 3-s timer after the tap**; ordinary POST works.
- **E human checks (3).** **F owner control (16):** 12-hour grant life and clock-rollback stop; Disconnect → a hacked relay replaying the old grant is refused by the devices; latched pause + RESUME; stop from another device; access switch; screenshot; metadata-only relay audit; offline Stop; daily budget; hash chain + relay anchors catch an edit and a full rewrite; tokens bound to approving device; remove a device; Disconnect; unpaired device refused.

## 13. Real-device pilot (the only way to 9.5) — pass marks
| Test | Device | Pass |
|---|---|---|
| Step 0: connector + OAuth on his Claude (web, desktop, phone) | all | works 3/3 |
| Wi-Fi ↔ mobile switch | phone, tablet | reconnect ≤10 s, 20/20, no lost/double command |
| Overnight on charger (Doze, OEM battery manager) | tablet, phone | online after 8 h, or clearly "offline" |
| Kill Ronin during Save | tablet | "uncertain" 10/10, never a second Save |
| Turnstile demo, owner solves | tablet, laptop | ≥19/20 (Claude-desktop-browser rate recorded beside it) |
| Trading terminal: owner logs in, Claude reads positions | laptop, tablet | 5/5; any input refused 5/5 |
| EPDS-style 20 records (dummy data) | tablet | 0 wrong, 0 double, ≤5 owner taps |
| Red team: ≥100 hostile pages with the helper deliberately poisoned (timers, WebSocket, WebRTC, GraphQL, service worker, sendBeacon, OTP-as-text, action links, overlays) | all | 0 passwords / OTP / money actions get through |
| Same 21 hostile cases inside the real APK / exe, per mechanism (tap, commitText, key fallback, EditorInfo check, accessibility tree, shouldInterceptRequest, service worker) | OnePlus, Motorola, laptop | 21/21 per mechanism, else that mechanism is switched off (fail closed) |
| Hindi name typing | laptop, tablet | 20/20 exact read-back |
| Crash at every journal step | tablet, laptop | never a double Save |
| Latency p95 | all | ≤1.5 s Wi-Fi, ≤3 s mobile |
| Battery idle, connected | phone | ≤3 %/hour |

## 14. Build order (after the owner approves)
0. Minimal relay with OAuth → add as connector in his Claude → verify on web/desktop/phone (cheap, decides everything).
1. Relay complete + these tests in CI (beside the existing server service, which is not touched).
2. Windows agent → pilot tests on the laptop.
3. Android agent (one APK, tablet + phone) → pilot tests.
4. Independent QA review → the owner sees it → publish only when he names the version. Live money or government sessions only after the pilot's pass marks are met; until then dummy data and read-only trading.
