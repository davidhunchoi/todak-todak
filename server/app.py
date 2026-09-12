import os
import uuid
import time
import secrets
import sqlite3
from datetime import datetime
from zoneinfo import ZoneInfo
from flask import Flask, request, jsonify, send_file, redirect
from flask_cors import CORS
from dotenv import load_dotenv

load_dotenv()

app = Flask(__name__)
CORS(app)


@app.errorhandler(500)
def handle_internal_error(e):
    """예상치 못한 서버 오류를 HTML 대신 JSON 으로 반환 (앱에서 사유를 안내할 수 있게)"""
    return jsonify({"error": "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."}), 500

# 환경 변수 설정 (Turso 연동)
TURSO_DB_URL = os.getenv("TURSO_DATABASE_URL")
TURSO_AUTH_TOKEN = os.getenv("TURSO_AUTH_TOKEN")

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
    return db.execute(stmt, params)


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


try:
    init_schema()
except Exception as _init_err:
    # 스키마 초기화 실패가 앱 import 실패(전 엔드포인트 500)로 이어지지 않게 격리.
    import traceback as _tb
    print(f"[init_schema] 시작 시 초기화 실패 (첫 요청 시 재시도됨): {_init_err}", flush=True)
    _tb.print_exc()


@app.before_request
def _ensure_schema_once():
    """첫 요청 시 테이블이 없으면 스키마 재적용 (init 실패 + 기존 DB 누락 테이블 대응)

    ⚠️ libsql-client 0.3.x 의 execute() 는 INSERT 등 쓰기 쿼리에 대해
    params 를 반드시 리스트(list)로 받아야 함. 튜플 전달 시
    libsql-client 패키지 내부에서 TypeError → Flask 500 → 앱에서
    "방 만들기 실패" 로 보이는 원인이 됨. 이 함수는 읽기 전용이라 안전.
    """
    if getattr(app, "_schema_ready", False):
        return
    db = get_db()
    try:
        probe = None
        try:
            probe = _q(db, "SELECT name FROM sqlite_master WHERE type='table' AND name='spaces'")
        except Exception:
            probe = None
        if _one(probe):
            app._schema_ready = True
            return
        schema_path = os.path.join(os.path.dirname(__file__), "schema.sql")
        with open(schema_path, "r", encoding="utf-8") as f:
            _execute_statements(db, f.read())
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
# 0. 헬스 체크 & Keep-Alive (UptimeRobot용)
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
    expires_at = now_ms + (10 * 60 * 1000)  # 10분 유효

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
        # Turso / SQLite 공통 처리
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
            "expires_in_minutes": 10
        }), 201
    finally:
        _db_close(db)


@app.route("/api/spaces/<space_id>/invite", methods=["GET"])
def get_space_invite(space_id):
    """스페이스의 유효한 초대 코드 조회 (없거나 만료 시 새로 발급)"""
    db = get_db()
    try:
        now_ms = int(time.time() * 1000)

        # 스페이스 존재 여부 확인
        s_res = _q(db, "SELECT id FROM spaces WHERE id = ?", (space_id,))
        s_rows = _rows(s_res)
        if not s_rows:
            return jsonify({"error": "존재하지 않는 스페이스입니다."}), 404

        # 아직 유효한 초대 코드가 있으면 재사용 (10분 내 반복 조회 지원)
        i_rows = _rows(_q(db,
            "SELECT code, expires_at FROM invites WHERE space_id = ? AND expires_at > ?",
            (space_id, now_ms)
        ))
        if i_rows:
            raw = _row_get(i_rows[0], "code", 0)
            expires_at = _row_get(i_rows[0], "expires_at", 1)
            remaining = max(1, int((expires_at - now_ms) / 60000))
            return jsonify({"invite_code": raw, "expires_in_minutes": remaining}), 200

        # 유효 코드가 없으면 새로 발급 (기존 만료 코드 정리 후)
        _q(db, "DELETE FROM invites WHERE space_id = ?", (space_id,))
        code = generate_unique_invite_code(db)
        expires_at = now_ms + (10 * 60 * 1000)
        _q(db, 
            "INSERT INTO invites (code, space_id, created_by, expires_at) VALUES (?, ?, ?, ?)",
            (code.replace("-", ""), space_id, None, expires_at)
        )
        _db_commit(db)

        return jsonify({"invite_code": code, "expires_in_minutes": 10}), 200
    finally:
        _db_close(db)


