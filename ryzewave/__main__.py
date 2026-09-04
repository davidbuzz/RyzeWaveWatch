import asyncio, sys
from .cli import main
asyncio.run(main(sys.argv[1:]))
