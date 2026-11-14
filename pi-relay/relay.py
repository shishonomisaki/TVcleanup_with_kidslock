"""TV remote relay: Parental Lock on the TV posts a key name here, and this sends it to the
TV's own remote service. The TV refuses remote connections that come from itself, so the
hop has to go through another machine.

Listens on the Pi's LAN address only, answers the TV's address only, and every request
carries the shared token. Keys outside KEYS are refused.

    POST /key   header X-Token: <token>   body: DPAD_UP

With RELAY_PAGE set (the TV's Tailscale address and page port), it also passes
127.0.0.1:6481 through to the phone page, for `tailscale serve` to put HTTPS in front of.
tailscaled's own connections skip the Tailscale routes, so serve can't reach the TV directly.

On 127.0.0.1:6482, also for `tailscale serve` (mounted at /pi), what the phone page sends
straight to the TV's remote service: typed text, the keyboard's delete and enter, and voice.
Each one asks the phone page first and is refused while the TV is locked.

    GET  /pi/state   {"on": true|false|null}: the TV's power as it reports it; null = unknown
    POST /pi/text    body: UTF-8 text, 200 characters at most
    POST /pi/key     body: DEL or ENTER
    POST /pi/vstart  opens the TV's voice search; answers once the TV listens
    POST /pi/vchunk  body: 16-bit PCM, mono, 8000 Hz, sent while the person speaks
    POST /pi/vend    closes it; the TV also gets closed after 3 s without audio or 15 s in all
"""
import asyncio
import hmac
import json
import logging
import os
import sys
from urllib.parse import quote

from androidtvremote2 import AndroidTVRemote, CannotConnect, ConnectionClosed, InvalidAuth
from androidtvremote2.remote import RemoteProtocol
from androidtvremote2.remotemessage_pb2 import (
    RemoteEditInfo, RemoteImeBatchEdit, RemoteImeObject, RemoteMessage,
)

BIND = os.environ["RELAY_BIND"]          # the Pi's LAN address
PORT = int(os.environ.get("RELAY_PORT", "6480"))
TV = os.environ["RELAY_TV"]              # the TV's LAN address; the only client allowed
HERE = os.path.dirname(os.path.abspath(__file__))
PAGE = os.environ.get("RELAY_PAGE")     # "<tv 100.x>:<page port>", optional
PAGE_PORT = int(os.environ.get("RELAY_PAGE_PORT", "6481"))
PHONE_PORT = int(os.environ.get("RELAY_PHONE_PORT", "6482"))
TEXT_MAX = 200
VOICE_MAX = 15 * 16000                   # 15 s of 8 kHz 16-bit mono
CHUNK_MAX = 20 * 1024                    # the largest chunk the TV takes; smaller ones are not recognised
TOKEN = open(os.path.join(HERE, "token")).read().strip().encode()
# While this file exists beside relay.py, text for YouTube is typed instead of opened as a search link
# (a test switch: touch it to try, rm it to go back). Checked on each request, no restart needed.
YOUTUBE_TYPING = os.path.join(HERE, "youtube-typing")

KEYS = {
    "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER",
    "BACK", "HOME", "MENU", "SEARCH",
    "VOLUME_UP", "VOLUME_DOWN", "VOLUME_MUTE",
    "MEDIA_PLAY_PAUSE", "MEDIA_NEXT", "MEDIA_PREVIOUS",
    "MEDIA_FAST_FORWARD", "MEDIA_REWIND", "POWER",
}

log = logging.getLogger("relay")
remote = None            # made in main(): the library needs the running event loop
ready = None
talk = None              # the open voice session: {"stream", "pcm", "last", "began"}
# The counters the TV takes in a text edit (androidtvremote2 #51): from remote_ime_key_inject when a
# keyboard opens, the field one renewed by each remote_ime_show_request. 0 = none seen yet.
ime = {"ime": 0, "field": 0}


async def keep_connected():
    """Connect once; the library reconnects by itself after that."""
    while True:
        try:
            await remote.async_connect()
            break
        except InvalidAuth:
            log.error("the TV no longer knows this relay: run pair.py again")
            sys.exit(1)
        except (CannotConnect, ConnectionClosed) as e:
            log.info("TV not reachable (%s), retrying in 30 s", e)
            await asyncio.sleep(30)
    remote.add_is_available_updated_callback(
        lambda ok: (ready.set() if ok else ready.clear(), log.info("TV available: %s", ok)))
    remote.add_is_on_updated_callback(lambda on: log.info("TV power: %s", "on" if on else "off"))
    remote.keep_reconnecting()
    await asyncio.sleep(2)  # the TV drops the first key sent right after connecting
    ready.set()
    log.info("connected to the TV, features %s",
             getattr(remote._remote_message_protocol, "_active_features", "?"))


