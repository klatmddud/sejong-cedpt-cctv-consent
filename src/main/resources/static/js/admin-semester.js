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

// 동의서 일괄 다운로드: 전체 선택, 선택한 강의 수, 받기 버튼.
(() => {
	const form = document.getElementById('consent-download');
	if (!form) {
		return;
	}
	const selectAll = document.getElementById('select-all-courses');
	const boxes = Array.from(form.querySelectorAll('input[name="codes"]'));
	const count = document.getElementById('selected-course-count');
	const submit = document.getElementById('download-consents');

	function selectedCodes() {
		return new Set(boxes.filter((box) => box.checked).map((box) => box.value));
	}

	function update() {
		const selected = selectedCodes().size;
		const total = new Set(boxes.map((box) => box.value)).size;
		count.textContent = selected === 0 ? '선택한 강의 없음' : `${selected}개 강의 선택`;
		selectAll.checked = selected > 0 && selected === total;
		selectAll.indeterminate = selected > 0 && selected < total;
		submit.disabled = selected === 0;
	}

	selectAll.addEventListener('change', () => {
		boxes.forEach((box) => {
			box.checked = selectAll.checked;
		});
		update();
	});
	boxes.forEach((box) => box.addEventListener('change', () => {
		// 여러 강의실 수업은 강의실마다 한 줄씩 있으므로 같은 과목 줄을 함께 바꾼다
		boxes.filter((other) => other.value === box.value).forEach((other) => {
			other.checked = box.checked;
		});
		update();
	}));
	update();
})();
