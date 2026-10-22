// 동의서 서명 패드: 마우스, 터치, 펜 모두 Pointer Events로 받는다.
// 제출할 때 투명 배경 PNG(data URL)를 hidden input에 넣는다.
// 서명이 생기거나 지워지면 canvas에 signaturechange 이벤트(detail.signed)를 보낸다(consent-form.js가 단계 표시에 쓴다).
(() => {
	const canvas = document.getElementById('signature-pad');
	if (!canvas) {
		return;
	}
	const input = document.getElementById('signature');
	const clearButton = document.getElementById('signature-clear');
	const error = document.getElementById('signature-error');
	const form = canvas.closest('form');
	const context = canvas.getContext('2d');
	let drawing = false;
	let hasInk = false;
	let last = null;

	function setInk(value) {
		if (hasInk !== value) {
			hasInk = value;
			canvas.dispatchEvent(new CustomEvent('signaturechange', {detail: {signed: value}}));
		}
	}

	function resize() {
		// 화면 크기가 바뀌면 그린 내용이 늘어나 왜곡되므로 지우고 새로 맞춘다
		const ratio = Math.max(window.devicePixelRatio || 1, 1);
		const rect = canvas.getBoundingClientRect();
		canvas.width = Math.round(rect.width * ratio);
		canvas.height = Math.round(rect.height * ratio);
		context.setTransform(ratio, 0, 0, ratio, 0, 0);
		context.lineWidth = 2.5;
		context.lineCap = 'round';
		context.lineJoin = 'round';
		context.strokeStyle = '#111';
		setInk(false);
	}

	function point(event) {
		const rect = canvas.getBoundingClientRect();
		return {x: event.clientX - rect.left, y: event.clientY - rect.top};
	}

	canvas.addEventListener('pointerdown', (event) => {
		event.preventDefault();
		canvas.setPointerCapture(event.pointerId);
		drawing = true;
		last = point(event);
		context.beginPath();
		context.arc(last.x, last.y, context.lineWidth / 2, 0, Math.PI * 2);
		context.fillStyle = context.strokeStyle;
		context.fill();
	});

	canvas.addEventListener('pointermove', (event) => {
		if (!drawing) {
			return;
		}
		const next = point(event);
		context.beginPath();
		context.moveTo(last.x, last.y);
		context.lineTo(next.x, next.y);
		context.stroke();
		last = next;
		setInk(true);
		error.hidden = true;
	});

	const stop = () => {
		drawing = false;
	};
	canvas.addEventListener('pointerup', stop);
	canvas.addEventListener('pointercancel', stop);

	clearButton.addEventListener('click', () => {
		context.clearRect(0, 0, canvas.width, canvas.height);
		setInk(false);
	});

	form.addEventListener('submit', (event) => {
		if (!hasInk) {
			event.preventDefault();
			error.hidden = false;
			canvas.scrollIntoView({block: 'center', behavior: 'smooth'});
			return;
		}
		input.value = canvas.toDataURL('image/png');
	});

	let width = window.innerWidth;
	window.addEventListener('resize', () => {
		// 모바일 주소창이 나타나고 사라질 때(높이만 변함)는 지우지 않는다
		if (window.innerWidth !== width) {
			width = window.innerWidth;
			resize();
		}
	});
	resize();
})();
