import os
import uuid
import time
import secrets
import sqlite3
import traceback as _tb
from datetime import datetime
from zoneinfo import ZoneInfo
from flask import Flask, request, jsonify, send_file, redirect, render_template, send_from_directory
from flask_cors import CORS
from dotenv import load_dotenv

load_dotenv()

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
app = Flask(
    __name__,
    template_folder=os.path.join(BASE_DIR, "templates"),
    static_folder=os.path.join(BASE_DIR, "static")
)
CORS(app)


@app.errorhandler(500)
def handle_internal_error(e):
    """예상치 못한 서버 오류를 HTML 대신 JSON 으로 반환 (앱에서 사유를 안내할 수 있게)"""
    import traceback as _tb2
    _traceback = _tb2.format_exc()
    print(f"[500 ERROR] {e}\n{_traceback}", flush=True)
    # TODO: 배포 후 디버깅용 - 실제 서비스에서는 error 필드만 반환
    return jsonify({"error": "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
                    "detail": str(e), "traceback": _traceback}), 500

# 환경 변수 설정 (Turso 연동)
TURSO_DB_URL = os.getenv("TURSO_DATABASE_URL")
TURSO_AUTH_TOKEN = os.getenv("TURSO_AUTH_TOKEN")

# 기기 재연결(relink) 관리자 토큰.
# 앱 재설치/기기교체 시 옛 멤버 자리를 새 기기로 이전할 때만 사용하는 비밀 토큰.
# 환경변수 RELINK_TOKEN 이 없으면 재연결 관련 관리자 API는 전부 403 으로 차단된다(안전한 기본값).
RELINK_TOKEN = os.getenv("RELINK_TOKEN")

# 한국 시간대 (KST)
KST = ZoneInfo("Asia/Seoul")


def get_db():
    """
    Turso 클라우드 데이터베이스 연결 객체 반환.
    libsql-client 0.3.x: create_client_sync(url=..., auth_token=...) → SyncClient.
    - execute(stmt, params) 단건 + batch(statements) 일괄 실행 지원
    - 자동 커밋 (별도 commit/close 불필요, 있어도 무시)
    환경변수가 없을 경우 로컬 SQLite(local_dev.db)로 자동 폴백 지원.
    """
    if TURSO_DB_URL and TURSO_AUTH_TOKEN:
        import libsql_client
        url = TURSO_DB_URL.replace("libsql://", "https://")
        return libsql_client.create_client_sync(url=url, auth_token=TURSO_AUTH_TOKEN)
    else:
        # 로컬 테스트용 sqlite3
        import sqlite3
        conn = sqlite3.connect("local_dev.db")
        conn.row_factory = sqlite3.Row
        return conn


def _is_libsql_client(db) -> bool:
    return db.__class__.__module__.startswith("libsql")


def _q(db, stmt, params=None):
    """Turso(libsql-client 0.3.x) + sqlite3 공용 쿼리 실행 래퍼.

    libsql-client 0.3.x 의 SyncClient.execute() 는 파라미터를 tuple 이 아닌
    list 로 받을 때만 정상 동작하므로, 여기서 tuple → list 로 변환한다.
    (tuple 전달 시 내부 TypeError → Flask 500 → 앱에서 "방 만들기 실패" 로 표시됨)
    """
    if params is None:
        return db.execute(stmt)
    if isinstance(params, tuple):
        params = list(params)
    try:
        return db.execute(stmt, params)
    except KeyError as _ke:
        _err_detail = _debug_libsql_error(stmt, params)
        print(f"[SQL ERROR] KeyError('result') | stmt: {stmt[:200]} | params: {params} | libsql_detail: {_err_detail}", flush=True)
        raise LibsqlQueryError(str(_err_detail)) from _ke
    except Exception as _e:
        print(f"[SQL ERROR] {type(_e).__name__} on stmt: {stmt[:200]} | params: {params} | err: {_e}", flush=True)
        raise


class LibsqlQueryError(Exception):
    """libsql 클라이언트에서 실제 SQL 에러 메시지를 추출해 전달"""


def _debug_libsql_error(stmt, params):
    """libsql HTTP 프로토콜로 직접 쿼리 실행하여 실제 에러 메시지 추출"""
    if not (TURSO_DB_URL and TURSO_AUTH_TOKEN):
        return "No Turso config"
    try:
        import urllib.request as _ulreq
        import json as _json
        url = TURSO_DB_URL.replace("libsql://", "https://")
        args_list = []
        if params:
            for p in params:
                if p is None:
                    args_list.append({"null": True})
                elif isinstance(p, bool):
                    args_list.append({"bool_val": p})
                elif isinstance(p, int):
                    args_list.append({"int64_val": p})
                elif isinstance(p, float):
                    args_list.append({"real_val": p})
                else:
                    args_list.append({"string_val": str(p)})
        request_body = {
            "stmt": {
                "sql": stmt,
                "args": args_list,
                "named_args": [],
                "want_rows": True,
            }
        }
        req = _ulreq.Request(
            url.rstrip("/") + "/v2/pipeline",
            data=_json.dumps(request_body).encode("utf-8"),
            headers={"Authorization": f"Bearer {TURSO_AUTH_TOKEN}", "Content-Type": "application/json"},
            method="POST"
        )
        with _ulreq.urlopen(req, timeout=10) as resp:
            data = _json.loads(resp.read().decode("utf-8"))
            if isinstance(data, dict):
                if "error" in data:
                    return f"libsql error: {data['error']}"
                if "result" in data:
                    return "success (result present)"
                return f"unknown response: {str(data)[:200]}"
            if isinstance(data, list):
                for item in data:
                    if isinstance(item, dict) and "error" in item:
                        return f"libsql error: {item['error']}"
            return f"response: {str(data)[:300]}"
    except Exception as e:
        return f"debug request failed: {type(e).__name__}: {e}"


def _execute_statements(db, sql):
    """schema.sql 을 문장 단위로 실행 (Turso/libSQL 과 sqlite3 공통)

    - 각 문장의 앞부분 주석(-- ...) 제거 후 실행 (구버전 함수가 주석 행을
      그대로 Turso 에 보내 500 을 유발하던 문제 방지)
    - DROP 없이 CREATE TABLE/INDEX IF NOT EXISTS 만 허용 (데이터 보호)
    - Turso batch() 는 dict 형태 batch 를 요구하므로 사용하지 않고,
      _q() 로 문장별 실행. 한 문장 실패해도 나머지는 계속 적용.
    """
    statements = []
    for raw in sql.split(";"):
        lines = [ln for ln in raw.splitlines() if not ln.strip().startswith("--")]
        stmt = "\n".join(lines).strip()
        if not stmt:
            continue
        head = stmt[:40].upper()
        if not (head.startswith("CREATE TABLE") or head.startswith("CREATE INDEX")):
            print(f"[init_schema] 허용되지 않은 문장 건너뜀: {stmt[:60]}", flush=True)
            continue
        statements.append(stmt)
    for stmt in statements:
        try:
            _q(db, stmt)
        except Exception as e:
            print(f"[init_schema] 문장 실패 (무시): {e} :: {stmt[:80]}", flush=True)


