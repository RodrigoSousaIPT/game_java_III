# Arena 3D RAG — Reconnaissance Report

**Target:** `https://arena.pmonteiro.ovh/` (FastAPI + NiceGUI)
**Goal:** Provide ground-truth for the JAVA bot author so the bot can be tuned without further reverse-engineering by hand.

Two game rooms exist:

| Room   | URL | Scenario                                |
|--------|-----|-----------------------------------------|
| `7A1071` | `https://arena.pmonteiro.ovh/?room=7A1071` | `aluno_treino_2026` (training)      |
| `C31233` | `https://arena.pmonteiro.ovh/?room=C31233` | `arena_batalha_global` (tournament) |

Both rooms currently return `game_started: false`. The game is gated behind an admin "professor" trigger from the dashboard (`Torre de Vigia`). All bot-authored actions return `{"status":"bloqueado","motivo":"Aguarde início."}` until that trigger fires.

---

## 1. API Contract (from `/openapi.json`)

| Method | Path | Body / Query | Notes |
|--------|------|--------------|-------|
| GET    | `/assets/{path}`                | path in URL                          | Static files (UI assets, glTF `.glb` models). Probed key names → all 404, path is opaque. |
| POST   | `/arena/{room_id}/register`      | query `robot_id`                     | Spawns a robot. Returns initial state. |
| GET    | `/arena/{room_id}/perceive/{robot_id}` | –                            | JSON snapshot of world around the robot (walls, vaults, resources, enemies, energy). |
| POST   | `/arena/action`                  | JSON `{room_id, robot_id, action}`   | Per-tick action submitter. Currently gates on `game_started`. |
| POST   | `/arena/{room_id}/unlock`        | query `robot_id`, `code`, optional `rag_chunk`, `llm_raw` | Unlock vault. Requires robot physically on the cofre. |
| GET    | `/arena/{room_id}/download_manual` | –                                 | Plain-text 3,750 byte manual (15 sections) for RAG retrieval. Identical for both rooms today. |

Body schema name: `BotActionRoom { room_id: string, robot_id: string, action: string }`.

The Swagger UI lives at `/docs` and pulls the spec from `/openapi.json`.

---

## 2. Perception / Game State Schema

Sample (`recon/recon_perceive_7A1071.json`):

```json
{
  "o_meu_estado":   { "x": 2.0, "y": 6.0, "z": 0.4, "energia": 200, "cor": "#0ea5e9" },
  "recursos_no_mundo": [],
  "objetos_fixos": [
    { "id": "parede_24", "type": "cubo", "model": "cubo.glb", "x": 0.0, "y": 2.0, "z": 0.5 },
    ...
    { "id": "parede_56", "type": "cubo", "model": "cubo.glb", "x": 4.0, "y": 10.0, "z": 0.5 }
  ],
  "outros_robots":  {},
  "cofres_no_mundo": [],
  "game_started": false,
  "game_over":    false,
  "vencedor":     null
}
```

Field meanings (Portuguese → English). **The shape is asymmetric — mixing `outros_robots` with the list fields is a common bug, mirror it carefully in the JAVA parser:**

| Field | Type | Meaning |
|-------|------|---------|
| `o_meu_estado`       | object          | Self: position, energy, hex color |
| `recursos_no_mundo`  | **list**        | Pickups (yellow = energy **baterias**, purple = **baú** chest per `old.txt`) |
| `objetos_fixos`      | list of objects | Static blocks — currently all `cubo.glb` walls (`parede_*`) |
| `outros_robots`      | **dict** keyed by robot_id | Other live bots in the same room (`{}` until they spawn). JAVA: `Map<String, Robot>` |
| `cofres_no_mundo`    | list of objects | Vaults the bot must `unlock` |
| `game_started` / `game_over` / `vencedor` | scalar | Game state machine |

**Success vs. error envelope.** The endpoint always returns 200 — the error path is `{"error":"Robô não registado","game_over":false}` (we observed this for `C31233`). The success tell is the *presence of `o_meu_estado`*, not the HTTP status. JAVA: branch on `root.has("o_meu_estado")` instead of `response.statusCode() == 200`.

The grid is **`x × y × z`** in metres. Walls march in 1 m increments (y 2…10). `z=0.4` is the floor-level for the bot, `z=0.5` for a wall cube — so the world is a 2-D maze with a single elevated layer. Expect e.g. `z=0.4` for chests, `z≈0.6` for vaults.

