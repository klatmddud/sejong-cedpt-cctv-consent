// 동의서 작성 화면: 전체 선택, 선택 개수와 제출 버튼 문구, 단계 표시(동의서 확인 → 수업 선택 → 서명).
(() => {
	const form = document.getElementById('consent-form');
	if (!form) {
		return;
	}
	const selectAll = document.getElementById('select-all');
	const courses = Array.from(form.querySelectorAll('input[name="enrollmentIds"]'));
	const count = document.getElementById('selected-count');
	const submit = document.getElementById('submit-button');
	const agreed = document.getElementById('agreed');
	const canvas = document.getElementById('signature-pad');
	const steps = {
		read: document.getElementById('step-read'),
		courses: document.getElementById('step-courses'),
		sign: document.getElementById('step-sign'),
	};
	let signed = false;

	function selectedCount() {
		return courses.filter((course) => course.checked).length;
	}

	function updateSelection() {
		const selected = selectedCount();
		count.textContent = selected;
		selectAll.checked = selected === courses.length;
		selectAll.indeterminate = selected > 0 && selected < courses.length;
		submit.textContent = submit.dataset.template.replace('{n}', selected);
		submit.disabled = selected === 0;
		updateSteps();
	}

	// 지금 할 단계에 aria-current, 끝낸 단계에 done 표시
	function updateSteps() {
		const done = {read: agreed.checked, courses: selectedCount() > 0, sign: signed};
		let current = null;
		for (const key of ['read', 'courses', 'sign']) {
			steps[key].classList.toggle('done', done[key]);
			steps[key].removeAttribute('aria-current');
			if (!done[key] && current === null) {
				current = key;
			}
		}
		if (current !== null) {
			steps[current].setAttribute('aria-current', 'step');
		}
	}

	selectAll.addEventListener('change', () => {
		courses.forEach((course) => {
			course.checked = selectAll.checked;
		});
		updateSelection();
	});
	courses.forEach((course) => course.addEventListener('change', updateSelection));
	agreed.addEventListener('change', updateSteps);
	canvas.addEventListener('pointerup', () => {
		signed = true;
		updateSteps();
	});
	document.getElementById('signature-clear').addEventListener('click', () => {
		signed = false;
		updateSteps();
	});

	updateSelection();
})();
