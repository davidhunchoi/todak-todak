-- FamilySpace Todo - Turso (SQLite) 데이터베이스 스키마

-- 1. 1:1 공유 스페이스 테이블
CREATE TABLE IF NOT EXISTS spaces (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    theme_color TEXT NOT NULL DEFAULT 'CORAL',
    created_by TEXT,
    created_at INTEGER NOT NULL
);

-- 2. 스페이스 멤버 매핑 테이블 (1:1 매핑)
CREATE TABLE IF NOT EXISTS space_members (
    space_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    joined_at INTEGER NOT NULL,
    PRIMARY KEY (space_id, user_id),
    FOREIGN KEY (space_id) REFERENCES spaces(id) ON DELETE CASCADE
);

-- 3. 일회성 4자리 숫자 초대 코드 테이블 (참여 즉시 자동 삭제)
CREATE TABLE IF NOT EXISTS invites (
    code TEXT PRIMARY KEY,
    space_id TEXT NOT NULL,
    created_by TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    FOREIGN KEY (space_id) REFERENCES spaces(id) ON DELETE CASCADE
);

-- 4. 할 일(Task) 테이블
CREATE TABLE IF NOT EXISTS tasks (
    id TEXT PRIMARY KEY,
    space_id TEXT NOT NULL,
    title TEXT NOT NULL,
    due_date TEXT,
    is_completed INTEGER NOT NULL DEFAULT 0,
    completed_at INTEGER,
    created_by TEXT,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (space_id) REFERENCES spaces(id) ON DELETE CASCADE
);

-- 5. 매일 반복 루틴(약 복용 등) 테이블
CREATE TABLE IF NOT EXISTS routines (
    id TEXT PRIMARY KEY,
    space_id TEXT NOT NULL,
    title TEXT NOT NULL,
    icon_type TEXT NOT NULL DEFAULT 'PILL',
    target_time TEXT DEFAULT '08:30',
    last_completed_date TEXT DEFAULT '',
    last_completed_time TEXT DEFAULT '',
    created_at INTEGER NOT NULL,
    FOREIGN KEY (space_id) REFERENCES spaces(id) ON DELETE CASCADE
);

-- 6. 루틴 완료 체크 (사람별 기록: 남편/아내 각각 체크)
CREATE TABLE IF NOT EXISTS routine_checks (
    routine_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    completed_date TEXT NOT NULL,
    completed_time TEXT NOT NULL,
    checked_at INTEGER NOT NULL,
    PRIMARY KEY (routine_id, user_id),
    FOREIGN KEY (routine_id) REFERENCES routines(id) ON DELETE CASCADE
);

-- 7. 방 삭제 동의 요청 (모든 멤버 동의 시 방 삭제)
CREATE TABLE IF NOT EXISTS space_delete_requests (
    space_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    requested_at INTEGER NOT NULL,
    PRIMARY KEY (space_id, user_id)
);

-- 8. 기기 재연결 코드 (앱 재설치/기기 교체 시 기존 멤버 자리로 복구)
--    - 재설치하면 기기 로컬 user_id가 새로 생성되므로, 옛 user_id 자리를 새 user_id로 이전한다.
--    - 코드는 4자리 숫자이며 30분간 유효하고, 사용 즉시 소멸한다.
CREATE TABLE IF NOT EXISTS relink_codes (
    code TEXT PRIMARY KEY,
    space_id TEXT NOT NULL,
    old_user_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    FOREIGN KEY (space_id) REFERENCES spaces(id) ON DELETE CASCADE
);

-- 인덱스 생성
CREATE INDEX IF NOT EXISTS idx_tasks_space ON tasks(space_id, is_completed);
CREATE INDEX IF NOT EXISTS idx_routines_space ON routines(space_id);
CREATE INDEX IF NOT EXISTS idx_members_user ON space_members(user_id);
CREATE INDEX IF NOT EXISTS idx_relink_space ON relink_codes(space_id);