@app.route("/api/spaces/join", methods=["POST"])
def join_space():
    """4자리 숫자 초대 코드로 참여 (참여 즉시 코드 영구 소멸)"""
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
            return jsonify({"error": "유효하지 않거나 이미 사용 완료된 초대 코드입니다."}), 404

        invite = rows[0]
        space_id = _row_get(invite, "space_id", 1)
        expires_at = _row_get(invite, "expires_at", 3)

        if now_ms > expires_at:
            _q(db, "DELETE FROM invites WHERE code = ?", (raw_code,))
            _db_commit(db)
            return jsonify({"error": "초대 코드 유효 시간(10분)이 만료되었습니다."}), 410

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
            SELECT s.id, s.title, s.theme_color, s.created_by, s.created_at
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
                "theme_color": _row_get(r, "theme_color", 2)
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
                "is_completed": bool(_row_get(r, "is_completed", 4))
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
    now_ms = int(time.time() * 1000)

    db = get_db()
    try:
        _q(db, 
            "INSERT INTO tasks (id, space_id, title, due_date, is_completed, created_by, created_at) VALUES (?, ?, ?, ?, 0, ?, ?)",
            (task_id, space_id, title, due_date, user_id, now_ms)
        )
        _db_commit(db)

        return jsonify({
            "task": {
                "id": task_id,
                "space_id": space_id,
                "title": title,
                "due_date": due_date,
                "is_completed": False
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
            _q(db, 
                "UPDATE tasks SET title = ?, due_date = ? WHERE id = ? AND space_id = ?",
                (title, due_date, task_id, space_id)
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
CURRENT_APP_VERSION_CODE = 5
CURRENT_APP_VERSION_NAME = "1.3.1"

@app.route("/api/version", methods=["GET"])
def get_app_version():
    """앱 최신 버전 조회 및 인앱 자동 업데이트 정보 반환"""
    return jsonify({
        "version_code": CURRENT_APP_VERSION_CODE,
        "version_name": CURRENT_APP_VERSION_NAME,
        # 고정 자산명: 구버전 앱(app-debug.apk 링크 내장) 호환을 위해 폴백 URL도 함께 제공
        "apk_url": "https://github.com/davidhunchoi/todak-todak/releases/latest/download/app-release.apk",
        "apk_url_fallback": "https://github.com/davidhunchoi/todak-todak/releases/latest/download/app-debug.apk",
        "changelog": "🎉 v1.3.1\n- 방 만들기 500 오류 수정 및 연결 안정성 대폭 개선\n- 오늘 챙길 일 카운트에 매일 루틴 통합\n- 루틴 카드 원터치 완료 토글 및 심플 디자인 개편\n- 카드 길게 눌러 내용/날짜 수정 및 즉시 삭제 지원\n- 상단 초대 코드 입력 버튼 상시 제공 및 중립 초대 문구\n- 홈 화면 위젯 실시간 자동 갱신\n- 음성 인식 개선: 녹음 중지 버튼(■) 및 실시간 자막 스트리밍"
    }), 200


@app.route("/download/app-latest.apk", methods=["GET"])
def download_latest_apk():
    """최신 APK 다운로드 제공 (서버 static 파일 또는 깃허브 최신 릴리스 리다이렉트)"""
    apk_file = os.path.join(os.path.dirname(__file__), "static", "app-latest.apk")
    if os.path.exists(apk_file):
        return send_file(apk_file, as_attachment=True, download_name="todak-todak.apk")

    # 깃허브 최신 릴리스로 안전하게 폴백 (신규 고정 자산명 → 구 자산명 순)
    return redirect("https://github.com/davidhunchoi/todak-todak/releases/latest/download/app-release.apk")


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    app.run(host="0.0.0.0", port=port, debug=False)
