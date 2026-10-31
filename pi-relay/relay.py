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

    POST /pi/text    body: UTF-8 text, 200 characters at most
    POST /pi/key     body: DEL or ENTER
    POST /pi/voice   body: 16-bit PCM, mono, 8000 Hz, 15 s at most
"""
import asyncio
import hmac
import json
import logging
import os
import sys

from androidtvremote2 import AndroidTVRemote, CannotConnect, ConnectionClosed, InvalidAuth

BIND = os.environ["RELAY_BIND"]          # the Pi's LAN address
PORT = int(os.environ.get("RELAY_PORT", "6480"))
TV = os.environ["RELAY_TV"]              # the TV's LAN address; the only client allowed
HERE = os.path.dirname(os.path.abspath(__file__))
PAGE = os.environ.get("RELAY_PAGE")     # "<tv 100.x>:<page port>", optional
PAGE_PORT = int(os.environ.get("RELAY_PAGE_PORT", "6481"))
PHONE_PORT = int(os.environ.get("RELAY_PHONE_PORT", "6482"))
TEXT_MAX = 200
VOICE_MAX = 15 * 16000                   # 15 s of 8 kHz 16-bit mono
VOICE_CHUNK = 8 * 1024                   # the smallest chunk the TV takes, 0.256 s
TOKEN = open(os.path.join(HERE, "token")).read().strip().encode()

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
    remote.keep_reconnecting()
    await asyncio.sleep(2)   # the TV drops the first key sent right after connecting
    ready.set()
    log.info("connected to the TV")


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


async def page(reader, writer):
    """Pass one connection from tailscale serve through to the TV's phone page."""
    host, port = PAGE.rsplit(":", 1)
    try:
        tv_reader, tv_writer = await asyncio.wait_for(asyncio.open_connection(host, int(port)), 5)
    except (OSError, asyncio.TimeoutError) as e:
        log.info("page: TV not reachable (%s)", e)
        writer.close()
        return
    await asyncio.gather(pipe(reader, tv_writer), pipe(tv_reader, writer))


async def tv_locked():
    """Ask the phone page; None when it can't be reached."""
    host, port = PAGE.rsplit(":", 1)
    try:
        r, w = await asyncio.wait_for(asyncio.open_connection(host, int(port)), 5)
        w.write(b"GET /api/status HTTP/1.0\r\n\r\n")
        await w.drain()
        data = await asyncio.wait_for(r.read(), 5)
        w.close()
        return bool(json.loads(data.split(b"\r\n\r\n", 1)[1])["locked"])
    except Exception as e:
        log.info("phone: status failed (%s)", e)
        return None


async def answer(writer, code, obj):
    body = json.dumps(obj).encode()
    writer.write(b"HTTP/1.1 %d X\r\nContent-Type: application/json\r\nContent-Length: %d\r\n"
                 b"Cache-Control: no-store\r\nConnection: close\r\n\r\n%s" % (code, len(body), body))
    await writer.drain()
    writer.close()


async def voice(pcm):
    pcm += bytes(8000)                    # half a second of quiet, so the TV hears the end
    async with await remote.start_voice() as stream:
        for i in range(0, len(pcm), VOICE_CHUNK):
            stream.send_chunk(pcm[i:i + VOICE_CHUNK])
            await asyncio.sleep(VOICE_CHUNK / 16000 * 0.9)   # about as fast as it was spoken


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
        if method != "POST" or what not in ("text", "key", "voice") or not 0 < length <= VOICE_MAX:
            return await answer(writer, 400, {"error": "bad_request"})
        body = await asyncio.wait_for(reader.readexactly(length), 30)
        locked = await tv_locked()
        if locked is None:
            return await answer(writer, 502, {"error": "remote_fail"})
        if locked:
            return await answer(writer, 409, {"error": "remote_locked"})
        if not ready.is_set():
            return await answer(writer, 503, {"error": "remote_fail"})
        if what == "text":
            text = body.decode("utf-8").strip()
            if not text or len(text) > TEXT_MAX:
                return await answer(writer, 400, {"error": "text_len"})
            remote.send_text(text)
        elif what == "key":
            key = body.decode().strip()
            if key not in ("DEL", "ENTER"):
                return await answer(writer, 400, {"error": "bad_request"})
            remote.send_key_command(key)
        else:
            if len(body) < 8000 or len(body) % 2:
                return await answer(writer, 400, {"error": "voice_short"})
            await voice(body)
        await answer(writer, 200, {"ok": True})
    except Exception as e:  # a bad request must never take the relay down
        log.info("phone: request failed: %s", e)
        try:
            await answer(writer, 502, {"error": "remote_fail"})
        except Exception:
            writer.close()


async def main():
    global remote, ready
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    remote = AndroidTVRemote("PiNAS remote relay",
                             os.path.join(HERE, "cert.pem"), os.path.join(HERE, "key.pem"), TV)
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
