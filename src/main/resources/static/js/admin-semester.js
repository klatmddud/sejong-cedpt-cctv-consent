// 학기 동의서 현황: 목록 행이나 시간표 칸을 누르면 수업 상세(미제출 학생 명단)를 연다.
(() => {
	document.addEventListener('click', (event) => {
		const opener = event.target.closest('[data-dialog]');
		if (opener) {
			document.getElementById(opener.dataset.dialog)?.showModal();
			return;
		}
		// 상세 바깥(배경)을 누르면 닫는다
		if (event.target instanceof HTMLDialogElement) {
			event.target.close();
		}
	});
})();

// 동의서 일괄 다운로드: 전체 선택, 강의실별 전체 선택, 선택한 강의 수, 받기 버튼.
(() => {
	const form = document.getElementById('consent-download');
	if (!form) {
		return;
	}
	const selectAll = document.getElementById('select-all-courses');
	const boxes = Array.from(form.querySelectorAll('input[name="codes"]'));
	const rooms = Array.from(form.querySelectorAll('.room-group')).map((group) => ({
		toggle: group.querySelector('.select-room'),
		boxes: Array.from(group.querySelectorAll('input[name="codes"]')),
	}));
	const count = document.getElementById('selected-course-count');
	const submit = document.getElementById('download-consents');

	/** 묶음 체크박스: 모두 고르면 체크, 일부만 고르면 중간 상태 */
	function reflect(toggle, members) {
		const checked = members.filter((box) => box.checked).length;
		toggle.checked = checked > 0 && checked === members.length;
		toggle.indeterminate = checked > 0 && checked < members.length;
	}

	// 여러 강의실 수업은 강의실마다 한 줄씩 있으므로 같은 과목 줄을 함께 바꾼다
	function setCourse(code, checked) {
		boxes.filter((box) => box.value === code).forEach((box) => {
			box.checked = checked;
		});
	}

	function update() {
		const selected = new Set(boxes.filter((box) => box.checked).map((box) => box.value)).size;
		count.textContent = selected === 0 ? '선택한 강의 없음' : `${selected}개 강의 선택`;
		reflect(selectAll, boxes);
		rooms.forEach((room) => reflect(room.toggle, room.boxes));
		submit.disabled = selected === 0;
	}

	selectAll.addEventListener('change', () => {
		boxes.forEach((box) => setCourse(box.value, selectAll.checked));
		update();
	});
	rooms.forEach((room) => room.toggle.addEventListener('change', () => {
		room.boxes.forEach((box) => setCourse(box.value, room.toggle.checked));
		update();
	}));
	boxes.forEach((box) => box.addEventListener('change', () => {
		setCourse(box.value, box.checked);
		update();
	}));
	update();
})();
