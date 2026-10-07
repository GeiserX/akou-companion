# The live protocol: `GET /v1/live`

This is the wire protocol between akou-companion and an akou server: one WebSocket that carries the phone's audio up and the live transcript down. The server side lives in akou; its setup, proxy and key docs are in [akou's server guide](https://github.com/GeiserX/akou/blob/main/docs/server.md). If this page and the server disagree, the server is right and this page is the bug.

Status: the route is merged in akou. A server that has it and a streaming model on disk says so with `capabilities.live` in `GET /v1/server`, and `live.engines` lists the streaming models it can open; on an older server the app records and uploads, without live text.

## The idea in four lines

- The phone records to an Ogg Opus file. That file is the recording; it is never at risk from the network.
- Each 200 ms Ogg page of that file is also sent, byte for byte, as one binary WebSocket message.
- The server decodes the pages, runs its streaming recognizer and sends words back as they come.
- When the recording stops, the whole file goes up as an ordinary akou job for the final, better transcript.

The socket is transcript only. It keeps no state after it closes, and nothing on it is ever resent.

## Connecting

```
GET /v1/live HTTP/1.1
Upgrade: websocket
Authorization: Bearer ak_...
```

- Server mode only. Any key reaches it (the `jobs` scope, the same one file jobs use).
- The server checks the key before the upgrade. A missing, wrong or revoked key gets a plain `401` HTTP answer, never a socket. A request without `Upgrade: websocket` gets `426` `upgrade_required`, and a server with no streaming model on disk answers `503` `no_live_engine`.
- Use `wss://` with an `https` server. Plain `ws://` is allowed only to a private address: loopback, RFC 1918, unique-local (`fc00::/7`) or `100.64.0.0/10`. The app refuses a host name over plain `http`.
- A reverse proxy must pass WebSocket upgrades and keep a quiet socket open. Caddy does by default; nginx needs the `Upgrade` and `Connection` headers and a read timeout longer than the ping interval.

## Frames

Text frames are JSON control messages, one object with a `type`. Binary frames are audio.

With `codec: "ogg-opus"` every binary frame is exactly one Ogg page. The first two are the OpusHead and OpusTags pages, then audio pages. The Ogg header already carries what the server needs: the page sequence, the stream serial, a CRC32 and the granule position (the audio clock at 48 kHz), so there is no extra header of our own.

With `codec: "pcm16"` binary frames are raw 16 kHz, 16-bit little-endian mono samples, a whole number of samples per frame. It exists for test clients and for comparing against Opus; the app does not use it.

A frame is at most 64 KB. A 200 ms Opus page is about 650 bytes.

At most 30 seconds of audio may be waiting for the engine, counted in decoded samples the engine has not processed yet. A client that sends faster than the engine decodes is closed with 4400 `too_fast`. A client that sends at the pace it records stays far below it.

### Audio settings

Opus from libopus, 16 kHz mono input, 20 ms frames, 24 kbit/s VBR, wideband at most, voice signal, no DTX, no in-band FEC (TCP loses nothing). Ten packets per page, so one page per 200 ms, about 650 bytes. That is about 11 MB per hour on the socket (a minute of speech measured 178 KB on the server), against about 115 MB for `pcm16`.

## Messages

Phone to server:

| Message | When |
|---|---|
| `{"type":"hello","v":1,"codec":"ogg-opus","language":"auto","model":"auto"}` | First, right after the socket opens. `language` is `auto` or a BCP 47 code; `model` is `auto` or a streaming model id from `live.engines` in `GET /v1/server`. With `auto`, `language` picks the model: `en` opens `nemotron-en-560`, `es` opens `nemotron-3.5-1120`, anything else `nemotron-3.5-560`, among the models on disk. |
| `{"type":"stop"}` | The recording stopped. |

Server to phone:

| Message | When |
|---|---|
| `{"type":"ready","engine":"nemotron-3.5-560","lang":"auto","tier_ms":560,"load_ms":4210}` | The live stream is open. Only now does the phone send pages. `tier_ms` is how far behind the audio a word can come. |
| `{"type":"words","tokens":[{"text":" hola","t":1.23,"conf":0.91}]}` | New tokens, as the engine returns them. A token whose text starts with a space starts a new word. Tokens are append-only: the server never takes one back. |
| `{"type":"closed"}` | After `stop`, once the last `words` went out. It marks the end; the server then closes the socket normally. |
| `{"type":"error","code":"engine_busy","message":"..."}` | Followed by a close with the matching code below. |

`t` is seconds into the recording, on the file's own timeline. With `ogg-opus` the server computes it from the granule of the first audio page it received in this session, so a session opened after a reconnect still reports times that match the file. The phone sends no timestamps. With `pcm16` there is no granule, so `t` counts from the session's first sample.

The phone pings every 20 seconds. The server pings an idle socket too and closes it after two minutes without an answer.

## Close codes

| Code | `error.code` | Meaning |
|---|---|---|
| 1000 | | Normal close after `closed`. |
| 4400 | `bad_message` | A control message that is not valid JSON, has an unknown type or field, or comes out of order (audio before `hello`, a second `hello`). |
| 4400 | `bad_page` | A binary frame that is not a valid Ogg page (bad capture pattern or CRC), belongs to another stream (its serial), skips a page sequence number, or is not mono; a `pcm16` frame that is not whole samples. |
| 4400 | `too_fast` | More than 30 s of audio is waiting for the engine, counted in decoded samples: the client sends faster than the engine decodes. |
| 4400 | `unknown_model` | `hello` names a live engine this server does not have. |
| 4400 | `unsupported_language` | `hello` names a language no live engine on this server hears. |
| 4401 | `key_revoked` | The key was revoked while the socket was open. The session checks its key on a one-second timer, so it closes within about a second, whether or not the client is sending. |
| 4409 | `engine_busy` | The `hello` resolves to a different streaming model than an open session uses. The server keeps one streaming model loaded; a `hello` that resolves to the same model runs beside the open one. Pin the same model on every phone. |
| 4500 | `stream_lost` | The live engine stopped during the session. |
| 4503 | `no_live_engine` | No live engine is on disk, or live text is off on this server. |

## Reconnecting

Pages within one session must be consecutive: the server closes with 4400 on any gap in page sequence numbers. So a client that falls behind, or drops pages for any reason, never skips ahead on the same socket; it closes it and opens a new session from the current page.

The same holds for `too_fast`: the only recovery is to close and reopen. So the phone's send queue must stay well under the server's 30 seconds; when it grows, the phone drops the session and opens a new one from the current page rather than flushing a backlog.

There is no resume. After a drop the phone opens a new socket: `hello`, OpusHead, OpusTags, then pages from where the recording is now. The pages produced while it was offline are not resent; they are in the file. The live view marks the gap ("live text paused, 0:12 to 0:41 will come with the final transcript") and the final pass fills it.

The trade this design accepts is that the audio exists only on the phone until the file is uploaded.

## After the recording

The file goes up as a normal akou job:

- `POST /v1/jobs` (multipart) with `file=<id>.opus`, `title`, `language`, `model` or `preset` when the user chose one, `keep_audio=true`, `metadata` (`{"companion":1,"recording_id":"...","workspace":"..."}`, at most 4 KB of JSON) and the header `Idempotency-Key: <recording id>`. The upload runs from a background `URLSession` with the body in a file.
- The answer is 202 with a new job, or 200 with the first job when the same key, file and options came before, so a retried upload never makes a second job. The same key with another file or other options is 422 `idempotency_conflict` (with the first job's `id` and the differing `fields`). A full queue is 429 `queue_full` with `Retry-After` in seconds, answered before the body is read.
- `keep_audio` and `metadata` are not compared on a repeated key: a retry gets the first job as it was made. The phone reads `keep_audio` from the job it gets back, never from what it sent.
- Workspaces are a desktop-only route, so on a server the workspace is the label in `metadata`. Every job answer carries `keep_audio` and `metadata`.
- `GET /v1/jobs/{id}?wait=60` until it is done, then `GET /v1/jobs/{id}/result?format=json` for `text`, `segments` and `words` (`{w, s, e, c}`: the word, its start and end in seconds and its confidence; `s`, `e` and `c` are null from an engine that gives none). The result answers 409 `not_done` before the job is done.
- A job with `keep_audio=true` keeps its audio on the server until it is deleted, whatever `retain_days` in `GET /v1/server` says, and `GET /v1/jobs/{id}/audio` streams it back byte for byte, with `Range`. It answers 409 `not_kept` for a job submitted without `keep_audio`, and 410 `gone` when a kept file is no longer on disk.
- `GET /v1/jobs?status=&q=&cursor=&limit=` lists the key's jobs newest first, as `{jobs, cursor}` (`cursor` is null on the last page; `q` matches the title, id or state only). `PATCH /v1/jobs/{id}` with `{"title": "..."}` renames a job and `DELETE /v1/jobs/{id}` deletes it with its result and kept audio.
- When the app opens after a long time it catches up with `GET /v1/events`. The server cannot push to a phone, so a finished transcript shows the next time the app runs.

### The upload queue

Every finished recording goes into a queue kept on the phone's disk, keyed by its recording id. Each one moves `pending`, `uploading`, `submitted` (the server answered with a job), `done` (the job was read back with `GET /v1/jobs/{id}`). The queue survives an app restart: an upload whose transfer did not survive goes back to `pending` and is sent again with the same key.

- No answer (no network, a dropped connection), 408, 5xx, or a 2xx that is not a job: try again after 5 s, doubling up to 15 minutes. 429: after its `Retry-After`. The queue also runs when the network comes back.
- `idempotency_conflict` and any other 4xx (a wrong key is 401): the recording is parked with the error and not sent again until the user retries, for example after fixing the key. A recording whose job was deleted before it was read back (404, 410) is parked too, and its file stays.
- The phone deletes its own file only after reading the job back with `keep_audio: true`, and only when the setting to keep a copy on the phone is off.
