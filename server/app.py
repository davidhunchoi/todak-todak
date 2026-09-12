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

# 환경 변수 설정 (Turso 연동)
TURSO_DB_URL = os.getenv("TURSO_DATABASE_URL")
TURSO_AUTH_TOKEN = os.getenv("TURSO_AUTH_TOKEN")

# 한국 시간대 (KST)
KST = ZoneInfo("Asia/Seoul")


def get_db():
    """
    Turso 클라우드 데이터베이스 연결 객체 반환.
    환경변수가 없을 경우 로컬 SQLite(local_dev.db)로 자동 폴백 지원.
    """
    if TURSO_DB_URL and TURSO_AUTH_TOKEN:
        import libsql_client
        # Turso libSQL 클라이언트
        url = TURSO_DB_URL.replace("libsql://", "https://")
        return libsql_client.create_client_sync(url=url, auth_token=TURSO_AUTH_TOKEN)
    else:
        # 로컬 테스트용 sqlite3
        conn = sqlite3.connect("local_dev.db")
        conn.row_factory = sqlite3.Row
        return conn


def init_local_db():
    """로컬 DB 초기 테이블 생성 (로컬 테스트용)"""
    if not (TURSO_DB_URL and TURSO_AUTH_TOKEN):
        schema_path = os.path.join(os.path.dirname(__file__), "schema.sql")
        if os.path.exists(schema_path):
            with open(schema_path, "r", encoding="utf-8") as f:
                schema_sql = f.read()
            conn = sqlite3.connect("local_dev.db")
            conn.executescript(schema_sql)
            conn.close()


def init_db_tables():
    """기존 DB(Turso/SQLite 공통)에 신규 테이블 자동 추가 (구버전 DB 마이그레이션)"""
    db = get_db()
    try:
        db.execute(
            """CREATE TABLE IF NOT EXISTS routine_checks (
                routine_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                completed_date TEXT NOT NULL,
                completed_time TEXT NOT NULL,
                checked_at INTEGER NOT NULL,
                PRIMARY KEY (routine_id, user_id)
            )"""
        )
        db.execute(
            """CREATE TABLE IF NOT EXISTS space_delete_requests (
                space_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                requested_at INTEGER NOT NULL,
                PRIMARY KEY (space_id, user_id)
            )"""
        )
        if hasattr(db, "commit"):
            db.commit()
    finally:
        if hasattr(db, "close"):
            db.close()


init_local_db()
init_db_tables()


def generate_invite_code():
    """4자리 숫자 초대 코드 생성 (예: "0837")"""
    return f"{secrets.randbelow(10000):04d}"


def generate_unique_invite_code(db):
    """다른 유효 초대 코드와 중복되지 않는 4자리 숫자 코드 생성 (충돌 시 재시도)"""
    code = generate_invite_code()
    for _ in range(30):
        res = db.execute("SELECT 1 FROM invites WHERE code = ?", (code,))
        rows = res.fetchall() if hasattr(res, "fetchall") else res.rows
        if not rows:
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
        dup_res = db.execute(
            """SELECT 1 FROM spaces s
               JOIN space_members sm ON s.id = sm.space_id
               WHERE sm.user_id = ? AND s.title = ?""",
            (user_id, title)
        )
        dup_rows = dup_res.fetchall() if hasattr(dup_res, "fetchall") else dup_res.rows
        if dup_rows:
            return jsonify({"error": "같은 이름의 방이 이미 있어요. 다른 이름을 사용해 주세요."}), 409

        # 동시에 유효한 다른 초대 코드와 중복되지 않는 4자리 숫자 코드 생성
        code = generate_unique_invite_code(db)
        # Turso / SQLite 공통 처리
        if hasattr(db, "execute"):
            db.execute(
                "INSERT INTO spaces (id, title, theme_color, created_by, created_at) VALUES (?, ?, ?, ?, ?)",
                (space_id, title, theme_color, user_id, now_ms)
            )
            db.execute(
                "INSERT INTO space_members (space_id, user_id, joined_at) VALUES (?, ?, ?)",
                (space_id, user_id, now_ms)
            )
            db.execute(
                "INSERT INTO invites (code, space_id, created_by, expires_at) VALUES (?, ?, ?, ?)",
                (code, space_id, user_id, expires_at)
            )
            if hasattr(db, "commit"):
                db.commit()

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
        if hasattr(db, "close"):
            db.close()


