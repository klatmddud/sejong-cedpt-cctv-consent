// 명부 업로드: 파일을 끌어다 놓거나 골라서 지금 고른 파일에 더하고, 고른 파일을 하나씩 뺄 수 있게 한다.
// 고른 파일은 selected에 모아 두고 바뀔 때마다 DataTransfer로 input.files를 다시 만들어 그대로 폼으로 보낸다.
// 동의서 삭제 확인: "확인했습니다"를 체크해야 반영 버튼이 켜진다.
(() => {
	const ack = document.getElementById('roster-ack');
	if (ack) {
		const apply = document.getElementById('roster-apply');
		const sync = () => {
			apply.disabled = !ack.checked;
		};
		ack.addEventListener('change', sync);
		sync();
	}

	const zone = document.getElementById('roster-dropzone');
	if (!zone) {
		return;
	}
	const input = document.getElementById('roster-files');
	const list = document.getElementById('roster-file-list');
	const error = document.getElementById('roster-file-error');
	const submit = document.getElementById('roster-upload');
	let selected = [];

	const key = (file) => `${file.name}\u0000${file.size}\u0000${file.lastModified}`;

	/** 새로 고른 파일 중 xlsx만 더한다. 같은 파일(이름, 크기, 수정 시각이 같음)은 한 번만 넣는다 */
	function add(files) {
		const picked = Array.from(files);
		const xlsx = picked.filter((file) => file.name.toLowerCase().endsWith('.xlsx'));
		error.hidden = xlsx.length === picked.length;
		const known = new Set(selected.map(key));
		xlsx.forEach((file) => {
			if (!known.has(key(file))) {
				known.add(key(file));
				selected.push(file);
			}
		});
		render();
	}

	function remove(index) {
		selected = selected.filter((_, i) => i !== index);
		render();
		// 지운 자리의 다음 파일, 없으면 파일 선택으로 초점을 옮긴다
		const buttons = list.querySelectorAll('button');
		(buttons[Math.min(index, buttons.length - 1)] ?? input).focus();
	}

	function render() {
		const transfer = new DataTransfer();
		selected.forEach((file) => transfer.items.add(file));
		input.files = transfer.files;

		list.replaceChildren(...selected.map((file, index) => {
			const item = document.createElement('li');
			const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
			icon.setAttribute('aria-hidden', 'true');
			icon.setAttribute('viewBox', '0 0 24 24');
			icon.classList.add('file-icon');
			icon.innerHTML = '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"></path>'
				+ '<path d="M14 2v4a2 2 0 0 0 2 2h4"></path><path d="m8 13 4 4"></path><path d="m12 13-4 4"></path>';
			const name = document.createElement('span');
			name.className = 'file-name';
			name.textContent = file.name;
			const button = document.createElement('button');
			button.type = 'button';
			button.className = 'icon-button small';
			button.setAttribute('aria-label', `${file.name} 빼기`);
			button.innerHTML = '<svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" '
				+ 'stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18"></path>'
				+ '<path d="m6 6 12 12"></path></svg>';
			button.addEventListener('click', () => remove(index));
			item.append(icon, name, button);
			return item;
		}));

		submit.disabled = selected.length === 0;
		submit.textContent = selected.length === 0 ? '업로드할 파일을 선택하세요' : `${selected.length}개 파일 업로드`;
	}

	['dragenter', 'dragover'].forEach((type) => zone.addEventListener(type, (event) => {
		event.preventDefault();
		zone.classList.add('is-dragover');
	}));
	zone.addEventListener('dragleave', (event) => {
		// 안쪽 요소로 옮겨 갈 때도 dragleave가 오므로 영역을 벗어났을 때만 끈다
		if (!zone.contains(event.relatedTarget)) {
			zone.classList.remove('is-dragover');
		}
	});
	zone.addEventListener('drop', (event) => {
		event.preventDefault();
		zone.classList.remove('is-dragover');
		add(event.dataTransfer.files);
	});
	// 파일 선택 창은 input.files를 새로 고른 파일로 바꾸므로, 바뀐 목록을 지금 고른 파일에 더해 다시 만든다
	input.addEventListener('change', () => add(input.files));

	// 뒤로 가기로 돌아왔을 때 브라우저가 남겨 둔 파일이 있으면 목록에 보여 준다
	add(input.files);

	// 영역 밖에 놓친 파일을 브라우저가 열어 화면을 벗어나지 않게 한다
	window.addEventListener('dragover', (event) => event.preventDefault());
	window.addEventListener('drop', (event) => event.preventDefault());
})();