def migrate_schema_columns(db):
    """기존 DB 테이블에 누락된 컬럼을 안전하게 자동 추가 (ALTER TABLE ... ADD COLUMN).
    CREATE TABLE IF NOT EXISTS 는 기존 테이블이 있으면 새 컬럼을 추가하지 못하므로,
    여기서 컬럼 존재 여부를 PRAGMA table_info 로 검사하여 보완한다.
    """
    migrations = [
        # (테이블, 컬럼명, ALTER 구문)
        ("spaces", "theme_color", "ALTER TABLE spaces ADD COLUMN theme_color TEXT NOT NULL DEFAULT 'CORAL'"),
        ("spaces", "created_by", "ALTER TABLE spaces ADD COLUMN created_by TEXT"),
        ("spaces", "created_at", "ALTER TABLE spaces ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0"),
        ("space_members", "joined_at", "ALTER TABLE space_members ADD COLUMN joined_at INTEGER NOT NULL DEFAULT 0"),
        ("invites", "created_by", "ALTER TABLE invites ADD COLUMN created_by TEXT"),
        ("invites", "expires_at", "ALTER TABLE invites ADD COLUMN expires_at INTEGER NOT NULL DEFAULT 0"),
        ("tasks", "created_by", "ALTER TABLE tasks ADD COLUMN created_by TEXT"),
        ("tasks", "due_date", "ALTER TABLE tasks ADD COLUMN due_date TEXT"),
        ("tasks", "alarm_time", "ALTER TABLE tasks ADD COLUMN alarm_time TEXT DEFAULT ''"),
        ("tasks", "has_alarm", "ALTER TABLE tasks ADD COLUMN has_alarm INTEGER NOT NULL DEFAULT 0"),
        ("routines", "icon_type", "ALTER TABLE routines ADD COLUMN icon_type TEXT NOT NULL DEFAULT 'PILL'"),
        ("routines", "target_time", "ALTER TABLE routines ADD COLUMN target_time TEXT DEFAULT '08:30'"),
        ("routines", "last_completed_date", "ALTER TABLE routines ADD COLUMN last_completed_date TEXT DEFAULT ''"),
        ("routines", "last_completed_time", "ALTER TABLE routines ADD COLUMN last_completed_time TEXT DEFAULT ''"),
    ]

    extra_tables = [
        """CREATE TABLE IF NOT EXISTS routine_checks (
            routine_id TEXT NOT NULL,
            user_id TEXT NOT NULL,
            completed_date TEXT NOT NULL,
            completed_time TEXT NOT NULL,
            checked_at INTEGER NOT NULL,
            PRIMARY KEY (routine_id, user_id),
            FOREIGN KEY (routine_id) REFERENCES routines(id) ON DELETE CASCADE
        )""",
        """CREATE TABLE IF NOT EXISTS space_delete_requests (
            space_id TEXT NOT NULL,
            user_id TEXT NOT NULL,
            requested_at INTEGER NOT NULL,
            PRIMARY KEY (space_id, user_id)
        )""",
        # 기기 재연결 코드 (앱 재설치/기기 교체 시 옛 멤버 자리를 새 기기 user_id 로 이전)
        """CREATE TABLE IF NOT EXISTS relink_codes (
            code TEXT PRIMARY KEY,
            space_id TEXT NOT NULL,
            old_user_id TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL,
            FOREIGN KEY (space_id) REFERENCES spaces(id) ON DELETE CASCADE
        )""",
        "CREATE INDEX IF NOT EXISTS idx_relink_space ON relink_codes(space_id)",
    ]
    for tbl_sql in extra_tables:
        try:
            _q(db, tbl_sql)
        except Exception as e:
            print(f"[migration table] {e}", flush=True)

    results = {}
    for table_name, col_name, alter_stmt in migrations:
        try:
            info_res = _q(db, f"PRAGMA table_info({table_name})")
            existing_cols = []
            for row in _rows(info_res):
                cname = _row_get(row, "name", 1)
                if cname:
                    existing_cols.append(str(cname).lower())

            if col_name.lower() not in existing_cols:
                print(f"[migration] {table_name} 에 컬럼 {col_name} 추가 중...", flush=True)
                _q(db, alter_stmt)
                results[f"{table_name}.{col_name}"] = "added"
            else:
                results[f"{table_name}.{col_name}"] = "already_exists"
        except Exception as e:
            results[f"{table_name}.{col_name}"] = f"error: {e}"
            print(f"[migration error] {table_name}.{col_name}: {e}", flush=True)

    _db_commit(db)
    return results


def init_schema():
    """
    서버 시작 시 전체 스키마 적용 (모든 CREATE TABLE IF NOT EXISTS)
    - Turso(Render)든 로컬 SQLite든 호출되어, DB에 누락된 테이블이 있으면 자동 생성
    - 기존 데이터는 절대 건드리지 않음 (IF NOT EXISTS)
    """
    schema_path = os.path.join(os.path.dirname(__file__), "schema.sql")
    if not os.path.exists(schema_path):
        return
    with open(schema_path, "r", encoding="utf-8") as f:
        schema_sql = f.read()
    db = get_db()
    try:
        _execute_statements(db, schema_sql)
        migrate_schema_columns(db)
        if hasattr(db, "commit"):
            try:
                db.commit()
            except Exception:
                pass
    finally:
        if hasattr(db, "close"):
            try:
                db.close()
            except Exception:
                pass


def _db_close(db):
    try:
        if hasattr(db, "close"):
            db.close()
    except Exception:
        pass


def _db_commit(db):
    try:
        commit = getattr(db, "commit", None)
        if callable(commit):
            commit()
    except Exception:
        pass


def _rows(res):
    """libSQL(Turso) / sqlite3 실행 결과 → 행 리스트로 정규화"""
    if res is None:
        return []
    if hasattr(res, "fetchall"):
        try:
            return res.fetchall() or []
        except Exception:
            return []
    return getattr(res, "rows", []) or []


def _one(res):
    rows = _rows(res)
    return rows[0] if rows else None


def _cell(res, index=0):
    row = _one(res)
    if row is None:
        return None
    try:
        return row[index]
    except Exception:
        return None


def _row_get(row, key, index):
    """libSQL(dict/tuple) / sqlite3.Row 행에서 값 추출"""
    try:
        v = row[key]
        if v is not None:
            return v
    except Exception:
        pass
    try:
        return row[index]
    except Exception:
        return None



def _db_close(db):
    try:
        if hasattr(db, "close"):
            db.close()
    except Exception:
        pass


def _db_commit(db):
    try:
        commit = getattr(db, "commit", None)
        if callable(commit):
            commit()
    except Exception:
        pass


def _rows(res):
    """libSQL(Turso) / sqlite3 실행 결과 → 행 리스트로 정규화"""
    if res is None:
        return []
    if hasattr(res, "fetchall"):
        try:
            return res.fetchall() or []
        except Exception:
            return []
    return getattr(res, "rows", []) or []


def _one(res):
    rows = _rows(res)
    return rows[0] if rows else None


def _cell(res, index=0):
    row = _one(res)
    if row is None:
        return None
    try:
        return row[index]
    except Exception:
        return None


def _row_get(row, key, index):
    """libSQL(dict/tuple) / sqlite3.Row 행에서 값 추출"""
    try:
        v = row[key]
        if v is not None:
            return v
    except Exception:
        pass
    try:
        return row[index]
    except Exception:
        return None


try:
    init_schema()
