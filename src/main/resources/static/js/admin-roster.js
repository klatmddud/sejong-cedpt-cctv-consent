// 명부 업로드: 파일을 끌어다 놓아도 선택되게 하고, 선택한 파일 이름을 보여 준다.
(() => {
	const zone = document.getElementById('roster-dropzone');
	if (!zone) {
		return;
	}
	const input = document.getElementById('roster-files');
	const list = document.getElementById('roster-file-list');
	const error = document.getElementById('roster-file-error');

	function showFiles() {
		list.replaceChildren(...Array.from(input.files).map((file) => {
			const item = document.createElement('li');
			item.textContent = file.name;
			return item;
		}));
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
		const dropped = Array.from(event.dataTransfer.files);
		const xlsx = dropped.filter((file) => file.name.toLowerCase().endsWith('.xlsx'));
		error.hidden = xlsx.length === dropped.length;
		if (xlsx.length === 0) {
			return;
		}
		const transfer = new DataTransfer();
		xlsx.forEach((file) => transfer.items.add(file));
		input.files = transfer.files;
		showFiles();
	});
	input.addEventListener('change', () => {
		error.hidden = true;
		showFiles();
	});

	// 영역 밖에 놓친 파일을 브라우저가 열어 화면을 벗어나지 않게 한다
	window.addEventListener('dragover', (event) => event.preventDefault());
	window.addEventListener('drop', (event) => event.preventDefault());
})();
