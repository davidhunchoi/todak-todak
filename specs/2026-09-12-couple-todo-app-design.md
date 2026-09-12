# [설계 사양서] 컴맹 아내와 가족을 위한 미니멀 1:1 투두 & 매일 루틴 안드로이드 앱 (FamilySpace Todo)

**작성일**: 2026-09-12 (개정 1차)  
**작성자**: 허니 오빠 & Antigravity  
**상태**: 기획 및 확장 아키텍처 브레인스토밍 완료 (설계 확정)

---

## 1. 프로젝트 배경 및 해결 과제

### 1.1 배경 및 문제 정의
* **사용자 특성**: 
  * 아내분은 스마트폰을 항상 손에 쥐고 있으나 디지털/복잡한 IT 기능(계정 가입, 복잡한 메뉴 등)에 취약한 컴맹 성향이며, 타이핑을 극도로 꺼림.
  * 나이가 들면서 아침 약 복용 여부 등 일상적인 할 일을 자주 깜빡하고, 이미 약을 먹었는지 안 먹었는지조차 헷갈려 불안해함.
  * 떨어져 사시는 부모님(어머니)의 건강/약 복용 챙기기, 혹은 아내분이 친구와 약속/할 일을 공유하고 싶어 하는 확장 요구 발생.
* **기존 투두 앱들의 실패 요인 (냉정한 분석)**:
  * 회원가입/로그인 강제로 인한 초기 진입 실패.
  * 복잡한 수치, D-Day 카운트, 세부 설정 등으로 인한 시각적/심리적 업무 피로도 유발.
  * 잦은 알람으로 인한 알림 권한 차단 또는 앱 삭제 유발.
  * 다중 사용자 환경에서 "지금 내가 누구 방에 글을 쓰는지" 헷갈려 발생하는 오발송/혼선 실수.

### 1.2 핵심 솔루션 원칙
1. **극단적 미니멀리즘 & 스마트 적응형 UI (Adaptive UI)**:
   * 방이 1개일 때는 탭이나 메뉴조차 없이 1초 만에 단일 화면으로 진입 (컴맹 완전 맞춤).
   * 방이 2개 이상일 때만 상단에 큼직한 알약 모양 방 전환 탭 표시.
2. **시각적 혼선 방지 (1-Tap 고유 테마 배경색)**:
   * 방마다 고유한 파스텔 배경색(코랄 핑크, 샐비어 그린, 스카이 블루 등)을 부여하여 텍스트를 읽기 전에 색상으로 상대방을 0.1초 만에 인지.
3. **앱 무실행(Zero-Open) 액션**:
   * 앱을 실행하지 않고도 **홈 화면 대형 위젯**과 **상단바 고정 알림**에서 원터치로 체크 완료.
4. **무계정 6자리 일회성 코드 페어링 (Multi-tenant 완벽 격리)**:
   * 회원가입 없이 6자리 코드(`H7-9K2`, 20억 개 이상 조합)로 1초 만에 연결되며, 연결 즉시 코드가 만료(잠금)되어 10만 쌍의 가족이 동시에 써도 데이터 100% 분리.
5. **매일 약 복용 안심 루틴**:
   * 버튼 터치 즉시 `"오늘 오전 08:25 복용 완료"` 시간 기록 및 자정(00:00) 자동 리셋.
6. **원터치 한국어 음성 입력**:
   * 타이핑 대신 마이크 버튼 터치 후 말하면 텍스트 자동 등록.

---

## 2. 시스템 아키텍처 & 다중 1:1 스페이스 (Multi-Space)

모든 사용자는 동등한 권한을 가지며, 누구나 원하는 상대(배우자, 부모님, 친구 등)와 독립된 1:1 스페이스(방)를 자유롭게 생성/참여할 수 있습니다.

```
       [허니 오빠] ──── (스페이스 A: 핑크) ──── [아내분] ──── (스페이스 C: 블루) ──── [아내의 친구]
            │
      (스페이스 B: 그린)
            │
        [어머니]
```

### 2.1 기술 스택
* **플랫폼**: Android (Min SDK: 26, Target SDK: 34)
* **언어 & UI 프레임워크**: Kotlin, Jetpack Compose, Material3
* **홈 화면 위젯**: Jetpack Glance (Compose 기반 네이티브 앱 위젯)
* **백엔드 & 데이터 동기화**: 
  * Firebase Anonymous Authentication (무계정 자동 UID 발급)
  * Firebase Firestore (실시간 스냅샷 리스너 동기화 + 로컬 오프라인 캐시)
* **로컬 설정 저장**: Jetpack DataStore Preferences (현재 선택된 spaceId, 사용자 로컬 프로필)
* **백그라운드 작업 & 알림**: Android WorkManager, NotificationManager
* **음성 인식 (STT)**: Android 내장 `android.speech.SpeechRecognizer`