async def reply(writer, code, text):
    body = text.encode()
    writer.write(b"HTTP/1.0 %d X\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s"
                 % (code, len(body), body))
    await writer.drain()
    writer.close()


async def handle(reader, writer):
    peer = writer.get_extra_info("peername")[0]
    try:
        if peer != TV:
            log.info("dropped %s", peer)
            writer.close()
            return
        head = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 5)
        lines = head.decode("latin-1").split("\r\n")
        method, path, _ = lines[0].split(" ", 2)
        headers = {k.strip().lower(): v.strip()
                   for k, v in (l.split(":", 1) for l in lines[1:] if ":" in l)}
        length = min(int(headers.get("content-length", "0")), 64)
        key = (await asyncio.wait_for(reader.readexactly(length), 5)).decode().strip()
        if not hmac.compare_digest(headers.get("x-token", "").encode(), TOKEN):
            return await reply(writer, 403, "token")
        if method != "POST" or path != "/key" or key not in KEYS:
            return await reply(writer, 400, "bad request")
        if not ready.is_set():
            return await reply(writer, 503, "TV not connected")
        remote.send_key_command(key)
        await reply(writer, 200, "ok")
    except Exception as e:  # a bad request must never take the relay down
        log.info("request from %s failed: %s", peer, e)
        writer.close()


async def pipe(reader, writer):
    try:
        while data := await reader.read(65536):
            writer.write(data)
            await writer.drain()
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        writer.close()


page_via = None   # (address, until): the way that worked last, kept a minute so each request skips a dead one


async def open_page():
    """Connect to the TV's phone page: over Tailscale, or, when the TV's own Tailscale is down, over the home
    Wi-Fi (the TV lets in this relay's LAN address too). Raises OSError when neither answers."""
    global page_via
    tail, port = PAGE.rsplit(":", 1)
    loop = asyncio.get_running_loop()
    ways = [tail, TV]
    if page_via and page_via[1] > loop.time() and page_via[0] in ways:
        ways.remove(page_via[0])
        ways.insert(0, page_via[0])
    last = None
    for host in ways:
        try:
            conn = await asyncio.wait_for(asyncio.open_connection(host, int(port)), 3)
            if host != tail and (not page_via or page_via[0] != host):
                log.info("page: the TV's Tailscale does not answer, using the home Wi-Fi")
            page_via = (host, loop.time() + 60)
            return conn
        except (OSError, asyncio.TimeoutError) as e:
            last = e
    raise OSError(f"TV not reachable ({last})")


async def page(reader, writer):
    """Pass one connection from tailscale serve through to the TV's phone page."""
    try:
        tv_reader, tv_writer = await open_page()
    except OSError as e:
        log.info("page: %s", e)
        writer.close()
        return
    await asyncio.gather(pipe(reader, tv_writer), pipe(tv_reader, writer))


async def tv_status():
    """Ask the phone page; None when it can't be reached."""
    try:
        r, w = await open_page()
        w.write(b"GET /api/status HTTP/1.0\r\n\r\n")
        await w.drain()
        data = await asyncio.wait_for(r.read(), 5)
        w.close()
        return json.loads(data.split(b"\r\n\r\n", 1)[1])
    except Exception as e:
        log.info("phone: status failed (%s)", e)
        return None


async def answer(writer, code, obj):
    body = json.dumps(obj).encode()
    writer.write(b"HTTP/1.1 %d X\r\nContent-Type: application/json\r\nContent-Length: %d\r\n"
                 b"Cache-Control: no-store\r\nConnection: close\r\n\r\n%s" % (code, len(body), body))
    await writer.drain()
    writer.close()