@app.route("/api/spaces/<space_id>/invite", methods=["GET"])
def get_space_invite(space_id):
    """스페이스의 유효한 초대 코드 조회 (없거나 만료 시 새로 발급)"""
    db = get_db()
    try:
        now_ms = int(time.time() * 1000)

        # 스페이스 존재 여부 확인
        s_res = db.execute("SELECT id FROM spaces WHERE id = ?", (space_id,))
        s_rows = s_res.fetchall() if hasattr(s_res, "fetchall") else s_res.rows
        if not s_rows:
            return jsonify({"error": "존재하지 않는 스페이스입니다."}), 404

        # 아직 유효한 초대 코드가 있으면 재사용 (10분 내 반복 조회 지원)
        i_res = db.execute(
            "SELECT code, expires_at FROM invites WHERE space_id = ? AND expires_at > ?",
            (space_id, now_ms)
        )
        i_rows = i_res.fetchall() if hasattr(i_res, "fetchall") else i_res.rows
        if i_rows:
            raw = i_rows[0]["code"] if isinstance(i_rows[0], dict) or hasattr(i_rows[0], "keys") else i_rows[0][0]
            expires_at = i_rows[0]["expires_at"] if isinstance(i_rows[0], dict) or hasattr(i_rows[0], "keys") else i_rows[0][1]
            remaining = max(1, int((expires_at - now_ms) / 60000))
            return jsonify({"invite_code": raw, "expires_in_minutes": remaining}), 200

        # 유효 코드가 없으면 새로 발급 (기존 만료 코드 정리 후)
        db.execute("DELETE FROM invites WHERE space_id = ?", (space_id,))
        code = generate_unique_invite_code(db)
        expires_at = now_ms + (10 * 60 * 1000)
        db.execute(
            "INSERT INTO invites (code, space_id, created_by, expires_at) VALUES (?, ?, ?, ?)",
            (code.replace("-", ""), space_id, None, expires_at)
        )
        if hasattr(db, "commit"):
            db.commit()

        return jsonify({"invite_code": code, "expires_in_minutes": 10}), 200
    finally:
        if hasattr(db, "close"):
            db.close()


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
        res = db.execute("SELECT * FROM invites WHERE code = ?", (raw_code,))
        rows = res.fetchall() if hasattr(res, "fetchall") else res.rows
        if not rows:
            return jsonify({"error": "유효하지 않거나 이미 사용 완료된 초대 코드입니다."}), 404

        invite = rows[0]
        space_id = invite["space_id"] if isinstance(invite, dict) or hasattr(invite, "keys") else invite[1]
        expires_at = invite["expires_at"] if isinstance(invite, dict) or hasattr(invite, "keys") else invite[3]

        if now_ms > expires_at:
            db.execute("DELETE FROM invites WHERE code = ?", (raw_code,))
            if hasattr(db, "commit"):
                db.commit()
            return jsonify({"error": "초대 코드 유효 시간(10분)이 만료되었습니다."}), 410

        # 멤버 추가 및 코드 즉시 소멸
        db.execute(
            "INSERT OR IGNORE INTO space_members (space_id, user_id, joined_at) VALUES (?, ?, ?)",
            (space_id, user_id, now_ms)
        )
        db.execute("DELETE FROM invites WHERE code = ?", (raw_code,))
        if hasattr(db, "commit"):
            db.commit()

        # 스페이스 정보 조회
        s_res = db.execute("SELECT * FROM spaces WHERE id = ?", (space_id,))
        s_rows = s_res.fetchall() if hasattr(s_res, "fetchall") else s_res.rows
        space = s_rows[0]

        return jsonify({
            "message": "성공적으로 연결되었습니다!",
            "space": {
                "id": space_id,
                "title": space["title"] if hasattr(space, "keys") else space[1],
                "theme_color": space["theme_color"] if hasattr(space, "keys") else space[2]
            }
        }), 200
    finally:
        if hasattr(db, "close"):
            db.close()