except Exception as _init_err:
    # 스키마 초기화 실패가 앱 import 실패(전 엔드포인트 500)로 이어지지 않게 격리.
    import traceback as _tb
    print(f"[init_schema] 시작 시 초기화 실패 (첫 요청 시 재시도됨): {_init_err}", flush=True)
    _tb.print_exc()


@app.before_request
def _ensure_schema_once():
    """첫 요청 시 스키마 및 누락 컬럼 자동 마이그레이션 보장"""
    if getattr(app, "_schema_ready", False):
        return
    db = get_db()
    try:
        schema_path = os.path.join(os.path.dirname(__file__), "schema.sql")
        if os.path.exists(schema_path):
            with open(schema_path, "r", encoding="utf-8") as f:
                _execute_statements(db, f.read())
        migrate_schema_columns(db)
        _db_commit(db)
        app._schema_ready = True
    except Exception as e:
        print(f"[schema] 요청 시 초기화 실패: {e}", flush=True)
    finally:
        _db_close(db)


def generate_invite_code():
    """4자리 숫자 초대 코드 생성 (예: "0837")"""
    return f"{secrets.randbelow(10000):04d}"


def generate_unique_invite_code(db):
    """다른 유효 초대 코드와 중복되지 않는 4자리 숫자 코드 생성 (충돌 시 재시도)"""
    code = generate_invite_code()
    for _ in range(30):
        if not _one(_q(db, "SELECT 1 FROM invites WHERE code = ?", (code,))):
            return code
        code = generate_invite_code()
    return code


def generate_unique_relink_code(db):
    """초대 코드/재연결 코드 어느 쪽과도 겹치지 않는 4자리 숫자 코드 생성"""
    code = generate_invite_code()
    for _ in range(30):
        used_invite = _one(_q(db, "SELECT 1 FROM invites WHERE code = ?", (code,)))
        used_relink = _one(_q(db, "SELECT 1 FROM relink_codes WHERE code = ?", (code,)))
        if not used_invite and not used_relink:
            return code
        code = generate_invite_code()
    return code


def _relink_token_ok():
    """재연결 관리자 API 토큰 검증. RELINK_TOKEN 미설정 시 항상 거부(안전한 기본값)."""
    if not RELINK_TOKEN:
        return False
    supplied = request.headers.get("X-Relink-Token") or ""
    if not supplied:
        body = request.get_json(silent=True) or {}
        supplied = str(body.get("token") or "")
    if not supplied:
        supplied = request.args.get("token", "")
    return secrets.compare_digest(supplied, RELINK_TOKEN)


def _space_members_detail(db, space_id):
    """스페이스 멤버 목록 + 식별에 도움이 되는 활동 통계 반환"""
    rows = _rows(_q(db, "SELECT user_id, joined_at FROM space_members WHERE space_id = ?", (space_id,)))
    members = []
    for row in rows:
        uid = _row_get(row, "user_id", 0)
        joined_at = _row_get(row, "joined_at", 1) or 0
        task_cnt = _cell(_q(db, "SELECT COUNT(*) FROM tasks WHERE space_id = ? AND created_by = ?", (space_id, uid))) or 0
        last_task = _cell(_q(db, "SELECT MAX(created_at) FROM tasks WHERE space_id = ? AND created_by = ?", (space_id, uid)))
        check_cnt = _cell(_q(db,
            "SELECT COUNT(*) FROM routine_checks WHERE user_id = ? AND routine_id IN (SELECT id FROM routines WHERE space_id = ?)",
            (uid, space_id))) or 0
        members.append({
            "user_id": uid,
            "joined_at": joined_at,
            "tasks_created": task_cnt,
            "last_task_at": last_task,
            "routine_checks": check_cnt,
        })
    members.sort(key=lambda m: m.get("joined_at") or 0)
    return members


def _relink_member(db, space_id, old_user_id, new_user_id):
    """옛 기기 user_id 자리를 새 기기 user_id 로 이전 (개인 기록까지 승계).

    앱을 삭제/재설치하면 기기 로컬 user_id 가 새로 발급되므로,
    방 멤버 자리와 사람별 기록(routine_checks, tasks.created_by)을 새 id 로 옮긴다.
    반환: (ok: bool, message: str, moved: dict)
    """
    if not old_user_id or not new_user_id:
        return False, "old_user_id 와 new_user_id 가 모두 필요합니다.", {}
    if old_user_id == new_user_id:
        return False, "이미 같은 사용자입니다.", {}

    if not _one(_q(db, "SELECT 1 FROM spaces WHERE id = ?", (space_id,))):
        return False, "존재하지 않는 스페이스입니다.", {}

    if not _one(_q(db, "SELECT 1 FROM space_members WHERE space_id = ? AND user_id = ?", (space_id, old_user_id))):
        return False, "이 방에 옛 사용자(user_id)가 없습니다. user_id를 확인해 주세요.", {}

    if _one(_q(db, "SELECT 1 FROM space_members WHERE space_id = ? AND user_id = ?", (space_id, new_user_id))):
        return False, "새 사용자(user_id)가 이미 이 방의 멤버입니다.", {}

    moved = {}

    # 1) 사람별 루틴 체크 기록 승계 (PK 충돌 방지를 위해 새 id 기존 행 먼저 정리)
    _q(db, "DELETE FROM routine_checks WHERE user_id = ? AND routine_id IN (SELECT id FROM routines WHERE space_id = ?)",
       (new_user_id, space_id))
    _q(db, "UPDATE routine_checks SET user_id = ? WHERE user_id = ? AND routine_id IN (SELECT id FROM routines WHERE space_id = ?)",
       (new_user_id, old_user_id, space_id))
    moved["routine_checks"] = True

    # 2) 카드/할 일 작성자 표기 승계
    _q(db, "UPDATE tasks SET created_by = ? WHERE space_id = ? AND created_by = ?", (new_user_id, space_id, old_user_id))

    # 3) 방 생성자 및 삭제 동의 요청 승계
    _q(db, "UPDATE spaces SET created_by = ? WHERE id = ? AND created_by = ?", (new_user_id, space_id, old_user_id))
    _q(db, "DELETE FROM space_delete_requests WHERE space_id = ? AND user_id = ?", (space_id, new_user_id))
    _q(db, "UPDATE space_delete_requests SET user_id = ? WHERE space_id = ? AND user_id = ?", (new_user_id, space_id, old_user_id))

    # 4) 멤버 자리 이전 (마지막에 실행 → 위 검증들이 유효하도록)
    _q(db, "UPDATE space_members SET user_id = ? WHERE space_id = ? AND user_id = ?", (new_user_id, space_id, old_user_id))

    # 5) 이 방의 남은 재연결 코드 정리
    _q(db, "DELETE FROM relink_codes WHERE space_id = ?", (space_id,))

    _db_commit(db)
    return True, "기기 재연결이 완료되었습니다.", moved


def get_current_korean_time():
    """현재 한국 시각 문자열 반환 (예: '오전 08:25', '오후 06:10')"""
    now = datetime.now(KST)
    ampm = "오후" if now.hour >= 12 else "오전"
    hour12 = now.hour % 12
    if hour12 == 0:
        hour12 = 12
    return f"{ampm} {hour12:02d}:{now.minute:02d}"


def get_today_date_string():
    """오늘 날짜 반환 (YYYY-MM-DD)"""
    return datetime.now(KST).strftime("%Y-%m-%d")