def tv_heard(handle):
    """Watch what the TV sends.

    Voice: the TV sends remote_voice_payload back once it has heard the end of what was said, and then
    searches. The library ignores it; the open session is marked over, so the phone stops, and the
    session is not ended by us before that (ending it early cancels the search).

    Text: remote_ime_key_inject (a keyboard opened) and remote_ime_show_request (the field after an
    edit) carry the counters send_text needs; they are kept in `ime` and logged."""
    def wrapped(self, raw):
        msg = RemoteMessage()
        try:
            msg.ParseFromString(raw)
            if talk is not None and (msg.HasField("remote_voice_payload") or msg.HasField("remote_voice_end")):
                talk["over"] = True
                log.info("voice: the TV heard the end after %.1f s: %s",
                         asyncio.get_running_loop().time() - talk["began"], str(msg).replace("\n", " "))
            if msg.HasField("remote_ime_key_inject"):
                k = msg.remote_ime_key_inject
                # taken as they come, 0 too: another app's numbers must not stay (YouTube sends 0/0
                # and still takes text edited with 0/0)
                ime["ime"] = k.app_info.counter
                ime["field"] = k.text_field_status.counter_field
                log.info("ime: key_inject app %s counter %d, field %d, label %r",
                         k.app_info.app_package, k.app_info.counter, k.text_field_status.counter_field,
                         k.text_field_status.label or k.app_info.label)
            elif msg.HasField("remote_ime_show_request"):
                f = msg.remote_ime_show_request.remote_text_field_status
                ime["field"] = f.counter_field or ime["field"]
                # the length only: the field holds what was typed
                log.info("ime: show_request field %d, %d chars, label %r", f.counter_field, len(f.value), f.label)
            elif msg.HasField("remote_ime_batch_edit"):
                b = msg.remote_ime_batch_edit
                log.info("ime: batch_edit ime %d, field %d", b.ime_counter, b.field_counter)
        except Exception:
            pass
        return handle(self, raw)
    return wrapped


def send_text(text):
    """Type text into the TV's open text field, with the key-inject counters when there are any
    (androidtvremote2 #51), else with the library's batch-edit ones."""
    proto = remote._remote_message_protocol
    if not ime["ime"]:
        log.info("text: %d chars, no key_inject yet: batch-edit counters %s/%s", len(text),
                 getattr(proto, "ime_counter", "?"), getattr(proto, "ime_field_counter", "?"))
        remote.send_text(text)
        return
    log.info("text: %d chars, key_inject counters %d/%d", len(text), ime["ime"], ime["field"])
    end = max(len(text) - 1, 0)
    edit = RemoteEditInfo(insert=1, text_field_status=RemoteImeObject(start=end, end=end, value=text))
    msg = RemoteMessage()
    msg.remote_ime_batch_edit.CopyFrom(
        RemoteImeBatchEdit(ime_counter=ime["ime"], field_counter=ime["field"], edit_info=[edit]))
    proto._send_message(msg)


RemoteProtocol._handle_message = tv_heard(RemoteProtocol._handle_message)