**Forward-compat note.** The server is free to add fields (`hp`, `turno`, `tempo_restante`, etc.). The JAVA parser should ignore unknown keys rather than crash; both `MeuEstado.from_json` and `ObjetoFixo.from_json` in the reference client do this.

---

## 3. Registration Response

```json
{"status":"registado","x":2.0,"y":6.0,"z":0.4,"energia":200,"cor":"#0ea5e9"}
```

`status` echoes `registado` on success and `erro` otherwise (e.g. wrong room id). `cor` is server-assigned per robot id.

---

## 4. Manual — 15 sections, Portuguese, role = RAG corpus

The manual returned by `download_manual` is the **RAG knowledge base for vault unlocking / situational reasoning**, *not* a list of action keywords. Each section is one chunk:

| # | Trigger | Code token (likely key in vault question) |
|---|---------|--------------------------------------------|
| 01 | Magnetic shields under kinetic bombardment | `SIGMA-3` |
| 02 | Reactor overheating / coolant loss | `XTR-99` |
| 03 | Proximity radar distortion | `OPTIC-ZOOM` |
| 04 | Hydraulic over-pressure | `VALVULA-ALT` |
| 05 | Energy < 50%, locomotion efficiency | `ECO-MARCH` |
| 06 | Clock drift in logic circuits | `CLK-SYNC` |
| 07 | Plasma cannon over-temperature | `VENT-OUT` |
| 08 | Electromagnetic storm saturating grid | `GROUND-0` |
| 09 | Nanobot medical recharge after damage | `REGEN-MAX` |
| 10 | Incoming projectile / opponent charge | `EVADE-NOW` |
| 11 | OS memory corruption / LLM thread stuck | `BOOT-HARD` |
| 12 | LiDAR readings break after collisions with concrete | `MAP-RESET` |
| 13 | API packet loss / channel drop | `LINK-UP` |
| 14 | Gamma levels above threshold | `SHIELD-LEAD` |
| 15 | Startup impulse after professor start signal | `NITRO-START` |

Used as: split by `SECÇÃO NN -` headers → 15 chunks → embed with `Qwen3-Embedding-0.6B` (already pulled into `qwe3embedd.txt` Dockerfile) → retrieve top-k against the vault prompt the server sends.

---

## 5. Vault / `unlock` Endpoint

```http
POST /arena/{room_id}/unlock?robot_id=<id>&code=<code>[&rag_chunk=…][&llm_raw=…]
```

**No JSON body — all params are query strings.** (`recon/openapi_snapshot.json` records this explicitly.) JAVA `HttpRequest.newBuilder()` + `.GET()` works; do not send `Content-Type: application/json`.

Behavior observed:
* Wrong-room / no-payload → `{"status":"erro","motivo":"Nao estas no cofre!"}`.
* Manual codes (`SIGMA-3`, …) all returned the same error → they are RAG distractors, **not** the unlock payload.
* `rag_chunk` and `llm_raw` are telemetry that the server records to grade the bot — *the bot should send its actual retrieved chunk and the raw LLM prompt-completion string so the admin dashboard can audit reasoning*.

Strategy: when a vault appears in `cofres_no_mundo`, navigate to it, query `/perceive` until you are colliding with it, then post:

```
code          = <the vault's true access code>
rag_chunk     = top-1 manual chunk (SECÇÃO string) returned by the embedding model
llm_raw       = full LLM prompt + answer that produced the code
```

**`code` is the unknown — we have never observed a successful unlock.** The candidate fuzz list below is **speculative**; treat it as a starting fuzzer list, not a checklist:

⚠️ *Speculative — no positive sample observed yet. Re-rate once a successful unlock fires.*

- the room id (`7A1071` / `C31233`)
- the vault id from `cofres_no_mundo`
- numerals that appear in chest positions
- pre-known constants `0000`, `1234`
- "BOSS" / "VAULT" style strings
- any 6-character alphanumeric string that the room title suggests (`aluno_treino_2026` → `2026`, `TREINO`, etc.)

---

## 6. Action Vocabulary — hypothesis

**⚠️ Speculative. Every probe today returned `{"status":"bloqueado","motivo":"Aguarde início."}` because `game_started=false`. The valid vocabulary can only be confirmed once an admin flips the game on.** Anything in this section is a starting fuzzer list, not a contract.

The manual contains sentences like *"execute a purga total de gases através da chave de exaustão **VENT-OUT**"* — the most parsimonious reading is that the action endpoint accepts Portuguese verb forms **plus** the manual code tokens as direct commands. The fact that all 30+ fuzz strings returned *the same* `bloqueado` envelope tells us nothing about validity: the gate runs *before* the parser.

