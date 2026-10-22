// 언어 선택 화면: 고른 언어로 "계속하기"와 대상 문구를 바로 바꾼다. 두 언어 문구는 서버가 form의 data-*에 넣어 준다.
// 선택과 이동은 스크립트 없이도 되는 GET 폼(/login?lang=)이 맡는다.
(() => {
	const form = document.getElementById('language-form');
	if (!form) {
		return;
	}
	const cta = document.getElementById('language-cta');
	const rooms = document.getElementById('rooms-label');

	function update() {
		const lang = form.querySelector('input[name="lang"]:checked')?.value === 'en' ? 'en' : 'ko';
		const suffix = lang === 'en' ? 'En' : 'Ko';
		cta.textContent = form.dataset['cta' + suffix];
		rooms.textContent = form.dataset['rooms' + suffix];
		cta.closest('button').lang = lang;
		rooms.lang = lang;
	}

	form.addEventListener('change', update);
	// 뒤로 가기로 돌아오면 브라우저가 이전 선택을 되살리므로 그에 맞춘다
	window.addEventListener('pageshow', update);
	update();
})();