def voice_end():
    global talk
    if talk is None:
        return
    t, talk = talk, None
    t["stream"].end()
    log.info("voice: %.1f s sent, peak %d", len(t["pcm"]) / 16000, t["peak"])
    # The last recording, for checking what the TV was sent; overwritten each time.
    import wave
    with wave.open(os.path.join(HERE, "last-voice.wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000); w.writeframes(bytes(t["pcm"]))


async def voice_watch(t):
    """Close the session once the TV has searched, one the phone stopped feeding, or one that ran
    too long. After the phone stops, quiet is sent for up to 4 s so the TV can hear the end."""
    loop = asyncio.get_running_loop()
    while talk is t:
        await asyncio.sleep(0.25)
        now = loop.time()
        if t["over"]:
            await asyncio.sleep(3)   # ending it at once can cancel the search
            if talk is t:
                voice_end()
        elif t.get("closing"):
            if now - t["closing"] > 4:
                voice_end()
            elif now - t.get("quiet", 0) >= 1.28:   # one 20 KB piece of quiet at a time
                t["quiet"] = now
                t["stream"].send_chunk(bytes(CHUNK_MAX))
        elif now - t["last"] > 3 or now - t["began"] > 15:
            voice_end()


async def phone(reader, writer):
    """Text, keyboard keys and voice from the phone page, through tailscale serve."""
    try:
        head = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 10)
        lines = head.decode("latin-1").split("\r\n")
        method, path, _ = lines[0].split(" ", 2)
        headers = {k.strip().lower(): v.strip()
                   for k, v in (l.split(":", 1) for l in lines[1:] if ":" in l)}
        length = int(headers.get("content-length", "0"))
        what = path.rstrip("/").rsplit("/", 1)[-1]
        if method == "GET" and what == "state":
            # on: True/False as the TV last reported it; None when the relay has no link to the TV (unknown)
            return await answer(writer, 200, {"on": remote.is_on if ready.is_set() else None})
        if method != "POST" or what not in ("text", "key", "vstart", "vchunk", "vend") \
                or not 0 <= length <= CHUNK_MAX:
            return await answer(writer, 400, {"error": "bad_request"})
        body = await asyncio.wait_for(reader.readexactly(length), 30)
        global talk
        if what == "vchunk":   # no lock check per chunk: the session only opens while unlocked
            if talk is None or talk["over"]:
                return await answer(writer, 409, {"error": "voice_over"})
            if length % 2 or len(talk["pcm"]) + length > VOICE_MAX:
                voice_end()
                return await answer(writer, 409, {"error": "voice_over"})
            talk["stream"].send_chunk(body)
            talk["pcm"] += body
            talk["peak"] = max(talk["peak"], max((abs(int.from_bytes(body[i:i + 2], "little", signed=True))
                                                  for i in range(0, length, 2)), default=0))
            talk["last"] = asyncio.get_running_loop().time()
            return await answer(writer, 200, {"ok": True})
        if what == "vend":
            if talk is not None and not talk["over"] and not talk.get("closing"):
                talk["closing"] = asyncio.get_running_loop().time()   # the TV may still be listening
            return await answer(writer, 200, {"ok": True})
        status = await tv_status()
        if status is None:
            return await answer(writer, 502, {"error": "remote_fail"})
        if status["locked"]:
            return await answer(writer, 409, {"error": "remote_locked"})
        if not ready.is_set():
            return await answer(writer, 503, {"error": "remote_fail"})
        if what == "text":
            text = body.decode("utf-8").strip()
            if not text or len(text) > TEXT_MAX:
                return await answer(writer, 400, {"error": "text_len"})
            if all(c in "0123456789" for c in text):
                # Digits go as the remote's number keys: PIN screens (YouTube's, for one) take those
                # even where typed text can't reach.
                log.info("text: %d digits as number keys", len(text))
                for c in text:
                    remote.send_key_command("KEYCODE_" + c)
                    await asyncio.sleep(0.15)
                return await answer(writer, 200, {"ok": True})
            if status.get("showing") == "YouTube" and not os.path.exists(YOUTUBE_TYPING):
                # YouTube draws its own keyboard, so typed text can't reach it: open its search instead.
                log.info("text: %d chars as a YouTube search", len(text))
                remote.send_launch_app_command("https://www.youtube.com/results?search_query=" + quote(text))
                return await answer(writer, 200, {"ok": True})
            send_text(text)
        elif what == "key":
            key = body.decode().strip()
            if key not in ("DEL", "ENTER"):
                return await answer(writer, 400, {"error": "bad_request"})
            remote.send_key_command(key)
        else:
            voice_end()
            stream = await remote.start_voice()
            now = asyncio.get_running_loop().time()
            talk = {"stream": stream, "pcm": bytearray(), "peak": 0, "last": now, "began": now,
                    "over": False}
            asyncio.create_task(voice_watch(talk))
            log.info("voice: session %s open", stream.session_id)
        await answer(writer, 200, {"ok": True})
    except Exception as e:  # a bad request must never take the relay down
        log.info("phone: request failed: %s", e)
        try:
            await answer(writer, 502, {"error": "remote_fail"})
        except Exception:
            writer.close()


async def main():
    global remote, ready
    # Also to relay.log beside this file: the user journal is not kept on every Pi.
    from logging.handlers import RotatingFileHandler
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s", handlers=[
        logging.StreamHandler(), RotatingFileHandler(os.path.join(HERE, "relay.log"), maxBytes=200_000, backupCount=1)])
    remote = AndroidTVRemote("PiNAS remote relay",
                             os.path.join(HERE, "cert.pem"), os.path.join(HERE, "key.pem"), TV,
                             enable_voice=True)   # off by default: the TV then never opens a voice session
    ready = asyncio.Event()
    asyncio.create_task(keep_connected())
    server = await asyncio.start_server(handle, BIND, PORT)
    log.info("listening on %s:%d for %s", BIND, PORT, TV)
    if PAGE:
        # localhost only: tailscale serve is the one client
        await asyncio.start_server(page, "127.0.0.1", PAGE_PORT)
        log.info("phone page on 127.0.0.1:%d -> %s", PAGE_PORT, PAGE)
        await asyncio.start_server(phone, "127.0.0.1", PHONE_PORT)
        log.info("text, keys and voice on 127.0.0.1:%d", PHONE_PORT)
    async with server:
        await server.serve_forever()


asyncio.run(main())
