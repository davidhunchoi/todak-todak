#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
토닥토닥(TodakTodak) 기기 재연결(relink) 관리 스크립트

용도
  앱을 삭제/재설치하면 기기 로컬 user_id 가 새로 발급되어,
  1:1 방(2명 제한)에 다시 참여할 수 없게 된다.
  이 스크립트는 옛 멤버 자리를 새 기기로 이전(승계)해
  방/카드/루틴 + 사람별 완료 체크 기록까지 그대로 복구한다.

준비물
  - 서버 환경변수 RELINK_TOKEN (Vercel/Render 환경변수에 등록)
  - 로컬 환경변수 RELINK_TOKEN (같은 값), 필요 시 TODAK_BASE_URL

사용법
  set RELINK_TOKEN=비밀토큰
  python scripts/relink_device.py list
  python scripts/relink_device.py members <space_id>
  python scripts/relink_device.py code <space_id> <old_user_id>
  python scripts/relink_device.py relink <space_id> <old_user_id> <new_user_id>
  python scripts/relink_device.py remove <space_id> <user_id>

복구 절차 (권장)
  1) list 로 대상 방(space_id)과 '상대(방을 만들지 않은 쪽)' user_id 확인
  2) code <space_id> <상대 user_id> → 4자리 재연결 코드 발급(30분 유효)
  3) 재설치한 폰에서 앱 → [초대 코드 입력] → 코드 입력 → 기존 방 그대로 복구
"""
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

DEFAULT_BASE_URL = "https://todak-todak-ruby.vercel.app"
KST = timezone(timedelta(hours=9))

# Windows 콘솔(cp949)에서도 안전하게 한글/기호를 출력하도록 표준출력을 UTF-8 로 재설정
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def log(*args):
    print("[relink]", *args)


def base_url():
    return (os.environ.get("TODAK_BASE_URL") or DEFAULT_BASE_URL).rstrip("/")


def token():
    t = os.environ.get("RELINK_TOKEN")
    if not t:
        sys.exit(
            "[오류] RELINK_TOKEN 환경변수가 없습니다.\n"
            "  서버(Vercel/Render) 환경변수와 같은 값을 로컬에도 설정해 주세요.\n"
            "  예) set RELINK_TOKEN=비밀토큰"
        )
    return t


def api(method, path, body=None):
    url = base_url() + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, method=method, data=data)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Relink-Token", token())
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        try:
            msg = json.loads(detail).get("error", detail)
        except Exception:
            msg = detail
        sys.exit(f"[오류] HTTP {e.code}: {msg}")
    except urllib.error.URLError as e:
        sys.exit(f"[오류] 서버에 연결할 수 없습니다: {e}")


def fmt_ms(ms):
    if not ms:
        return "-"
    try:
        return datetime.fromtimestamp(int(ms) / 1000, KST).strftime("%Y-%m-%d %H:%M")
    except Exception:
        return str(ms)


def print_members(space):
    members = space.get("members") or []
    creator = space.get("created_by")
    for m in members:
        role = "방 만든이" if m.get("user_id") == creator else "상대(파트너)"
        log(f"   · user_id={m.get('user_id')}  [{role}]")
        log(f"       가입: {fmt_ms(m.get('joined_at'))} | 카드 작성: {m.get('tasks_created')}건"
            f" | 마지막 카드: {fmt_ms(m.get('last_task_at'))} | 루틴 체크: {m.get('routine_checks')}건")


def cmd_list():
    res = api("GET", "/api/admin/spaces")
    spaces = res.get("spaces") or []
    log(f"방 {len(spaces)}개 (base: {base_url()})")
    for s in spaces:
        log(f"- {s.get('title')} (space_id={s.get('space_id')}, 만든 날짜={fmt_ms(s.get('created_at'))})")
        print_members(s)
        if len(s.get("members") or []) >= 2:
            log("   -> 2명 모두 연결되어 있어 재참여가 막힌 상태입니다. 위 '상대' user_id 로 code 명령을 실행하세요.")
    if not spaces:
        log("등록된 방이 없습니다.")


def cmd_members(space_id):
    res = api("GET", f"/api/spaces/{space_id}/members")
    log(f"- {res.get('title')} (space_id={res.get('space_id')})")
    print_members(res)


def cmd_code(space_id, old_user_id):
    res = api("POST", f"/api/spaces/{space_id}/relink-code", {"old_user_id": old_user_id})
    log("기기 재연결 코드 발급 완료 (30분 유효)")
    log(f"  space_id   : {res.get('space_id')}")
    log(f"  old_user_id: {res.get('old_user_id')}")
    print(f"RELINK_CODE={res.get('relink_code')}")
    log(f"  -> 재연결 코드: {res.get('relink_code')}")
    log("  재설치한 폰에서 앱 → [초대 코드 입력]에 위 4자리를 입력하세요.")


def cmd_relink(space_id, old_user_id, new_user_id):
    res = api("POST", f"/api/spaces/{space_id}/relink", {
        "old_user_id": old_user_id,
        "new_user_id": new_user_id,
    })
    log(res.get("message") or "완료")


def cmd_remove(space_id, user_id):
    res = api("POST", f"/api/admin/spaces/{space_id}/remove-member", {"user_id": user_id})
    log(res.get("message") or "완료")
    log("현재 멤버: " + ", ".join(str(m.get("user_id")) for m in (res.get("members") or [])))


def main():
    args = sys.argv[1:]
    if not args:
        log(__doc__)
        return
    cmd = args[0]
    if cmd == "list":
        cmd_list()
    elif cmd == "members" and len(args) >= 2:
        cmd_members(args[1])
    elif cmd == "code" and len(args) >= 3:
        cmd_code(args[1], args[2])
    elif cmd == "relink" and len(args) >= 4:
        cmd_relink(args[1], args[2], args[3])
    elif cmd == "remove" and len(args) >= 3:
        cmd_remove(args[1], args[2])
    else:
        log(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main()