# ==========================================
# 0. 모바일 웹 & PWA 인터페이스 (아이폰/PC 겸용)
# ==========================================
@app.route("/", methods=["GET"])
@app.route("/mobile", methods=["GET"])
def mobile_web_index():
    """아이폰(iOS) 및 모바일 브라우저용 반응형 PWA 인터페이스"""
    return render_template("index.html")


@app.route("/manifest.json", methods=["GET"])
def pwa_manifest():
    """PWA 매니페스트 서빙"""
    return send_from_directory(os.path.join(os.path.dirname(__file__), "static"), "manifest.json", mimetype="application/manifest+json")


@app.route("/sw.js", methods=["GET"])
def pwa_service_worker():
    """PWA 서비스 워커 서빙"""
    return send_from_directory(os.path.join(os.path.dirname(__file__), "static"), "sw.js", mimetype="application/javascript")


# ==========================================
# 0-1. 헬스 체크 & Keep-Alive (UptimeRobot용)
# ==========================================
@app.route("/health", methods=["GET"])
@app.route("/ping", methods=["GET"])
def health_check():
    """Render 15분 슬립 방지용 핑 엔드포인트"""
    return jsonify({
        "status": "online",
        "service": "토닥토닥 (TodakTodak) API",
        "time": datetime.now(KST).isoformat()
    }), 200


@app.route("/debug/schema", methods=["GET"])
def debug_schema():
    """디버깅용: Turso DB 스키마 및 마이그레이션 확인 (임시 엔드포인트)"""
    db = get_db()
    result = {}
    try:
        try:
            result["migration_results"] = migrate_schema_columns(db)
        except Exception as e:
            result["migration_error"] = str(e)

        try:
            probe = _q(db, "SELECT name FROM sqlite_master WHERE type='table'")
            tables = [r[0] if not hasattr(r, 'keys') else r['name'] for r in _rows(probe)]
            result["sqlite_master_tables"] = tables
        except Exception as e:
            result["sqlite_master_error"] = str(e)

        table_columns = {}
        for tbl in ["spaces", "space_members", "invites", "routines"]:
            try:
                res = _q(db, f"PRAGMA table_info({tbl})")
                cols = [_row_get(r, "name", 1) for r in _rows(res)]
                table_columns[tbl] = cols
            except Exception as e:
                table_columns[tbl] = f"error: {e}"
        result["table_columns"] = table_columns

        try:
            import uuid as _uuid
            _test_id = f"debug-{_uuid.uuid4()}"
            _q(db,
                "INSERT INTO spaces (id, title, theme_color, created_by, created_at) VALUES (?, ?, ?, ?, ?)",
                [_test_id, "디버그", "CORAL", "debug-user", 123]
            )
            result["insert_spaces"] = "success"
        except Exception as e:
            result["insert_spaces_error"] = str(e)

        try:
            res = _q(db, "SELECT * FROM spaces LIMIT 5")
            rows = _rows(res)
            result["select_spaces_count"] = len(rows)
            result["select_spaces_ok"] = True
        except Exception as e:
            result["select_spaces_error"] = str(e)
    finally:
        _db_close(db)
    return jsonify(result), 200


# ==========================================
# 1. 스페이스 & 초대 코드 API
# ==========================================
@app.route("/api/spaces", methods=["POST"])
def create_space():
    """새 1:1 스페이스 생성 및 4자리 숫자 일회성 코드 발급"""
    data = request.json or {}
    title = data.get("title", "우리 공간").strip()
    theme_color = data.get("theme_color", "CORAL").upper()
    user_id = data.get("user_id") or str(uuid.uuid4())

    space_id = str(uuid.uuid4())
    now_ms = int(time.time() * 1000)
    expires_at = now_ms + (30 * 60 * 1000)  # 30분 유효

    db = get_db()
    try:
        # 같은 사용자가 이미 같은 이름의 방을 가지고 있으면 생성 거부 (중복 방지)
        dup_res = _q(db, 
            """SELECT 1 FROM spaces s
               JOIN space_members sm ON s.id = sm.space_id
               WHERE sm.user_id = ? AND s.title = ?""",
            (user_id, title)
        )
        if _one(dup_res):
            return jsonify({"error": "같은 이름의 방이 이미 있어요. 다른 이름을 사용해 주세요."}), 409

        # 동시에 유효한 다른 초대 코드와 중복되지 않는 4자리 숫자 코드 생성
        code = generate_unique_invite_code(db)
        _q(db,
            "INSERT INTO spaces (id, title, theme_color, created_by, created_at) VALUES (?, ?, ?, ?, ?)",
            (space_id, title, theme_color, user_id, now_ms)
        )
        _q(db,
            "INSERT INTO space_members (space_id, user_id, joined_at) VALUES (?, ?, ?)",
            (space_id, user_id, now_ms)
        )
        _q(db,
            "INSERT INTO invites (code, space_id, created_by, expires_at) VALUES (?, ?, ?, ?)",
            (code, space_id, user_id, expires_at)
        )
        _db_commit(db)

        return jsonify({
            "space": {
                "id": space_id,
                "title": title,
                "theme_color": theme_color,
                "created_by": user_id
            },
            "invite_code": code,
            "expires_in_minutes": 30
        }), 201
    except Exception as e:
        import traceback as _tb
        _trace = _tb.format_exc()
        print(f"[create_space ERROR] {e}\n{_trace}", flush=True)
        return jsonify({
            "error": f"방 만들기 실패: {e}",
            "detail": str(e),
            "traceback": _trace
        }), 500
    finally:
        _db_close(db)


@app.route("/api/spaces/<space_id>", methods=["PATCH"])
def update_space(space_id):
    """스페이스 제목/테마 수정 (방 이름 언제든 변경)"""
    data = request.json or {}
    title = data.get("title", "").strip()
    theme_color = data.get("theme_color")

    if not title and not theme_color:
        return jsonify({"error": "수정할 내용이 없습니다."}), 400

    db = get_db()
    try:
        if title:
            _q(db, "UPDATE spaces SET title = ? WHERE id = ?", (title, space_id))
        if theme_color:
            _q(db, "UPDATE spaces SET theme_color = ? WHERE id = ?", (theme_color, space_id))
        _db_commit(db)
        return jsonify({"message": "방 이름이 수정되었습니다.", "title": title}), 200
    finally:
        _db_close(db)