**Prioritised fuzz list once the admin starts the game** (try in this order):

```
andare / mover    — generic movement
avancar / recuar  — forward / back
girar_esq / girar_dir — rotate left / right
subir / descer    — z-axis (climb / drop)
saltar            — jump
atacar            — engage another robot
abrir             — open a chest (baú)
recolher          — pick up energy (yellow)
usar              — use a manual protocol (SIGMA-3, XTR-99, …)
status            — telemetry
```

Action endpoint already accepts any string; `bloqueado` is the *only* return for everything while the room is locked, so the actual vocabulary can only be probed once `game_started` flips true. Have the bot script auto-fuzz on connect.

---

## 7. Status Codes (Portuguese → English)

| Returned | Where | Meaning |
|----------|-------|---------|
| `registado`                                | `register`  | Register succeeded |
| `{"status":"erro","message":"Acesso Negado: A arena já está ocupada por outro agente! Aguarde a sua vez."}` | `register` | **Room is locked by another agent** — single-occupancy queue, retry with backoff. HTTP 200 even on rejection. |
| `bloqueado` / `Aguarde início.`            | `action`    | Game not started — wait |
| `Nao estas no cofre!`                      | `unlock`    | Bot is not at the vault tile |
| `Robô não registado`                       | `perceive`  | perceive called for unregistered id |

**Two failure shapes.** Some failures come through as **non-200 HTTP statuses** (the reference client raises). Others come through as **HTTP 200 with `{"status":"erro","motivo":"…"}`** — those must be inspected by the bot. The bot should treat `status` as a first-class field, not a side-effect of transport.

---

## 8. Recommended Probes After the Admin Starts the Game

1. Spray all 30+ candidate actions at `/arena/action` and log each response.
2. Walk the bot to a known `cubo.glb` wall, then `unlock` with empty `code` → capture exact error.
3. Place bot on a chest tile, try `action="abrir"` and `action="recolher"`.
4. Spam `unlock` with `code` ∈ {vault id, room id, "0000"…} from the chest tile position.
5. Inspect `recursos_no_mundo` after moving one tile — confirm energy delta semantics.
6. Check `outros_robots` for opponent awareness — what fields (id, hp, x/y/z, cor)?
7. Check if `game_started` becomes `true` once a bot successfully moves; if not, the professor almost certainly has a `POST /arena/{room_id}/start` (or similar in PT: `iniciar`, `começar`, `largar`) admin endpoint.

---

## 9. Bot Improvement Targets (from this recon)

* **Map caching:** Treat `objetos_fixos` as a *persistent* layer — re-fetch `/perceive` only to get deltas in `recursos_no_mundo`, `outros_robots`, `cofres_no_mundo`.
* **Energy budgeting:** Energy starts at 200. Movement likely costs energy. Prioritise `recolher` (yellow) on any tile that drops energy below a threshold; use `usar ECO-MARCH` once below 50 % per SECÇÃO 05.
* **Vault solving pipeline:** Embed vault prompt with `Qwen3-Embedding-0.6B` → cosine search vs. 15 manual chunks → call `llm_raw` reasoning (Qwen 2B-MTP) → POST `unlock` with `code`, `rag_chunk=top-1`, `llm_raw=full`.
* **Anti-collision:** Hard-block any move whose `(x+dx, y+dy)` is inside `objetos_fixos`. Wall cubes are at `(0,2..10,0.5)` — walls bound the corridor on x=0; only the diagonal at `(4,10,0.5)` blocks y=10.
* **JIT contract changes:** Re-pull `/openapi.json` on every connect — if new endpoints appear (e.g. `/arena/{room_id}/event`) the bot should pick them up dynamically.
* **Concurrency / queueing:** Each room is **single-occupancy**. The server returns `{"status":"erro","message":"Acesso Negado: … Aguarde a sua vez."}` (HTTP 200) when another agent holds the slot. The bot must implement exponential backoff (start 2 s, cap 60 s, jitter) on this rejection — hammering register will not get it in faster.

---

## 10. Files Dropped Into `recon/`

* `openapi_snapshot.json` — minimal OpenAPI summary for offline agent context
* `manual_7A1071.txt`, `manual_C31233.txt` — raw RAG corpus
* `recon_perceive_7A1071.json`, `recon_perceive_C31233.json` — raw perception samples
* `arena_client.py` — minimal Python reference client to mirror in JAVA
* `ARENA_API.md` — this document
