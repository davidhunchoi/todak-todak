import os
import sys
from pathlib import Path

# server 디렉터리를 파이썬 모듈 검색 경로(sys.path)에 추가
server_dir = Path(__file__).resolve().parent.parent / "server"
if str(server_dir) not in sys.path:
    sys.path.append(str(server_dir))

from app import app
