// 관리자 화면 공통 창: data-dialog를 누르면 그 id의 <dialog>를 열고, data-dialog-close나 창 바깥(배경)을 누르면 닫는다.
// data-confirm-form 안의 입력이 data-expected와 같아야 제출 버튼이 켜진다(학기 초기화, 과목 삭제처럼 되돌릴 수 없는 작업).
// data-busy-label이 있는 버튼으로 제출하면 다시 누르지 못하게 끄고 "초기화 중…"처럼 진행 중으로 보여 준다.
(() => {
	// 누르기 시작한 곳도 창 밖이어야 닫는다. 입력 칸에서 글자를 끌어 선택하다 창 밖에서 놓으면 click이 <dialog>에 오기 때문이다
	let pressedOutside = false;
	document.addEventListener('pointerdown', (event) => {
		pressedOutside = event.target instanceof HTMLDialogElement
			&& outside(event, event.target.getBoundingClientRect());
	});

	document.addEventListener('click', (event) => {
		const opener = event.target.closest('[data-dialog]');
		if (opener) {
			const dialog = document.getElementById(opener.dataset.dialog);
			// 확인 창은 열 때마다 입력을 비워 매번 다시 입력하게 한다
			dialog?.querySelectorAll('form[data-confirm-form]').forEach(resetConfirm);
			dialog?.showModal();
			return;
		}
		const closer = event.target.closest('[data-dialog-close]');
		if (closer) {
			closer.closest('dialog')?.close();
			return;
		}
		// 창의 안쪽 여백을 눌러도 target이 <dialog>이므로 누른 위치가 창 밖인지 확인한다. 제출 중이면 닫지 않는다
		if (event.target instanceof HTMLDialogElement && pressedOutside && !busy(event.target)
				&& outside(event, event.target.getBoundingClientRect())) {
			event.target.close();
		}
	});

	// 제출 중에는 Esc로도 닫지 않는다
	document.addEventListener('cancel', (event) => {
		if (event.target instanceof HTMLDialogElement && busy(event.target)) {
			event.preventDefault();
		}
	}, true);

	function busy(dialog) {
		return dialog.querySelector('button.is-busy') !== null;
	}

	function outside(event, rect) {
		return event.clientX < rect.left || event.clientX > rect.right
			|| event.clientY < rect.top || event.clientY > rect.bottom;
	}

	function confirmParts(form) {
		return {
			input: form.querySelector('[data-expected]'),
			submit: form.querySelector('button[type="submit"]'),
		};
	}

	function resetConfirm(form) {
		const { input, submit } = confirmParts(form);
		input.value = '';
		input.readOnly = false;
		form.classList.remove('is-matched');
		restore(submit);
		submit.disabled = true;
	}

	document.querySelectorAll('form[data-confirm-form]').forEach((form) => {
		const { input, submit } = confirmParts(form);
		input.addEventListener('input', () => {
			const matched = input.value.trim() === input.dataset.expected;
			form.classList.toggle('is-matched', matched);
			submit.disabled = !matched;
		});
	});

	// 제출 중 표시. 입력 칸은 disabled로 하면 값이 보내지지 않으므로 readOnly로 막는다
	document.addEventListener('submit', (event) => {
		const button = event.submitter;
		if (!button?.dataset.busyLabel || event.defaultPrevented) {
			return;
		}
		event.target.querySelectorAll('input[type="text"]').forEach((input) => {
			input.readOnly = true;
		});
		button.dataset.idleHtml = button.innerHTML;
		const spinner = document.createElement('span');
		spinner.className = 'spinner';
		spinner.setAttribute('aria-hidden', 'true');
		const label = document.createElement('span');
		label.textContent = button.dataset.busyLabel;
		button.replaceChildren(spinner, label);
		button.classList.add('is-busy');
		button.setAttribute('aria-busy', 'true');
		// 보낼 값을 모은 뒤에 끈다. 제출 이벤트 안에서 바로 끄면 누른 버튼이 보내는 값에서 빠진다
		setTimeout(() => {
			button.disabled = true;
		});
	});

	function restore(button) {
		if (button.dataset.idleHtml === undefined) {
			return;
		}
		button.innerHTML = button.dataset.idleHtml;
		delete button.dataset.idleHtml;
		button.classList.remove('is-busy');
		button.removeAttribute('aria-busy');
		button.disabled = false;
	}

	// 뒤로 가기로 페이지가 그대로 돌아오면(bfcache) 진행 중 표시를 걷는다
	window.addEventListener('pageshow', (event) => {
		if (!event.persisted) {
			return;
		}
		document.querySelectorAll('button.is-busy').forEach(restore);
		document.querySelectorAll('input[readonly]').forEach((input) => {
			input.readOnly = false;
		});
	});
})();