@app.route("/api/spaces/<space_id>/invite", methods=["GET"])
def get_space_invite(space_id):
    """스페이스의 유효한 초대 코드 조회 (1:1 2명 한정 검증 및 30분 만료)"""
    db = get_db()
    try:
        now_ms = int(time.time() * 1000)

        # 1. 스페이스 존재 및 멤버 수(2명 한정) 확인
        s_res = _q(db, "SELECT id, title FROM spaces WHERE id = ?", (space_id,))
        s_rows = _rows(s_res)
        if not s_rows:
            return jsonify({"error": "존재하지 않는 스페이스입니다."}), 404

        m_res = _q(db, "SELECT COUNT(*) FROM space_members WHERE space_id = ?", (space_id,))
        m_count = _cell(m_res) or 0
        if m_count >= 2:
            return jsonify({
                "error": "이미 2명이 모두 연결된 오붓한 공간이에요 🌸\n새로운 분과 함께하시려면 [+ 새 방 만들기]로 새로운 1:1 방을 만들어 보세요!",
                "is_full": True
            }), 400

        # 아직 유효한 초대 코드가 있으면 재사용 (30분 유효)
        i_rows = _rows(_q(db,
            "SELECT code, expires_at FROM invites WHERE space_id = ? AND expires_at > ?",
            (space_id, now_ms)
        ))
        if i_rows:
            raw = _row_get(i_rows[0], "code", 0)
            expires_at = _row_get(i_rows[0], "expires_at", 1)
            remaining = max(1, int((expires_at - now_ms) / 60000))
            return jsonify({"invite_code": raw, "expires_in_minutes": remaining}), 200

        # 유효 코드가 없으면 새로 발급 (30분 유효)
        _q(db, "DELETE FROM invites WHERE space_id = ?", (space_id,))
        code = generate_unique_invite_code(db)
        expires_at = now_ms + (30 * 60 * 1000)
        _q(db, 
            "INSERT INTO invites (code, space_id, created_by, expires_at) VALUES (?, ?, ?, ?)",
            (code.replace("-", ""), space_id, None, expires_at)
        )
        _db_commit(db)

        return jsonify({"invite_code": code, "expires_in_minutes": 30}), 200
    finally:
        _db_close(db)


@app.route("/api/spaces/join", methods=["POST"])
def join_space():
    """4자리 숫자 코드로 참여 또는 기기 재연결 (1:1 2명 제한 보호)

    - 초대 코드: 새 멤버로 참여 (2명 초과 시 거부)
    - 재연결 코드: 재설치/기기교체 시 옛 멤버 자리를 이 기기 user_id 로 이전 (인원 제한 예외)
    """
    data = request.json or {}
    raw_code = data.get("code", "").replace("-", "").replace(" ", "").upper()
    user_id = data.get("user_id") or str(uuid.uuid4())

    if len(raw_code) != 4 or not raw_code.isdigit():
        return jsonify({"error": "올바른 4자리 숫자 초대 코드를 입력해 주세요."}), 400

    db = get_db()
    try:
        now_ms = int(time.time() * 1000)
        res = _q(db, "SELECT * FROM invites WHERE code = ?", (raw_code,))
        rows = _rows(res)
        if not rows:
            # 초대 코드가 아니면 '기기 재연결 코드'인지 확인
            r_rows = _rows(_q(db, "SELECT * FROM relink_codes WHERE code = ?", (raw_code,)))
            if not r_rows:
                return jsonify({"error": "유효하지 않거나 이미 사용 완료된 초대 코드입니다."}), 404

            relink = r_rows[0]
            r_space_id = _row_get(relink, "space_id", 1)
            r_old_uid = _row_get(relink, "old_user_id", 2)
            r_expires = _row_get(relink, "expires_at", 4)

            if now_ms > (r_expires or 0):
                _q(db, "DELETE FROM relink_codes WHERE code = ?", (raw_code,))
                _db_commit(db)
                return jsonify({"error": "기기 재연결 코드 유효 시간(30분)이 만료되었습니다."}), 410

            moved_ok, moved_msg, _moved = _relink_member(db, r_space_id, r_old_uid, user_id)
            if not moved_ok:
                return jsonify({"error": moved_msg}), 400

            s_rows = _rows(_q(db, "SELECT * FROM spaces WHERE id = ?", (r_space_id,)))
            if not s_rows:
                return jsonify({"error": "존재하지 않는 스페이스입니다."}), 404
            relinked_space = s_rows[0]

            return jsonify({
                "message": "기기 재연결이 완료되었습니다! 기존 방으로 그대로 복구되었어요.",
                "relinked": True,
                "space": {
                    "id": r_space_id,
                    "title": _row_get(relinked_space, "title", 1),
                    "theme_color": _row_get(relinked_space, "theme_color", 2)
                }
            }), 200

        invite = rows[0]
        space_id = _row_get(invite, "space_id", 1)
        expires_at = _row_get(invite, "expires_at", 3)

        if now_ms > expires_at:
            _q(db, "DELETE FROM invites WHERE code = ?", (raw_code,))
            _db_commit(db)
            return jsonify({"error": "초대 코드 유효 시간(30분)이 만료되었습니다."}), 410

        # 1:1 인원 제한 (2명 초과 방지)
        m_res = _q(db, "SELECT COUNT(*) FROM space_members WHERE space_id = ?", (space_id,))
        m_count = _cell(m_res) or 0
        if m_count >= 2:
            return jsonify({
                "error": "이 방은 이미 2명이 연결 완료된 오붓한 공간입니다. 새로운 1:1 방을 만들어 주세요."
            }), 400

        # 멤버 추가 및 코드 즉시 소멸
        _q(db, 
            "INSERT OR IGNORE INTO space_members (space_id, user_id, joined_at) VALUES (?, ?, ?)",
            (space_id, user_id, now_ms)
        )
        _q(db, "DELETE FROM invites WHERE code = ?", (raw_code,))
        _db_commit(db)

        # 스페이스 정보 조회
        s_res = _q(db, "SELECT * FROM spaces WHERE id = ?", (space_id,))
        s_rows = _rows(s_res)
        space = s_rows[0]

        return jsonify({
            "message": "성공적으로 연결되었습니다!",
            "space": {
                "id": space_id,
                "title": _row_get(space, "title", 1),
                "theme_color": _row_get(space, "theme_color", 2)
            }
        }), 200
    finally:
        _db_close(db)


# ==========================================
# 1-1. 기기 재연결(relink) 관리자 API
#   - 앱 재설치/기기 교체 시 기기 로컬 user_id 가 새로 발급되어
#     기존 방에 재참여할 수 없게 되는 문제(1:1 2명 제한)를 해결한다.
#   - 옛 멤버 자리와 개인 기록(routine_checks, tasks.created_by)을 새 기기로 승계.
#   - 환경변수 RELINK_TOKEN 이 설정된 경우에만 동작한다(미설정 시 전부 403).
# ==========================================
@app.route("/api/admin/spaces", methods=["GET"])
def admin_list_spaces():
    """(관리자) 방 목록 + 멤버 활동 통계 조회 — 어떤 user_id 가 누구인지 식별용"""
    if not _relink_token_ok():
        return jsonify({"error": "권한이 없습니다. RELINK_TOKEN 을 확인해 주세요."}), 403

    db = get_db()
    try:
        spaces = _rows(_q(db, "SELECT id, title, created_by, created_at FROM spaces ORDER BY created_at DESC"))
        result = []
        for s in spaces:
            space_id = _row_get(s, "id", 0)
            result.append({
                "space_id": space_id,
                "title": _row_get(s, "title", 1),
                "created_by": _row_get(s, "created_by", 2),
                "created_at": _row_get(s, "created_at", 3),
                "members": _space_members_detail(db, space_id),
            })
        return jsonify({"count": len(result), "spaces": result}), 200
    finally:
        _db_close(db)


@app.route("/api/spaces/<space_id>/members", methods=["GET"])
def get_space_members(space_id):
    """(관리자) 특정 방의 멤버 목록 조회"""
    if not _relink_token_ok():
        return jsonify({"error": "권한이 없습니다. RELINK_TOKEN 을 확인해 주세요."}), 403

    db = get_db()
    try:
        s = _one(_q(db, "SELECT id, title, created_by FROM spaces WHERE id = ?", (space_id,)))
        if not s:
            return jsonify({"error": "존재하지 않는 스페이스입니다."}), 404
        return jsonify({
            "space_id": space_id,
            "title": _row_get(s, "title", 1),
            "created_by": _row_get(s, "created_by", 2),
            "members": _space_members_detail(db, space_id),
        }), 200
    finally:
        _db_close(db)


