// 로그인 화면: 비밀번호 보기/숨기기, Caps Lock 경고, 칸별 오류(빈 칸, 학번 형식), 오류 때 흔들기.
// 스크립트가 있을 때만 브라우저 기본 검증 말풍선 대신 칸 아래에 오류를 보여 준다. 문구는 서버가 data-*에 넣어 준다.
(() => {
	const form = document.getElementById('login-form');
	if (!form) {
		return;
	}
	const username = document.getElementById('username');
	const password = document.getElementById('password');
	const usernameError = document.getElementById('username-error');
	const passwordError = document.getElementById('password-error');
	const toggle = document.getElementById('password-toggle');
	const caps = document.getElementById('caps-warning');

	form.noValidate = true;

	toggle.hidden = false;
	toggle.addEventListener('click', () => {
		const show = password.type === 'password';
		password.type = show ? 'text' : 'password';
		toggle.setAttribute('aria-pressed', String(show));
		toggle.setAttribute('aria-label', show ? toggle.dataset.hide : toggle.dataset.show);
	});

	function updateCaps(event) {
		if (typeof event.getModifierState === 'function') {
			caps.hidden = !event.getModifierState('CapsLock');
		}
	}
	password.addEventListener('keydown', updateCaps);
	password.addEventListener('keyup', updateCaps);
	password.addEventListener('blur', () => {
		caps.hidden = true;
	});

	function usernameMessage() {
		if (username.validity.valueMissing) {
			return usernameError.dataset.missing;
		}
		return username.validity.patternMismatch ? usernameError.dataset.pattern : '';
	}

	function show(input, error, message) {
		// 숨길 때 글자도 지운다. aria-describedby로 직접 가리킨 요소는 숨겨도 설명으로 읽히기 때문이다
		error.textContent = message || '';
		error.hidden = !message;
		input.setAttribute('aria-invalid', String(Boolean(message)));
	}

	function shake() {
		form.classList.remove('shake');
		// 클래스를 뗐다 다시 붙여야 애니메이션이 다시 돈다
		void form.offsetWidth;
		form.classList.add('shake');
	}

	form.addEventListener('submit', (event) => {
		const usernameProblem = usernameMessage();
		const passwordMissing = password.value === '';
		show(username, usernameError, usernameProblem);
		show(password, passwordError, passwordMissing ? passwordError.dataset.missing : '');
		if (usernameProblem || passwordMissing) {
			event.preventDefault();
			shake();
			(usernameProblem ? username : password).focus();
		}
	});

	// 고치는 중에는 오류를 바로 걷는다(새 오류는 다시 제출할 때 보여 준다)
	username.addEventListener('input', () => {
		if (!usernameError.hidden && !usernameMessage()) {
			show(username, usernameError, '');
		}
	});
	password.addEventListener('input', () => {
		if (!passwordError.hidden && password.value !== '') {
			show(password, passwordError, '');
		}
	});
})();
