// 학생 화면 상단 바와 알림: 계정 메뉴(<details>)는 Esc나 바깥을 누르면 닫고, 알림의 닫기 버튼은 알림을 걷는다.
// 언어 바꾸기 링크는 지금 주소의 다른 값을 유지하게 고친다.
// 메뉴 열기/닫기 자체는 <details>가 하므로 스크립트가 없어도 쓸 수 있다.
(() => {
	const menu = document.querySelector('details.account');
	if (menu) {
		const summary = menu.querySelector('summary');
		document.addEventListener('keydown', (event) => {
			if (event.key === 'Escape' && menu.open) {
				menu.open = false;
				summary.focus();
			}
		});
		document.addEventListener('click', (event) => {
			if (menu.open && !menu.contains(event.target)) {
				menu.open = false;
			}
		});
		// 메뉴 밖으로 Tab을 넘기면 닫는다
		menu.addEventListener('focusout', (event) => {
			if (menu.open && event.relatedTarget && !menu.contains(event.relatedTarget)) {
				menu.open = false;
			}
		});
	}

	// 언어 바꾸기는 지금 주소의 다른 값(예: 작성 화면의 enrollmentId)을 그대로 두고 lang만 바꾼다
	document.querySelectorAll('a.lang-switch').forEach((link) => {
		const target = new URL(link.href);
		const here = new URL(window.location.href);
		here.searchParams.set('lang', target.searchParams.get('lang'));
		link.href = here.pathname + here.search;
	});

	document.querySelectorAll('[data-toast-close]').forEach((button) => {
		button.hidden = false;
		button.addEventListener('click', () => {
			button.closest('.toast').remove();
		});
	});
})();