@app.route("/api/spaces/<space_id>/relink-code", methods=["POST"])
def issue_relink_code(space_id):
    """(관리자) 기기 재연결 코드 발급 (4자리, 30분 유효)

    body: { "old_user_id": "<이전할 옛 멤버 user_id>" }
    새로 설치한 기기의 [초대 코드 입력]에 이 코드를 넣으면 기존 방으로 복구된다.
    """
    if not _relink_token_ok():
        return jsonify({"error": "권한이 없습니다. RELINK_TOKEN 을 확인해 주세요."}), 403

    data = request.json or {}
    old_user_id = str(data.get("old_user_id") or "").strip()
    if not old_user_id:
        return jsonify({"error": "old_user_id(이전할 옛 멤버 user_id)가 필요합니다."}), 400

    db = get_db()
    try:
        now_ms = int(time.time() * 1000)
        if not _one(_q(db, "SELECT 1 FROM spaces WHERE id = ?", (space_id,))):
            return jsonify({"error": "존재하지 않는 스페이스입니다."}), 404
        if not _one(_q(db, "SELECT 1 FROM space_members WHERE space_id = ? AND user_id = ?", (space_id, old_user_id))):
            return jsonify({"error": "이 방에 해당 user_id 멤버가 없습니다. /members 로 확인해 주세요."}), 404

        _q(db, "DELETE FROM relink_codes WHERE space_id = ?", (space_id,))
        code = generate_unique_relink_code(db)
        expires_at = now_ms + (30 * 60 * 1000)
        _q(db,
            "INSERT INTO relink_codes (code, space_id, old_user_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
            (code, space_id, old_user_id, now_ms, expires_at))
        _db_commit(db)

        return jsonify({
            "relink_code": code,
            "space_id": space_id,
            "old_user_id": old_user_id,
            "expires_in_minutes": 30,
            "guide": "앱을 삭제 후 새로 설치한 기기에서 [초대 코드 입력]에 이 4자리 코드를 입력하면 기존 방으로 복구됩니다."
        }), 200
    finally:
        _db_close(db)


@app.route("/api/spaces/<space_id>/relink", methods=["POST"])
def direct_relink_member(space_id):
    """(관리자) 재연결 코드 없이 즉시 멤버 자리 이전

    body: { "old_user_id": "...", "new_user_id": "..." }
    """
    if not _relink_token_ok():
        return jsonify({"error": "권한이 없습니다. RELINK_TOKEN 을 확인해 주세요."}), 403

    data = request.json or {}
    db = get_db()
    try:
        ok, msg, moved = _relink_member(
            db, space_id,
            str(data.get("old_user_id") or "").strip(),
            str(data.get("new_user_id") or "").strip()
        )
        if not ok:
            return jsonify({"error": msg}), 400
        return jsonify({"message": msg, "moved": moved}), 200
    finally:
        _db_close(db)


@app.route("/api/admin/spaces/<space_id>/remove-member", methods=["POST"])
def admin_remove_member(space_id):
    """(관리자) 멤버 자리 제거 — 양쪽 기기를 모두 재설치한 경우 등 비상용

    body: { "user_id": "<제거할 멤버 user_id>" }
    제거 후에는 남은 인원이 2명 미만이므로 초대 코드로 재참여할 수 있다.
    """
    if not _relink_token_ok():
        return jsonify({"error": "권한이 없습니다. RELINK_TOKEN 을 확인해 주세요."}), 403

    data = request.json or {}
    user_id = str(data.get("user_id") or "").strip()
    if not user_id:
        return jsonify({"error": "user_id 가 필요합니다."}), 400

    db = get_db()
    try:
        if not _one(_q(db, "SELECT 1 FROM space_members WHERE space_id = ? AND user_id = ?", (space_id, user_id))):
            return jsonify({"error": "해당 멤버가 이 방에 없습니다."}), 404

        _q(db, "DELETE FROM space_members WHERE space_id = ? AND user_id = ?", (space_id, user_id))
        _q(db, "DELETE FROM relink_codes WHERE space_id = ? AND old_user_id = ?", (space_id, user_id))
        _db_commit(db)

        return jsonify({
            "message": "멤버 자리를 제거했습니다. 이제 초대 코드로 재참여할 수 있어요.",
            "members": _space_members_detail(db, space_id),
        }), 200
    finally:
        _db_close(db)


# ==========================================
# 2. 방 삭제 동의 API (모든 멤버 동의 시 삭제)
# ==========================================
def delete_space_data(db, space_id):
    """스페이스 및 관련 데이터 전체 삭제 (할 일, 루틴, 초대 코드 포함)"""
    _q(db, 
        "DELETE FROM routine_checks WHERE routine_id IN (SELECT id FROM routines WHERE space_id = ?)",
        (space_id,)
    )
    _q(db, "DELETE FROM tasks WHERE space_id = ?", (space_id,))
    _q(db, "DELETE FROM routines WHERE space_id = ?", (space_id,))
    _q(db, "DELETE FROM invites WHERE space_id = ?", (space_id,))
    _q(db, "DELETE FROM space_delete_requests WHERE space_id = ?", (space_id,))
    _q(db, "DELETE FROM space_members WHERE space_id = ?", (space_id,))
    _q(db, "DELETE FROM spaces WHERE id = ?", (space_id,))
    _db_commit(db)


@app.route("/api/spaces/<space_id>/delete-request", methods=["POST"])
def request_space_delete(space_id):
    """
    방 삭제 요청.
    - 모든 멤버가 동의(요청)하면 즉시 삭제
    - 멤버가 1명뿐인 방은 즉시 삭제
    """
    data = request.json or {}
    user_id = data.get("user_id") or "unknown-user"
    now_ms = int(time.time() * 1000)

    db = get_db()
    try:
        s_res = _q(db, "SELECT id FROM spaces WHERE id = ?", (space_id,))
        s_rows = _rows(s_res)
        if not s_rows:
            return jsonify({"error": "이미 삭제되었거나 존재하지 않는 방입니다."}), 404

        m_res = _q(db, "SELECT user_id FROM space_members WHERE space_id = ?", (space_id,))
        m_rows = _rows(m_res)
        member_ids = [_row_get(r, "user_id", 0) for r in m_rows]
        member_count = len(member_ids)

        _q(db, 
            "INSERT OR IGNORE INTO space_delete_requests (space_id, user_id, requested_at) VALUES (?, ?, ?)",
            (space_id, user_id, now_ms)
        )
        _db_commit(db)

        p_res = _q(db, "SELECT user_id FROM space_delete_requests WHERE space_id = ?", (space_id,))
        p_rows = _rows(p_res)
        pending_ids = [_row_get(r, "user_id", 0) for r in p_rows]
        pending = len(pending_ids)

        if member_count <= 1 or pending >= member_count:
            delete_space_data(db, space_id)
            return jsonify({
                "deleted": True,
                "message": "모두 동의하여 방이 삭제되었습니다.",
                "member_count": member_count,
                "pending": pending
            }), 200

        return jsonify({
            "deleted": False,
            "message": "상대방 동의를 기다리는 중입니다.",
            "member_count": member_count,
            "pending": pending
        }), 200
    finally:
        _db_close(db)


