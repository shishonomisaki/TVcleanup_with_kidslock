# One-time pairing with the TV. Starts pairing (the TV shows a 6-character code),
# then waits for the code to appear in ./pair-code and finishes pairing.
import asyncio, os, sys
from androidtvremote2 import AndroidTVRemote

TV = sys.argv[1]

async def main():
    r = AndroidTVRemote("PiNAS remote relay", "cert.pem", "key.pem", TV)
    if await r.async_generate_cert_if_missing():
        print("new cert", flush=True)
    await r.async_start_pairing()
    print("PAIRING_STARTED: code is on the TV", flush=True)
    for _ in range(300):
        if os.path.exists("pair-code"):
            code = open("pair-code").read().strip()
            os.remove("pair-code")
            await r.async_finish_pairing(code)
            print("PAIRED", flush=True)
            return
        await asyncio.sleep(1)
    print("TIMEOUT", flush=True)

asyncio.run(main())
