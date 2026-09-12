#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
토닥토닥(TodakTodak) APK GitHub Release 배포 스크립트

사용법:
    python scripts/deploy.py v1.2.0

준비물 (둘 중 하나):
  1) GitHub CLI(gh) 설치 + `gh auth login`
  2) GITHUB_TOKEN 환경변수 (repo 권한의 Personal Access Token)

동작:
  - app/build/outputs/apk/debug/app-debug.apk 가 없으면 먼저 빌드
  - 지정 태그(v1.2.0)로 Release 생성(+실패 시 업데이트)하고
    app-debug.apk 를 첨부 (배포 링크가 고정됨)
"""
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import json

REPO = "davidhunchoi/todak-todak"
API = f"https://api.github.com/repos/{REPO}"
UPLOADS = f"https://uploads.github.com/repos/{REPO}"
APK_REL = os.path.join("app", "build", "outputs", "apk", "debug", "app-debug.apk")
DEFAULT_TAG = "v1.2.0"


def log(*args):
    print("[deploy]", *args)


def run(cmd):
    log("$", " ".join(cmd))
    return subprocess.call(cmd)


def build_if_missing():
    if os.path.exists(APK_REL):
        log(f"APK 발견: {APK_REL}")
        return
    log("APK가 없어 빌드를 시작합니다...")
    wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
    rc = run([wrapper, "assembleDebug", "--console=plain"])
    if rc != 0:
        sys.exit("빌드 실패. 먼저 ./gradlew assembleDebug 을 확인해 주세요.")
    if not os.path.exists(APK_REL):
        sys.exit(f"빌드 후에도 APK가 없습니다: {APK_REL}")


def api_request(method, url, token, body=None, content_type="application/json"):
    req = urllib.request.Request(url, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
        req.add_header("Content-Type", content_type)
    else:
        data = None
    try:
        with urllib.request.urlopen(req, data=data) as resp:
            return json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        return {"_http_error": e.code, "_detail": detail}


def main():
    tag = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_TAG
    if not tag.startswith("v"):
        tag = "v" + tag

    build_if_missing()

    # 1) gh CLI 우선 사용
    if shutil.which("gh"):
        log("gh CLI로 Release 생성/업데이트")
        rc = run(["gh", "release", "create", tag, APK_REL,
                  "--title", tag, "--generate-notes", "--latest"])
        if rc != 0:
            rc = run(["gh", "release", "upload", tag, APK_REL, "--clobber"])
            if rc != 0:
                sys.exit("gh release 조작 실패")
        log("배포 완료 →",
            f"https://github.com/{REPO}/releases/latest/download/app-debug.apk")
        return

    # 2) GITHUB_TOKEN 폴백
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not token:
        sys.exit(
            "[오류] gh CLI가 없고 GITHUB_TOKEN도 없습니다.\n"
            "  방법1: https://cli.github.com/ 에서 gh 설치 후 `gh auth login`\n"
            "  방법2: GitHub → Settings → Developer settings → Personal access tokens\n"
            "         (repo 권한) 토큰을 만들어 GITHUB_TOKEN 환경변수 설정"
        )

    # Release 조회 → 없으면 생성
    rel = api_request("GET", f"{API}/releases/tags/{tag}", token)
    release_id = rel.get("id")
    if not release_id:
        log(f"Release {tag} 생성 중...")
        rel = api_request("POST", f"{API}/releases", token, {
            "tag_name": tag,
            "name": tag,
            "generate_release_notes": True,
        })
        release_id = rel.get("id")
        if not release_id:
            sys.exit(f"Release 생성 실패: {rel}")

    # 기존 동일 asset 제거 후 업로드
    assets = rel.get("assets") or []
    for a in assets:
        if a.get("name") == "app-debug.apk":
            api_request("DELETE", f"{API}/releases/assets/{a['id']}", token)
            log("기존 app-debug.apk 제거 후 재업로드")

    with open(APK_REL, "rb") as f:
        apk_bytes = f.read()
    log(f"app-debug.apk ({len(apk_bytes)//1024}KB) 업로드 중...")
    result = api_request(
        "POST", f"{UPLOADS}/releases/{release_id}/assets?name=app-debug.apk",
        token, apk_bytes, content_type="application/vnd.android.package-archive",
    )
    if result.get("id"):
        log("배포 완료 →",
            f"https://github.com/{REPO}/releases/latest/download/app-debug.apk")
    else:
        sys.exit(f"업로드 실패: {result}")


if __name__ == "__main__":
    main()