# ==========================================
# 2. 방 삭제 동의 API (모든 멤버 동의 시 삭제)
# ==========================================
def delete_space_data(db, space_id):
    """스페이스 및 관련 데이터 전체 삭제 (할 일, 루틴, 초대 코드 포함)"""
    db.execute(
        "DELETE FROM routine_checks WHERE routine_id IN (SELECT id FROM routines WHERE space_id = ?)",
        (space_id,)
    )
    db.execute("DELETE FROM tasks WHERE space_id = ?", (space_id,))
    db.execute("DELETE FROM routines WHERE space_id = ?", (space_id,))
    db.execute("DELETE FROM invites WHERE space_id = ?", (space_id,))
    db.execute("DELETE FROM space_delete_requests WHERE space_id = ?", (space_id,))
    db.execute("DELETE FROM space_members WHERE space_id = ?", (space_id,))
    db.execute("DELETE FROM spaces WHERE id = ?", (space_id,))
    if hasattr(db, "commit"):
        db.commit()


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
        s_res = db.execute("SELECT id FROM spaces WHERE id = ?", (space_id,))
        s_rows = s_res.fetchall() if hasattr(s_res, "fetchall") else s_res.rows
        if not s_rows:
            return jsonify({"error": "이미 삭제되었거나 존재하지 않는 방입니다."}), 404

        m_res = db.execute("SELECT user_id FROM space_members WHERE space_id = ?", (space_id,))
        m_rows = m_res.fetchall() if hasattr(m_res, "fetchall") else m_res.rows
        member_ids = [r["user_id"] if isinstance(r, dict) or hasattr(r, "keys") else r[0] for r in m_rows]
        member_count = len(member_ids)

        db.execute(
            "INSERT OR IGNORE INTO space_delete_requests (space_id, user_id, requested_at) VALUES (?, ?, ?)",
            (space_id, user_id, now_ms)
        )
        if hasattr(db, "commit"):
            db.commit()

        p_res = db.execute("SELECT user_id FROM space_delete_requests WHERE space_id = ?", (space_id,))
        p_rows = p_res.fetchall() if hasattr(p_res, "fetchall") else p_res.rows
        pending_ids = [r["user_id"] if isinstance(r, dict) or hasattr(r, "keys") else r[0] for r in p_rows]
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
        if hasattr(db, "close"):
            db.close()


@app.route("/api/spaces/<space_id>/delete-request", methods=["GET"])
def get_space_delete_status(space_id):
    """방 삭제 요청 상태 조회 (내 요청 여부 / 상대 요청 여부 / 멤버 수)"""
    my_user_id = request.args.get("user_id", "")
    db = get_db()
    try:
        s_res = db.execute("SELECT id FROM spaces WHERE id = ?", (space_id,))
        s_rows = s_res.fetchall() if hasattr(s_res, "fetchall") else s_res.rows
        if not s_rows:
            # 방이 이미 삭제됨
            return jsonify({
                "deleted": True,
                "my_requested": False,
                "other_requested": False,
                "member_count": 0
            }), 200

        m_res = db.execute("SELECT user_id FROM space_members WHERE space_id = ?", (space_id,))
        m_rows = m_res.fetchall() if hasattr(m_res, "fetchall") else m_res.rows
        member_count = len(m_rows)

        p_res = db.execute("SELECT user_id FROM space_delete_requests WHERE space_id = ?", (space_id,))
        p_rows = p_res.fetchall() if hasattr(p_res, "fetchall") else p_res.rows
        pending_ids = [r["user_id"] if isinstance(r, dict) or hasattr(r, "keys") else r[0] for r in p_rows]

        return jsonify({
            "deleted": False,
            "my_requested": my_user_id in pending_ids,
            "other_requested": any(uid != my_user_id for uid in pending_ids),
            "member_count": member_count
        }), 200
    finally:
        if hasattr(db, "close"):
            db.close()


