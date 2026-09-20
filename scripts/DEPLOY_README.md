# 🚀 APK 배포 (GitHub Release)

카톡은 `.apk` 파일 첨부를 차단하므로, **GitHub Release에 APK를 올려 "링크"로 배포**합니다.
링크가 열리면 브라우저에서 설치 파일이 바로 다운로드됩니다.

## 배포 링크 (고정)
```
https://github.com/davidhunchoi/todak-todak/releases/latest/download/app-release.apk
```
앱/서버 코드에도 이 링크가 등록되어 있습니다 (서버 `/api/version`의 `apk_url`).

## ⚠️ 서명 키 규칙 (반드시 지킬 것)

안드로이드는 **같은 패키지라도 서명 키가 다르면 덮어설치를 거부**합니다.
그 결과 사용자 화면에는 **"앱이 설치되지 않았습니다"** 만 표시됩니다.

| 시기 | 배포 자산 | 실제 서명 키 | 결과 |
|---|---|---|---|
| v1.2.0 ~ v1.3.0 | `app-debug.apk` | CI 러너의 debug 키(실행마다 달라짐) | 서로 업데이트 불가 |
| v1.3.1 ~ v1.3.2 | `app-release.apk` (이름만 release) | debug 키 | 서로 업데이트 불가 |
| **v1.3.3 ~** | `app-release.apk` | **고정 release 키(동일)** | ✅ 정상 업데이트 |

- **항상 `assembleRelease` + 고정 release 키로만** 배포합니다.
  - 필요 환경변수: `KEYSTORE_PATH` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
  - GitHub Actions는 Secrets(`ANDROID_KEYSTORE_BASE64` 등)를 사용하며,
    **키가 없으면 워크플로우가 실패하도록** 설정되어 있습니다(사고 재발 방지).
- debug 서명 APK를 배포하면 **기존 설치와 충돌해 아무도 업데이트할 수 없습니다.**
- 배포 후 서명 확인:
  ```bat
  %LOCALAPPDATA%\Android\Sdk\build-tools\34.0.0\apksigner.bat verify --print-certs app-release.apk
  ```
  `CN=TodakTodak, OU=Mobile, O=Honey, L=Seoul, ST=Seoul, C=KR` 이 보이면 정상입니다.

---

## 📱 앱 재설치 후 데이터 복구 (기기 재연결)

앱을 삭제/재설치하면 기기 로컬 `user_id`가 새로 발급되어
**1:1 방(2명 제한)** 에 다시 참여할 수 없게 됩니다(초대 코드 발급도 거부됨).
이때는 **기기 재연결 코드**로 옛 멤버 자리를 새 기기로 이전합니다
(방·카드·루틴 + 사람별 완료 체크 기록까지 그대로 복구).

```bat
:: 준비: 서버와 동일한 RELINK_TOKEN 환경변수
set RELINK_TOKEN=비밀토큰
python scripts\relink_device.py list
python scripts\relink_device.py code <space_id> <상대(방을 만들지 않은 쪽) user_id>
```
1. `list` → 대상 방의 `space_id` 와 **상대(방 만든이가 아닌 쪽)** 의 `user_id` 확인
2. `code` → 4자리 재연결 코드 발급 (30분 유효)
3. 재설치한 폰: 앱 → **[초대 코드 입력]** 에 그 4자리 입력 → 기존 방으로 복구
4. 양쪽 폰을 모두 재설치한 비상 상황이면:
   `python scripts\relink_device.py remove <space_id> <user_id>` 로 옛 자리를 지운 뒤
   일반 초대 코드로 다시 연결합니다.

> 서버 환경변수 `RELINK_TOKEN` 이 없으면 재연결 관리자 API는 전부 403으로 차단됩니다.


---

## 방법 A — GitHub Actions 자동 빌드/배포 (권장, 자동화 완성형)

1. 이 저장소를 GitHub에 푸시합니다 (`.github/workflows/release.yml` 포함).
2. **수동 실행**: GitHub → Actions → *Build & Release APK* → **Run workflow** → 버전 `v1.2.0` 입력.
   또는 **자동**: 릴리스 태그를 푸시하면 자동 실행됩니다.
   ```
   git tag v1.2.0
   git push origin v1.2.0
   ```
3. Actions가 APK를 빌드해 `v1.2.0` Release에 첨부합니다. 끝.

> 참고: Actions 빌드가 Android SDK 34.0.0 설치에 실패하면,
> 워크플로우의 `android-version: '34.0.0'` 값을 `'latest'`로 바꿔보세요 (app/build.gradle.kts의 `compileSdk`와 일치시키면 됨).

---

## 방법 B — 로컬 빌드 결과를 즉시 업로드 (gh CLI 또는 토큰 필요)

```bat
:: 준비물: Python 3 + (gh CLI 로그인 또는 GITHUB_TOKEN)
scripts\deploy.bat v1.2.0
:: 또는
python scripts\deploy.py v1.2.0
```

- gh CLI 설치: https://cli.github.com/ → `gh auth login`
- 또는 GitHub → Settings → Developer settings → **Personal access tokens (classic)**
  → 토큰 생성 시 **`repo`** 권한 선택 → 환경변수 `GITHUB_TOKEN`에 저장.

---

## 배포 후 확인
1. https://github.com/davidhunchoi/todak-todak/releases 에 `v1.2.0`과 `app-release.apk` 확인
2. 스마트폰에서 배포 링크 접속 → APK 다운로드 → 설치
   (Android가 "출처를 알 수 없는 앱" 경고 시 → 설정에서 허용)
3. Render 서버도 재배포 (서버의 최신 버전 정보인 `/api/version` 값 변경 반영)
   - 서버 재배포 전에는 구버전과 신버전이 섞여 동작하므로, 가능하면 서버 → 앱 순서로 배포