@app.route("/api/spaces/<space_id>/delete-request", methods=["GET"])
def get_space_delete_status(space_id):
    """방 삭제 요청 상태 조회 (내 요청 여부 / 상대 요청 여부 / 멤버 수)"""
    my_user_id = request.args.get("user_id", "")
    db = get_db()
    try:
        s_res = _q(db, "SELECT id FROM spaces WHERE id = ?", (space_id,))
        s_rows = _rows(s_res)
        if not s_rows:
            # 방이 이미 삭제됨
            return jsonify({
                "deleted": True,
                "my_requested": False,
                "other_requested": False,
                "member_count": 0
            }), 200

        m_res = _q(db, "SELECT user_id FROM space_members WHERE space_id = ?", (space_id,))
        m_rows = _rows(m_res)
        member_count = len(m_rows)

        p_res = _q(db, "SELECT user_id FROM space_delete_requests WHERE space_id = ?", (space_id,))
        p_rows = _rows(p_res)
        pending_ids = [_row_get(r, "user_id", 0) for r in p_rows]

        return jsonify({
            "deleted": False,
            "my_requested": my_user_id in pending_ids,
            "other_requested": any(uid != my_user_id for uid in pending_ids),
            "member_count": member_count
        }), 200
    finally:
        _db_close(db)


@app.route("/api/spaces/<space_id>/delete-request", methods=["DELETE"])
def cancel_space_delete(space_id):
    """삭제 요청 취소/거절 — 해당 방의 모든 대기 요청 제거"""
    db = get_db()
    try:
        _q(db, "DELETE FROM space_delete_requests WHERE space_id = ?", (space_id,))
        _db_commit(db)
        return jsonify({"message": "삭제 요청이 취소되었습니다."}), 200
    finally:
        _db_close(db)


@app.route("/api/users/<user_id>/spaces", methods=["GET"])
def get_user_spaces(user_id):
    """사용자가 참여 중인 스페이스 목록 조회"""
    db = get_db()
    try:
        query = """
            SELECT s.id, s.title, s.theme_color, s.created_by, s.created_at,
                   (SELECT COUNT(*) FROM space_members WHERE space_id = s.id) AS member_count
            FROM spaces s
            JOIN space_members sm ON s.id = sm.space_id
            WHERE sm.user_id = ?
            ORDER BY s.created_at DESC
        """
        res = _q(db, query, (user_id,))
        rows = _rows(res)
        spaces = []
        for r in rows:
            spaces.append({
                "id": _row_get(r, "id", 0),
                "title": _row_get(r, "title", 1),
                "theme_color": _row_get(r, "theme_color", 2),
                "member_count": _row_get(r, "member_count", 5) or 1
            })
        return jsonify({"spaces": spaces}), 200
    finally:
        _db_close(db)


# ==========================================
# 2. 할 일 (Task) API
# ==========================================
@app.route("/api/spaces/<space_id>/tasks", methods=["GET"])
def get_tasks(space_id):
    """할 일 목록 조회"""
    db = get_db()
    try:
        res = _q(db, 
            "SELECT * FROM tasks WHERE space_id = ? ORDER BY is_completed ASC, due_date ASC, created_at ASC",
            (space_id,)
        )
        rows = _rows(res)
        tasks = []
        for r in rows:
            tasks.append({
                "id": _row_get(r, "id", 0),
                "title": _row_get(r, "title", 2),
                "due_date": _row_get(r, "due_date", 3),
                "is_completed": bool(_row_get(r, "is_completed", 4)),
                "alarm_time": _row_get(r, "alarm_time", None) or "",
                "has_alarm": bool(_row_get(r, "has_alarm", 0))
            })
        return jsonify({"tasks": tasks}), 200
    finally:
        _db_close(db)


@app.route("/api/spaces/<space_id>/tasks", methods=["POST"])
def add_task(space_id):
    """할 일 추가"""
    data = request.json or {}
    title = data.get("title", "").strip()
    if not title:
        return jsonify({"error": "할 일 제목을 입력해 주세요."}), 400

    task_id = str(uuid.uuid4())
    due_date = data.get("due_date", "")
    user_id = data.get("user_id", "")
    alarm_time = data.get("alarm_time", "")
    has_alarm = int(data.get("has_alarm", 0))
    now_ms = int(time.time() * 1000)

    db = get_db()
    try:
        _q(db, 
            "INSERT INTO tasks (id, space_id, title, due_date, is_completed, created_by, created_at, alarm_time, has_alarm) VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?)",
            (task_id, space_id, title, due_date, user_id, now_ms, alarm_time, has_alarm)
        )
        _db_commit(db)

        return jsonify({
            "task": {
                "id": task_id,
                "space_id": space_id,
                "title": title,
                "due_date": due_date,
                "is_completed": False,
                "alarm_time": alarm_time,
                "has_alarm": bool(has_alarm)
            }
        }), 201
    finally:
        _db_close(db)


@app.route("/api/spaces/<space_id>/tasks/<task_id>", methods=["PATCH"])
def update_or_toggle_task(space_id, task_id):
    """할 일 수정 및 완료 토글"""
    data = request.json or {}
    db = get_db()
    try:
        if "is_completed" in data:
            is_completed = data.get("is_completed", True)
            now_ms = int(time.time() * 1000) if is_completed else None
            _q(db, 
                "UPDATE tasks SET is_completed = ?, completed_at = ? WHERE id = ? AND space_id = ?",
                (1 if is_completed else 0, now_ms, task_id, space_id)
            )
        if "title" in data:
            title = data.get("title", "").strip()
            due_date = data.get("due_date", "")
            alarm_time = data.get("alarm_time", "")
            has_alarm = int(data.get("has_alarm", 0))
            _q(db, 
                "UPDATE tasks SET title = ?, due_date = ?, alarm_time = ?, has_alarm = ? WHERE id = ? AND space_id = ?",
                (title, due_date, alarm_time, has_alarm, task_id, space_id)
            )
        _db_commit(db)
        return jsonify({"message": "수정되었습니다."}), 200
    finally:
        _db_close(db)


@app.route("/api/spaces/<space_id>/tasks/<task_id>", methods=["DELETE"])
def delete_task(space_id, task_id):
    """할 일 삭제"""
    db = get_db()
    try:
        _q(db, "DELETE FROM tasks WHERE id = ? AND space_id = ?", (task_id, space_id))
        _db_commit(db)
        return jsonify({"message": "삭제되었습니다."}), 200
    finally:
        _db_close(db)


# ==========================================
# 3. 매일 루틴 (약 먹기 안심) API
# ==========================================
@app.route("/api/spaces/<space_id>/routines", methods=["POST"])
def create_routine(space_id):
    """새 매일 반복 루틴 생성 (예: 약 먹기, 영양제 등)"""
    data = request.json or {}
    routine_id = data.get("id") or str(uuid.uuid4())
    title = data.get("title", "").strip() or "매일 루틴"
    icon_type = data.get("icon_type", "PILL").upper()
    target_time = data.get("target_time", "08:30")
    now_ms = int(time.time() * 1000)

    db = get_db()
    try:
        _q(db, 
            "INSERT INTO routines (id, space_id, title, icon_type, target_time, last_completed_date, last_completed_time, created_at) VALUES (?, ?, ?, ?, ?, '', '', ?)",
            (routine_id, space_id, title, icon_type, target_time, now_ms)
        )
        _db_commit(db)

        return jsonify({
            "routine": {
                "id": routine_id,
                "space_id": space_id,
                "title": title,
                "icon_type": icon_type,
                "target_time": target_time
            }
        }), 201
    finally:
        _db_close(db)


