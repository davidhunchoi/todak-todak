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


init_local_db()


def generate_invite_code():
    """0, O, 1, I를 배제한 6자리 안전 난수 코드 생성"""
    pool = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    raw = "".join(secrets.choice(pool) for _ in range(6))
    return f"{raw[:3]}-{raw[3:]}"


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
    """새 1:1 스페이스 생성 및 6자리 일회성 코드 발급"""
    data = request.json or {}
    title = data.get("title", "우리 공간").strip()
    theme_color = data.get("theme_color", "CORAL").upper()
    user_id = data.get("user_id") or str(uuid.uuid4())

    space_id = str(uuid.uuid4())
    now_ms = int(time.time() * 1000)
    code = generate_invite_code()
    expires_at = now_ms + (10 * 60 * 1000)  # 10분 유효

    db = get_db()
    try:
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
                (code.replace("-", ""), space_id, user_id, expires_at)
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


@app.route("/api/spaces/join", methods=["POST"])
def join_space():
    """6자리 초대 코드로 참여 (참여 즉시 코드 영구 소멸)"""
    data = request.json or {}
    raw_code = data.get("code", "").replace("-", "").replace(" ", "").upper()
    user_id = data.get("user_id") or str(uuid.uuid4())

    if len(raw_code) != 6:
        return jsonify({"error": "올바른 6자리 초대 코드를 입력해 주세요."}), 400

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
@app.route("/api/spaces/<space_id>/routines", methods=["GET"])
def get_routines(space_id):
    """매일 루틴 목록 조회 (자정 자동 리셋 계산)"""
    db = get_db()
    today_str = get_today_date_string()
    try:
        res = db.execute("SELECT * FROM routines WHERE space_id = ? ORDER BY created_at ASC", (space_id,))
        rows = res.fetchall() if hasattr(res, "fetchall") else res.rows
        routines = []
        for r in rows:
            last_date = r["last_completed_date"] if hasattr(r, "keys") else r[5]
            last_time = r["last_completed_time"] if hasattr(r, "keys") else r[6]
            is_done_today = (last_date == today_str)

            routines.append({
                "id": r["id"] if hasattr(r, "keys") else r[0],
                "title": r["title"] if hasattr(r, "keys") else r[2],
                "icon_type": r["icon_type"] if hasattr(r, "keys") else r[3],
                "is_done_today": is_done_today,
                "last_completed_time": last_time if is_done_today else ""
            })
        return jsonify({"routines": routines}), 200
    finally:
        if hasattr(db, "close"):
            db.close()


@app.route("/api/spaces/<space_id>/routines/<routine_id>/check", methods=["POST"])
def check_routine(space_id, routine_id):
    """
    약 먹기 원터치 완료 체크
    핵심: 누른 즉시 한국어 시각('오전 08:25')을 DB에 영구 기록
    """
    today_str = get_today_date_string()
    time_str = get_current_korean_time()

    db = get_db()
    try:
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
CURRENT_APP_VERSION_CODE = 2
CURRENT_APP_VERSION_NAME = "1.1.0"

@app.route("/api/version", methods=["GET"])
def get_app_version():
    """앱 최신 버전 조회 및 인앱 자동 업데이트 정보 반환"""
    return jsonify({
        "version_code": CURRENT_APP_VERSION_CODE,
        "version_name": CURRENT_APP_VERSION_NAME,
        "apk_url": "https://todak-todak.onrender.com/download/app-latest.apk",
        "changelog": "글로벌 다국어(한국어/영어) 음성 인식과 여유로운 발화 대기 시간이 적용되었습니다! 🌸"
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
