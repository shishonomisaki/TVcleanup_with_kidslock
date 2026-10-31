"""TV remote relay: Parental Lock on the TV posts a key name here, and this sends it to the
TV's own remote service. The TV refuses remote connections that come from itself, so the
hop has to go through another machine.

Listens on the Pi's LAN address only, answers the TV's address only, and every request
carries the shared token. Keys outside KEYS are refused.

    POST /key   header X-Token: <token>   body: DPAD_UP
"""
import asyncio
import hmac
import logging
import os
import sys

from androidtvremote2 import AndroidTVRemote, CannotConnect, ConnectionClosed, InvalidAuth

BIND = os.environ["RELAY_BIND"]          # the Pi's LAN address
PORT = int(os.environ.get("RELAY_PORT", "6480"))
TV = os.environ["RELAY_TV"]              # the TV's LAN address; the only client allowed
HERE = os.path.dirname(os.path.abspath(__file__))
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


async def main():
    global remote, ready
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    remote = AndroidTVRemote("PiNAS remote relay",
                             os.path.join(HERE, "cert.pem"), os.path.join(HERE, "key.pem"), TV)
    ready = asyncio.Event()
    asyncio.create_task(keep_connected())
    server = await asyncio.start_server(handle, BIND, PORT)
    log.info("listening on %s:%d for %s", BIND, PORT, TV)
    async with server:
        await server.serve_forever()


asyncio.run(main())
