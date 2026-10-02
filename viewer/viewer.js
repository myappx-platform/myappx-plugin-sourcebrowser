(function () {
	var params = new URLSearchParams(location.search);
	var ticket = params.get('ticket') || '';
	var editor = null;
	var pending = null;
	var lastKey = '';
	var decoIds = [];
	var msgEl = document.getElementById('msg');
	var edEl = document.getElementById('ed');
	var themeSel = document.getElementById('theme-sel');
	var themeLabel = document.getElementById('theme-label');
	var THEME_KEY = 'myappx.sourcebrowser.theme';
	var THEMES = ['vs', 'vs-dark'];
	var zh = (params.get('lang') || '').toLowerCase().indexOf('zh') === 0;

	function themeLabels() {
		return zh
			? { vs: '浅色', 'vs-dark': '深色' }
			: { vs: 'Light', 'vs-dark': 'Dark' };
	}

	function allowedTheme(id) {
		return THEMES.indexOf(id) >= 0 ? id : 'vs-dark';
	}

	function storedTheme() {
		try {
			return allowedTheme(localStorage.getItem(THEME_KEY) || 'vs-dark');
		} catch (e) {
			return 'vs-dark';
		}
	}

	function applyTheme(id) {
		var t = allowedTheme(id);
		try {
			localStorage.setItem(THEME_KEY, t);
		} catch (e) {}
		document.documentElement.setAttribute('data-theme', t);
		if (typeof monaco !== 'undefined' && monaco.editor) {
			monaco.editor.setTheme(t);
		}
		if (themeSel && themeSel.value !== t) {
			themeSel.value = t;
		}
		if (editor) {
			editor.layout();
		}
	}

	function initThemeSelect() {
		if (!themeSel) {
			return;
		}
		if (themeLabel) {
			themeLabel.textContent = zh ? '主题' : 'Theme';
		}
		var labels = themeLabels();
		themeSel.innerHTML = '';
		THEMES.forEach(function (id) {
			var opt = document.createElement('option');
			opt.value = id;
			opt.textContent = labels[id];
			themeSel.appendChild(opt);
		});
		themeSel.value = storedTheme();
		themeSel.addEventListener('change', function () {
			applyTheme(themeSel.value);
		});
	}

	function showError(text) {
		if (!msgEl) return;
		msgEl.className = 'error';
		msgEl.textContent = text;
		if (edEl) edEl.style.display = 'none';
	}

	function hideError() {
		if (msgEl) {
			msgEl.className = '';
			msgEl.textContent = '';
			msgEl.style.display = 'none';
		}
		if (edEl) edEl.style.display = 'block';
	}

	function hashOpen() {
		var raw = (location.hash || '').replace(/^#/, '');
		if (!raw) {
			return null;
		}
		var q = new URLSearchParams(raw);
		var path = q.get('p') || q.get('path');
		if (!path) {
			return null;
		}
		return { path: path, line: parseInt(q.get('l') || q.get('line') || '1', 10) || 1 };
	}

	function revealLine(n) {
		if (!editor) {
			return;
		}
		var model = editor.getModel();
		var max = model ? model.getLineCount() : 1;
		var line = Math.min(Math.max(1, parseInt(n, 10) || 1), max);
		function go() {
			if (!editor) {
				return;
			}
			editor.layout();
			editor.revealLineInCenter(line);
			editor.setPosition({ lineNumber: line, column: 1 });
			decoIds = editor.deltaDecorations(decoIds, [{
				range: new monaco.Range(line, 1, line, 1),
				options: {
					isWholeLine: true,
					className: 'src-line-highlight',
					overviewRuler: { color: '#16a34a', position: 2 }
				}
			}]);
		}
		go();
		requestAnimationFrame(function () {
			requestAnimationFrame(go);
		});
		setTimeout(go, 80);
	}

	function applyOpen(path, line) {
		if (!path) {
			return;
		}
		if (!editor) {
			pending = { path: path, line: line };
			return;
		}
		openFile(path, line);
	}

	initThemeSelect();
	applyTheme(params.get('theme') || storedTheme());

	if (typeof require === 'undefined') {
		showError('Monaco vs/ is missing. Run mvn generate-resources in org.idempiere.myappx.plugin.sourcebrowser.');
		return;
	}

	require.config({ paths: { vs: 'vs' } });
	require(['vs/editor/editor.main'], function () {
		editor = monaco.editor.create(edEl, {
			value: '',
			language: 'plaintext',
			readOnly: true,
			theme: storedTheme(),
			automaticLayout: true,
			minimap: { enabled: false },
			scrollBeyondLastLine: true,
			fontSize: 13,
			renderLineHighlight: 'all',
			wordWrap: 'off'
		});
		applyTheme(storedTheme());
		if (pending) {
			openFile(pending.path, pending.line);
			pending = null;
		} else {
			var h = hashOpen();
			if (h) {
				applyOpen(h.path, h.line);
			}
		}
	}, function () {
		showError('Failed to load Monaco. Check viewer/vs/ after Maven unpack.');
	});

	window.addEventListener('message', function (ev) {
		var d = ev.data || {};
		if (d.type === 'auth' && d.ticket) {
			ticket = d.ticket;
		}
		if (d.type === 'theme' && d.theme) {
			applyTheme(d.theme);
		}
		if (d.type === 'open' && d.path) {
			if (d.ticket) {
				ticket = d.ticket;
			}
			if (d.theme) {
				applyTheme(d.theme);
			}
			applyOpen(d.path, d.line || 1);
		}
	});
	window.addEventListener('hashchange', function () {
		var h = hashOpen();
		if (h) {
			applyOpen(h.path, h.line);
		}
	});

	function openFile(path, line) {
		var n = Math.max(1, parseInt(line, 10) || 1);
		var key = path + ':' + n;
		if (key === lastKey && editor && editor.getModel() && editor.getModel().getValue()) {
			revealLine(n);
			return;
		}
		if (!ticket) {
			showError('No source ticket. Re-open Source Browser from the iDempiere menu.');
			return;
		}
		hideError();
		var url = '/sourcebrowser/api/file?path=' + encodeURIComponent(path);
		fetch(url, { headers: { 'X-Source-Ticket': ticket } })
			.then(function (r) { return r.json().then(function (j) { return { ok: r.ok, json: j }; }); })
			.then(function (res) {
				if (!res.json || res.json.ok === false) {
					lastKey = '';
					showError((res.json && res.json.error) || 'Unable to read file.');
					return;
				}
				var lang = res.json.language || 'plaintext';
				var model = monaco.editor.createModel(res.json.content || '', lang);
				var old = editor.getModel();
				editor.setModel(model);
				if (old) old.dispose();
				lastKey = key;
				revealLine(n);
			})
			.catch(function (err) {
				lastKey = '';
				showError(err && err.message ? err.message : 'Fetch failed.');
			});
	}
})();