@app.route("/api/spaces/<space_id>/routines", methods=["GET"])
def get_routines(space_id):
    """매일 루틴 목록 조회 (사람별 오늘 체크 상태 포함, 자정 자동 리셋 계산)"""
    my_user_id = request.args.get("user_id", "")
    db = get_db()
    today_str = get_today_date_string()
    try:
        res = _q(db, "SELECT * FROM routines WHERE space_id = ? ORDER BY created_at ASC", (space_id,))
        rows = _rows(res)
        routines = []

        # 이 스페이스 루틴들의 오늘 체크 기록을 한 번에 조회 (사람별)
        checks = {}
        try:
            c_res = _q(db, 
                """SELECT rc.routine_id, rc.user_id, rc.completed_time
                   FROM routine_checks rc
                   JOIN routines r ON rc.routine_id = r.id
                   WHERE r.space_id = ? AND rc.completed_date = ?""",
                (space_id, today_str)
            )
            c_rows = _rows(c_res)
            for c in c_rows:
                rid = _row_get(c, "routine_id", 0)
                uid = _row_get(c, "user_id", 1)
                ctime = _row_get(c, "completed_time", 2)
                checks.setdefault(rid, []).append((uid, ctime))
        except Exception:
            # routine_checks 테이블이 아직 없는 구버전 DB 호환 (폴백)
            pass

        for r in rows:
            rid = _row_get(r, "id", 0)
            title = _row_get(r, "title", 2)
            icon_type = _row_get(r, "icon_type", 3)
            last_date = _row_get(r, "last_completed_date", 5)
            last_time = _row_get(r, "last_completed_time", 6)
            is_done_today = (last_date == today_str)

            # 사람별 체크 상태 분리
            my_completed_date = ""
            my_completed_time = ""
            partner_completed_date = ""
            partner_completed_time = ""
            routine_checks = checks.get(rid, [])
            for uid, ctime in routine_checks:
                if my_user_id and uid == my_user_id:
                    my_completed_date = today_str
                    my_completed_time = ctime
                else:
                    partner_completed_date = today_str
                    partner_completed_time = ctime

            # 구버전 호환: 사람별 기록이 없으면 기존 단일 기록을 내 기록으로 사용
            if not routine_checks and is_done_today:
                my_completed_date = last_date
                my_completed_time = last_time

            routines.append({
                "id": rid,
                "title": title,
                "icon_type": icon_type,
                "is_done_today": is_done_today,
                "last_completed_time": last_time if is_done_today else "",
                "my_completed_date": my_completed_date,
                "my_completed_time": my_completed_time,
                "partner_completed_date": partner_completed_date,
                "partner_completed_time": partner_completed_time
            })
        return jsonify({"routines": routines}), 200
    finally:
        _db_close(db)


@app.route("/api/spaces/<space_id>/routines/<routine_id>/check", methods=["POST"])
def check_routine(space_id, routine_id):
    """
    약 먹기 원터치 완료 체크 (사람별 기록)
    - routine_checks에 user별 오늘 기록 upsert (남편/아내 각각 체크 가능)
    - 핵심: 누른 즉시 한국어 시각('오전 08:25')을 DB에 영구 기록
    """
    data = request.json or {}
    user_id = data.get("user_id") or "unknown-user"
    today_str = get_today_date_string()
    time_str = get_current_korean_time()
    now_ms = int(time.time() * 1000)

    db = get_db()
    try:
        # 사람별 체크 기록 upsert
        try:
            _q(db, 
                """INSERT INTO routine_checks (routine_id, user_id, completed_date, completed_time, checked_at)
                   VALUES (?, ?, ?, ?, ?)
                   ON CONFLICT(routine_id, user_id) DO UPDATE SET
                     completed_date = excluded.completed_date,
                     completed_time = excluded.completed_time,
                     checked_at = excluded.checked_at""",
                (routine_id, user_id, today_str, time_str, now_ms)
            )
        except Exception:
            # 구버전 DB(routine_checks 테이블 없음) 호환 폴백
            pass

        # 기존 단일 완료 필드도 갱신 (위젯/구버전 앱 호환)
        _q(db, 
            "UPDATE routines SET last_completed_date = ?, last_completed_time = ? WHERE id = ? AND space_id = ?",
            (today_str, time_str, routine_id, space_id)
        )
        _db_commit(db)

        return jsonify({
            "message": "약 복용 체크가 완료되었습니다!",
            "last_completed_time": time_str,
            "last_completed_date": today_str
        }), 200
    finally:
        _db_close(db)


@app.route("/api/spaces/<space_id>/routines/<routine_id>", methods=["DELETE"])
def delete_routine(space_id, routine_id):
    """매일 반복 루틴 삭제"""
    db = get_db()
    try:
        _q(db, "DELETE FROM routines WHERE id = ? AND space_id = ?", (routine_id, space_id))
        try:
            _q(db, "DELETE FROM routine_checks WHERE routine_id = ?", (routine_id,))
        except Exception:
            pass
        _db_commit(db)
        return jsonify({"message": "루틴이 삭제되었습니다."}), 200
    finally:
        _db_close(db)


# ==========================================
# 4. 앱 버전 및 자체 자동 업데이트 API
# ==========================================
CURRENT_APP_VERSION_CODE = 15
CURRENT_APP_VERSION_NAME = "1.5.0"

@app.route("/api/version", methods=["GET"])
def get_app_version():
    """앱 최신 버전 조회 및 인앱 자동 업데이트 정보 반환"""
    return jsonify({
        "version_code": CURRENT_APP_VERSION_CODE,
        "version_name": CURRENT_APP_VERSION_NAME,
        # 고정 자산명: 릴리스마다 '동일한 release 키'로 서명된 app-release.apk 를 사용한다.
        # (debug 서명 APK 를 올리면 기존 설치와 서명이 충돌해 "앱이 설치되지 않았습니다" 발생)
        # 폴백도 같은 정식 자산을 가리킨다 — 과거에는 존재하지 않는 app-debug.apk(404) 였음.
        "apk_url": "https://github.com/davidhunchoi/todak-todak/releases/latest/download/app-release.apk",
        "apk_url_fallback": "https://github.com/davidhunchoi/todak-todak/releases/latest/download/app-release.apk",
        "changelog": "🌸 v1.5.0 초고속 업데이트\n- ⚡ 초고속 Vercel 서버 이전 (지연 없는 즉시 응답)\n- 🏡 스페이스 방 생성 및 4자리 초대 코드 연결 안정화"
    }), 200


@app.route("/download/app-latest.apk", methods=["GET"])
def download_latest_apk():
    """최신 APK 다운로드 제공 (항상 깃허브 최신 정식 릴리스로 직통 리다이렉트)"""
    return redirect("https://github.com/davidhunchoi/todak-todak/releases/latest/download/app-release.apk")


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    app.run(host="0.0.0.0", port=port, debug=False)