@app.route("/api/spaces/<space_id>/delete-request", methods=["DELETE"])
def cancel_space_delete(space_id):
    """삭제 요청 취소/거절 — 해당 방의 모든 대기 요청 제거"""
    db = get_db()
    try:
        db.execute("DELETE FROM space_delete_requests WHERE space_id = ?", (space_id,))
        if hasattr(db, "commit"):
            db.commit()
        return jsonify({"message": "삭제 요청이 취소되었습니다."}), 200
    finally:
        if hasattr(db, "close"):
            db.close()


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
        res = db.execute(query, (user_id,))
        rows = res.fetchall() if hasattr(res, "fetchall") else res.rows
        spaces = []
        for r in rows:
            spaces.append({
                "id": r["id"] if hasattr(r, "keys") else r[0],
                "title": r["title"] if hasattr(r, "keys") else r[1],
                "theme_color": r["theme_color"] if hasattr(r, "keys") else r[2]
            })
        return jsonify({"spaces": spaces}), 200
    finally:
        if hasattr(db, "close"):
            db.close()


# ==========================================
# 2. 할 일 (Task) API
# ==========================================
@app.route("/api/spaces/<space_id>/tasks", methods=["GET"])
def get_tasks(space_id):
    """할 일 목록 조회"""
    db = get_db()
    try:
        res = db.execute(
            "SELECT * FROM tasks WHERE space_id = ? ORDER BY is_completed ASC, due_date ASC, created_at ASC",
            (space_id,)
        )
        rows = res.fetchall() if hasattr(res, "fetchall") else res.rows
        tasks = []
        for r in rows:
            tasks.append({
                "id": r["id"] if hasattr(r, "keys") else r[0],
                "title": r["title"] if hasattr(r, "keys") else r[2],
                "due_date": r["due_date"] if hasattr(r, "keys") else r[3],
                "is_completed": bool(r["is_completed"] if hasattr(r, "keys") else r[4])
            })
        return jsonify({"tasks": tasks}), 200
    finally:
        if hasattr(db, "close"):
            db.close()


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
        db.execute(
            "INSERT INTO tasks (id, space_id, title, due_date, is_completed, created_by, created_at) VALUES (?, ?, ?, ?, 0, ?, ?)",
            (task_id, space_id, title, due_date, user_id, now_ms)
        )
        if hasattr(db, "commit"):
            db.commit()

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
        if hasattr(db, "close"):
            db.close()


@app.route("/api/spaces/<space_id>/tasks/<task_id>", methods=["PATCH"])
def toggle_task(space_id, task_id):
    """할 일 완료 토글"""
    data = request.json or {}
    is_completed = data.get("is_completed", True)
    now_ms = int(time.time() * 1000) if is_completed else None

    db = get_db()
    try:
        db.execute(
            "UPDATE tasks SET is_completed = ?, completed_at = ? WHERE id = ? AND space_id = ?",
            (1 if is_completed else 0, now_ms, task_id, space_id)
        )
        if hasattr(db, "commit"):
            db.commit()
        return jsonify({"message": "수정되었습니다."}), 200
    finally:
        if hasattr(db, "close"):
            db.close()


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
        db.execute(
            "INSERT INTO routines (id, space_id, title, icon_type, target_time, last_completed_date, last_completed_time, created_at) VALUES (?, ?, ?, ?, ?, '', '', ?)",
            (routine_id, space_id, title, icon_type, target_time, now_ms)
        )
        if hasattr(db, "commit"):
            db.commit()

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
        if hasattr(db, "close"):
            db.close()


