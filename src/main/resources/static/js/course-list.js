// 내 수업: 전체/미제출/제출 완료 필터. 목록은 서버가 모두 그려 두고 여기서는 보이고 숨기기만 한다.
(() => {
	const filters = document.getElementById('course-filters');
	if (!filters) {
		return;
	}
	const buttons = Array.from(filters.querySelectorAll('button[data-filter]'));
	const courses = Array.from(document.querySelectorAll('.course-list > li[data-status]'));
	const status = document.getElementById('filter-status');
	const empty = document.getElementById('filter-empty');

	function apply(filter) {
		let shown = 0;
		courses.forEach((course) => {
			const visible = filter === 'all' || course.dataset.status === filter;
			course.hidden = !visible;
			shown += visible ? 1 : 0;
		});
		buttons.forEach((button) => {
			button.setAttribute('aria-pressed', String(button.dataset.filter === filter));
		});
		empty.hidden = shown > 0;
		status.textContent = status.dataset.template.replace('{n}', shown);
	}

	buttons.forEach((button) => {
		button.addEventListener('click', () => apply(button.dataset.filter));
	});
	filters.hidden = false;
})();
