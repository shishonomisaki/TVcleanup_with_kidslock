import asyncio, sys
from androidtvremote2 import AndroidTVRemote

async def main():
    r = AndroidTVRemote("PiNAS remote relay", "cert.pem", "key.pem", sys.argv[1])
    await r.async_connect()
    print("CONNECTED", flush=True); await asyncio.sleep(2)
    for k in sys.argv[2:]:
        r.send_key_command(k)
        await asyncio.sleep(0.8)
    r.disconnect()
    print("SENT", sys.argv[2:], flush=True)

asyncio.run(main())