@app.route("/api/spaces/<space_id>/routines", methods=["GET"])
def get_routines(space_id):
    """매일 루틴 목록 조회 (사람별 오늘 체크 상태 포함, 자정 자동 리셋 계산)"""
    my_user_id = request.args.get("user_id", "")
    db = get_db()
    today_str = get_today_date_string()
    try:
        res = db.execute("SELECT * FROM routines WHERE space_id = ? ORDER BY created_at ASC", (space_id,))
        rows = res.fetchall() if hasattr(res, "fetchall") else res.rows
        routines = []

        # 이 스페이스 루틴들의 오늘 체크 기록을 한 번에 조회 (사람별)
        checks = {}
        try:
            c_res = db.execute(
                """SELECT rc.routine_id, rc.user_id, rc.completed_time
                   FROM routine_checks rc
                   JOIN routines r ON rc.routine_id = r.id
                   WHERE r.space_id = ? AND rc.completed_date = ?""",
                (space_id, today_str)
            )
            c_rows = c_res.fetchall() if hasattr(c_res, "fetchall") else c_res.rows
            for c in c_rows:
                rid = c["routine_id"] if isinstance(c, dict) or hasattr(c, "keys") else c[0]
                uid = c["user_id"] if isinstance(c, dict) or hasattr(c, "keys") else c[1]
                ctime = c["completed_time"] if isinstance(c, dict) or hasattr(c, "keys") else c[2]
                checks.setdefault(rid, []).append((uid, ctime))
        except Exception:
            # routine_checks 테이블이 아직 없는 구버전 DB 호환 (폴백)
            pass

        for r in rows:
            rid = r["id"] if hasattr(r, "keys") else r[0]
            title = r["title"] if hasattr(r, "keys") else r[2]
            icon_type = r["icon_type"] if hasattr(r, "keys") else r[3]
            last_date = r["last_completed_date"] if hasattr(r, "keys") else r[5]
            last_time = r["last_completed_time"] if hasattr(r, "keys") else r[6]
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
        if hasattr(db, "close"):
            db.close()


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
            db.execute(
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
        db.execute(
            "UPDATE routines SET last_completed_date = ?, last_completed_time = ? WHERE id = ? AND space_id = ?",
            (today_str, time_str, routine_id, space_id)
        )
        if hasattr(db, "commit"):
            db.commit()

        return jsonify({
            "message": "약 복용 체크가 완료되었습니다!",
            "last_completed_time": time_str,
            "last_completed_date": today_str
        }), 200
    finally:
        if hasattr(db, "close"):
            db.close()


# ==========================================
# 4. 앱 버전 및 자체 자동 업데이트 API
# ==========================================
CURRENT_APP_VERSION_CODE = 3
CURRENT_APP_VERSION_NAME = "1.2.0"

@app.route("/api/version", methods=["GET"])
def get_app_version():
    """앱 최신 버전 조회 및 인앱 자동 업데이트 정보 반환"""
    return jsonify({
        "version_code": CURRENT_APP_VERSION_CODE,
        "version_name": CURRENT_APP_VERSION_NAME,
        "apk_url": "https://github.com/davidhunchoi/todak-todak/releases/latest/download/app-debug.apk",
        "changelog": "🎉 v1.2.0\n- 4자리 숫자 초대 코드 (카톡으로 설치 링크+코드 한 번에 전송)\n- 매일 반복 루틴 등록 (나와 상대 각각 체크)\n- 캘린더로 마감 날짜 직접 선택\n- 방 이름 중복 방지 & 상대 동의 받은 방 삭제"
    }), 200


@app.route("/download/app-latest.apk", methods=["GET"])
def download_latest_apk():
    """최신 APK 다운로드 제공 (서버 static 파일 또는 깃허브 최신 릴리스 리다이렉트)"""
    apk_file = os.path.join(os.path.dirname(__file__), "static", "app-latest.apk")
    if os.path.exists(apk_file):
        return send_file(apk_file, as_attachment=True, download_name="todak-todak.apk")

    # 깃허브 최신 릴리스로 안전하게 폴백
    return redirect("https://github.com/davidhunchoi/todak-todak/releases/latest/download/app-debug.apk")


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5000))
    app.run(host="0.0.0.0", port=port, debug=False)
