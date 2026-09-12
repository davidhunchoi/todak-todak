# 🚀 APK 배포 (GitHub Release)

카톡은 `.apk` 파일 첨부를 차단하므로, **GitHub Release에 APK를 올려 "링크"로 배포**합니다.
링크가 열리면 브라우저에서 설치 파일이 바로 다운로드됩니다.

## 배포 링크 (고정)
```
https://github.com/davidhunchoi/todak-todak/releases/latest/download/app-debug.apk
```
앱/서버 코드에도 이 링크가 등록되어 있습니다 (서버 `/api/version`의 `apk_url`, 초대 메시지에 포함).

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
1. https://github.com/davidhunchoi/todak-todak/releases 에 `v1.2.0`과 `app-debug.apk` 확인
2. 스마트폰에서 배포 링크 접속 → APK 다운로드 → 설치
   (Android가 "출처를 알 수 없는 앱" 경고 시 → 설정에서 허용)
3. Render 서버도 재배포 (서버의 최신 버전 정보인 `/api/version` 값 변경 반영)
   - 서버 재배포 전에는 구버전과 신버전이 섞여 동작하므로, 가능하면 서버 → 앱 순서로 배포