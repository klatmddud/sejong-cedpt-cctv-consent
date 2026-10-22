// 학기 동의서 현황.
// 목록: 검색, 미제출 있는 강의만 보기, 전체 선택, 강의실별 전체 선택, 선택한 강의 수, 받기 버튼.
// 걸러서 숨긴 줄은 전체 선택, 강의실 전체 선택이 건드리지 않는다. 숨기기 전에 고른 강의는 고른 채로 남아 함께 받는다.
// 상세 창: 학생 명단을 전체/미제출로 걸러 본다.
(() => {
	document.querySelectorAll('.course-detail').forEach((dialog) => {
		const buttons = Array.from(dialog.querySelectorAll('[data-student-filter]'));
		const rows = Array.from(dialog.querySelectorAll('.student-cols[data-submitted]'));
		const empty = dialog.querySelector('.student-empty');
		function show(filter) {
			buttons.forEach((button) => button.setAttribute('aria-pressed', String(button.dataset.studentFilter === filter)));
			rows.forEach((row) => {
				row.hidden = filter === 'missing' && row.dataset.submitted === 'true';
			});
			empty.hidden = rows.some((row) => !row.hidden);
		}
		buttons.forEach((button) => button.addEventListener('click', () => show(button.dataset.studentFilter)));
		// 창을 다시 열면 전체 명단부터 보여 준다
		dialog.addEventListener('close', () => {
			if (buttons.length > 0) {
				show('all');
			}
		});
	});

	const form = document.getElementById('consent-download');
	if (!form) {
		return;
	}
	const selectAll = document.getElementById('select-all-courses');
	const boxes = Array.from(form.querySelectorAll('input[name="codes"]'));
	const rooms = Array.from(form.querySelectorAll('.room-group')).map((group) => ({
		group,
		toggle: group.querySelector('.select-room'),
		boxes: Array.from(group.querySelectorAll('input[name="codes"]')),
	}));
	const count = document.getElementById('selected-course-count');
	const submit = document.getElementById('download-consents');
	const search = document.getElementById('course-search');
	const onlyUnsubmitted = document.getElementById('only-unsubmitted');
	const empty = document.getElementById('filter-empty');

	const line = (box) => box.closest('.course-line');
	const shown = (box) => !line(box).hidden;
	const codesOf = (members) => new Set(members.map((box) => box.value));

	/** 묶음 체크박스: 보이는 강의를 모두 고르면 체크, 일부만 고르면 중간 상태. 보이는 강의가 없으면 끈다 */
	function reflect(toggle, members) {
		const visible = members.filter(shown);
		const checked = visible.filter((box) => box.checked).length;
		toggle.checked = checked > 0 && checked === visible.length;
		toggle.indeterminate = checked > 0 && checked < visible.length;
		toggle.disabled = visible.length === 0;
	}

	// 여러 강의실 수업은 강의실마다 한 줄씩 있으므로 같은 과목 줄을 함께 바꾼다
	function setCourses(codes, checked) {
		boxes.forEach((box) => {
			if (codes.has(box.value)) {
				box.checked = checked;
			}
		});
	}

	function update() {
		const selected = codesOf(boxes.filter((box) => box.checked));
		const hidden = codesOf(boxes.filter((box) => box.checked && !shown(box)));
		if (selected.size === 0) {
			count.textContent = '선택한 강의 없음';
		}
		else {
			count.textContent = hidden.size === 0 ? `${selected.size}개 강의 선택`
				: `${selected.size}개 강의 선택 (${hidden.size}개는 숨김)`;
		}
		count.classList.toggle('is-on', selected.size > 0);
		reflect(selectAll, boxes);
		rooms.forEach((room) => reflect(room.toggle, room.boxes));
		submit.disabled = selected.size === 0;
	}

	/** 과목명이나 학수번호에 검색어가 들어 있고, "미제출 있는 강의만"이면 미제출이 있는 줄만 보인다 */
	function filter() {
		const needle = search.value.trim().toLowerCase();
		const only = onlyUnsubmitted.getAttribute('aria-pressed') === 'true';
		boxes.forEach((box) => {
			const row = line(box);
			row.hidden = (only && row.dataset.unsubmitted === '0')
				|| (needle !== '' && !row.dataset.search.toLowerCase().includes(needle));
		});
		rooms.forEach((room) => {
			room.group.hidden = !room.boxes.some(shown);
		});
		empty.hidden = boxes.some(shown);
		update();
	}

	selectAll.addEventListener('change', () => {
		setCourses(codesOf(boxes.filter(shown)), selectAll.checked);
		update();
	});
	rooms.forEach((room) => room.toggle.addEventListener('change', () => {
		setCourses(codesOf(room.boxes.filter(shown)), room.toggle.checked);
		update();
	}));
	boxes.forEach((box) => box.addEventListener('change', () => {
		setCourses(new Set([box.value]), box.checked);
		update();
	}));
	search.addEventListener('input', filter);
	// 검색 칸에서 Enter를 눌러도 PDF 받기가 제출되지 않게 한다
	search.addEventListener('keydown', (event) => {
		if (event.key === 'Enter') {
			event.preventDefault();
		}
	});
	onlyUnsubmitted.addEventListener('click', () => {
		onlyUnsubmitted.setAttribute('aria-pressed', String(onlyUnsubmitted.getAttribute('aria-pressed') !== 'true'));
		filter();
	});
	document.getElementById('clear-filters').addEventListener('click', () => {
		search.value = '';
		onlyUnsubmitted.setAttribute('aria-pressed', 'false');
		filter();
		search.focus();
	});
	// 뒤로 가기로 돌아오면 브라우저가 검색어와 체크를 남겨 두므로 그대로 다시 거른다
	filter();
})();
