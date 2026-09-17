"""
Launcher for Manga Panel Search Web Application.
"""

import argparse
import uvicorn

from backend.app import app

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Manga Panel Search Web Server")
    parser.add_argument("--host", type=str, default="0.0.0.0", help="Host IP")
    parser.add_argument("--port", type=int, default=8088, help="Port number (default: 8088)")
    args = parser.parse_args()

    print("=====================================================")
    print("  Manga Panel Search (Material Design 3)")
    print(f"  Server starting at: http://127.0.0.1:{args.port} ({args.host}:{args.port})")
    print("=====================================================")
    uvicorn.run(app, host=args.host, port=args.port)