---

## 3. 6자리 일회성 페어링 & 방 생성 메커니즘

### 3.1 페어링 흐름
1. **익명 자동 인증**: 앱 최초 구동 시 백그라운드에서 Firebase Anonymous Auth를 통해 기기 고유 익명 UID 자동 발급.
2. **새 스페이스 개설자**:
   * `[새 방 만들기]` 클릭 → 방 이름(예: "우리 부부", "엄마와 나") 입력 및 **테마 컬러(5종 파스텔)** 선택.
   * 6자리 난수 코드(예: `H7-9K2`) 생성 및 Firestore `invites/{code}`에 10분 유효 제한으로 등록.
3. **초대 참여자**:
   * `[초대 코드 입력]` 클릭 → 6자리 코드 입력 후 [연결] 탭.
   * Firestore에서 일치하는 초대 코드를 확인하고 스페이스 멤버로 등록 후, **해당 초대 코드는 즉시 소멸(Delete/Expired)**.
4. **결과**:
   * 초대 코드가 즉시 잠기므로 제3자 침입이나 번호 충돌이 원천 불가능함.
   * 각자의 폰 로컬 DataStore에 참여 중인 `spaceIds` 목록이 영구 보관됨.

---

## 4. 데이터 모델 설계

### 4.1 Firestore 구조
```
users/ (collection)
  └── {uid}/ (document)
        ├── nickname: String
        └── joinedSpaceIds: List<String>

spaces/ (collection)
  └── {spaceId}/ (document)
        ├── title: String                    // 예: "우리 부부"
        ├── themeColor: String               // "CORAL", "GREEN", "BLUE", "YELLOW", "LAVENDER"
        ├── memberUids: List<String>         // [uidA, uidB]
        ├── createdAt: Long
        ├── tasks/ (sub-collection)
        │     └── {taskId}/ (document)
        └── routines/ (sub-collection)
              └── {routineId}/ (document)

invites/ (collection)
  └── {inviteCode}/ (document)
        ├── spaceId: String
        ├── createdBy: String
        ├── expiresAt: Long
```

### 4.2 할 일 모델 (`Task`)
```kotlin
data class Task(
    val id: String = "",
    val spaceId: String = "",
    val title: String = "",               // 예: "세탁소 정장 찾기"
    val dueDate: String = "",             // "YYYY-MM-DD"
    val isCompleted: Boolean = false,     // 완료 여부
    val completedAt: Long? = null,        // 완료 시점 타임스탬프
    val createdBy: String = "",           // 작성자 UID
    val createdAt: Long = System.currentTimeMillis()
)
```

### 4.3 매일 반복 루틴 모델 (`DailyRoutine`)
```kotlin
data class DailyRoutine(
    val id: String = "",
    val spaceId: String = "",
    val title: String = "",               // 예: "아침 혈압약/영양제 먹기"
    val iconType: String = "PILL",        // "PILL", "WATER", "WALK" 등
    val targetTime: String = "08:30",     // 권장 시간 (참고용)
    val lastCompletedDate: String = "",   // "YYYY-MM-DD" (마지막 완료일자)
    val lastCompletedTime: String = "",   // "오전 08:25" (마지막 완료 시각 - 핵심 안심 기능!)
    val isDoneToday: Boolean = false      // 오늘 완료 여부 (자정에 false로 리셋)
)
```

---

## 5. UI/UX 상세 명세 (시각적 혼선 방지 & 컴맹 특화)

### 5.1 스페이스별 1-Tap 고유 테마 컬러 시스템 (상대방 혼선 완벽 방지)
방마다 고유한 감성 파스텔 톤을 지정하여 시각적으로 즉시 분별:
* 🌸 **로맨틱 코랄 (부부 추천)**: `Background: #FFF3E0 / #FFEBEE`, `Card: #FFFFFF`, `Accent: #FF8A80`
* 🌿 **힐링 샐비어 그린 (부모님 추천)**: `Background: #F1F8E9`, `Card: #FFFFFF`, `Accent: #81C784`
* 🫐 **산뜻 스카이 블루 (친구 추천)**: `Background: #E1F5FE`, `Card: #FFFFFF`, `Accent: #4FC3F7`
* 🍋 **화사 크림 옐로우**: `Background: #FFFDE7`, `Card: #FFFFFF`, `Accent: #FFF176`
* 🍇 **포근 소프트 라벤더**: `Background: #F3E5F5`, `Card: #FFFFFF`, `Accent: #BA68C8`

### 5.2 스마트 적응형 헤더 (Adaptive Header)
* **방이 1개일 때 (아내 초기 화면 / 어머니 화면)**:
  * 상단에 탭이나 설정 없이 `[ 오늘 우리 집 할 일 ]` 또는 상대방 이름만 크게 노출.
