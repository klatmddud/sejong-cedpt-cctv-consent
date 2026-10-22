// 동의서 작성 화면: 단계 표시(동의서 확인 → 수업 선택 → 서명)와 진행 막대, 전체 선택과 선택 개수, 하단의 남은 단계 안내.
// 세 단계를 모두 마쳐야 제출 버튼이 켜지고, 누르면 고른 수업을 확인하는 창(<dialog>)을 먼저 띄운다.
// 문구는 서버가 data-template 등에 넣어 준다. 서버 검증은 그대로이므로 여기서는 안내만 한다.
(() => {
	const form = document.getElementById('consent-form');
	if (!form) {
		return;
	}
	const selectAll = document.getElementById('select-all');
	const courses = Array.from(form.querySelectorAll('input[name="enrollmentIds"]'));
	const count = document.getElementById('selected-count');
	const coursesError = document.getElementById('courses-error');
	const agreed = document.getElementById('agreed');
	const agreeDone = document.getElementById('agree-done');
	const canvas = document.getElementById('signature-pad');
	const signatureBox = document.getElementById('signature-box');
	const clear = document.getElementById('signature-clear');
	const progress = document.getElementById('step-progress');
	const status = document.getElementById('submit-status');
	const statusText = document.getElementById('submit-status-text');
	const submit = document.getElementById('submit-button');
	const dialog = document.getElementById('confirm-dialog');
	const confirmTitle = document.getElementById('confirm-title');
	const confirmList = document.getElementById('confirm-list');
	const confirmCancel = document.getElementById('confirm-cancel');
	const confirmSubmit = document.getElementById('confirm-submit');
	const steps = Array.from(document.querySelectorAll('.steps a[data-step]'));
	let signed = false;
	let busy = false;

	function selected() {
		return courses.filter((course) => course.checked);
	}

	function update() {
		const selectedCount = selected().length;
		const done = {read: agreed.checked, courses: selectedCount > 0, sign: signed};

		count.textContent = count.dataset.template.replace('{n}', selectedCount);
		selectAll.checked = selectedCount === courses.length;
		selectAll.indeterminate = selectedCount > 0 && selectedCount < courses.length;
		coursesError.hidden = selectedCount > 0;
		agreeDone.hidden = !done.read;
		signatureBox.classList.toggle('is-signed', signed);
		clear.setAttribute('aria-disabled', String(!signed));

		// 끝낸 단계는 done, 아직 안 한 첫 단계에 aria-current
		let current = null;
		const missing = [];
		steps.forEach((step) => {
			const finished = done[step.dataset.step];
			const state = step.querySelector('.step-state');
			step.classList.toggle('done', finished);
			step.removeAttribute('aria-current');
			state.textContent = finished ? state.dataset.done : '';
			if (!finished) {
				missing.push(step.querySelector('.step-label').textContent);
				current ??= step;
			}
		});
		current?.setAttribute('aria-current', 'step');

		const doneCount = steps.length - missing.length;
		progress.setAttribute('aria-valuenow', String(doneCount));
		progress.firstElementChild.style.width = Math.round((doneCount / steps.length) * 100) + '%';

		const ready = missing.length === 0;
		status.classList.toggle('ready', ready);
		statusText.textContent = ready
			? status.dataset.ready
			: status.dataset.remaining.replace('{steps}', missing.join(' · '));
		submit.disabled = !ready;
		submit.textContent = selectedCount > 0
			? submit.dataset.template.replace('{n}', selectedCount)
			: submit.dataset.empty;
	}

	selectAll.addEventListener('change', () => {
		courses.forEach((course) => {
			course.checked = selectAll.checked;
		});
		update();
	});
	courses.forEach((course) => course.addEventListener('change', update));
	agreed.addEventListener('change', update);
	canvas.addEventListener('signaturechange', (event) => {
		signed = event.detail.signed;
		update();
	});

	function openConfirm() {
		const chosen = selected();
		confirmTitle.textContent = confirmTitle.dataset.template.replace('{n}', chosen.length);
		confirmList.replaceChildren(...chosen.map((course) => {
			const item = document.createElement('li');
			const name = document.createElement('strong');
			name.textContent = course.dataset.name;
			const room = document.createElement('span');
			room.textContent = course.dataset.room;
			item.append(name, room);
			return item;
		}));
		dialog.showModal();
	}

	// 하단 버튼은 확인 창만 연다. 실제 제출은 확인 창의 제출 버튼으로 한다.
	// signature-pad.js가 먼저 서명을 hidden input에 넣고, 서명이 없으면 제출을 막는다(defaultPrevented)
	form.addEventListener('submit', (event) => {
		if (event.defaultPrevented) {
			return;
		}
		if (event.submitter !== confirmSubmit) {
			event.preventDefault();
			if (!submit.disabled) {
				openConfirm();
			}
			return;
		}
		busy = true;
		confirmSubmit.dataset.idleHtml = confirmSubmit.innerHTML;
		const spinner = document.createElement('span');
		spinner.className = 'spinner';
		spinner.setAttribute('aria-hidden', 'true');
		const label = document.createElement('span');
		label.textContent = confirmSubmit.dataset.busyLabel;
		confirmSubmit.replaceChildren(spinner, label);
		confirmSubmit.setAttribute('aria-busy', 'true');
		// 보낼 값을 모은 뒤에 끈다
		setTimeout(() => {
			confirmSubmit.disabled = true;
			confirmCancel.disabled = true;
		});
	});

	confirmCancel.addEventListener('click', () => dialog.close());
	// 제출 중에는 Esc나 바깥 클릭으로 닫지 않는다
	dialog.addEventListener('cancel', (event) => {
		if (busy) {
			event.preventDefault();
		}
	});
	dialog.addEventListener('click', (event) => {
		const rect = dialog.getBoundingClientRect();
		const outside = event.clientX < rect.left || event.clientX > rect.right
			|| event.clientY < rect.top || event.clientY > rect.bottom;
		if (event.target === dialog && outside && !busy) {
			dialog.close();
		}
	});
	dialog.addEventListener('close', () => submit.focus());

	// 뒤로 가기로 페이지가 그대로 돌아오면(bfcache) 진행 중 표시를 걷는다
	window.addEventListener('pageshow', (event) => {
		if (!event.persisted || !busy) {
			return;
		}
		busy = false;
		confirmSubmit.innerHTML = confirmSubmit.dataset.idleHtml;
		confirmSubmit.removeAttribute('aria-busy');
		confirmSubmit.disabled = false;
		confirmCancel.disabled = false;
		dialog.close();
	});

	update();
})();
