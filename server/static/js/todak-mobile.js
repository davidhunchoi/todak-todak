// 토닥토닥 모바일 웹 & PWA 클라이언트 로직
(function () {
  'use strict';

  // State
  const state = {
    spaceId: localStorage.getItem('todak_space_id') || '',
    spaceTitle: localStorage.getItem('todak_space_title') || '우리 가족 방',
    userId: localStorage.getItem('todak_user_id') || '',
    userName: localStorage.getItem('todak_user_name') || '자녀',
    currentTab: 'tasks', // 'tasks' | 'routines' | 'chats'
    tasks: [],
    routines: [],
    chats: [],
    pollTimer: null,
    speechRecognition: null
  };

  // DOM Elements
  const el = {
    appContainer: document.getElementById('app-container'),
    viewJoin: document.getElementById('view-join'),
    viewMain: document.getElementById('view-main'),
    headerSpaceTitle: document.getElementById('header-space-title'),
    headerUserBadge: document.getElementById('header-user-badge'),
    btnChangeSpace: document.getElementById('btn-change-space'),
    tabTasks: document.getElementById('tab-tasks'),
    tabRoutines: document.getElementById('tab-routines'),
    tabChats: document.getElementById('tab-chats'),
    secTasks: document.getElementById('sec-tasks'),
    secRoutines: document.getElementById('sec-routines'),
    secChats: document.getElementById('sec-chats'),
    pendingTaskList: document.getElementById('pending-task-list'),
    completedTaskList: document.getElementById('completed-task-list'),
    routineList: document.getElementById('routine-list'),
    chatContainer: document.getElementById('chat-container'),
    chatInput: document.getElementById('chat-input'),
    btnSendChat: document.getElementById('btn-send-chat'),
    fabAdd: document.getElementById('fab-add'),
    modalAddTask: document.getElementById('modal-add-task'),
    btnVoiceStt: document.getElementById('btn-voice-stt'),
    inputTaskTitle: document.getElementById('input-task-title'),
    inputTaskAssignee: document.getElementById('input-task-assignee'),
    inputTaskDate: document.getElementById('input-task-date'),
    chkAlarm: document.getElementById('chk-alarm'),
    alarmTimeRow: document.getElementById('alarm-time-row'),
    inputAlarmTime: document.getElementById('input-alarm-time'),
    btnSaveTask: document.getElementById('btn-save-task'),
    btnCloseModal: document.getElementById('btn-close-modal'),
    // Join view elements
    inputJoinName: document.getElementById('input-join-name'),
    inputJoinCode: document.getElementById('input-join-code'),
    btnSubmitJoin: document.getElementById('btn-submit-join'),
    btnCreateNewSpace: document.getElementById('btn-create-new-space'),
    iosBanner: document.getElementById('ios-banner'),
    btnCloseBanner: document.getElementById('btn-close-banner')
  };

  // 1. 초기화
  function init() {
    registerServiceWorker();
    checkIosPwaBanner();
    setupEventListeners();

    if (state.spaceId && state.userId) {
      showMainView();
      loadAllData();
      startPolling();
    } else {
      showJoinView();
    }
  }

  // PWA 서비스 워커 등록
  function registerServiceWorker() {
    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.register('/sw.js').catch(err => {
        console.log('SW registration failed:', err);
      });
    }
  }

  // iOS 사파리 홈 화면 추가 안내
  function checkIosPwaBanner() {
    const isIos = /iphone|ipad|ipod/.test(window.navigator.userAgent.toLowerCase());
    const isStandalone = window.navigator.standalone === true || window.matchMedia('(display-mode: standalone)').matches;
    const bannerClosed = sessionStorage.getItem('todak_banner_closed') === 'true';

    if (isIos && !isStandalone && !bannerClosed) {
      el.iosBanner.style.display = 'flex';
    } else {
      el.iosBanner.style.display = 'none';
    }
  }

  // 화면 전환
  function showJoinView() {
    el.viewJoin.style.display = 'flex';
    el.viewMain.style.display = 'none';
    if (el.fabAdd) el.fabAdd.style.display = 'none';
  }

  function showMainView() {
    el.viewJoin.style.display = 'none';
    el.viewMain.style.display = 'block';
    el.headerSpaceTitle.textContent = state.spaceTitle;
    el.headerUserBadge.textContent = `${state.userName} 님`;
    switchTab(state.currentTab);
  }

  // 탭 전환
  function switchTab(tabName) {
    state.currentTab = tabName;
    document.querySelectorAll('.tab-item').forEach(t => t.classList.remove('active'));
    el.secTasks.style.display = 'none';
    el.secRoutines.style.display = 'none';
    el.secChats.style.display = 'none';

    if (tabName === 'tasks') {
      el.tabTasks.classList.add('active');
      el.secTasks.style.display = 'block';
      if (el.fabAdd) el.fabAdd.style.display = 'flex';
    } else if (tabName === 'routines') {
      el.tabRoutines.classList.add('active');
      el.secRoutines.style.display = 'block';
      if (el.fabAdd) el.fabAdd.style.display = 'none';
    } else if (tabName === 'chats') {
      el.tabChats.classList.add('active');
      el.secChats.style.display = 'block';
      if (el.fabAdd) el.fabAdd.style.display = 'none';
      scrollChatToBottom();
    }
  }

  // 이벤트 리스너 등록
  function setupEventListeners() {
    // 탭 클릭
    el.tabTasks.addEventListener('click', () => switchTab('tasks'));
    el.tabRoutines.addEventListener('click', () => switchTab('routines'));
    el.tabChats.addEventListener('click', () => switchTab('chats'));

    // 배너 닫기
    if (el.btnCloseBanner) {
      el.btnCloseBanner.addEventListener('click', () => {
        el.iosBanner.style.display = 'none';
        sessionStorage.setItem('todak_banner_closed', 'true');
      });
    }

    // 방 변경 / 나가기
    el.btnChangeSpace.addEventListener('click', () => {
      if (confirm('현재 방에서 나가시겠어요? (언제든 초대코드로 다시 입장할 수 있어요)')) {
        localStorage.removeItem('todak_space_id');
        state.spaceId = '';
        stopPolling();
        showJoinView();
      }
    });

    // 4자리 코드로 참여
    el.btnSubmitJoin.addEventListener('click', joinSpaceWithCode);
    el.btnCreateNewSpace.addEventListener('click', createNewSpace);

    // 할 일 추가 모달
    el.fabAdd.addEventListener('click', () => {
      openAddTaskModal();
    });
    el.btnCloseModal.addEventListener('click', closeAddTaskModal);
    el.modalAddTask.addEventListener('click', (e) => {
      if (e.target === el.modalAddTask) closeAddTaskModal();
    });

    // 알람 스위치 토글 시 시간 입력창 표시
    el.chkAlarm.addEventListener('change', () => {
      el.alarmTimeRow.style.display = el.chkAlarm.checked ? 'flex' : 'none';
    });

    // 음성 인식 STT
    el.btnVoiceStt.addEventListener('click', toggleVoiceInput);

    // 할 일 저장
    el.btnSaveTask.addEventListener('click', saveNewTask);

    // 채팅 전송
    el.btnSendChat.addEventListener('click', sendChatMessage);
    el.chatInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        sendChatMessage();
      }
    });
  }

  // 방 참여 (초대코드)
  async function joinSpaceWithCode() {
    const code = el.inputJoinCode.value.trim();
    const name = el.inputJoinName.value.trim() || '자녀';

    if (!code || code.length !== 4) {
      alert('아빠가 알려준 4자리 숫자 코드를 정확히 입력해 주세요 🌸');
      return;
    }

    const userId = state.userId || 'user_' + Math.random().toString(36).substring(2, 9);

    try {
      const res = await fetch('/api/spaces/join', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: code, user_id: userId, user_name: name })
      });
      const data = await res.json();

      if (!res.ok) {
        alert(data.error || '초대 코드가 만료되었거나 올바르지 않습니다.');
        return;
      }

      state.spaceId = data.space_id;
      state.spaceTitle = data.space_title || '우리 가족 방';
      state.userId = userId;
      state.userName = name;

      localStorage.setItem('todak_space_id', state.spaceId);
      localStorage.setItem('todak_space_title', state.spaceTitle);
      localStorage.setItem('todak_user_id', state.userId);
      localStorage.setItem('todak_user_name', state.userName);

      showMainView();
      loadAllData();
      startPolling();
    } catch (err) {
      alert('서버와 통신할 수 없습니다: ' + err.message);
    }
  }

  // 새 방 만들기
  async function createNewSpace() {
    const name = prompt('만드실 가족 공간의 이름을 적어주세요:', '우리 가족 방 🌸');
    if (!name) return;

    const myName = el.inputJoinName.value.trim() || '자녀';
    const userId = state.userId || 'user_' + Math.random().toString(36).substring(2, 9);

    try {
      const res = await fetch('/api/spaces', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: name, user_id: userId, user_name: myName })
      });
      const data = await res.json();

      if (!res.ok) {
        alert(data.error || '방 생성에 실패했습니다.');
        return;
      }

      state.spaceId = data.space_id;
      state.spaceTitle = data.title || name;
      state.userId = userId;
      state.userName = myName;

      localStorage.setItem('todak_space_id', state.spaceId);
      localStorage.setItem('todak_space_title', state.spaceTitle);
      localStorage.setItem('todak_user_id', state.userId);
      localStorage.setItem('todak_user_name', state.userName);

      alert(`새 방이 만들어졌어요! 초대코드 [${data.invite_code}] 를 가족에게 알려주세요 💕`);
      showMainView();
      loadAllData();
      startPolling();
    } catch (err) {
      alert('방 생성 오류: ' + err.message);
    }
  }

  // 전체 데이터 불러오기
  async function loadAllData() {
    if (!state.spaceId) return;
    await Promise.all([loadTasks(), loadRoutines(), loadChats()]);
  }

  // 5초 간격 실시간 동기화 폴링
  function startPolling() {
    stopPolling();
    state.pollTimer = setInterval(() => {
      if (state.spaceId) {
        loadAllData();
      }
    }, 5000);
  }

  function stopPolling() {
    if (state.pollTimer) {
      clearInterval(state.pollTimer);
      state.pollTimer = null;
    }
  }

  // 2. 할 일 (Tasks) 로직
  async function loadTasks() {
    try {
      const res = await fetch(`/api/spaces/${state.spaceId}/tasks`);
      if (!res.ok) return;
      const data = await res.json();
      state.tasks = data.tasks || [];
      renderTasks();
    } catch (e) {
      console.error('loadTasks err:', e);
    }
  }

  function renderTasks() {
    const pending = state.tasks.filter(t => !t.is_completed);
    const completed = state.tasks.filter(t => t.is_completed);

    // 미완료 렌더링
    if (pending.length === 0) {
      el.pendingTaskList.innerHTML = `
        <div style="text-align:center; padding: 24px; color: #8E9AAF; font-size:14px;">
          남은 할 일이 없어요! 편안한 시간 보내세요 ☕
        </div>`;
    } else {
      el.pendingTaskList.innerHTML = pending.map(t => createTaskCardHtml(t)).join('');
    }

    // 완료 렌더링
    if (completed.length === 0) {
      el.completedTaskList.innerHTML = '';
    } else {
      el.completedTaskList.innerHTML = completed.map(t => createTaskCardHtml(t)).join('');
    }

    // 체크 및 삭제 이벤트 바인딩
    document.querySelectorAll('.task-check-circle').forEach(btn => {
      btn.addEventListener('click', (e) => {
        const card = e.currentTarget.closest('.task-card');
        const taskId = card.dataset.taskId;
        const currentCompleted = card.classList.contains('completed');
        toggleTask(taskId, currentCompleted);
      });
    });

    document.querySelectorAll('.task-del-btn').forEach(btn => {
      btn.addEventListener('click', (e) => {
        const card = e.currentTarget.closest('.task-card');
        const taskId = card.dataset.taskId;
        deleteTask(taskId);
      });
    });
  }

  function createTaskCardHtml(task) {
    const isDone = task.is_completed;
    const hasAlarm = task.has_alarm || (task.alarm_time && task.alarm_time.length > 0);
    const alarmTime = task.alarm_time || '';

    let alarmBadge = '';
    if (hasAlarm && alarmTime) {
      alarmBadge = `<span class="badge badge-alarm">⏰ ${formatAmPm(alarmTime)}</span>`;
    }

    let dueBadge = '';
    if (task.due_date) {
      dueBadge = `<span class="badge badge-due">📅 ${task.due_date}</span>`;
    }

    let assigneeBadge = '';
    if (task.assignee_name) {
      assigneeBadge = `<span class="badge badge-assignee">👤 ${task.assignee_name}</span>`;
    }

    return `
      <div class="task-card ${isDone ? 'completed' : ''}" data-task-id="${task.id}">
        <div class="task-check-circle">
          ${isDone ? '✓' : ''}
        </div>
        <div class="task-info">
          <div class="task-title">${escapeHtml(task.title)}</div>
          <div class="task-meta">
            ${assigneeBadge}
            ${dueBadge}
            ${alarmBadge}
          </div>
        </div>
        <button class="task-del-btn" title="삭제">✕</button>
      </div>
    `;
  }

  async function toggleTask(taskId, currentCompleted) {
    try {
      // 낙관적 UI 업데이트
      const task = state.tasks.find(t => t.id === taskId);
      if (task) {
        task.is_completed = !currentCompleted;
        renderTasks();
      }

      await fetch(`/api/spaces/${state.spaceId}/tasks/${taskId}/toggle`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ is_completed: !currentCompleted })
      });
      loadTasks();
    } catch (e) {
      console.error('toggleTask err:', e);
      loadTasks();
    }
  }

  async function deleteTask(taskId) {
    if (!confirm('이 할 일을 삭제하시겠어요?')) return;
    try {
      await fetch(`/api/spaces/${state.spaceId}/tasks/${taskId}`, {
        method: 'DELETE'
      });
      loadTasks();
    } catch (e) {
      console.error('deleteTask err:', e);
    }
  }

  function openAddTaskModal() {
    el.inputTaskTitle.value = '';
    el.inputTaskAssignee.value = state.userName;
    const today = new Date().toISOString().split('T')[0];
    el.inputTaskDate.value = today;
    el.chkAlarm.checked = false;
    el.alarmTimeRow.style.display = 'none';
    el.inputAlarmTime.value = '10:00';
    el.modalAddTask.classList.add('active');
    el.inputTaskTitle.focus();
  }

  function closeAddTaskModal() {
    el.modalAddTask.classList.remove('active');
    stopVoiceInput();
  }

  // Web Speech API 음성인식
  function toggleVoiceInput() {
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRecognition) {
      alert('이 브라우저에서는 음성 인식을 지원하지 않습니다. (사파리 설정에서 마이크 허용 확인)');
      return;
    }

    if (state.speechRecognition) {
      stopVoiceInput();
      return;
    }

    const recog = new SpeechRecognition();
    recog.lang = 'ko-KR';
    recog.interimResults = true;
    recog.maxAlternatives = 1;

    el.btnVoiceStt.classList.add('listening');
    el.btnVoiceStt.innerHTML = '🎙️ 듣고 있어요...';

    recog.onresult = (event) => {
      const transcript = Array.from(event.results)
        .map(result => result[0].transcript)
        .join('');
      el.inputTaskTitle.value = transcript;
    };

    recog.onerror = (event) => {
      console.error('Speech error:', event.error);
      stopVoiceInput();
    };

    recog.onend = () => {
      stopVoiceInput();
    };

    state.speechRecognition = recog;
    recog.start();
  }

  function stopVoiceInput() {
    if (state.speechRecognition) {
      try { state.speechRecognition.stop(); } catch (_) {}
      state.speechRecognition = null;
    }
    el.btnVoiceStt.classList.remove('listening');
    el.btnVoiceStt.innerHTML = '🎙️ 음성 입력';
  }

  async function saveNewTask() {
    const title = el.inputTaskTitle.value.trim();
    if (!title) {
      alert('할 일 내용을 입력해 주세요!');
      return;
    }

    const assignee = el.inputTaskAssignee.value.trim() || state.userName;
    const dueDate = el.inputTaskDate.value || '';
    const hasAlarm = el.chkAlarm.checked;
    const alarmTime = hasAlarm ? (el.inputAlarmTime.value || '10:00') : '';

    const payload = {
      title: title,
      assignee_name: assignee,
      due_date: dueDate,
      has_alarm: hasAlarm ? 1 : 0,
      alarm_time: alarmTime,
      created_by: state.userId
    };

    try {
      const res = await fetch(`/api/spaces/${state.spaceId}/tasks`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      if (res.ok) {
        closeAddTaskModal();
        loadTasks();
      } else {
        const errData = await res.json();
        alert(errData.error || '할 일 저장 실패');
      }
    } catch (e) {
      alert('서버 저장 실패: ' + e.message);
    }
  }

  // 3. 매일 루틴 (Routines) 로직
  async function loadRoutines() {
    try {
      const res = await fetch(`/api/spaces/${state.spaceId}/routines`);
      if (!res.ok) return;
      const data = await res.json();
      state.routines = data.routines || [];
      renderRoutines();
    } catch (e) {
      console.error('loadRoutines err:', e);
    }
  }

  function renderRoutines() {
    if (state.routines.length === 0) {
      el.routineList.innerHTML = `
        <div style="text-align:center; padding: 30px; color: #8E9AAF; font-size:14px;">
          등록된 매일 루틴이 없어요 🌿<br>부모님 앱에서 영양제, 약 복용 등을 등록해 보세요!
        </div>`;
      return;
    }

    const todayStr = new Date().toISOString().split('T')[0];

    el.routineList.innerHTML = state.routines.map(r => {
      const isDoneToday = r.last_completed_date === todayStr;
      const icon = r.icon_type === 'EXERCISE' ? '🏃' : (r.icon_type === 'WATER' ? '💧' : '💊');

      return `
        <div class="routine-card" data-routine-id="${r.id}">
          <div class="routine-left">
            <div class="routine-icon">${icon}</div>
            <div>
              <div class="routine-name">${escapeHtml(r.title)}</div>
              <div class="routine-time">매일 ${r.target_time || '08:30'} 챙기기</div>
            </div>
          </div>
          <button class="routine-btn ${isDoneToday ? 'done' : 'pending'}" ${isDoneToday ? 'disabled' : ''}>
            ${isDoneToday ? `✓ ${r.last_completed_time || '완료'}` : '챙겼어요!'}
          </button>
        </div>
      `;
    }).join('');

    document.querySelectorAll('.routine-btn.pending').forEach(btn => {
      btn.addEventListener('click', (e) => {
        const card = e.currentTarget.closest('.routine-card');
        const routineId = card.dataset.routineId;
        checkRoutine(routineId);
      });
    });
  }

  async function checkRoutine(routineId) {
    try {
      const res = await fetch(`/api/spaces/${state.spaceId}/routines/${routineId}/check`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ user_id: state.userId })
      });
      if (res.ok) {
        loadRoutines();
      }
    } catch (e) {
      console.error('checkRoutine err:', e);
    }
  }

  // 4. 따뜻한 한마디 (Chats) 로직
  async function loadChats() {
    try {
      const res = await fetch(`/api/spaces/${state.spaceId}/chats`);
      if (!res.ok) return;
      const data = await res.json();
      state.chats = data.chats || [];
      renderChats();
    } catch (e) {
      console.error('loadChats err:', e);
    }
  }

  function renderChats() {
    if (state.chats.length === 0) {
      el.chatContainer.innerHTML = `
        <div style="text-align:center; padding: 40px; color: #8E9AAF; font-size:14px;">
          첫 번째 따뜻한 응원의 한마디를 남겨보세요 💕
        </div>`;
      return;
    }

    el.chatContainer.innerHTML = state.chats.map(c => {
      const isMe = c.user_id === state.userId;
      return `
        <div class="chat-bubble ${isMe ? 'me' : 'other'}">
          ${!isMe ? `<div class="chat-author">${escapeHtml(c.user_name || '가족')}</div>` : ''}
          <div class="chat-text">${escapeHtml(c.message)}</div>
          <div class="chat-time">${c.created_at_time || ''}</div>
        </div>
      `;
    }).join('');

    if (state.currentTab === 'chats') {
      scrollChatToBottom();
    }
  }

  async function sendChatMessage() {
    const msg = el.chatInput.value.trim();
    if (!msg) return;

    el.chatInput.value = '';
    try {
      const res = await fetch(`/api/spaces/${state.spaceId}/chats`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          user_id: state.userId,
          user_name: state.userName,
          message: msg
        })
      });
      if (res.ok) {
        await loadChats();
        scrollChatToBottom();
      }
    } catch (e) {
      console.error('sendChatMessage err:', e);
    }
  }

  function scrollChatToBottom() {
    setTimeout(() => {
      window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
    }, 100);
  }

  // 유틸 함수
  function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/[&<>'"]/g, tag => ({
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      "'": '&#39;',
      '"': '&quot;'
    }[tag] || tag));
  }

  function formatAmPm(timeStr) {
    if (!timeStr || !timeStr.includes(':')) return timeStr;
    const parts = timeStr.split(':');
    let hour = parseInt(parts[0], 10);
    const minute = parts[1];
    const ampm = hour >= 12 ? '오후' : '오전';
    hour = hour % 12;
    if (hour === 0) hour = 12;
    return `${ampm} ${hour}:${minute}`;
  }

  // 시작!
  document.addEventListener('DOMContentLoaded', init);
})();