* **방이 2개 이상일 때 (허니 오빠 화면 / 다중 사용자)**:
  * 상단에 큼직한 알약 탭 제공: `[ 🏠 아내 (코랄색) ]` `[ 👵 엄마 (그린색) ]`
  * 탭을 탭하면 화면 전체 배경색과 카드 악센트가 즉시 해당 방의 테마 컬러로 부드럽게 전환.

### 5.3 홈 화면 대형 위젯 (Glance AppWidget)
* **크기**: 4x2 또는 4x3 영역
* **상단 루틴 빠른 체크**:
  * `[💊 아침 약 먹기]` 버튼
  * 미완료 시: 강조 버튼 `[먹었어요! 탭]`
  * 완료 시: `[✅ 오전 08:25 복용 완료]`
* **할 일 리스트**:
  * 마감 임박/기한 초과 할 일 최대 3~4개 노출.
  * 다중 방 사용자의 경우 카드 옆에 작은 컬러 뱃지(`[아내]`, `[엄마]`) 부착.
  * 위젯에서 체크박스 터치 시 앱 실행 없이 Firestore 실시간 반영.
* **하단 우측**: `[+]` 원형 버튼 → 초간단 입력 화면으로 인텐트 실행.

### 5.4 상단바 조용한 고정 알림 (Ongoing Notification)
* **특성**: 무음, 소리/진동 없음, 지속 상주.
* **표시 내용**:
  * `📌 [오늘 할 일 2개] 세탁소 정장 찾기, 관리비 납부`
  * `💊 아침 약: 오전 08:25 복용 완료 ✅`
* **액션 버튼**: 알림창 내에서 `[약 복용 체크]`, `[할일 완료]` 즉시 실행 가능.

### 5.5 컴맹 아내 맞춤형 초간단 입력 화면
1. **원터치 한국어 음성 입력 (🎙️)**:
   * 마이크 아이콘 터치 → 말씀하시면 구글 SpeechRecognizer가 한글 문장 인식 후 제목 필드에 자동 입력.
2. **원터치 추천 일상 태그 칩**:
   * `[🛒 마트 장보기]`, `[🧺 세탁소]`, `[💊 병원/약]`, `[🗑️ 분리수거]`, `[🧹 청소]`, `[💳 공과금]`
3. **원터치 마감일 설정**:
   * `[ 오늘 ]`, `[ 내일 ]`, `[ 이번 주말 ]`, `[ 날짜 직접 선택 ]`

### 5.6 하루 2회 스마트 리마인더 (WorkManager)
* **오전 09:00 브리핑**: `"좋은 아침이에요! 오늘 챙길 일 {N}개가 있어요 ☀️"`
* **오후 18:00 저녁 확인**: 미완료 항목이 있는 경우에만 `"오늘 {N}개 남아있어요, 같이 털어내요! 🌙"`

---

## 6. 예외 상황 및 안정성 설계 (기술 분석)

| 예외 시나리오 | 대응 설계 |
|---|---|
| **초대 코드 중복/탈취** | 6자리 영문+숫자 난수 조합(20억 개) 사용 및 1회 바인딩 즉시 코드 영구 소멸. |
| **방 혼선으로 인한 실수** | 각 방별 독자적인 파스텔 배경색 & 테마 컬러 강제 적용으로 시각적 오발송 원천 차단. |
| **인터넷 단절 (마트/지하)** | Firestore 로컬 오프라인 캐시 활성화. 단절 중에도 즉시 체크되며 복구 시 자동 동기화. |
| **스마트폰 재부팅** | `BOOT_COMPLETED` 리시버로 상단바 고정 알림 및 위젯, WorkManager 리마인더 자동 재가동. |
| **실수로 완료를 눌렀을 때** | 완료 직후 5초간 `[방금 완료 취소]` 스낵바 제공 및 완료 목록에서 언제든 체크 해제 가능. |

---

## 7. 구현 로드맵 (Next Steps)
1. Android Studio 프로젝트 뼈대 구성 (Jetpack Compose, Glance, Firebase 의존성 설정)
2. Firebase Anonymous Auth 및 6자리 일회성 초대 코드/스페이스 관리 모듈 구현
3. 다중 스페이스별 테마 컬러 시스템 및 컴포즈 뷰모델 구현
4. 할 일(`Task`) & 매일 루틴(`DailyRoutine`) 실시간 동기화 구현
5. Jetpack Glance 홈 위젯 및 Ongoing 알림 서비스 연동
6. 한국어 음성 인식(STT) 및 1-Tap 태그 입력 UI 구현
7. APK 디바이스 빌드 및 부부 폰 연동 테스트
