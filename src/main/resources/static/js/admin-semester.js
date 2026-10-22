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
